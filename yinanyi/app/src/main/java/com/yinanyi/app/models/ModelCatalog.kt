package com.yinanyi.app.models

import com.yinanyi.app.SourceLang
import java.io.File

/** 需要下载的文件。 */
data class FileSpec(val rel: String, val size: Long, val sha256: String, val urls: List<String>)

/**
 * 一组离线模型。所有大小和 SHA-256 都是官方文件的真实值（阶段 0 实测用的就是这些文件），
 * 所以下载源可以是任何镜像。
 */
data class ModelPack(
    val id: String,
    val label: String,
    val description: String,
    val files: List<FileSpec>,
) {
    /** 要下载的总字节数。 */
    val downloadBytes: Long get() = files.sumOf { it.size }

    /** 所有文件都在，且大小对。文件是校验通过后才改名到位的，所以大小对就够了。 */
    fun isInstalled(root: File): Boolean =
        files.all { File(root, it.rel).let { f -> f.isFile && f.length() == it.size } }

    fun delete(root: File) {
        files.forEach { File(root, it.rel).delete() }
    }
}

object ModelCatalog {
    private const val MS = "https://modelscope.cn/models"

    /** 英语、韩语的识别模型。ModelScope 上有两份第三方上传，内容和官方一致（SHA-256 相同）。 */
    val SENSE_VOICE = ModelPack(
        id = "sense-voice",
        label = "英语、韩语识别",
        description = "SenseVoice",
        files = listOf(
            FileSpec(
                "sense-voice/model.int8.onnx", 237_115_547,
                "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d",
                listOf(
                    "$MS/aistoy/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/master/model.int8.onnx",
                    "$MS/Mr7Cat/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/master/model.int8.onnx",
                ),
            ),
            FileSpec(
                "sense-voice/tokens.txt", 315_894,
                "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                listOf(
                    "$MS/aistoy/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/master/tokens.txt",
                    "$MS/Mr7Cat/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/master/tokens.txt",
                ),
            ),
        ),
    )

    /**
     * 日语识别模型（ReazonSpeech zipformer ja-en，int8，共 73 MB）。官方文件只发布在 GitHub，
     * 国内很多网络连不上，所以转存了一份到项目自己的 ModelScope 仓库（只有这 4 个 int8 文件，不含压缩包里的
     * fp32 / fp16 版本）。文件内容靠固定的 SHA-256 保证和官方一致。
     * 仓库地址见 [REAZON_REPO]。
     */
    const val REAZON_REPO = "yinannn/yinanyi-reazon-ja"

    private fun reazon(name: String, size: Long, sha256: String) =
        FileSpec("reazon-ja/$name", size, sha256, listOf("$MS/$REAZON_REPO/resolve/master/$name"))

    val REAZON_JA = ModelPack(
        id = "reazon-ja",
        label = "日语识别",
        description = "ReazonSpeech",
        files = listOf(
            reazon("encoder-epoch-35-avg-1.int8.onnx", 70_876_409, "ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268"),
            reazon("decoder-epoch-35-avg-1.int8.onnx", 1_308_690, "d0179db78a2e65445c5c3dc41e94c62068fc539fe4e45060e32f438cca76432f"),
            reazon("joiner-epoch-35-avg-1.int8.onnx", 1_033_417, "c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495"),
            reazon("tokens.txt", 26_631, "144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24"),
        ),
    )

    /** 翻译模型。ModelScope 上是腾讯官方账号 Tencent-Hunyuan 发的，和 Hugging Face 上的文件一致。 */
    val TRANSLATOR = ModelPack(
        id = "translator",
        label = "翻译模型",
        description = "腾讯混元翻译 HY-MT1.5-1.8B",
        files = listOf(
            FileSpec(
                "HY-MT1.5-1.8B-Q4_K_M.gguf", 1_133_080_512,
                "4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7",
                listOf(
                    "$MS/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF/resolve/master/HY-MT1.5-1.8B-Q4_K_M.gguf",
                    "https://hf-mirror.com/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf",
                    "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf",
                ),
            ),
        ),
    )

    val all = listOf(SENSE_VOICE, REAZON_JA, TRANSLATOR)

    /** 这个源语言离线翻译需要哪些包：日语用 ReazonSpeech，英韩用 SenseVoice，翻译统一用 HY-MT。 */
    fun packsFor(lang: SourceLang): List<ModelPack> =
        listOf(if (lang == SourceLang.JA) REAZON_JA else SENSE_VOICE, TRANSLATOR)
}
