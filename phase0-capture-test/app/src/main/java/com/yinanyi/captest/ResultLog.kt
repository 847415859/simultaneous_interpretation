package com.yinanyi.captest

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把每句识别 / 翻译结果追加写到 files/results/ 下的 TSV 文件，
 * 方便用 adb 拉到电脑上统计。每次开始采集新建一个文件。
 */
object ResultLog {
    @Volatile
    private var file: File? = null
    private var asrLabel = ""

    fun begin(dir: File, engine: AsrEngine, language: String, mt: MtModel) {
        val d = File(dir, "results").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        asrLabel = "${engine.name}/$language"
        file = File(d, "result-$stamp.tsv").apply {
            writeText("time\tasr\tmt\tlang\taudio_sec\tasr_ms\tmt_ms\ttext\ttranslation\n")
        }
        // 不翻译时，识别完就写；翻译时由翻译线程写
        writeAsrOnly = mt == MtModel.NONE
    }

    @Volatile
    var writeAsrOnly = true
        private set

    @Synchronized
    fun write(line: AsrLine, mtName: String) {
        val f = file ?: return
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        fun clean(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ')
        f.appendText(
            listOf(
                time, asrLabel, mtName, line.lang, "%.2f".format(line.audioSec),
                line.decodeMs.toString(), line.translateMs.toString(),
                clean(line.text), clean(line.translation),
            ).joinToString("\t") + "\n",
        )
    }
}
