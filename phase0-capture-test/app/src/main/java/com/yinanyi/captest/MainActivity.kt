package com.yinanyi.captest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 一个可选的录音目标：某个已安装的 B站 客户端，或 uid = -1 表示所有 App。 */
data class Target(val label: String, val packageName: String?, val uid: Int)

class MainActivity : ComponentActivity() {

    private val biliPackages = listOf(
        "tv.danmaku.bili" to "哔哩哔哩",
        "tv.danmaku.bilibilihd" to "哔哩哔哩 HD",
        "com.bilibili.app.blue" to "哔哩哔哩 概念版",
    )

    private val asrLanguages = listOf("en" to "英语", "ja" to "日语", "ko" to "韩语", "auto" to "自动")

    private var targets: List<Target> = emptyList()
    private var selected by mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            askForProjection()
        } else {
            CaptureState.message.value = "需要录音权限才能采集播放的声音"
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
                .putExtra(CaptureService.EXTRA_TARGET_UID, targets[selected].uid)
            startForegroundService(intent)
        } else {
            CaptureState.message.value = "你取消了录屏授权"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targets = findTargets()
        // 装了 B站 时默认只录 B站，这样测出来的才是 B站 本身是否允许被录
        selected = if (targets.size > 1) 1 else 0

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Screen()
                }
            }
        }
    }

    private fun findTargets(): List<Target> {
        val list = mutableListOf(Target("所有 App", null, -1))
        for ((pkg, label) in biliPackages) {
            try {
                val uid = packageManager.getApplicationInfo(pkg, 0).uid
                list += Target("只录 $label", pkg, uid)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return list
    }

    private fun start() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun askForProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun sendToService(action: String) {
        startService(Intent(this, CaptureService::class.java).setAction(action))
    }

    private fun openBili() {
        val pkg = targets.getOrNull(selected)?.packageName
            ?: targets.firstOrNull { it.packageName != null }?.packageName
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            startActivity(launch)
        } else {
            CaptureState.message.value = "没有找到已安装的 B站"
        }
    }

    @Composable
    private fun Screen() {
        val running by CaptureState.running.collectAsState()
        val level by CaptureState.levelDb.collectAsState()
        val peak by CaptureState.peakDb.collectAsState()
        val seconds by CaptureState.seconds.collectAsState()
        val message by CaptureState.message.collectAsState()
        val wavPath by CaptureState.lastWavPath.collectAsState()
        val asrLanguage by CaptureState.asrLanguage.collectAsState()
        val asrStatus by CaptureState.asrStatus.collectAsState()
        val partial by CaptureState.partialText.collectAsState()
        val lines by CaptureState.finalLines.collectAsState()
        val asrStats by CaptureState.asrStats.collectAsState()
        val asrEngine by CaptureState.asrEngine.collectAsState()
        val mtModel by CaptureState.mtModel.collectAsState()
        val mtStatus by CaptureState.mtStatus.collectAsState()
        val mtStats by CaptureState.mtStats.collectAsState()

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("B站声音采集测试", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "1. 选择录音范围，点「开始采集」并允许录屏\n" +
                    "2. 点「打开 B站」，播放一个有声音的直播或视频\n" +
                    "3. 回到这里看音量条有没有跳动\n" +
                    "4. 点「保存最近 15 秒」，用文件管理器播放听一听",
                fontSize = 14.sp,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("录音范围", fontWeight = FontWeight.Bold)
                    targets.forEachIndexed { i, t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == i,
                                    enabled = !running,
                                    onClick = { selected = i },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == i, onClick = null, enabled = !running)
                            Text(t.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (targets.size == 1) {
                        Text("这台手机上没有找到 B站", fontSize = 13.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!running) {
                    Button(onClick = { start() }) { Text("开始采集") }
                } else {
                    Button(onClick = { sendToService(CaptureService.ACTION_STOP) }) { Text("停止") }
                }
                OutlinedButton(onClick = { openBili() }) { Text("打开 B站") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前音量", fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { ((level + 60f) / 60f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "当前 ${level.roundToInt()} dB · 最高 ${peak.roundToInt()} dB · 已采集 $seconds 秒",
                        fontSize = 14.sp,
                    )
                    Text(verdict(running, peak, seconds), fontWeight = FontWeight.Bold)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("离线识别模型", fontWeight = FontWeight.Bold)
                    AsrEngine.entries.forEach { e ->
                        Choice(e.label, asrEngine == e, !running) { CaptureState.asrEngine.value = e }
                    }
                    Text("源语言（SenseVoice 和 LiveTranslate 使用）", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        asrLanguages.forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .selectable(
                                        selected = asrLanguage == code,
                                        enabled = !running,
                                        onClick = { CaptureState.asrLanguage.value = code },
                                    )
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = asrLanguage == code, onClick = null, enabled = !running)
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                    Text("离线翻译模型", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MtModel.entries.forEach { m ->
                            Choice(m.label, mtModel == m, !running) { CaptureState.mtModel.value = m }
                        }
                    }
                    Text(asrStatus, fontSize = 13.sp)
                    if (asrStats.isNotEmpty()) Text(asrStats, fontSize = 13.sp)
                    if (mtStatus.isNotEmpty()) Text(mtStatus, fontSize = 13.sp)
                    if (mtStats.isNotEmpty()) Text(mtStats, fontSize = 13.sp)
                    if (partial.isNotEmpty()) {
                        Text(partial, fontSize = 16.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    lines.forEach { line ->
                        Column {
                            line.translation?.let { Text(it, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                            Text(line.text, fontSize = 15.sp)
                            Text(
                                "[%s] %.1f 秒语音，识别 %d 毫秒%s".format(
                                    line.lang, line.audioSec, line.decodeMs,
                                    if (line.translateMs > 0) "，翻译 ${line.translateMs} 毫秒" else "",
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { sendToService(CaptureService.ACTION_SAVE) },
                enabled = running,
            ) { Text("保存最近 15 秒") }

            message?.let { Text(it, fontSize = 14.sp) }
            wavPath?.let { Text("文件位置：$it", fontSize = 12.sp) }
        }
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .selectable(selected = selected, enabled = enabled, onClick = onClick)
                .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(label, fontSize = 14.sp)
        }
    }

    private fun verdict(running: Boolean, peak: Float, seconds: Int): String = when {
        !running && seconds == 0 -> "还没开始"
        peak > -50f -> "录到了声音：这个范围可以采集"
        seconds < 10 -> "正在检测……请确认 B站 正在播放有声音的内容"
        else -> "一直是静音：可能被禁止录音，或者没有在播放"
    }
}
