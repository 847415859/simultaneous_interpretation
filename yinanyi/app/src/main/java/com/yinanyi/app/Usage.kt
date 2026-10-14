package com.yinanyi.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 当天的在线用量。 */
data class DailyUsage(
    val date: String = "",
    /** 实际上传给在线服务的音频秒数。 */
    val uploadedSec: Long = 0,
    /** 采集到的总秒数（含没上传的静音），用来算省了多少。 */
    val capturedSec: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    /** 按百炼北京地域列表价估算：音频输入 40 元/百万 token，文本输出 100 元/百万 token。 */
    val estimatedYuan: Double get() = inputTokens * 40.0 / 1_000_000 + outputTokens * 100.0 / 1_000_000
}

/** 在线用量统计，按自然日存在 SharedPreferences 里，跨日自动清零。 */
object Usage {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(DailyUsage())
    val today: StateFlow<DailyUsage> = state

    // 秒数按毫秒累计，写盘时取整，避免每 100 毫秒写一次
    private var uploadedMs = 0L
    private var capturedMs = 0L

    fun init(context: Context) {
        prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val date = prefs.getString("date", "") ?: ""
        state.value = if (date == todayKey()) {
            DailyUsage(
                date,
                prefs.getLong("uploadedSec", 0),
                prefs.getLong("capturedSec", 0),
                prefs.getLong("inputTokens", 0),
                prefs.getLong("outputTokens", 0),
            )
        } else {
            DailyUsage(todayKey())
        }
        uploadedMs = state.value.uploadedSec * 1000
        capturedMs = state.value.capturedSec * 1000
    }

    private fun todayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun rollover() {
        if (state.value.date != todayKey()) {
            state.value = DailyUsage(todayKey())
            uploadedMs = 0
            capturedMs = 0
        }
    }

    /** 跨日时清零，读「今天」用量前调用。 */
    @Synchronized
    fun refreshDate() = rollover()

    /** 采集线程每 100 毫秒调用一次。 */
    @Synchronized
    fun addAudio(ms: Long, uploaded: Boolean) {
        rollover()
        capturedMs += ms
        if (uploaded) uploadedMs += ms
        val s = state.value
        if (capturedMs / 1000 != s.capturedSec || uploadedMs / 1000 != s.uploadedSec) {
            state.value = s.copy(uploadedSec = uploadedMs / 1000, capturedSec = capturedMs / 1000)
            if (capturedMs / 1000 % 10 == 0L) save()
        }
    }

    /** 每轮 response.done 的计费 token。 */
    @Synchronized
    fun addTokens(input: Long, output: Long) {
        rollover()
        val s = state.value
        state.value = s.copy(inputTokens = s.inputTokens + input, outputTokens = s.outputTokens + output)
        save()
    }

    @Synchronized
    fun save() {
        val s = state.value
        prefs.edit()
            .putString("date", s.date)
            .putLong("uploadedSec", s.uploadedSec)
            .putLong("capturedSec", s.capturedSec)
            .putLong("inputTokens", s.inputTokens)
            .putLong("outputTokens", s.outputTokens)
            .apply()
    }
}
