package com.yinanyi.app.translate

import android.content.Context
import com.yinanyi.app.SettingsStore
import com.yinanyi.app.engine.LlamaNative
import com.yinanyi.app.engine.Models
import com.yinanyi.app.models.ModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslateFailure(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NEED_SETUP, NEED_MODEL, AUTH, NETWORK, LIMIT, OTHER }
}

data class TranslateRequest(
    val text: String,
    val target: TargetLang,
    val tone: ToneDef,
    val keepTokens: Boolean,
    val onlineFirst: Boolean,
)

/** [online] 为 false 表示用了离线；[notice] 是要告诉用户的话（比如在线失败改用了离线）。 */
data class TranslateOutcome(val text: String, val online: Boolean, val millis: Long, val notice: String? = null)

/**
 * 输入翻译的引擎：在线用阿里云百炼的通用大模型（能按语气和术语要求翻译），
 * 离线用本机的 HY-MT。在线失败自动改用离线。
 */
object TextTranslator {
    private const val MODEL = "qwen-plus"
    private const val URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun offlineReady(context: Context): Boolean = ModelCatalog.TRANSLATOR.isInstalled(Models(context).dir)

    /** 在线可用：用户选了在线优先、填了 Key，并且确认过上传。 */
    fun onlineAllowed(onlineFirst: Boolean): Boolean =
        onlineFirst && SettingsStore.apiKey.value.isNotEmpty() && SettingsStore.onlineConsent

    suspend fun translate(context: Context, req: TranslateRequest): TranslateOutcome {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        var notice: String? = null
        val ready = offlineReady(context)

        if (onlineAllowed(req.onlineFirst)) {
            try {
                return TranslateOutcome(online(req), true, ms())
            } catch (e: TranslateFailure) {
                if (!ready) throw e
                notice = when (e.kind) {
                    TranslateFailure.Kind.AUTH -> "API Key 无效，已改用离线模型。"
                    TranslateFailure.Kind.LIMIT -> "在线翻译太频繁，已改用离线模型。"
                    else -> "在线翻译暂时不可用，已改用离线模型。离线译文可能没那么自然。"
                }
            }
        }
        if (!ready) {
            val noKey = SettingsStore.apiKey.value.isEmpty()
            throw TranslateFailure(
                if (req.onlineFirst && noKey) TranslateFailure.Kind.NEED_SETUP else TranslateFailure.Kind.NEED_MODEL,
                if (req.onlineFirst && noKey) "还没有填写 API Key，也没有下载离线模型" else "还没有下载离线翻译模型",
            )
        }
        return TranslateOutcome(OfflineText.translate(context, req), false, ms(), notice)
    }

    /** 其他说法：只有在线能给（离线模型不擅长按要求换说法）。 */
    suspend fun alternatives(req: TranslateRequest, main: String): List<String> {
        if (!onlineAllowed(req.onlineFirst)) return emptyList()
        val system = TextRules.systemPrompt(req.target, req.tone, SettingsStore.glossary.value, req.text, req.keepTokens, alternatives = true)
        return TextRules.parseAlternatives(chat(system, req.text, temperature = 0.9), main)
    }

    private suspend fun online(req: TranslateRequest): String {
        val system = TextRules.systemPrompt(req.target, req.tone, SettingsStore.glossary.value, req.text, req.keepTokens)
        return chat(system, req.text, temperature = 0.3).trim()
    }

    private suspend fun chat(system: String, user: String, temperature: Double): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("temperature", temperature)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString()
        val request = HttpRequest.Builder().url(URL)
            .header("Authorization", "Bearer ${SettingsStore.apiKey.value}")
            .post(body.toRequestBody(JSON))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw TranslateFailure(TranslateFailure.Kind.NETWORK, "没连上在线翻译")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 || r.code == 403 -> throw TranslateFailure(TranslateFailure.Kind.AUTH, "API Key 无效")
                r.code == 429 -> throw TranslateFailure(TranslateFailure.Kind.LIMIT, "请求太频繁")
                !r.isSuccessful -> throw TranslateFailure(TranslateFailure.Kind.NETWORK, "在线翻译返回 ${r.code}")
            }
            return runCatching {
                JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }.getOrElse { throw TranslateFailure(TranslateFailure.Kind.OTHER, "在线翻译返回了看不懂的内容") }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
}

/** 离线文字翻译：模型第一次用时才加载，空闲 60 秒后释放，避免常驻 1 GB 多内存。 */
private object OfflineText {
    private const val THREADS = 4
    private const val IDLE_MS = 60_000L
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var handle = 0L
    private var freeJob: Job? = null

    suspend fun translate(context: Context, req: TranslateRequest): String = lock.withLock {
        freeJob?.cancel()
        try {
            withContext(Dispatchers.Default) {
                if (handle == 0L) handle = LlamaNative.load(Models(context).translator.absolutePath, THREADS)
                if (handle == 0L) throw TranslateFailure(TranslateFailure.Kind.OTHER, "离线翻译模型加载失败")
                run(req)
            }
        } finally {
            freeJob = scope.launch {
                delay(IDLE_MS)
                lock.withLock {
                    if (handle != 0L) {
                        LlamaNative.free(handle)
                        handle = 0L
                    }
                }
            }
        }
    }

    private suspend fun run(req: TranslateRequest): String {
        val (work, tokens) = if (req.keepTokens) TextRules.mask(req.text) else req.text to emptyList()
        val toChinese = req.target.code == "zh"
        val glossary = TextRules.glossaryFor(req.text, SettingsStore.glossary.value, toChinese)
        val glue = if (req.target.code in setOf("zh", "ja", "th")) "" else " "
        val lines = ArrayList<String>()
        for (chunks in TextRules.linesAndChunks(work)) {
            val parts = ArrayList<String>()
            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()
                val prompt = buildString {
                    if (glossary.isNotEmpty()) {
                        append("参考下面的翻译：\n")
                        glossary.forEach { append("${it.first} 翻译成 ${it.second}\n") }
                        append("\n")
                    }
                    append(TextRules.hyMtPrompt(chunk, req.target.zh))
                }
                parts += String(LlamaNative.translate(handle, "", prompt, minOf(700, chunk.length * 3 + 16)), Charsets.UTF_8).trim()
            }
            lines += parts.joinToString(glue)
        }
        val out = lines.joinToString("\n")
        return TextRules.unmask(out, tokens)
    }
}
