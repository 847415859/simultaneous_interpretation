package com.yinanyi.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yinanyi.app.engine.Models

private val Accent = Color(0xFF2448C8)
private val Ground = Color(0xFFF4F5F7)
private val Ink = Color(0xFF15181D)
private val Quiet = Color(0xFF555B66)
private val Line = Color(0xFFDDE0E5)
private val Good = Color(0xFF1F8A4C)
private val Warn = Color(0xFF8A6100)

private val TextColors = listOf(0xFFFFFFFF to "白色", 0xFFFFE066 to "黄色", 0xFF9FD3FF to "浅蓝", 0xFFA8F0C0 to "浅绿", 0xFF15181D to "黑色")
private val BgColors = listOf(0xFF101217 to "黑色", 0xFF3A3F47 to "深灰", 0xFF10244F to "深蓝", 0xFFFFFFFF to "白色")

private val BiliPackages = listOf("tv.danmaku.bili", "tv.danmaku.bilibilihd", "com.bilibili.app.blue")

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf("home")

    /** onResume 时递增，让界面重新检查悬浮窗权限和模型。 */
    private var resumeTick by mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, TranslationService::class.java)
                    .setAction(TranslationService.ACTION_START)
                    .putExtra(TranslationService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(TranslationService.EXTRA_DATA, data),
            )
            openBilibili()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ground) {
                    if (screen == "settings") {
                        BackHandler { screen = "home" }
                        SettingsScreen(onBack = { screen = "home" })
                    } else {
                        HomeScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun start() {
        if (!canDrawOverlays()) {
            openOverlaySettings()
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun stop() {
        startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
    }

    private fun openBilibili() {
        BiliPackages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }?.let { startActivity(it) }
    }

    // ---------------- 首页 ----------------

    @Composable
    private fun HomeScreen() {
        val settings by SettingsStore.settings.collectAsState()
        val running by SubtitleBus.running.collectAsState()
        val mode by SubtitleBus.mode.collectAsState()
        val status by SubtitleBus.status.collectAsState()
        val locked by SubtitleBus.locked.collectAsState()
        val usage by Usage.today.collectAsState()
        // 读 resumeTick 让界面在回到前台时重新检查权限
        val overlayOk = resumeTick >= 0 && canDrawOverlays()
        val missing = Models(this).missing(settings.sourceLang)
        val hasKey = BuildConfig.DASHSCOPE_API_KEY.isNotEmpty()

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("以南译", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("B站直播 · 投稿视频实时翻译", fontSize = 13.sp, color = Quiet)
                }
                TextButton(onClick = { screen = "settings" }, modifier = Modifier.semantics { contentDescription = "设置" }) {
                    Text("设置", color = Ink)
                }
            }

            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (title, detail, color) = when {
                        running -> Triple("正在翻译（${mode.label}）", status, if (mode == EngineMode.ONLINE) Good else Warn)
                        settings.forceOffline -> Triple("离线模式", "设置里开启了「始终使用离线模式」", Warn)
                        !hasKey -> Triple("离线模式", "没有配置在线翻译密钥", Warn)
                        else -> Triple("在线模式", "断网时自动切换到离线模型，字幕不中断", Good)
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                        if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = Quiet)
                    }
                    if (running && locked) {
                        OutlinedButton(onClick = { SubtitleBus.locked.value = false }) { Text("解锁字幕", fontSize = 12.sp) }
                    }
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text("今天在线用量", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
                        Text("约 ¥%.2f".format(usage.estimatedYuan), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                    }
                    val saved = usage.capturedSec - usage.uploadedSec
                    Text(
                        "上传 ${minutes(usage.uploadedSec)}，没人说话跳过 ${minutes(saved)}",
                        fontSize = 12.sp, color = Quiet,
                    )
                    Text("按阿里云百炼北京地域列表价估算，以控制台账单为准", fontSize = 11.sp, color = Quiet)
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row {
                        Text("主播说的是", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
                        Text("翻译为 中文", fontSize = 12.sp, color = Quiet)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceLang.entries.forEach { lang ->
                            val on = settings.sourceLang == lang
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                modifier = Modifier.weight(1f).height(44.dp).clip(shape)
                                    .background(if (on) Accent else Color.White)
                                    .border(1.dp, if (on) Accent else Color(0xFFC9CDD4), shape)
                                    .semantics { contentDescription = "源语言 ${lang.label}" + if (on) "，已选" else "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                TextButton(onClick = { SettingsStore.update { it.copy(sourceLang = lang) } }, modifier = Modifier.fillMaxSize()) {
                                    Text(lang.label, color = if (on) Color.White else Ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Text("默认英语，可在设置里改；翻译时也能在字幕窗上切换", fontSize = 12.sp, color = Quiet)
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("准备工作", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                    CheckRow("悬浮窗权限", overlayOk, if (overlayOk) "已开启" else "去开启", if (overlayOk) null else ::openOverlaySettings)
                    CheckRow("录制播放的声音", null, "开始时系统会询问", null)
                    CheckRow(
                        "离线模型（${settings.sourceLang.label}）",
                        missing.isEmpty(),
                        if (missing.isEmpty()) "已就绪" else "缺少 ${missing.size} 个",
                        null,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { if (running) stop() else start() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Ink else Accent),
            ) {
                Text(if (running) "停止翻译" else "开始翻译", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (running) "字幕浮在 B站 画面上，可拖动、缩放、收起" else "开始后会打开 B站，字幕浮在画面上方",
                fontSize = 12.sp, color = Quiet, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun minutes(sec: Long): String = if (sec < 60) "$sec 秒" else "${sec / 60} 分 ${sec % 60} 秒"

    @Composable
    private fun CheckRow(label: String, ok: Boolean?, value: String, onClick: (() -> Unit)?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (ok) {
                true -> Good
                false -> Warn
                null -> Warn
            }
            Text(if (ok == true) "✓" else "!", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
            if (onClick != null) {
                OutlinedButton(onClick = onClick) { Text(value, fontSize = 12.sp) }
            } else {
                Text(value, fontSize = 12.sp, color = color)
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
        ) { content() }
    }

    // ---------------- 设置 ----------------

    @Composable
    private fun SettingsScreen(onBack: () -> Unit) {
        val s by SettingsStore.settings.collectAsState()
        val missing = Models(this).missing(s.sourceLang)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("‹ 返回", color = Ink) }
                Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }

            Section("翻译")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("首选源语言", fontSize = 15.sp, color = Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceLang.entries.forEach { lang ->
                            val on = s.sourceLang == lang
                            if (on) {
                                Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(lang.label) }
                            } else {
                                OutlinedButton(onClick = { SettingsStore.update { it.copy(sourceLang = lang) } }) { Text(lang.label, color = Ink) }
                            }
                        }
                    }
                    Row {
                        Text("翻译为", fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
                        Text("中文", fontSize = 14.sp, color = Quiet)
                    }
                }
            }

            Section("字幕外观")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 实时预览
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3F47)).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val alpha = (100 - s.transparency) / 100f
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(Color(s.bgColor).copy(alpha = alpha)).padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (s.showOriginal) Text("So today we're trying the new ranked mode.", fontSize = 12.sp, color = Color(0xFFB4BAC4))
                            Text("今天我们来试试新的排位模式。", fontSize = s.fontSize.sp, color = Color(s.textColor), fontWeight = FontWeight.Medium)
                        }
                    }

                    Text("文字颜色", fontSize = 15.sp, color = Ink)
                    Swatches(TextColors, s.textColor) { c -> SettingsStore.update { it.copy(textColor = c) } }
                    Text("背景颜色", fontSize = 15.sp, color = Ink)
                    Swatches(BgColors, s.bgColor) { c -> SettingsStore.update { it.copy(bgColor = c) } }

                    LabeledSlider("背景透明度", "${s.transparency}%", s.transparency.toFloat(), 0f..100f, 19) {
                        SettingsStore.update { st -> st.copy(transparency = it.toInt()) }
                    }
                    LabeledSlider("字号", "${s.fontSize}", s.fontSize.toFloat(), 14f..26f, 11) {
                        SettingsStore.update { st -> st.copy(fontSize = it.toInt()) }
                    }
                    LabeledSlider("最多显示行数", "${s.maxLines} 行", s.maxLines.toFloat(), 1f..5f, 3) {
                        SettingsStore.update { st -> st.copy(maxLines = it.toInt()) }
                    }
                    SwitchRow("同时显示原文", "在线模式暂时只有译文", s.showOriginal) { v -> SettingsStore.update { it.copy(showOriginal = v) } }
                }
            }

            Section("识别与翻译")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow("始终使用离线模式", "关闭时默认在线，断网自动切离线", s.forceOffline) { v ->
                        SettingsStore.update { it.copy(forceOffline = v) }
                    }
                    Row {
                        Text("离线模型（${s.sourceLang.label}）", fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
                        Text(if (missing.isEmpty()) "已就绪" else "缺少：" + missing.joinToString(), fontSize = 13.sp, color = if (missing.isEmpty()) Good else Warn)
                    }
                }
            }

            Section("术语表")
            Card { GlossaryEditor() }

            Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Quiet, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }

    /** 术语表：主播名、游戏名、常见梗的固定译法；在线和离线翻译都会用上。 */
    @Composable
    private fun GlossaryEditor() {
        val terms by SettingsStore.glossary.collectAsState()
        var source by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("指定固定译法，比如主播名、游戏名、常见梗。翻译时修改会自动重新连接后生效。", fontSize = 12.sp, color = Quiet)
            terms.forEach { term ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${term.source}  →  ${term.target}", fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { SettingsStore.removeTerm(term) }) { Text("删除", color = Warn) }
                }
            }
            if (terms.isEmpty()) Text("还没有术语", fontSize = 13.sp, color = Quiet)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = source, onValueChange = { source = it }, singleLine = true,
                    label = { Text("原文") }, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = target, onValueChange = { target = it }, singleLine = true,
                    label = { Text("译成") }, modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = {
                    SettingsStore.putTerm(source, target)
                    source = ""
                    target = ""
                },
                enabled = source.isNotBlank() && target.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("添加") }
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(title, fontSize = 12.sp, color = Quiet, modifier = Modifier.padding(start = 4.dp))
    }

    @Composable
    private fun Swatches(options: List<Pair<Long, String>>, selected: Int, onPick: (Int) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (hex, label) ->
                val color = hex.toInt()
                val on = color == selected
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(color))
                        .border(if (on) 3.dp else 1.dp, if (on) Accent else Color(0xFFB8BDC6), CircleShape)
                        .semantics { contentDescription = label + if (on) "，已选" else "" },
                ) {
                    TextButton(onClick = { onPick(color) }, modifier = Modifier.fillMaxSize()) {}
                }
            }
        }
    }

    @Composable
    private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
        Column {
            Row {
                Text(label, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
                Text(value, fontSize = 14.sp, color = Quiet)
            }
            Slider(
                value = current, onValueChange = onChange, valueRange = range, steps = steps,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
        }
    }

    @Composable
    private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, color = Ink)
                Text(detail, fontSize = 12.sp, color = Quiet)
            }
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
    }
}
