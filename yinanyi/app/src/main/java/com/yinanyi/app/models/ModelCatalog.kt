package com.yinanyi.app.models

import com.yinanyi.app.SourceLang
import java.io.File

/** 直接下载的文件。 */
data class FileSpec(val rel: String, val size: Long, val sha256: String, val urls: List<String>)

/** 压缩包里要取出来的一个文件。 */
data class EntrySpec(val entryName: String, val rel: String, val size: Long, val sha256: String)

/** 压缩包：先整体下载，再取出 [entries]。 */
data class ArchiveSpec(val urls: List<String>, val size: Long, val sha256: String, val entries: List<EntrySpec>)

/**
 * 一组离线模型。所有大小和 SHA-256 都是官方文件的真实值（阶段 0 实测用的就是这些文件），
 * 所以下载源可以是任何镜像。
 */
data class ModelPack(
    val id: String,
    val label: String,
    val description: String,
    val files: List<FileSpec> = emptyList(),
    val archive: ArchiveSpec? = null,
) {
    /** 要下载的总字节数（压缩包按整体算）。 */
    val downloadBytes: Long get() = files.sumOf { it.size } + (archive?.size ?: 0L)

    /** 装好之后占用的字节数。 */
    val installedBytes: Long get() = files.sumOf { it.size } + (archive?.entries?.sumOf { it.size } ?: 0L)

    private fun allFiles(): List<Pair<String, Long>> =
        files.map { it.rel to it.size } + (archive?.entries?.map { it.rel to it.size } ?: emptyList())

    /** 所有文件都在，且大小对。文件是校验通过后才改名到位的，所以大小对就够了。 */
    fun isInstalled(root: File): Boolean =
        allFiles().all { (rel, size) -> File(root, rel).let { it.isFile && it.length() == size } }

    fun delete(root: File) {
        allFiles().forEach { (rel, _) -> File(root, rel).delete() }
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
     * 日语识别模型。国内镜像上没有同一份文件，官方只发在 GitHub，而且是 418 MB 的压缩包
     * （里面有我们要的 73 MB）。GitHub 在国内经常很慢，所以后面跟了一个公共加速源。
     */
    private const val REAZON_ARCHIVE = "sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17"
    private const val REAZON_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$REAZON_ARCHIVE.tar.bz2"
    val REAZON_JA = ModelPack(
        id = "reazon-ja",
        label = "日语识别",
        description = "ReazonSpeech",
        archive = ArchiveSpec(
            // 公共加速源随时可能失效；内容有 SHA-256 兜底，所以只会影响能不能下，不影响安全
            urls = listOf(REAZON_URL, "https://ghfast.top/$REAZON_URL", "https://gh-proxy.com/$REAZON_URL"),
            size = 437_969_761,
            sha256 = "dc03758608c0280e2cbcaac4597467ffcf846ae0b06436f1706738a11da86f5d",
            entries = listOf(
                EntrySpec(
                    "encoder-epoch-35-avg-1.int8.onnx", "reazon-ja/encoder-epoch-35-avg-1.int8.onnx", 70_876_409,
                    "ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268",
                ),
                EntrySpec(
                    "decoder-epoch-35-avg-1.int8.onnx", "reazon-ja/decoder-epoch-35-avg-1.int8.onnx", 1_308_690,
                    "d0179db78a2e65445c5c3dc41e94c62068fc539fe4e45060e32f438cca76432f",
                ),
                EntrySpec(
                    "joiner-epoch-35-avg-1.int8.onnx", "reazon-ja/joiner-epoch-35-avg-1.int8.onnx", 1_033_417,
                    "c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495",
                ),
                EntrySpec(
                    "tokens.txt", "reazon-ja/tokens.txt", 26_631,
                    "144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24",
                ),
            ),
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
