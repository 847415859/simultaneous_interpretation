package com.yinanyi.app.models

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.StatFs
import com.yinanyi.app.MainActivity
import com.yinanyi.app.YinanyiApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在前台服务里下载离线模型，这样切到别的 App 或锁屏也不会中断。
 * 同一时间只下一个包（模型都很大，并行只会互相抢带宽）；断点续传，取消后保留已下载的部分。
 */
class DownloadService : Service() {

    companion object {
        private const val ACTION_START = "com.yinanyi.app.DOWNLOAD_START"
        private const val ACTION_CANCEL = "com.yinanyi.app.DOWNLOAD_CANCEL"
        private const val EXTRA_PACK = "pack"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, packId: String) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_PACK, packId),
            )
        }

        fun cancel(context: Context, packId: String) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_PACK, packId),
            )
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val pending = AtomicInteger(0)
    private val installer = PackInstaller()
    private lateinit var root: File

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        root = com.yinanyi.app.engine.Models(this).dir
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pack = ModelCatalog.all.firstOrNull { it.id == intent?.getStringExtra(EXTRA_PACK) }
        when (intent?.action) {
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID, notification("准备下载……", 0, 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                if (pack == null || !queued.add(pack.id)) {
                    finishIfIdle()
                } else {
                    cancelled.remove(pack.id)
                    ModelManager.set(pack.id, ModelManager.State.Downloading(0, pack.downloadBytes))
                    pending.incrementAndGet()
                    executor.execute { run(pack) }
                }
            }
            ACTION_CANCEL -> pack?.let { cancelled.add(it.id) }
        }
        return START_NOT_STICKY
    }

    private fun run(pack: ModelPack) {
        try {
            root.mkdirs()
            val needed = pack.downloadBytes
            val free = StatFs(root.path).availableBytes
            if (free < needed) {
                ModelManager.set(pack.id, ModelManager.State.Failed("存储空间不足：需要约 ${mb(needed)}，只剩 ${mb(free)}"))
                return
            }

            var lastUpdate = 0L
            installer.install(
                pack, root,
                onProgress = { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500 || done >= total) {
                        lastUpdate = now
                        ModelManager.set(pack.id, ModelManager.State.Downloading(done, total))
                        updateNotification(pack.label, done, total)
                    }
                },
                cancelled = { pack.id in cancelled },
            )
            ModelManager.set(pack.id, ModelManager.State.Installed)
        } catch (_: DownloadCancelled) {
            ModelManager.set(pack.id, ModelManager.State.Missing)
        } catch (e: Exception) {
            ModelManager.set(pack.id, ModelManager.State.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            cancelled.remove(pack.id)
            queued.remove(pack.id)
            if (pending.decrementAndGet() == 0) finishIfIdle()
        }
    }

    private fun finishIfIdle() {
        if (pending.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "${bytes / 1048576} MB"

    private fun notification(text: String, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, YinanyiApp.CHANNEL_ID)
            .setContentTitle("正在下载离线模型")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false) else setProgress(0, 0, true) }
            .build()
    }

    private fun updateNotification(label: String, done: Long, total: Long) {
        val text = if (total > 0) "$label ${done * 100 / total}%" else label
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, done, total))
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
