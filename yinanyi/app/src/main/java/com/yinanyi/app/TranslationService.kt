package com.yinanyi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.yinanyi.app.engine.Engine
import com.yinanyi.app.engine.Models
import com.yinanyi.app.engine.OfflineEngine
import com.yinanyi.app.engine.OnlineEngine
import com.yinanyi.app.overlay.SubtitleOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 前台服务：录制手机正在播放的声音 → 交给在线或离线引擎 → 结果显示在悬浮字幕窗。
 * 默认在线；强制离线、没有密钥、断网、在线出错时用离线，网络恢复后自动切回在线。
 */
class TranslationService : Service() {

    companion object {
        const val ACTION_START = "com.yinanyi.app.START"
        const val ACTION_TOGGLE_PAUSE = "com.yinanyi.app.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.yinanyi.app.STOP"
        const val ACTION_TOGGLE_LOCK = "com.yinanyi.app.TOGGLE_LOCK"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000

        /** 在线出错后，至少等这么久再尝试回到在线，避免反复重连。 */
        private const val ONLINE_RETRY_MS = 30_000L

        /** 一块音频的均方根超过它才算「有声音」（16 位采样，数字静音是 0）。 */
        private const val SOUND_RMS = 30.0
        private const val IDLE_NOTIFICATION_ID = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** 引擎的启动 / 停止都排队在这个线程上，互不打架。 */
    private val engineThread = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var overlay: SubtitleOverlay? = null
    private lateinit var models: Models

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var capturing = false

    @Volatile
    private var networkOk = true
    private var onlineBlockedUntil = 0L
    private val apiKey get() = SettingsStore.apiKey.value

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                SubtitleBus.paused.value = !SubtitleBus.paused.value
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCK -> {
                SubtitleBus.locked.value = !SubtitleBus.locked.value
                return START_NOT_STICKY
            }
        }
        if (intent?.action != ACTION_START || capturing) return START_NOT_STICKY

        // Android 14 要求先进入前台服务，再获取 MediaProjection
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
        if (mp == null) {
            SubtitleBus.status("没有拿到录制声音的授权")
            stopSelf()
            return START_NOT_STICKY
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)
        projection = mp
        models = Models(this)

        SubtitleBus.clear()
        History.begin()
        lastSoundAt = SystemClock.elapsedRealtime()
        idleWarned = false
        SubtitleBus.paused.value = false
        SubtitleBus.locked.value = false
        SubtitleBus.onFailed = { reason -> main.post { onEngineFailed(reason) } }

