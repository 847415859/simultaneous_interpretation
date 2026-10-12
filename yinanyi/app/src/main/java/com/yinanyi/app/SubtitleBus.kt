package com.yinanyi.app

import kotlinx.coroutines.flow.MutableStateFlow

data class SubtitleLine(val id: Long, val original: String, val translation: String?)

enum class EngineMode(val label: String) { ONLINE("在线"), OFFLINE("离线") }

/** 引擎往这里写结果，引擎只认这个接口，不关心界面。 */
interface SubtitleSink {
    /** 正在说的话（还会变），null 或空串表示清空。 */
    fun partial(text: String)
    fun addLine(original: String, translation: String?): Long
    fun setTranslation(id: Long, translation: String)
    fun status(text: String)
    /** 引擎无法继续（断网、鉴权失败等），由控制器决定切换。 */
    fun failed(reason: String)
}

/** 全局字幕状态：服务、悬浮窗、首页都读这里。 */
object SubtitleBus : SubtitleSink {
    private const val KEEP_LINES = 50

    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val mode = MutableStateFlow(EngineMode.ONLINE)
    val status = MutableStateFlow("")
    val partialText = MutableStateFlow("")
    val lines = MutableStateFlow<List<SubtitleLine>>(emptyList())

    /** 由 TranslationService 设置，用来处理引擎失败。 */
    @Volatile
    var onFailed: ((String) -> Unit)? = null

    private var nextId = 1L

    fun clear() {
        partialText.value = ""
        lines.value = emptyList()
        status.value = ""
    }

    override fun partial(text: String) {
        partialText.value = text
    }

    @Synchronized
    override fun addLine(original: String, translation: String?): Long {
        val id = nextId++
        lines.value = (lines.value + SubtitleLine(id, original, translation)).takeLast(KEEP_LINES)
        return id
    }

    @Synchronized
    override fun setTranslation(id: Long, translation: String) {
        lines.value = lines.value.map { if (it.id == id) it.copy(translation = translation) else it }
    }

    override fun status(text: String) {
        status.value = text
    }

    override fun failed(reason: String) {
        status.value = reason
        onFailed?.invoke(reason)
    }
}
