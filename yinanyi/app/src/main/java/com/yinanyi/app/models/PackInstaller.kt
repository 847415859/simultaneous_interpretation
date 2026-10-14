package com.yinanyi.app.models

import java.io.File

/** 把一个 [ModelPack] 里需要下载的文件下载并装到 [root] 下。不依赖 Android。 */
class PackInstaller(private val downloader: Downloader = Downloader()) {

    /** @param onProgress (已下载字节, 总字节) */
    fun install(
        pack: ModelPack,
        root: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
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
    }
}
