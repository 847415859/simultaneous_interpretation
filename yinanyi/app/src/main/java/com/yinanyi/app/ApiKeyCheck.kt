package com.yinanyi.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** API Key 的识别、保存和连通性检测。检测只握手不发音频，不产生费用。 */
object ApiKeyCheck {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object Ok : State
        data class Bad(val message: String) : State
    }

    private val KEY_PATTERN = Regex("sk-[A-Za-z0-9]{16,}")
    private const val URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3.5-livetranslate-flash-realtime"

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    /** 从任意文本里找出 API Key（复制时常带空格、换行或前后缀）。 */
    fun extract(text: CharSequence?): String? = text?.let { KEY_PATTERN.find(it)?.value }

    /** 保存并立即检测。 */
    fun save(key: String) {
        SettingsStore.setApiKey(key)
        verify()
    }

    fun clear() {
        job?.cancel()
        SettingsStore.setApiKey("")
        stateFlow.value = State.Idle
    }

    fun verify() {
        val key = SettingsStore.apiKey.value
        if (key.isEmpty()) return
        job?.cancel()
        stateFlow.value = State.Checking
        job = scope.launch {
            val result = check(key)
            // 检测期间 Key 被改过就丢弃这次结果
            if (SettingsStore.apiKey.value == key) stateFlow.value = result
        }
    }

    private fun check(key: String): State {
        val outcome = AtomicReference<State>()
        val done = CountDownLatch(1)
        fun finish(s: State) {
            if (outcome.compareAndSet(null, s)) done.countDown()
        }
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $key").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 和正式翻译一样先发 session.update，收到 session.updated 才算真正可用
                webSocket.send(
                    JSONObject().put("type", "session.update").put(
                        "session",
                        JSONObject()
                            .put("modalities", JSONArray().put("text"))
                            .put("input_audio_format", "pcm")
                            .put("sample_rate", 16000)
                            .put("translation", JSONObject().put("language", "zh")),
                    ).toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "session.updated" -> finish(State.Ok)
                    "error" -> finish(State.Bad(json.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "服务端返回错误" }))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                finish(if (code == 1007) NO_ACCESS else State.Bad("连接被服务端关闭（$code）"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                finish(
                    when {
                        code == 401 || code == 403 -> State.Bad("Key 无效：请确认复制完整，并且是北京地域的 Key")
                        code != null -> State.Bad("服务端返回 $code")
                        else -> State.Bad("没连上阿里云，检查网络后再测试")
                    },
                )
            }
        })
        if (!done.await(8, TimeUnit.SECONDS)) finish(State.Bad("连接超时，检查网络后再测试"))
        ws.cancel()
        return outcome.get()
    }

    private val NO_ACCESS = State.Bad("Key 有效，但账号没有开通该模型或欠费，请到百炼控制台确认")
}
