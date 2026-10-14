package com.yinanyi.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(val time: Long, val original: String, val translation: String)

/** 一次翻译（从点「开始」到停止）的记录。 */
data class HistorySession(val file: File, val startMs: Long, val count: Int)

/**
 * 翻译记录：每次翻译存成 filesDir/history 下的一个 jsonl 文件，只在本机，最多保留 [MAX_SESSIONS] 次。
 * 字幕窗只保留最近几行，漏看的句子可以事后在这里回看、复制或分享。
 */
object History {
    private const val MAX_SESSIONS = 30
    private const val MAX_ENTRIES = 20_000
    private const val SHARE_LIMIT = 400_000

    private lateinit var dir: File

    // 当前这次翻译的内容，按字幕 id 去重（离线翻译会先出原文、后补译文）
    private val entries = LinkedHashMap<Long, HistoryEntry>()
    private var current: File? = null
    private var dirty = false

    fun init(context: Context) {
        dir = File(context.filesDir, "history").also { it.mkdirs() }
    }

    @Synchronized
    fun begin() {
        entries.clear()
        dirty = false
        current = if (SettingsStore.settings.value.saveHistory) File(dir, "session-${System.currentTimeMillis()}.jsonl") else null
    }

    /** 字幕列表有变化时调用。 */
    @Synchronized
    fun update(lines: List<SubtitleLine>) {
        if (current == null) return
        for (l in lines) {
            val original = l.original.trim()
            val translation = l.translation.orEmpty().trim()
            if (original.isEmpty() && translation.isEmpty()) continue
            val old = entries[l.id]
            if (old == null || old.original != original || old.translation != translation) {
                entries[l.id] = HistoryEntry(old?.time ?: System.currentTimeMillis(), original, translation)
                dirty = true
            }
        }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** 把当前内容写盘；没有内容或没变化时什么也不做。 */
    @Synchronized
    fun flush() {
        val file = current ?: return
        if (!dirty || entries.isEmpty()) return
        runCatching {
            val tmp = File(dir, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                entries.values.forEach {
                    w.write(JSONObject().put("t", it.time).put("o", it.original).put("z", it.translation).toString())
                    w.newLine()
                }
            }
            tmp.renameTo(file)
            dirty = false
        }
        prune()
    }

    @Synchronized
    fun end() {
        flush()
        current = null
        entries.clear()
    }

    private fun prune() {
        sessionFiles().drop(MAX_SESSIONS).forEach { it.delete() }
    }

    private fun sessionFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { startOf(it) }
            .orEmpty()

    private fun startOf(file: File): Long = file.name.removePrefix("session-").removeSuffix(".jsonl").toLongOrNull() ?: 0L

    /** 新的在前。 */
    fun sessions(): List<HistorySession> = sessionFiles().map { f ->
        HistorySession(f, startOf(f), runCatching { f.useLines { it.count() } }.getOrDefault(0))
    }.filter { it.count > 0 }

    fun read(file: File): List<HistoryEntry> = runCatching {
        file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                HistoryEntry(o.optLong("t"), o.optString("o"), o.optString("z"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun delete(file: File) {
        file.delete()
    }

    fun clearAll() {
        sessionFiles().forEach { it.delete() }
    }

    fun title(session: HistorySession): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(session.startMs))

    /** 导出成纯文本：时间、译文、原文，每句一段。过长时只取最后一部分。 */
    fun toText(entries: List<HistoryEntry>): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        val sb = StringBuilder()
        for (e in entries) {
            sb.append('[').append(fmt.format(Date(e.time))).append("] ")
            sb.append(e.translation.ifEmpty { e.original }).append('\n')
            if (e.translation.isNotEmpty() && e.original.isNotEmpty()) sb.append("    ").append(e.original).append('\n')
            sb.append('\n')
        }
        return if (sb.length > SHARE_LIMIT) sb.substring(sb.length - SHARE_LIMIT) else sb.toString()
    }
}
