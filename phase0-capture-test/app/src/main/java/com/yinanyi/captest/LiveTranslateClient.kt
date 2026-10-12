package com.yinanyi.captest

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 阿里云百炼 千问 LiveTranslate 实时同传（Realtime API，WebSocket）。
 * 协议参照官方 Python SDK dashscope 1.27.7 的 qwen_omni/omni_realtime.py：
 * 连上后发 session.update（只要文本输出、源语言、目标语言 zh），
 * 再不断发 input_audio_buffer.append（base64 的 16k PCM），结束发 session.finish。
 * 返回事件名没有完整文档，未识别的事件类型会打到 logcat（标签 livetranslate）便于对照。
 */
class LiveTranslateClient(
    private val apiKey: String,
    private val sourceLanguage: String,
    private val onFinal: (AsrLine) -> Unit,
) {
    companion object {
        private const val TAG = "livetranslate"
        const val MODEL = "qwen3.5-livetranslate-flash-realtime"
        private const val URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$MODEL"
        private const val SAMPLE_RATE = 16000
        private const val MAX_LINES = 30
        private const val LOUD_RMS = 300.0 // 约 -40 dBFS，用来粗略判断「正在说话」
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val closed = CountDownLatch(1)
    private val seenTypes = HashSet<String>()

    @Volatile
    private var sentSamples = 0L

    @Volatile
    private var lastLoudMs = 0L

    @Volatile
    private var ready = false

    private val translation = StringBuilder()
    private var sourceText = ""
    private var nextId = 1L
    private var count = 0
    private var totalLatencyMs = 0L

    fun start() {
        CaptureState.asrStatus.value = "正在连接 LiveTranslate……"
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey").build()
        ws = client.newWebSocket(request, listener)
    }

    /** 采集线程调用：100 毫秒一块，转 base64 发出去。 */
    fun send(samples: ShortArray, n: Int) {
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        for (i in 0 until n) {
            buf.putShort(samples[i])
            sum += samples[i].toDouble() * samples[i]
        }
        // 会话确认之前不发音频，避免服务端直接断开
        if (!ready) return
        sentSamples += n
        if (sqrt(sum / n) > LOUD_RMS) lastLoudMs = sentSamples * 1000 / SAMPLE_RATE
        val event = JSONObject()
            .put("event_id", "event_" + UUID.randomUUID().toString().replace("-", ""))
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
        ws?.send(event.toString())
    }

    fun stop() {
        val socket = ws ?: return
        socket.send(JSONObject().put("type", "session.finish").toString())
        closed.await(3, TimeUnit.SECONDS)
        socket.close(1000, null)
        client.dispatcher.executorService.shutdown()
    }

    private fun sessionUpdate(): String {
        val session = JSONObject()
            .put("modalities", JSONArray().put("text"))
            .put("input_audio_format", "pcm")
            .put("sample_rate", SAMPLE_RATE)
            .put("translation", JSONObject().put("language", "zh"))
        val transcription = JSONObject()
        if (sourceLanguage != "auto") transcription.put("language", sourceLanguage)
        session.put("input_audio_transcription", transcription)
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code} ${response.header("x-request-id")}")
            val update = sessionUpdate()
            Log.i(TAG, "send $update")
            webSocket.send(update)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closing $code $reason")
            CaptureState.asrStatus.value = "LiveTranslate 被服务端关闭：$code $reason"
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (seenTypes.add(type)) Log.i(TAG, "first $type: ${text.take(600)}")
            when (type) {
                "session.created" -> CaptureState.asrStatus.value = "LiveTranslate 会话已创建，正在配置……"
                "session.updated" -> {
                    ready = true
                    CaptureState.asrStatus.value = "LiveTranslate 已连接，等待说话……"
                }

                "response.created" -> {
                    translation.setLength(0)
                    committed = 0
                    stash = ""
                }

                // 实测：译文用 response.text.text 流式返回，text 是已确定部分，stash 是还会变的尾巴
                "response.text.text" -> {
                    if (textEventsLogged < 15) {
                        textEventsLogged++
                        Log.i(TAG, "text.text ${text.take(400)}")
                    }
                    val t = json.optString("text")
                    // 兼容两种可能：text 是累计全文，或只是新增片段
                    if (t.length >= translation.length && t.startsWith(translation)) {
                        translation.setLength(0)
                        translation.append(t)
                    } else {
                        translation.append(t)
                    }
                    stash = json.optString("stash")
                    commitSentences(force = false)
                    showPartial()
                }

                // 一段（服务端 VAD 判定的一轮）结束，剩下的全部出字幕
                "response.text.done" -> {
                    val done = json.optString("text")
                    if (done.length >= translation.length) {
                        translation.setLength(0)
                        translation.append(done)
                    }
                    stash = ""
                    commitSentences(force = true)
                    showPartial()
                }

                // 源语言识别文本
                "conversation.item.input_audio_transcription.text",
                "conversation.item.input_audio_transcription.delta" -> {
                    sourceText = json.optString("text").ifEmpty { sourceText + json.optString("delta") }
                    showPartial()
                }
                "conversation.item.input_audio_transcription.completed" ->
                    sourceText = json.optString("transcript").ifEmpty { sourceText }

                "error" -> CaptureState.asrStatus.value =
                    "LiveTranslate 出错：" + (json.optJSONObject("error")?.let { "${it.optString("code")} ${it.optString("message")}" } ?: text.take(200))

                "session.finished" -> closed.countDown()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = try { response?.body?.string()?.take(300) } catch (_: Exception) { null }
            Log.e(TAG, "failure ${response?.code} ${t.javaClass.simpleName} ${t.message} body=$body", t)
            CaptureState.asrStatus.value =
                "LiveTranslate 连接失败：${response?.code ?: ""} ${t.javaClass.simpleName} ${t.message} ${body ?: ""}"
            closed.countDown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
        }
    }

    private var committed = 0
    private var stash = ""
    private var textEventsLogged = 0

    /** 把已确定译文里完整的句子（以 。！？ 结尾）切出来，各自作为一行字幕。 */
    private fun commitSentences(force: Boolean) {
        val all = translation.toString()
        var start = committed
        var i = committed
        var depth = 0 // 书名号、引号里的「！」不算句末，例如《BanG Dream!》
        while (i < all.length) {
            when (all[i]) {
                '《', '「', '『', '“', '（', '(' -> depth++
                '》', '」', '』', '”', '）', ')' -> if (depth > 0) depth--
            }
            if (depth == 0 && all[i] in "。！？!?") {
                // 句末标点后紧跟的引号、括号也算进这一句
                var end = i + 1
                while (end < all.length && all[end] in "」』”）)") end++
                finishSentence(all.substring(start, end))
                start = end
                i = end
                continue
            }
            i++
        }
        if (force && start < all.length) {
            finishSentence(all.substring(start))
            start = all.length
        }
        committed = start
    }

    private fun showPartial() {
        val pending = translation.toString().substring(committed.coerceAtMost(translation.length)) + stash
        CaptureState.partialText.value =
            listOf(pending, sourceText).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun finishSentence(text: String) {
        if (text.isBlank()) return
        // 粗略延迟：现在的音频时间点 - 最近一次有人声的时间点
        val audioNowMs = sentSamples * 1000 / SAMPLE_RATE
        val latency = (audioNowMs - lastLoudMs).coerceAtLeast(0)
        val line = AsrLine(
            id = nextId++,
            text = sourceText,
            lang = sourceLanguage,
            audioSec = 0f,
            decodeMs = latency,
            translation = text.trim(),
        )
        sourceText = ""
        count++
        totalLatencyMs += latency
        CaptureState.asrStats.value = "LiveTranslate 已译 $count 句，说完到出译文平均约 ${totalLatencyMs / count} 毫秒"
        CaptureState.partialText.value = ""
        CaptureState.finalLines.value = (listOf(line) + CaptureState.finalLines.value).take(MAX_LINES)
        onFinal(line)
    }
}