        if (!startCapture(mp)) {
            stopSelf()
            return START_NOT_STICKY
        }
        SubtitleBus.running.value = true
        showOverlay()
        watchNetwork()
        watchSettings()
        watchUsage()
        scope.launch { SubtitleBus.lines.collect { History.update(it) } }
        restartEngine()
        main.postDelayed(tick, 60_000)
        return START_NOT_STICKY
    }

    // ---------------- 采集 ----------------

    @SuppressLint("MissingPermission")
    private fun startCapture(mp: MediaProjection): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            SubtitleBus.status("没有录音权限")
            return false
        }
        val builder = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        if (SettingsStore.settings.value.onlyBili) {
            // 只录来源 App，微信提示音、别的 App 的声音不会混进来，也不会白白上传；没装 B站 就录全部
            SourceApps.uids(packageManager, SettingsStore.settings.value).forEach { builder.addMatchingUid(it) }
        }
        val config = builder.build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            SubtitleBus.status("无法录制播放的声音：${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            SubtitleBus.status("无法录制播放的声音")
            return false
        }
        record = rec
        capturing = true
        rec.startRecording()
        reader = Thread({ readLoop(rec) }, "capture").also { it.start() }
        return true
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return Math.sqrt(sum / n)
    }

    @Volatile
    private var lastSoundAt = SystemClock.elapsedRealtime()

    @Volatile
    private var idleWarned = false

    /** 连续无声够久就自动停止，避免直播看完忘了关，悬浮窗和后台服务一直挂着。 */
    private fun checkIdleStop() {
        val minutes = SettingsStore.settings.value.idleStopMinutes
        if (minutes <= 0) return
        val silentMs = SystemClock.elapsedRealtime() - lastSoundAt
        val limitMs = minutes * 60_000L
        if (silentMs >= limitMs) {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            getSystemService(android.app.NotificationManager::class.java).notify(
                IDLE_NOTIFICATION_ID,
                Notification.Builder(this, YinanyiApp.CHANNEL_ID)
                    .setContentTitle("以南译已停止")
                    .setContentText("连续 $minutes 分钟没有声音，已自动停止翻译")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
            stopSelf()
        } else if (minutes >= 5 && silentMs >= limitMs - 60_000L && !idleWarned) {
            idleWarned = true
            SubtitleBus.notify("1 分钟内没有声音，将自动停止翻译")
        }
    }

    private fun readLoop(rec: AudioRecord) {
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100 毫秒
        while (capturing) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            // 不管是否暂停都看有没有声音，用来判断「无声自动停止」
            if (rms(chunk, n) > SOUND_RMS) {
                lastSoundAt = SystemClock.elapsedRealtime()
                idleWarned = false
            }
            if (SubtitleBus.paused.value) continue
            engine?.feed(chunk, n)
        }
    }

    // ---------------- 引擎选择与切换 ----------------

    private fun wantedMode(): EngineMode {
        val s = SettingsStore.settings.value
        val onlineAllowed = !s.forceOffline && apiKey.isNotEmpty() && SettingsStore.onlineConsent && networkOk &&
            SystemClock.elapsedRealtime() >= onlineBlockedUntil && !overLimit()
        return if (onlineAllowed) EngineMode.ONLINE else EngineMode.OFFLINE
    }

    /** 今天的在线估算费用是否已到上限。 */
    private fun overLimit(): Boolean {
        val limit = SettingsStore.settings.value.dailyLimitYuan
        if (limit <= 0) return false
        Usage.refreshDate()
        return Usage.today.value.estimatedYuan >= limit
    }

    /** 想用在线却用不了时的原因；用户自己选了离线、没填 Key 则不算「出问题」。 */
    private fun offlineCause(): String? {
        val s = SettingsStore.settings.value
        return when {
            s.forceOffline || apiKey.isEmpty() -> null
            !networkOk -> "网络断开，已切换到离线翻译"
            SystemClock.elapsedRealtime() < onlineBlockedUntil -> "在线翻译出错，已切换到离线翻译，稍后自动重试"
            overLimit() -> "今日在线用量已达上限，已切换到离线翻译"
            else -> null
        }
    }

    private var engineStarted = false
    private var warnedKey = ""

    /** 停掉当前引擎，按当前条件起一个新的。模式变化时在字幕窗上提示。 */
    private fun restartEngine() {
        val prev = SubtitleBus.mode.value
        val mode = wantedMode()
        if (!engineStarted) {
            if (mode == EngineMode.OFFLINE) offlineCause()?.let { SubtitleBus.notify(it) }
        } else if (prev != mode) {
            if (mode == EngineMode.OFFLINE) offlineCause()?.let { SubtitleBus.notify(it) } else SubtitleBus.notify("已切换回在线翻译")
        }
        engineStarted = true
        val lang = SettingsStore.settings.value.sourceLang
        val terms = SettingsStore.glossary.value
        SubtitleBus.mode.value = mode
        updateNotification()
        engineThread.execute {
            val old = engine
            engine = null
            old?.stop()
            if (!capturing) return@execute
            SubtitleBus.partial("")
            val next = if (mode == EngineMode.ONLINE) {
                OnlineEngine(apiKey, lang, SubtitleBus, models.vad, terms)
            } else {
                OfflineEngine(models, lang, SubtitleBus, terms)
            }
            next.start()
            engine = next
        }
    }

    private fun onEngineFailed(reason: String) {
        if (!capturing) return
        if (SubtitleBus.mode.value == EngineMode.ONLINE) {
            // 在线出错：先切离线，过一会儿再试在线
            onlineBlockedUntil = SystemClock.elapsedRealtime() + ONLINE_RETRY_MS
            restartEngine()
            main.postDelayed({ if (capturing && wantedMode() != SubtitleBus.mode.value) restartEngine() }, ONLINE_RETRY_MS + 500)
        } else {
            SubtitleBus.status(reason)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { setNetwork(ok) }
        }

        override fun onLost(network: Network) {
            main.post { setNetwork(false) }
        }
    }

    private fun setNetwork(ok: Boolean) {
        if (ok == networkOk) return
        networkOk = ok
        if (capturing && wantedMode() != SubtitleBus.mode.value) restartEngine()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkOk = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    /** 换源语言、切换「始终离线」、改术语表，都要重启引擎才生效。 */
    private fun watchSettings() {
        scope.launch {
            var first = true
            combine(SettingsStore.settings, SettingsStore.glossary) { s, terms -> listOf(s.sourceLang, s.forceOffline, s.dailyLimitYuan, terms) }
                .distinctUntilChanged()
                .collect {
                    if (first) first = false else restartEngine()
                }
        }
    }

    /** 在线用量逼近或超过每日上限时提示、切离线。 */
    private fun watchUsage() {
        scope.launch {
            Usage.today.collect { u ->
                val limit = SettingsStore.settings.value.dailyLimitYuan
                if (limit <= 0 || SubtitleBus.mode.value != EngineMode.ONLINE) return@collect
                if (u.estimatedYuan >= limit) {
                    restartEngine()
                } else if (u.estimatedYuan >= limit * 0.8) {
                    val key = "${u.date}/$limit"
                    if (warnedKey != key) {
                        warnedKey = key
                        SubtitleBus.notify("今日在线用量已达上限的 80%")
                    }
                }
            }
        }
    }

    /** 每分钟看一次：跨日后用量清零、出错冷却结束，都能自动回到在线。 */
    private val tick = object : Runnable {
        override fun run() {
            if (!capturing) return
            if (wantedMode() != SubtitleBus.mode.value) restartEngine()
            History.flush()
            checkIdleStop()
            main.postDelayed(this, 60_000)
        }
    }

    // ---------------- 悬浮窗 ----------------

    private fun showOverlay() {
        val o = SubtitleOverlay(
            this,
            onTogglePause = {
                SubtitleBus.paused.value = !SubtitleBus.paused.value
                updateNotification()
            },
            onStop = { stopSelf() },
            onCycleLanguage = {
                SettingsStore.update {
                    val all = SourceLang.entries
                    it.copy(sourceLang = all[(all.indexOf(it.sourceLang) + 1) % all.size])
                }
            },
            onLock = { SubtitleBus.locked.value = true },
        )
        overlay = o
        o.show()
        scope.launch {
            SubtitleBus.notice.collect { n -> if (n != null) o.showNotice(n.text) }
        }
        scope.launch {
            SubtitleBus.locked.collect { locked ->
                o.setLocked(locked)
                updateNotification()
            }
        }
        scope.launch {
            combine(
                SettingsStore.settings,
                SubtitleBus.lines,
                SubtitleBus.partialText,
                combine(SubtitleBus.mode, SubtitleBus.paused, SubtitleBus.status) { m, p, st -> Triple(m, p, st) },
            ) { s, lines, partial, (mode, paused, status) ->
                SubtitleOverlay.State(s, lines, partial, mode, paused, status)
            }.collect { o.render(it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    // ---------------- 通知 ----------------

    private fun notification(): Notification {
        fun action(name: String, label: String, req: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, req, Intent(this, TranslationService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(null, label, pi).build()
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = SubtitleBus.paused.value
        val locked = SubtitleBus.locked.value
        val text = (if (paused) "已暂停" else "正在翻译（${SubtitleBus.mode.value.label}）") +
            if (locked) " · 字幕已锁定" else ""
        return Notification.Builder(this, YinanyiApp.CHANNEL_ID)
            .setContentTitle("以南译")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_TOGGLE_LOCK, if (locked) "解锁字幕" else "锁定字幕", 3))
            .addAction(action(ACTION_TOGGLE_PAUSE, if (paused) "继续" else "暂停", 1))
            .addAction(action(ACTION_STOP, "停止", 2))
            .build()
    }

    private fun updateNotification() {
        if (!capturing) return
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    // ---------------- 结束 ----------------

    override fun onDestroy() {
        capturing = false
        History.end()
        SubtitleBus.running.value = false
        SubtitleBus.onFailed = null
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        overlay?.hide()
        overlay = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        reader?.join(500)
        record?.release()
        record = null
        engineThread.execute {
            engine?.stop()
            engine = null
        }
        engineThread.shutdown()
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
