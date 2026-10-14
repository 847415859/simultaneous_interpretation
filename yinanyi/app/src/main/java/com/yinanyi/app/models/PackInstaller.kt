package com.yinanyi.app.models

import java.io.File

/** 把一个 [ModelPack] 下载并装到 [root] 下。不依赖 Android。 */
class PackInstaller(private val downloader: Downloader = Downloader()) {

    /**
     * @param onProgress (已下载字节, 总字节)
     * @param onExtracting 压缩包下载完、开始解压时调用一次
     */
    fun install(
        pack: ModelPack,
        root: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        onExtracting: () -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        val total = pack.downloadBytes
        var base = 0L

        for (file in pack.files) {
            downloader.download(
                file.urls, File(root, file.rel), file.size, file.sha256,
                onProgress = { onProgress(base + it, total) },
                cancelled = cancelled,
            )
            base += file.size
        }

        pack.archive?.let { archive ->
            // 压缩包放在临时目录，解压完就删；中途失败时留着，下次继续下载
            val temp = File(root, "_download/${pack.id}.tar.bz2")
            downloader.download(
                archive.urls, temp, archive.size, archive.sha256,
                onProgress = { onProgress(base + it, total) },
                cancelled = cancelled,
            )
            onExtracting()
            ArchiveExtractor.extract(temp, root, archive.entries, cancelled)
            temp.delete()
            File(root, "_download").delete() // 目录空了才会删掉
        }
    }
}
