package com.yinanyi.captest

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 离线识别：Silero VAD 把声音切成一句一句，SenseVoice 识别每一句。
 * 说话过程中每隔一小段时间对已有部分识别一次，作为临时结果。
 * 在独立线程里运行，不阻塞采集线程。
 */
class OfflineAsr(
    private val modelDir: File,
    private val engine: AsrEngine,
    private val language: String,
    private val onFinal: (AsrLine) -> Unit,
) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512
        private const val PARTIAL_INTERVAL_MS = 700L
        private const val MAX_LINES = 30
    }

    private val queue = LinkedBlockingQueue<FloatArray>(300)

    @Volatile
    private var running = true
    private val thread = Thread({ run() }, "offline-asr")

    private var totalAudioSec = 0f
    private var totalDecodeMs = 0L
    private var dropped = 0

    fun start() = thread.start()

    /** 采集线程调用；队列满时丢弃，说明识别跟不上。 */
    fun offer(samples: FloatArray) {
        if (!queue.offer(samples)) dropped++
    }

    fun stop() {
        running = false
        queue.offer(FloatArray(0))
        thread.join(3000)
    }

    private fun run() {
        val vadModel = File(modelDir, "silero_vad.onnx")
        if (!vadModel.canRead()) {
            CaptureState.asrStatus.value = "没有找到模型：${vadModel.path}"
            return
        }

        CaptureState.asrStatus.value = "正在加载 ${engine.label}……"
        val loadStart = SystemClock.elapsedRealtime()
        val recognizer: OfflineRecognizer
        val vad: Vad
        try {
            recognizer = OfflineRecognizer(
                null,
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig(),
                ),
            )
            vad = Vad(
                null,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModel.absolutePath,
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
        } catch (e: Throwable) {
            CaptureState.asrStatus.value = "加载模型失败：${e.message}"
            return
        }
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        CaptureState.asrStatus.value = "${engine.label} 已加载（${loadMs} 毫秒），等待说话……"

        val window = FloatArray(VAD_WINDOW)
        var windowLen = 0
        val speech = FloatBuffer()
        var lastPartialAt = 0L

        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
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
                    decodeFinal(recognizer, segment.samples)
                    speech.clear()
                    CaptureState.partialText.value = ""
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (vad.isSpeechDetected() && speech.size > SAMPLE_RATE / 2 &&
                now - lastPartialAt > PARTIAL_INTERVAL_MS
            ) {
                lastPartialAt = now
                val text = decode(recognizer, speech.toArray()).text.trim()
                if (text.isNotEmpty()) CaptureState.partialText.value = text
            }
        }

        vad.release()
        recognizer.release()
    }

    /** 按引擎拼出模型配置；文件按前缀查找，同名时优先 int8 版。 */
    private fun modelConfig(): OfflineModelConfig {
        val dir = File(modelDir, engine.dir)
        val tokens = File(dir, "tokens.txt")
        fun onnx(prefix: String): String? = dir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?.minByOrNull { if (it.name.contains("int8")) 0 else 1 }
            ?.absolutePath
        fun require(path: String?, what: String) =
            path ?: throw IllegalStateException("${dir.path} 里没有 $what")
        if (!tokens.canRead()) throw IllegalStateException("没有找到 ${tokens.path}")

        // 识别只用 2 个线程，把其余大核留给翻译模型
        val base = OfflineModelConfig(tokens = tokens.absolutePath, numThreads = 2, provider = "cpu")
        return when (engine) {
            AsrEngine.SENSE_VOICE -> base.apply {
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = require(onnx("model"), "model*.onnx"),
                    language = language,
                    useInverseTextNormalization = true,
                )
            }
            AsrEngine.LIVETRANSLATE -> throw IllegalStateException("LiveTranslate 是在线服务，不走离线识别")
            AsrEngine.PARAKEET_JA, AsrEngine.REAZON_JA -> {
                val encoder = onnx("encoder")
                if (encoder != null) {
                    base.apply {
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = require(onnx("decoder"), "decoder*.onnx"),
                            joiner = require(onnx("joiner"), "joiner*.onnx"),
                        )
                        if (engine == AsrEngine.PARAKEET_JA) modelType = "nemo_transducer"
                    }
                } else {
                    base.apply { nemo = OfflineNemoEncDecCtcModelConfig(model = require(onnx("model"), "model*.onnx")) }
                }
            }
        }
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray) =
        recognizer.createStream().let { stream ->
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()
            result
        }

    private fun decodeFinal(recognizer: OfflineRecognizer, samples: FloatArray) {
        val start = SystemClock.elapsedRealtime()
        val result = decode(recognizer, samples)
        val decodeMs = SystemClock.elapsedRealtime() - start
        val audioSec = samples.size.toFloat() / SAMPLE_RATE

        totalAudioSec += audioSec
        totalDecodeMs += decodeMs
        val rtf = totalDecodeMs / 1000f / totalAudioSec
        CaptureState.asrStats.value =
            "已识别 %.1f 秒语音，平均每秒语音耗时 %d 毫秒（实时率 %.2f）%s".format(
                totalAudioSec, (rtf * 1000).toInt(), rtf,
                if (dropped > 0) "，丢弃 $dropped 段（跟不上）" else "",
            )

        val text = result.text.trim()
        if (text.isEmpty()) return
        // 只有 SenseVoice 会报告语种；日语专用模型固定为 ja
        val detected = result.lang.removePrefix("<|").removeSuffix("|>")
        val line = AsrLine(
            id = nextId++,
            text = text,
            lang = when {
                engine != AsrEngine.SENSE_VOICE -> "ja"
                detected.isNotEmpty() -> detected
                else -> language
            },
            audioSec = audioSec,
            decodeMs = decodeMs,
        )
        CaptureState.finalLines.value = (listOf(line) + CaptureState.finalLines.value).take(MAX_LINES)
        onFinal(line)
    }

    private var nextId = 1L

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

/** 可选的识别模型；dir 是 files/models/ 下的子目录。 */
enum class AsrEngine(val label: String, val dir: String) {
    SENSE_VOICE("SenseVoice（英日韩）", "sense-voice"),
    PARAKEET_JA("Parakeet（日语专用）", "parakeet-ja"),
    REAZON_JA("ReazonSpeech（日语专用）", "reazon-ja"),
    LIVETRANSLATE("阿里云 LiveTranslate（在线，自带翻译）", ""),
}

data class AsrLine(
    val id: Long,
    val text: String,
    val lang: String,
    val audioSec: Float,
    val decodeMs: Long,
    val translation: String? = null,
    val translateMs: Long = 0,
)
