package com.yinanyi.app.models

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 用户取消；已下载的部分保留，下次继续。 */
class DownloadCancelled : IOException("已取消")

/** 所有下载源都失败。 */
class DownloadFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 带断点续传、多源回退、SHA-256 校验的文件下载。不依赖 Android，可以直接在 JVM 上做单元测试。
 *
 * 下载源只需要「内容一致」，不需要「可信」：下完之后按固定的 SHA-256 校验，
 * 内容对不上就丢弃，所以可以放心用第三方镜像。
 */
class Downloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        // 超时要短：国内连 GitHub 经常是连接挂住而不是报错，要尽快换下一个源
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /**
     * 按顺序尝试 [urls]，直到有一个下载并通过校验。
     * 文件先写成 `dest.part`，校验通过才改名成 [dest]，所以 [dest] 存在就一定是完整的。
     *
     * @param onProgress 当前文件已有的字节数（含续传前已有的部分）
     * @param cancelled 返回 true 时尽快停下，抛 [DownloadCancelled]
     */
    fun download(
        urls: List<String>,
        dest: File,
        expectedSize: Long,
        sha256: String?,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        if (dest.isFile && dest.length() == expectedSize) {
            onProgress(expectedSize)
            return
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")

        var last: Throwable? = null
        for (url in urls) {
            try {
                fetch(url, part, expectedSize, onProgress, cancelled)
                if (part.length() != expectedSize) {
                    part.delete()
                    throw DownloadFailed("文件大小不对：应为 $expectedSize，实际 ${part.length()}")
                }
                if (sha256 != null && sha256(part) != sha256.lowercase()) {
                    part.delete()
                    throw DownloadFailed("校验失败，下载到的内容和预期不一致")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                return
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw DownloadFailed("所有下载源都失败了：${last?.message ?: "未知错误"}", last)
    }

    private fun fetch(url: String, part: File, expectedSize: Long, onProgress: (Long) -> Unit, cancelled: () -> Boolean) {
        var offset = if (part.isFile) part.length() else 0L
        if (offset > expectedSize) {
            part.delete()
            offset = 0
        }
        if (offset == expectedSize) {
            onProgress(offset)
            return
        }
        if (cancelled()) throw DownloadCancelled()

        val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> {
                    // 服务端必须从我们要的位置开始给，否则拼出来的文件是坏的
                    val range = response.header("Content-Range")
                    if (range == null || !range.startsWith("bytes $offset-")) {
                        part.delete()
                        throw DownloadFailed("服务器返回的续传位置不对")
                    }
                }
                200 -> offset = 0 // 服务端不支持 Range，从头来
                else -> throw DownloadFailed("HTTP ${response.code}")
            }
            val body = response.body ?: throw DownloadFailed("响应为空")

            var written = offset
            FileOutputStream(part, offset > 0).use { out ->
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw DownloadCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= 256 * 1024) {
                            lastReport = written
                            onProgress(written)
                        }
                    }
                }
            }
            onProgress(written)
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
