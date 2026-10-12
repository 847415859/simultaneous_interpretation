package com.yinanyi.captest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 录制手机正在播放的声音（Android 10+ 的 AudioPlaybackCapture），
 * 计算音量，并保留最近 15 秒的音频以便存成 WAV 回放检查。
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        /** 只录这个 uid 的声音；-1 表示录所有 App。 */
        const val EXTRA_TARGET_UID = "targetUid"
        const val ACTION_STOP = "com.yinanyi.captest.STOP"
        const val ACTION_SAVE = "com.yinanyi.captest.SAVE"

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val KEEP_SECONDS = 15
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var asr: OfflineAsr? = null
    private var translator: Translator? = null
    private var cloud: LiveTranslateClient? = null

    @Volatile
    private var stopping = false

    // 环形缓冲区，保存最近 KEEP_SECONDS 秒的 16 位单声道 PCM
    private val ring = ShortArray(SAMPLE_RATE * KEEP_SECONDS)
    private var ringPos = 0
    private var ringFilled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SAVE -> {
                saveWav()
                return START_NOT_STICKY
            }
        }
        if (record != null || intent == null) return START_NOT_STICKY

        // Android 14 要求先进入前台服务，再获取 MediaProjection
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            fail("没有拿到录屏授权")
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            fail("系统拒绝了录屏授权")
            return START_NOT_STICKY
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        startRecording(mp, intent.getIntExtra(EXTRA_TARGET_UID, -1))
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(mp: MediaProjection, targetUid: Int) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("没有录音权限")
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .apply { if (targetUid >= 0) addMatchingUid(targetUid) }
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, SAMPLE_RATE)) // 至少约 0.5 秒
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            fail("创建录音失败：${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("录音初始化失败")
            return
        }

        record = rec
        synchronized(ring) {
            ringPos = 0
            ringFilled = false
        }
        CaptureState.reset()
        CaptureState.running.value = true

        // 测试阶段模型用 adb 推到 App 自己的目录：files/models/
        val modelDir = File(getExternalFilesDir(null) ?: filesDir, "models")
        ResultLog.begin(
            getExternalFilesDir(null) ?: filesDir,
            CaptureState.asrEngine.value,
            CaptureState.asrLanguage.value,
            CaptureState.mtModel.value,
        )
        if (CaptureState.asrEngine.value == AsrEngine.LIVETRANSLATE) {
            startCloud()
        } else {
            startOffline(modelDir)
        }

        rec.startRecording()

        stopping = false
        worker = Thread({ readLoop(rec) }, "capture-reader").also { it.start() }
    }

    /** 密钥放在 files/keys.properties：dashscope_api_key=sk-xxx，由用户自己用 adb 推上来。 */
    private fun startCloud() {
        val keyFile = File(getExternalFilesDir(null) ?: filesDir, "keys.properties")
        val props = java.util.Properties()
        if (keyFile.canRead()) keyFile.inputStream().use { props.load(it) }
        val key = props.getProperty("dashscope_api_key")?.trim()
        if (key.isNullOrEmpty()) {
            CaptureState.asrStatus.value = "没有找到阿里云 API Key，请把 dashscope_api_key 写进 ${keyFile.path}"
            return
        }
        cloud = LiveTranslateClient(key, CaptureState.asrLanguage.value) { line ->
            ResultLog.write(line, LiveTranslateClient.MODEL)
        }.also { it.start() }
    }

    private fun startOffline(modelDir: File) {
        translator = CaptureState.mtModel.value.file?.let { Translator(File(modelDir, it)).also { t -> t.start() } }
        asr = OfflineAsr(
            modelDir,
            CaptureState.asrEngine.value,
            CaptureState.asrLanguage.value,
            onFinal = { line ->
                if (ResultLog.writeAsrOnly) ResultLog.write(line, "none")
                translator?.submit(line)
            },
        ).also { it.start() }
    }

    private fun readLoop(rec: AudioRecord) {
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100 毫秒
        var total = 0L
        while (!stopping) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) continue

            var sum = 0.0
            for (i in 0 until n) {
                val s = chunk[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / n)
            val db = if (rms < 1.0) CaptureState.SILENCE_DB
            else (20 * log10(rms / 32768.0)).toFloat().coerceAtLeast(CaptureState.SILENCE_DB)

            CaptureState.levelDb.value = db
            if (db > CaptureState.peakDb.value) CaptureState.peakDb.value = db
            total += n
            CaptureState.seconds.value = (total / SAMPLE_RATE).toInt()

            asr?.offer(FloatArray(n) { chunk[it] / 32768f })
            cloud?.send(chunk, n)

            synchronized(ring) {
                for (i in 0 until n) {
                    ring[ringPos] = chunk[i]
                    ringPos++
                    if (ringPos == ring.size) {
                        ringPos = 0
                        ringFilled = true
                    }
                }
            }
        }
    }

    private fun saveWav() {
        val samples: ShortArray = synchronized(ring) {
            if (ringFilled) {
                ring.copyOfRange(ringPos, ring.size) + ring.copyOfRange(0, ringPos)
            } else {
                ring.copyOfRange(0, ringPos)
            }
        }
        if (samples.isEmpty()) {
            CaptureState.message.value = "还没有录到任何数据"
            return
        }

        val dir = getExternalFilesDir(null) ?: filesDir
        val name = "capture-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
        val file = File(dir, name)
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                  // fmt 块大小
            putShort(1)                 // PCM
            putShort(1)                 // 单声道
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)     // 每秒字节数
            putShort(2)                 // 每帧字节数
            putShort(16)                // 位深
            put("data".toByteArray())
            putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(samples)

        try {
            FileOutputStream(file).use {
                it.write(header.array())
                it.write(body.array())
            }
            CaptureState.lastWavPath.value = file.absolutePath
            CaptureState.message.value = "已保存 ${samples.size / SAMPLE_RATE} 秒音频"
        } catch (e: Exception) {
            CaptureState.message.value = "保存失败：${e.message}"
        }
    }

    private fun fail(reason: String) {
        CaptureState.message.value = reason
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "音频采集", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("以南译 采集测试")
            .setContentText("正在录制手机播放的声音")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        // 先停录音，让阻塞中的 read() 返回，再等读线程结束
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        worker?.join(500)
        worker = null
        asr?.stop()
        asr = null
        translator?.stop()
        translator = null
        cloud?.stop()
        cloud = null
        record?.release()
        record = null
        projection?.stop()
        projection = null
        CaptureState.running.value = false
        super.onDestroy()
    }
}
