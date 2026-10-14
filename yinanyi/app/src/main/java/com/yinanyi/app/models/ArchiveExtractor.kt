package com.yinanyi.app.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 从 tar.bz2 里按文件名取出需要的几个文件，逐个校验后写到目标位置。 */
object ArchiveExtractor {

    /**
     * @param wanted 键是压缩包里的文件名（只比较最后一段，不管外面套了几层目录）
     * @param cancelled 返回 true 时尽快停下
     * @param onProgress 已处理的压缩包字节数
     */
    fun extract(
        archive: File,
        root: File,
        wanted: List<EntrySpec>,
        cancelled: () -> Boolean = { false },
    ) {
        val remaining = wanted.associateBy { it.entryName }.toMutableMap()
        archive.inputStream().buffered().use { raw ->
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(raw, 1 shl 20))).use { tar ->
                while (remaining.isNotEmpty()) {
                    if (cancelled()) throw DownloadCancelled()
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    val spec = remaining[name] ?: continue

                    val dest = File(root, spec.rel)
                    dest.parentFile?.mkdirs()
                    val part = File(dest.path + ".part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = tar.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            size += n
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (size != spec.size || hash != spec.sha256.lowercase()) {
                        part.delete()
                        throw IOException("${spec.entryName} 解压后校验失败")
                    }
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    remaining.remove(name)
                }
            }
        }
        if (remaining.isNotEmpty()) throw IOException("压缩包里没有找到：" + remaining.keys.joinToString())
    }
}
