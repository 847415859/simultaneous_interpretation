package com.yinanyi.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.yinanyi.app.translate.ToneConfig
import java.util.Properties

enum class SourceLang(val code: String, val label: String, val short: String) {
    EN("en", "英语", "英"),
    JA("ja", "日语", "日"),
    KO("ko", "韩语", "韩"),
}

/** 用户设置，全部有默认值；对应需求文档的 P0 功能和设置页原型。 */
data class AppSettings(
    val sourceLang: SourceLang = SourceLang.EN,
    val showOriginal: Boolean = true,
    val fontSize: Int = 18,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val bgColor: Int = 0xFF101217.toInt(),
    /** 背景透明度 0–100，0 为不透明。 */
    val transparency: Int = 20,
    val maxLines: Int = 3,
    val forceOffline: Boolean = false,
    /** 每天在线翻译的估算费用上限（元），超过后自动切离线；0 表示不限制。 */
    val dailyLimitYuan: Double = 10.0,
    /** 保存翻译记录（只在本机）。 */
    val saveHistory: Boolean = true,
    /** 可选：只录来源 App 的声音，不录手机上其他 App 的播放；默认关，录所有播放的声音。 */
    val onlyBili: Boolean = false,
    /** 字幕文字描边，亮背景下更清楚。 */
    val textOutline: Boolean = true,
    /** 一句字幕在屏幕上停留多少秒后消失；0 表示一直留到被新字幕顶掉。 */
    val lineSeconds: Int = 0,
    /** 来源 App 的包名；空表示 B站（国内版、HD 版、国际版哪个装了用哪个）。 */
    val sourcePkg: String = "",
    /** 点「开始翻译」后自动打开来源 App。 */
    val autoOpen: Boolean = true,
    /** 连续这么多分钟没有声音就自动停止翻译；0 表示不自动停止。 */
    val idleStopMinutes: Int = 30,
    /** 输入翻译：在文字选择菜单里显示「译成…」。 */
    val inputMenu: Boolean = true,
    /** 菜单里点「译成X」直接替换，不打开面板。 */
    val inputQuick: Boolean = true,
    /** 默认译成哪种语言（语言代码）。面板里换了也会记住。 */
    val inputLang: String = "en",
    /** 默认语气（语气 id）。 */
    val inputTone: String = "casual",
    /** 在线优先；关掉就只用离线。 */
    val inputOnline: Boolean = true,
    /** 一开始就给出备选说法；关掉则折叠，点开才生成。 */
    val inputAlts: Boolean = false,
    /** 保留 @提及、[表情]、链接和 emoji 不翻译。 */
    val inputKeepTokens: Boolean = true,
    /** 最近用过的目标语言，用逗号隔开，新的在前。 */
    val inputRecent: String = "en,ja,ko",
    /** 输入框旁显示「译」按钮（需要无障碍服务）。 */
    val inputFloat: Boolean = false,
    /** 用户关掉了「译」按钮的 App 包名，逗号隔开。 */
    val inputFloatOff: String = "",
)

/** 术语表的一条：原文（主播名、游戏名、梗……）固定翻成 target。 */
data class Term(val source: String, val target: String)

/** 字幕窗的位置和大小（像素），-1 表示用默认值。 */
data class OverlayGeometry(val x: Int = -1, val y: Int = -1, val width: Int = -1, val height: Int = -1)

