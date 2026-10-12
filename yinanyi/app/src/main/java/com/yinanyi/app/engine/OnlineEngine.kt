package com.yinanyi.app.engine

import android.util.Base64
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.yinanyi.app.SourceLang
import com.yinanyi.app.SubtitleSink
import com.yinanyi.app.Term
import com.yinanyi.app.Usage
import java.io.File
import java.util.concurrent.Executors
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
    /** Silero VAD 模型；有就用来跳过没人说话的音频（省钱），没有就全部上传。 */
    private val vadModel: File?,
    /** 术语表，作为 translation.corpus.phrases 发给服务端。 */
    private val terms: List<Term>,
) : Engine {

    companion object {
        private const val TAG = "OnlineEngine"
        private const val MODEL = "qwen3.5-livetranslate-flash-realtime"
        private const val URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$MODEL"
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512
        private const val SPEECH_PROB = 0.5f

        /** 连续这么久没人说话就停止上传。 */
        private const val HANGOVER_MS = 1000L

        /** 停止上传时补发的静音长度，略长于服务端判定一轮结束的 1 秒。 */
        private const val END_SILENCE_MS = 1200L

        /** 不上传期间缓存最近 5 块（0.5 秒）。 */
        private const val PRE_ROLL_CHUNKS = 5
        private const val SENTENCE_END = "。！？!?"
        private const val OPEN = "《「『“（("
        private const val CLOSE = "》」』”）)"
    }

    // 实测服务端不回 WebSocket pong，开 ping 会在 20 秒后被 OkHttp 判定超时断开，所以不开
    private val client = OkHttpClient.Builder().build()
    private val seenTypes = HashSet<String>()

    @Volatile
    private var ws: WebSocket? = null
    private val closed = CountDownLatch(1)

    // ---------- 断线重连 ----------
    // 断线后先原地重连，这几次都失败才交给服务切到离线
    private val retryDelaysMs = longArrayOf(1000, 3000)
    private var attempts = 0
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var ready = false

    @Volatile
    private var stopping = false

    // 只在 OkHttp 回调线程里读写
    private val translation = StringBuilder()
    private var committed = 0
    private var stash = ""

    // ---------- 静音不上传 ----------
    // 只在采集线程里读写
    private var vad: Vad? = null
    private val preRoll = ArrayDeque<ShortArray>()
    private var uploading = false
    private var silentMs = 0L

    override fun start() {
        val model = vadModel
        if (model != null && model.canRead()) {
            vad = runCatching {
                Vad(
                    null,
                    VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(model = model.absolutePath, windowSize = VAD_WINDOW),
                        sampleRate = SAMPLE_RATE,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                )
            }.getOrNull()
        }
        sink.status("正在连接在线翻译……")
        connect()
    }

    private fun connect() {
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey").build()
        ws = client.newWebSocket(request, listener)
    }

    /**
     * 连接断了：鉴权、账户问题直接判失败；其他情况（网络抖动、服务端偶发断开）先重连几次。
     * 只处理当前这条连接的回调，旧连接迟到的回调忽略。
     */
    private fun onDropped(socket: WebSocket, reason: String, fatal: Boolean) {
        if (stopping || socket !== ws) return
        ws = null
        ready = false
        if (!fatal && attempts < retryDelaysMs.size) {
            val delay = retryDelaysMs[attempts++]
            Log.i(TAG, "reconnect #$attempts in ${delay}ms: $reason")
            sink.status("在线连接中断，正在重连……")
            scheduler.schedule({
                if (stopping) return@schedule
                translation.setLength(0)
                committed = 0
                stash = ""
                sink.partial("")
                connect()
            }, delay, TimeUnit.MILLISECONDS)
        } else {
            sink.failed(reason)
        }
    }

    // 与 releaseVad 同步，避免切换引擎时采集线程还在用已释放的 VAD
    @Synchronized
    override fun feed(samples: ShortArray, n: Int) {
        if (!ready || stopping) return
        val chunk = samples.copyOf(n)
        val chunkMs = n * 1000L / SAMPLE_RATE
        val v = vad
        if (v == null) {
            send(chunk)
            Usage.addAudio(chunkMs, uploaded = true)
            return
        }

        if (hasSpeech(v, chunk)) {
            silentMs = 0
            if (!uploading) {
                // 补发说话前缓存的半秒，避免句首被切掉
                while (preRoll.isNotEmpty()) send(preRoll.removeFirst())
                uploading = true
            }
        } else {
            silentMs += chunkMs
        }

        if (uploading) {
            send(chunk)
            Usage.addAudio(chunkMs, uploaded = true)
            if (silentMs >= HANGOVER_MS) {
                // 停止上传前补一段静音，让服务端判定这一轮结束、把剩下的译文吐出来
                repeat((END_SILENCE_MS / chunkMs.coerceAtLeast(1)).toInt()) { send(ShortArray(n)) }
                uploading = false
            }
        } else {
            preRoll.addLast(chunk)
            while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
            Usage.addAudio(chunkMs, uploaded = false)
        }
    }

    /** 100 毫秒的块里任意一个 512 点窗口像人声就算有人说话。 */
    private fun hasSpeech(v: Vad, chunk: ShortArray): Boolean {
        var i = 0
        while (i + VAD_WINDOW <= chunk.size) {
            val window = FloatArray(VAD_WINDOW) { chunk[i + it] / 32768f }
            if (v.compute(window) > SPEECH_PROB) return true
            i += VAD_WINDOW
        }
        return false
    }

    private fun send(chunk: ShortArray) {
        val buf = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in chunk) buf.putShort(s)
        val event = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
        ws?.send(event.toString())
    }

    override fun stop() {
        stopping = true
        scheduler.shutdownNow()
        val socket = ws ?: run {
            releaseVad()
            return
        }
        if (ready) {
            socket.send(JSONObject().put("type", "session.finish").toString())
            closed.await(2, TimeUnit.SECONDS)
        }
        socket.close(1000, null)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Usage.save()
        releaseVad()
    }

    @Synchronized
    private fun releaseVad() {
        vad?.release()
        vad = null
    }

    private fun sessionUpdate(): String {
        val session = JSONObject()
            .put("modalities", JSONArray().put("text"))
            .put("input_audio_format", "pcm")
            .put("sample_rate", SAMPLE_RATE)
        // 不再发 input_audio_transcription：服务端本来也不回原文，且怀疑 language=en 时会导致无响应
        val translation = JSONObject().put("language", "zh")
        if (terms.isNotEmpty()) {
            val phrases = JSONObject()
            terms.forEach { phrases.put(it.source, it.target) }
            translation.put("corpus", JSONObject().put("phrases", phrases))
        }
        session.put("translation", translation)
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
                    attempts = 0
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
                "response.done" -> json.optJSONObject("response")?.optJSONObject("usage")?.let {
                    Usage.addTokens(it.optLong("input_tokens"), it.optLong("output_tokens"))
                }
                "session.finished" -> closed.countDown()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "failure ${response?.code}", t)
            closed.countDown()
            val code = response?.code ?: 0
            onDropped(
                webSocket,
                "在线翻译断开：${if (code > 0) code else ""} ${t.message ?: t.javaClass.simpleName}",
                fatal = code == 401 || code == 403,
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
            // 1007 是「Access denied, 账户状态异常」，重连也没用
            onDropped(webSocket, "在线翻译被服务端关闭：$code $reason", fatal = code == 1007)
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
