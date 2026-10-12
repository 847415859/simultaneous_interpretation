package com.yinanyi.app.engine

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.yinanyi.app.SourceLang
import com.yinanyi.app.SubtitleSink
import com.yinanyi.app.Term
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 离线：Silero VAD 断句 → 识别（日语 ReazonSpeech，英韩 SenseVoice）→ HY-MT1.5-1.8B 翻译。
 * 识别和翻译各一个线程；识别只用 2 线程，把大核留给翻译（阶段 0 实测：抢 CPU 时翻译慢 10 倍）。
 */
class OfflineEngine(
    private val models: Models,
    private val lang: SourceLang,
    private val sink: SubtitleSink,
    private val terms: List<Term>,
) : Engine {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512
        private const val PARTIAL_INTERVAL_MS = 700L
        private const val MT_THREADS = 4
        private const val MT_MAX_TOKENS = 128
        private const val MAX_BACKLOG = 2
    }

    private data class Pending(val id: Long, val text: String)

    private val audio = LinkedBlockingQueue<FloatArray>(300)
    private val toTranslate = LinkedBlockingDeque<Pending>()

    @Volatile
    private var running = true
    private val asrThread = Thread({ runAsr() }, "offline-asr")
    private val mtThread = Thread({ runMt() }, "offline-mt")

    override fun start() {
        val missing = models.missing(lang)
        if (missing.isNotEmpty()) {
            sink.failed("缺少离线模型：" + missing.joinToString())
            running = false
            return
        }
        sink.status("正在加载离线模型……")
        asrThread.start()
        mtThread.start()
    }

    override fun feed(samples: ShortArray, n: Int) {
        if (running) audio.offer(FloatArray(n) { samples[it] / 32768f })
    }

    override fun stop() {
        running = false
        audio.offer(FloatArray(0))
        mtThread.interrupt()
        if (asrThread.isAlive) asrThread.join(3000)
        if (mtThread.isAlive) mtThread.join(5000)
    }

    // ---------------- 识别 ----------------

    private fun recognizer(): OfflineRecognizer {
        val dir = models.asrDir(lang)
        fun onnx(prefix: String): String = dir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?.minByOrNull { if (it.name.contains("int8")) 0 else 1 }
            ?.absolutePath ?: throw IllegalStateException("${dir.path} 里没有 $prefix*.onnx")

        val model = OfflineModelConfig(
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = 2,
            provider = "cpu",
        )
        if (lang == SourceLang.JA) {
            model.transducer = OfflineTransducerModelConfig(
                encoder = onnx("encoder"),
                decoder = onnx("decoder"),
                joiner = onnx("joiner"),
            )
        } else {
            model.senseVoice = OfflineSenseVoiceModelConfig(
                model = onnx("model"),
                language = lang.code,
                useInverseTextNormalization = true,
            )
        }
        return OfflineRecognizer(
            null,
            OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80), modelConfig = model),
        )
    }

    private fun vad() = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = models.vad.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 8f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        return text
    }

    private fun runAsr() {
        val recognizer: OfflineRecognizer
        val vad: Vad
        try {
            recognizer = recognizer()
            vad = vad()
        } catch (e: Throwable) {
            sink.failed("加载离线识别模型失败：${e.message}")
            return
        }

        val window = FloatArray(VAD_WINDOW)
        var windowLen = 0
        val speech = FloatBuffer()
        var lastPartialAt = 0L

        while (running) {
            val chunk = audio.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (chunk.isEmpty()) break

            var i = 0
            while (i < chunk.size) {
                val n = min(VAD_WINDOW - windowLen, chunk.size - i)
                System.arraycopy(chunk, i, window, windowLen, n)
                windowLen += n
                i += n
                if (windowLen < VAD_WINDOW) continue
                windowLen = 0

                vad.acceptWaveform(window.copyOf())
                if (vad.isSpeechDetected()) speech.append(window)
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val text = decode(recognizer, segment.samples)
                    speech.clear()
                    sink.partial("")
                    if (text.isNotEmpty()) {
                        val id = sink.addLine(original = text, translation = null)
                        toTranslate.offerLast(Pending(id, text))
                    }
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (vad.isSpeechDetected() && speech.size > SAMPLE_RATE / 2 && now - lastPartialAt > PARTIAL_INTERVAL_MS) {
                lastPartialAt = now
                sink.partial(decode(recognizer, speech.toArray()))
            }
        }
        vad.release()
        recognizer.release()
    }

    // ---------------- 翻译 ----------------

    private fun runMt() {
        val handle = try {
            LlamaNative.load(models.translator.absolutePath, MT_THREADS)
        } catch (e: Throwable) {
            0L
        }
        if (handle == 0L) {
            sink.failed("加载离线翻译模型失败")
            return
        }
        sink.status("离线翻译已就绪")
        var previous: Pending? = null
        try {
            while (running) {
                val p = try {
                    toTranslate.pollFirst(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                // 跟不上时跳过旧句子，保证字幕不越积越晚
                if (toTranslate.size >= MAX_BACKLOG) {
                    sink.setTranslation(p.id, "")
                    continue
                }
                // 「え」「はい」这类一两个字的语气词不翻
                if (p.text.count { it.isLetter() } <= 2) {
                    sink.setTranslation(p.id, "")
                    continue
                }
                val maxTokens = minOf(MT_MAX_TOKENS, p.text.length * 2 + 8)
                val out = String(LlamaNative.translate(handle, "", prompt(p.text, previous?.text), maxTokens), Charsets.UTF_8).trim()
                sink.setTranslation(p.id, out)
                previous = p
            }
        } finally {
            LlamaNative.free(handle)
        }
    }

    /**
     * 按 HY-MT 官方提示词格式拼：术语干预（只带这句里出现的术语）+ 上一句作为上文 + 翻译指令。
     * 见 https://huggingface.co/tencent/HY-MT1.5-1.8B 的 Prompts 一节。
     */
    private fun prompt(text: String, previous: String?): String = buildString {
        val hits = terms.filter { text.contains(it.source, ignoreCase = true) }
        if (hits.isNotEmpty()) {
            append("参考下面的翻译：\n")
            hits.forEach { append("${it.source} 翻译成 ${it.target}\n") }
            append("\n")
        }
        if (previous != null) {
            append(previous).append("\n")
            append("参考上面的信息，把下面的文本翻译成中文，注意不需要翻译上文，也不要额外解释：\n")
        } else {
            append("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n")
        }
        append(text)
    }

    /** 可增长的 float 数组，避免装箱。 */
    private class FloatBuffer {
        private var data = FloatArray(SAMPLE_RATE * 10)
        var size = 0
            private set

        fun append(src: FloatArray) {
            if (size + src.size > data.size) data = data.copyOf((size + src.size) * 2)
            System.arraycopy(src, 0, data, size, src.size)
            size += src.size
        }

        fun clear() {
            size = 0
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
