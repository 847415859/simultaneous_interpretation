package com.yinanyi.captest

import android.os.SystemClock
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** llama.cpp 的 JNI 入口，实现在 src/main/cpp/translator.cpp。 */
object LlamaNative {
    init {
        System.loadLibrary("yinanyi_llm")
    }

    external fun load(path: String, threads: Int): Long
    external fun translate(handle: Long, system: String, user: String, maxTokens: Int): ByteArray
    external fun free(handle: Long)
}

/**
 * 在独立线程里逐句翻译识别结果。跟不上时只保留最新的几句，
 * 旧句子标记为「跳过」，保证字幕不会越积越晚。
 */
class Translator(private val modelFile: File) {

    companion object {
        private const val THREADS = 4
        private const val MAX_TOKENS = 128
        private const val MAX_BACKLOG = 2
    }

    private val queue = LinkedBlockingDeque<AsrLine>()

    @Volatile
    private var running = true
    private val thread = Thread({ run() }, "translator")

    private var count = 0
    private var totalMs = 0L

    fun start() = thread.start()

    fun submit(line: AsrLine) {
        queue.offerLast(line)
    }

    fun stop() {
        running = false
        thread.interrupt()
        thread.join(5000)
    }

    private fun run() {
        if (!modelFile.canRead()) {
            CaptureState.mtStatus.value = "没有找到翻译模型：${modelFile.path}"
            return
        }
        CaptureState.mtStatus.value = "正在加载翻译模型……"
        val loadStart = SystemClock.elapsedRealtime()
        val handle = try {
            LlamaNative.load(modelFile.absolutePath, THREADS)
        } catch (e: Throwable) {
            CaptureState.mtStatus.value = "加载翻译模型失败：${e.message}"
            return
        }
        if (handle == 0L) {
            CaptureState.mtStatus.value = "加载翻译模型失败"
            return
        }
        CaptureState.mtStatus.value =
            "翻译模型已加载（${SystemClock.elapsedRealtime() - loadStart} 毫秒）：${modelFile.name}"

        try {
            while (running) {
                val line = try {
                    queue.pollFirst(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                // 积压太多就跳过旧句子
                if (queue.size >= MAX_BACKLOG) {
                    updateLine(line.id) { it.copy(translation = "（跳过：翻译跟不上）") }
                    ResultLog.write(line.copy(translation = "[SKIPPED]"), modelFile.name)
                    continue
                }

                // 「え」「はい」这类一两个字的语气词不值得占用翻译时间
                val letters = line.text.count { it.isLetter() }
                if (letters <= 2) {
                    updateLine(line.id) { it.copy(translation = "") }
                    continue
                }

                val start = SystemClock.elapsedRealtime()
                // 中文译文通常不比原文长太多，限制长度防止模型跑题后停不下来
                val maxTokens = minOf(MAX_TOKENS, line.text.length * 2 + 8)
                val bytes = LlamaNative.translate(handle, "", userPrompt(line), maxTokens)
                val ms = SystemClock.elapsedRealtime() - start
                val text = String(bytes, Charsets.UTF_8).trim()

                count++
                totalMs += ms
                CaptureState.mtStats.value = "已翻译 $count 句，平均每句 ${totalMs / count} 毫秒"
                updateLine(line.id) { it.copy(translation = text, translateMs = ms) }
                ResultLog.write(line.copy(translation = text, translateMs = ms), modelFile.name)
            }
        } finally {
            LlamaNative.free(handle)
        }
    }

    /** 采用 HY-MT 官方的「XX 译中文」提示词格式，对通用小模型同样适用。 */
    private fun userPrompt(line: AsrLine): String =
        "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n${line.text}"

    private fun updateLine(id: Long, change: (AsrLine) -> AsrLine) {
        CaptureState.finalLines.value = CaptureState.finalLines.value.map { if (it.id == id) change(it) else it }
    }
}
