package com.yinanyi.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

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
)

/** 术语表的一条：原文（主播名、游戏名、梗……）固定翻成 target。 */
data class Term(val source: String, val target: String)

/** 字幕窗的位置和大小（像素），-1 表示用默认值。 */
data class OverlayGeometry(val x: Int = -1, val y: Int = -1, val width: Int = -1, val height: Int = -1)

object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = state

    private val glossaryState = MutableStateFlow<List<Term>>(emptyList())
    val glossary: StateFlow<List<Term>> = glossaryState

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
            .apply()
    }

    /** 新增或覆盖一条术语（原文相同则覆盖，忽略大小写）。 */
    fun putTerm(source: String, target: String) {
        val s = source.trim()
        val t = target.trim()
        if (s.isEmpty() || t.isEmpty()) return
        saveGlossary(glossaryState.value.filterNot { it.source.equals(s, ignoreCase = true) } + Term(s, t))
    }

    fun removeTerm(term: Term) = saveGlossary(glossaryState.value - term)

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
