package com.yinanyi.app.engine

import android.content.Context
import com.yinanyi.app.SourceLang
import com.yinanyi.app.models.ModelCatalog
import com.yinanyi.app.models.ModelPack
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
class Models(private val context: Context) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val vad = File(dir, "silero_vad.onnx")
    val senseVoice = File(dir, "sense-voice")
    val reazonJa = File(dir, "reazon-ja")
    val translator = File(dir, "HY-MT1.5-1.8B-Q4_K_M.gguf")

    /** 这个语言离线需要的识别模型目录：日语用 ReazonSpeech，英韩用 SenseVoice。 */
    fun asrDir(lang: SourceLang): File = if (lang == SourceLang.JA) reazonJa else senseVoice

    /** 这个语言离线翻译还缺哪些模型包（空表示齐了）。 */
    fun missingPacks(lang: SourceLang): List<ModelPack> =
        ModelCatalog.packsFor(lang).filter { !it.isInstalled(dir) }

    fun missing(lang: SourceLang): List<String> = buildList {
        if (!vad.isFile) add(vad.name)
        missingPacks(lang).forEach { add(it.label) }
    }

    /**
     * 语音检测模型很小（643 KB），直接打在安装包里，第一次运行时放到模型目录，不用下载。
     * Silero VAD，MIT 许可证。
     */
    fun ensureVad() {
        if (vad.isFile && vad.length() == VAD_SIZE) return
        dir.mkdirs()
        context.assets.open("silero_vad.onnx").use { input -> vad.outputStream().use { input.copyTo(it) } }
    }

    private companion object {
        const val VAD_SIZE = 643_854L
    }
}
