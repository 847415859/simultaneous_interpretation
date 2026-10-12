package com.yinanyi.captest

import kotlinx.coroutines.flow.MutableStateFlow

/** 采集服务和界面之间共享的状态。 */
object CaptureState {
    val running = MutableStateFlow(false)

    /** 最近 100 毫秒的音量，dBFS，-90 表示静音。 */
    val levelDb = MutableStateFlow(SILENCE_DB)

    /** 本次采集以来的最高音量。 */
    val peakDb = MutableStateFlow(SILENCE_DB)

    val seconds = MutableStateFlow(0)
    val message = MutableStateFlow<String?>(null)
    val lastWavPath = MutableStateFlow<String?>(null)

    /** 离线识别的源语言：auto / en / ja / ko，开始采集时生效。 */
    val asrLanguage = MutableStateFlow("en")
    val asrStatus = MutableStateFlow("未启动")
    val partialText = MutableStateFlow("")
    val finalLines = MutableStateFlow<List<AsrLine>>(emptyList())
    val asrStats = MutableStateFlow("")
    val asrEngine = MutableStateFlow(AsrEngine.SENSE_VOICE)

    /** 离线翻译模型，开始采集时生效。 */
    val mtModel = MutableStateFlow(MtModel.QWEN_15B)
    val mtStatus = MutableStateFlow("")
    val mtStats = MutableStateFlow("")

    fun reset() {
        mtStatus.value = ""
        mtStats.value = ""
        levelDb.value = SILENCE_DB
        peakDb.value = SILENCE_DB
        seconds.value = 0
        message.value = null
        asrStatus.value = "未启动"
        partialText.value = ""
        finalLines.value = emptyList()
        asrStats.value = ""
    }

    const val SILENCE_DB = -90f
}

/** 可选的离线翻译模型；file 位于 files/models/ 下。 */
enum class MtModel(val label: String, val file: String?) {
    NONE("不翻译", null),
    QWEN_05B("Qwen 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
    QWEN_15B("Qwen 1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
    HY_MT("HY-MT 1.8B", "HY-MT1.5-1.8B-Q4_K_M.gguf"),
}
