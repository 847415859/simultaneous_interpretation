package com.yinanyi.app.engine

import android.content.Context
import com.yinanyi.app.SourceLang
import java.io.File

/** 识别 + 翻译引擎。start/stop 可能耗时，不要在主线程调用。 */
interface Engine {
    fun start()

    /** 采集线程每 100 毫秒调用一次，16k 单声道 PCM。 */
    fun feed(samples: ShortArray, n: Int)
    fun stop()
}

/**
 * 离线模型的位置。开发阶段用 adb 推到 App 外部目录 files/models/，
 * 目录结构与阶段 0 测试 App 相同，便于直接复用已推送的文件。
 */
class Models(context: Context) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val vad = File(dir, "silero_vad.onnx")
    val senseVoice = File(dir, "sense-voice")
    val reazonJa = File(dir, "reazon-ja")
    val translator = File(dir, "HY-MT1.5-1.8B-Q4_K_M.gguf")

    /** 这个语言离线需要的识别模型目录：日语用 ReazonSpeech，英韩用 SenseVoice。 */
    fun asrDir(lang: SourceLang): File = if (lang == SourceLang.JA) reazonJa else senseVoice

    fun missing(lang: SourceLang): List<String> = buildList {
        if (!vad.canRead()) add(vad.name)
        if (!File(asrDir(lang), "tokens.txt").canRead()) add(asrDir(lang).name)
        if (!translator.canRead()) add(translator.name)
    }
}