object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = state

    private val tonesState = MutableStateFlow(ToneConfig())
    val tones: StateFlow<ToneConfig> = tonesState

    fun updateTones(change: (ToneConfig) -> ToneConfig) {
        tonesState.value = change(tonesState.value)
        prefs.edit().putString("tones", tonesState.value.toJson()).apply()
    }

    private val glossaryState = MutableStateFlow<List<Term>>(emptyList())
    val glossary: StateFlow<List<Term>> = glossaryState

    /**
     * 阿里云百炼 API Key。只存在本机 App 私有目录，不打进安装包，
     * 这样安装包可以公开发布而不泄露 Key。
     */
    private val apiKeyState = MutableStateFlow("")
    val apiKey: StateFlow<String> = apiKeyState

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        apiKeyState.value = prefs.getString("apiKey", "") ?: ""
        tonesState.value = ToneConfig.fromJson(prefs.getString("tones", null))
        importKeyFile(context)
        glossaryState.value = runCatching {
            val arr = JSONArray(prefs.getString("glossary", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Term(o.getString("s"), o.getString("t")) } }
        }.getOrDefault(emptyList())
        val d = AppSettings()
        state.value = AppSettings(
            sourceLang = SourceLang.entries.firstOrNull { it.code == prefs.getString("sourceLang", null) } ?: d.sourceLang,
            showOriginal = prefs.getBoolean("showOriginal", d.showOriginal),
            fontSize = prefs.getInt("fontSize", d.fontSize),
            textColor = prefs.getInt("textColor", d.textColor),
            bgColor = prefs.getInt("bgColor", d.bgColor),
            transparency = prefs.getInt("transparency", d.transparency),
            maxLines = prefs.getInt("maxLines", d.maxLines),
            forceOffline = prefs.getBoolean("forceOffline", d.forceOffline),
            dailyLimitYuan = prefs.getFloat("dailyLimit", d.dailyLimitYuan.toFloat()).toDouble(),
            saveHistory = prefs.getBoolean("saveHistory", d.saveHistory),
            onlyBili = prefs.getBoolean("onlyBili", d.onlyBili),
            textOutline = prefs.getBoolean("textOutline", d.textOutline),
            lineSeconds = prefs.getInt("lineSeconds", d.lineSeconds),
            sourcePkg = prefs.getString("sourcePkg", d.sourcePkg) ?: d.sourcePkg,
            autoOpen = prefs.getBoolean("autoOpen", d.autoOpen),
            idleStopMinutes = prefs.getInt("idleStopMinutes", d.idleStopMinutes),
            inputMenu = prefs.getBoolean("inputMenu", d.inputMenu),
            inputQuick = prefs.getBoolean("inputQuick", d.inputQuick),
            inputLang = prefs.getString("inputLang", d.inputLang) ?: d.inputLang,
            inputTone = prefs.getString("inputTone", d.inputTone) ?: d.inputTone,
            inputOnline = prefs.getBoolean("inputOnline", d.inputOnline),
            inputAlts = prefs.getBoolean("inputAlts", d.inputAlts),
            inputKeepTokens = prefs.getBoolean("inputKeepTokens", d.inputKeepTokens),
            inputRecent = prefs.getString("inputRecent", d.inputRecent) ?: d.inputRecent,
            inputFloat = prefs.getBoolean("inputFloat", d.inputFloat),
            inputFloatOff = prefs.getString("inputFloatOff", d.inputFloatOff) ?: d.inputFloatOff,
        )
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(state.value)
        state.value = s
        prefs.edit()
            .putString("sourceLang", s.sourceLang.code)
            .putBoolean("showOriginal", s.showOriginal)
            .putInt("fontSize", s.fontSize)
            .putInt("textColor", s.textColor)
            .putInt("bgColor", s.bgColor)
            .putInt("transparency", s.transparency)
            .putInt("maxLines", s.maxLines)
            .putBoolean("forceOffline", s.forceOffline)
            .putFloat("dailyLimit", s.dailyLimitYuan.toFloat())
            .putBoolean("saveHistory", s.saveHistory)
            .putBoolean("onlyBili", s.onlyBili)
            .putBoolean("textOutline", s.textOutline)
            .putInt("lineSeconds", s.lineSeconds)
            .putString("sourcePkg", s.sourcePkg)
            .putBoolean("autoOpen", s.autoOpen)
            .putInt("idleStopMinutes", s.idleStopMinutes)
            .putBoolean("inputMenu", s.inputMenu)
            .putBoolean("inputQuick", s.inputQuick)
            .putString("inputLang", s.inputLang)
            .putString("inputTone", s.inputTone)
            .putBoolean("inputOnline", s.inputOnline)
            .putBoolean("inputAlts", s.inputAlts)
            .putBoolean("inputKeepTokens", s.inputKeepTokens)
            .putString("inputRecent", s.inputRecent)
            .putBoolean("inputFloat", s.inputFloat)
            .putString("inputFloatOff", s.inputFloatOff)
            .apply()
    }

    /** 用户是否确认过「在线翻译会把声音上传到阿里云」。 */
    var onlineConsent: Boolean
        get() = prefs.getBoolean("onlineConsent", false)
        set(value) = prefs.edit().putBoolean("onlineConsent", value).apply()

    /** 新手引导是否走完（或被跳过）。 */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    fun setApiKey(key: String) {
        apiKeyState.value = key.trim()
        prefs.edit().putString("apiKey", apiKeyState.value).apply()
    }

    /**
     * 方便导入：用 adb 把 keys.properties（内容 dashscope_api_key=sk-…）推到
     * /sdcard/Android/data/com.yinanyi.app/files/，下次启动自动读进来并删除该文件。
     */
    private fun importKeyFile(context: Context) {
        val file = File(context.getExternalFilesDir(null) ?: return, "keys.properties")
        if (!file.canRead()) return
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }
        props.getProperty("dashscope_api_key")?.trim()?.takeIf { it.isNotEmpty() }?.let { setApiKey(it) }
        file.delete()
    }

    /** 新增或覆盖一条术语（原文相同则覆盖，忽略大小写）。 */
    fun putTerm(source: String, target: String) {
        val s = source.trim()
        val t = target.trim()
        if (s.isEmpty() || t.isEmpty()) return
        saveGlossary(glossaryState.value.filterNot { it.source.equals(s, ignoreCase = true) } + Term(s, t))
    }

    fun removeTerm(term: Term) = saveGlossary(glossaryState.value - term)

    /** 批量加入术语，返回实际加入的条数。 */
    fun putTerms(terms: List<Term>): Int {
        terms.forEach { putTerm(it.source, it.target) }
        return terms.size
    }

    private fun saveGlossary(list: List<Term>) {
        glossaryState.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("s", it.source).put("t", it.target)) }
        prefs.edit().putString("glossary", arr.toString()).apply()
    }

    fun geometry(landscape: Boolean): OverlayGeometry {
        val p = if (landscape) "land_" else "port_"
        return OverlayGeometry(
            prefs.getInt(p + "x", -1), prefs.getInt(p + "y", -1),
            prefs.getInt(p + "w", -1), prefs.getInt(p + "h", -1),
        )
    }

    fun saveGeometry(landscape: Boolean, g: OverlayGeometry) {
        val p = if (landscape) "land_" else "port_"
        prefs.edit().putInt(p + "x", g.x).putInt(p + "y", g.y).putInt(p + "w", g.width).putInt(p + "h", g.height).apply()
    }
}

/** 术语表的批量导入 / 导出格式：每行一条，原文和译文用 = 、→、-> 、Tab 或逗号隔开。 */
object GlossaryText {
    // 明确的分隔符优先；只有整行都没有它们时才退而求其次用逗号、冒号（名字里可能自带冒号）
    private val STRONG = listOf("=", "＝", "→", "->", "\t")
    private val WEAK = listOf("，", ",", "：", ":")

    fun parse(text: String): List<Term> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        // 取最靠前的分隔符，这样译文里再出现逗号也不会被切开
        fun firstOf(seps: List<String>) = seps.map { line.indexOf(it) to it }.filter { it.first > 0 }.minByOrNull { it.first }
        val (idx, sep) = firstOf(STRONG) ?: firstOf(WEAK) ?: return@mapNotNull null
        val source = line.substring(0, idx).trim()
        val target = line.substring(idx + sep.length).trim()
        if (source.isEmpty() || target.isEmpty()) null else Term(source, target)
    }.toList()

    fun format(terms: List<Term>): String = terms.joinToString("\n") { "${it.source}=${it.target}" }
}
