package com.yinanyi.app.engine

import android.util.Base64
import android.util.Log
import com.yinanyi.app.SourceLang
import com.yinanyi.app.SubtitleSink
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 在线：阿里云百炼 千问 LiveTranslate 实时同传（Realtime API over WebSocket）。
 * 阶段 0 实测的协议要点：
 * - 连上后发 session.update，收到 session.updated 再发音频（input_audio_buffer.append，base64 PCM）；
 * - 译文用 response.text.text 流式返回，text 是新确定的部分，stash 是还会变的尾巴；
 * - 服务端静音 1 秒才结束一轮，直播里一轮可能十几秒，所以客户端按句末标点自己断句。
 */
class OnlineEngine(
    private val apiKey: String,
    private val lang: SourceLang,
    private val sink: SubtitleSink,
) : Engine {

    companion object {
        private const val TAG = "OnlineEngine"
        private const val MODEL = "qwen3.5-livetranslate-flash-realtime"
        private const val URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$MODEL"
        private const val SAMPLE_RATE = 16000
        private const val SENTENCE_END = "。！？!?"
        private const val OPEN = "《「『“（("
        private const val CLOSE = "》」』”）)"
    }

    // 实测服务端不回 WebSocket pong，开 ping 会在 20 秒后被 OkHttp 判定超时断开，所以不开
    private val client = OkHttpClient.Builder().build()
    private val seenTypes = HashSet<String>()
    private var ws: WebSocket? = null
    private val closed = CountDownLatch(1)

    @Volatile
    private var ready = false

    @Volatile
    private var stopping = false

    // 只在 OkHttp 回调线程里读写
    private val translation = StringBuilder()
    private var committed = 0
    private var stash = ""

    override fun start() {
        sink.status("正在连接在线翻译……")
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey").build()
        ws = client.newWebSocket(request, listener)
    }

    override fun feed(samples: ShortArray, n: Int) {
        if (!ready) return
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) buf.putShort(samples[i])
        val event = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
        ws?.send(event.toString())
    }

    override fun stop() {
        stopping = true
        val socket = ws ?: return
        if (ready) {
            socket.send(JSONObject().put("type", "session.finish").toString())
            closed.await(2, TimeUnit.SECONDS)
        }
        socket.close(1000, null)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun sessionUpdate(): String {
        val session = JSONObject()
            .put("modalities", JSONArray().put("text"))
            .put("input_audio_format", "pcm")
            .put("sample_rate", SAMPLE_RATE)
            // 不再发 input_audio_transcription：服务端本来也不回原文，且怀疑 language=en 时会导致无响应
            .put("translation", JSONObject().put("language", "zh"))
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code} lang=${lang.code}")
            webSocket.send(sessionUpdate())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closing $code $reason")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (seenTypes.add(type)) Log.i(TAG, "first $type: ${text.take(300)}")
            when (type) {
                "session.updated" -> {
                    ready = true
                    sink.status("在线翻译已连接")
                }
                "response.created" -> {
                    translation.setLength(0)
                    committed = 0
                    stash = ""
                }
                "response.text.text" -> {
                    val t = json.optString("text")
                    // text 一般是新增片段；万一是累计全文也能正确处理
                    if (t.length >= translation.length && t.startsWith(translation)) {
                        translation.setLength(0)
                    }
                    translation.append(t)
                    stash = json.optString("stash")
                    commitSentences(force = false)
                }
                "response.text.done" -> {
                    val done = json.optString("text")
                    if (done.length >= translation.length) {
                        translation.setLength(0)
                        translation.append(done)
                    }
                    stash = ""
                    commitSentences(force = true)
                }
                "error" -> {
                    val err = json.optJSONObject("error")
                    sink.status("在线翻译出错：${err?.optString("code")} ${err?.optString("message")}")
                }
                "session.finished" -> closed.countDown()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "failure ${response?.code}", t)
            closed.countDown()
            if (!stopping) sink.failed("在线翻译断开：${response?.code ?: ""} ${t.message ?: t.javaClass.simpleName}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
            if (!stopping) sink.failed("在线翻译被服务端关闭：$code $reason")
        }
    }

    /** 已确定译文里每出现一个完整句子就出一行字幕；书名号、引号里的标点不断句。 */
    private fun commitSentences(force: Boolean) {
        val all = translation.toString()
        var start = committed
        var depth = 0
        var i = committed
        while (i < all.length) {
            val c = all[i]
            if (c in OPEN) depth++ else if (c in CLOSE && depth > 0) depth--
            if (depth == 0 && c in SENTENCE_END) {
                var end = i + 1
                while (end < all.length && all[end] in CLOSE) end++
                emit(all.substring(start, end))
                start = end
                i = end
                continue
            }
            i++
        }
        if (force && start < all.length) {
            emit(all.substring(start))
            start = all.length
        }
        committed = start
        sink.partial(all.substring(committed) + stash)
    }

    private fun emit(sentence: String) {
        val s = sentence.trim()
        // 服务端目前不返回源语言原文，原文留空
        if (s.isNotEmpty()) sink.addLine(original = "", translation = s)
    }
}
