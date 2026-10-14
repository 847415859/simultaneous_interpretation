package com.yinanyi.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yinanyi.app.models.DownloadService
import com.yinanyi.app.models.ModelCatalog
import com.yinanyi.app.models.ModelManager
import com.yinanyi.app.models.ModelPack

private val Accent = Color(0xFF2448C8)
private val Ground = Color(0xFFF4F5F7)
private val Ink = Color(0xFF15181D)
private val Quiet = Color(0xFF555B66)
private val Line = Color(0xFFDDE0E5)
private val Good = Color(0xFF1F8A4C)
private val Warn = Color(0xFF8A6100)

private val TextColors = listOf(0xFFFFFFFF to "白色", 0xFFFFE066 to "黄色", 0xFF9FD3FF to "浅蓝", 0xFFA8F0C0 to "浅绿", 0xFF15181D to "黑色")
private val BgColors = listOf(0xFF101217 to "黑色", 0xFF3A3F47 to "深灰", 0xFF10244F to "深蓝", 0xFFFFFFFF to "白色")

/** 一键套用的字幕样式；颜色只用设置页里有的几种，这样切过去后色块也会高亮。 */
private class StylePreset(val name: String, val detail: String, val apply: (AppSettings) -> AppSettings)

private val StylePresets = listOf(
    StylePreset("默认", "深底白字") {
        it.copy(textColor = 0xFFFFFFFF.toInt(), bgColor = 0xFF101217.toInt(), transparency = 20, fontSize = 18, maxLines = 3, showOriginal = true)
    },
    StylePreset("电影", "很透明，只留译文") {
        it.copy(textColor = 0xFFFFFFFF.toInt(), bgColor = 0xFF101217.toInt(), transparency = 75, fontSize = 20, maxLines = 2, showOriginal = false)
    },
    StylePreset("明亮", "浅底黑字") {
        it.copy(textColor = 0xFF15181D.toInt(), bgColor = 0xFFFFFFFF.toInt(), transparency = 15, fontSize = 18, maxLines = 3, showOriginal = true)
    },
    StylePreset("大字", "黄字深蓝底") {
        it.copy(textColor = 0xFFFFE066.toInt(), bgColor = 0xFF10244F.toInt(), transparency = 15, fontSize = 24, maxLines = 2, showOriginal = false)
    },
)

class MainActivity : ComponentActivity() {

    companion object {
        /** 快捷开关用它让界面直接开始翻译。 */
        const val EXTRA_AUTOSTART = "autostart"
    }

    // 第一次使用（没引导过、也没填过 Key）先走新手引导
    private var screen by mutableStateOf(if (!SettingsStore.onboarded && SettingsStore.apiKey.value.isEmpty()) "onboarding" else "home")

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
        handleLaunch(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ground) {
                    if (keepAliveDialog) KeepAliveDialog()
                    if (consentDialog) ConsentDialog()
                    clipKey?.let { ClipKeyDialog(it) }
                    MeteredDialog()
                    val keyState by ApiKeyCheck.state.collectAsState()
                    LaunchedEffect(keyState) {
                        when (val st = keyState) {
                            ApiKeyCheck.State.Ok -> Toast.makeText(this@MainActivity, "API Key 可用，在线翻译已就绪", Toast.LENGTH_SHORT).show()
                            is ApiKeyCheck.State.Bad -> Toast.makeText(this@MainActivity, st.message, Toast.LENGTH_LONG).show()
                            else -> {}
                        }
                    }
                    when (screen) {
                        "settings" -> {
                            BackHandler { screen = "home" }
                            SettingsScreen(onBack = { screen = "home" })
                        }
                        "onboarding" -> OnboardingScreen()
                        "history" -> HistoryScreen(onBack = { screen = "home" })
                        "models" -> {
                            BackHandler { screen = "home" }
                            ModelsScreen(onBack = { screen = "home" })
                        }
                        else -> HomeScreen()
                    }
                }
            }
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                android.content.ComponentName(this, TranslationTileService::class.java),
                "以南译",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor,
            ) { }
        } else {
            Toast.makeText(this, "下拉通知栏，点编辑（铅笔图标），找到「以南译」拖到上面", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    /** 从快捷开关来的：还没在翻译就直接走「开始」的流程（权限、授权都在这里处理）。 */
    private fun handleLaunch(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        i.removeExtra(EXTRA_AUTOSTART)
        if (!SubtitleBus.running.value) start()
    }

    /** 在线翻译要上传声音，第一次用之前让用户确认。 */
    private var consentDialog by mutableStateOf(false)

    /** 在百炼网页复制 Key 后切回来，没填过 Key（或正在设置页）时提示一键使用。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipKey != null) return
        if (SettingsStore.apiKey.value.isNotEmpty() && screen != "settings") return
        val key = clipboardKey() ?: return
        if (key != SettingsStore.apiKey.value && key != clipDismissed) clipKey = key
    }

    private var clipKey by mutableStateOf<String?>(null)
    private var clipDismissed: String? = null

    private fun clipboardKey(): String? {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return ApiKeyCheck.extract(clip.getItemAt(0).coerceToText(this))
    }

    private fun pasteKey() {
        val key = clipboardKey()
        if (key == null) {
            Toast.makeText(this, "剪贴板里没有 API Key，先在百炼网页上复制", Toast.LENGTH_LONG).show()
        } else {
            ApiKeyCheck.save(key)
        }
    }

    private fun openKeyPage() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bailian.console.aliyun.com/?tab=model#/api-key")))
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    /** 第一次点「开始翻译」时，如果还没处理后台限制，先提醒一次。 */
    private var keepAliveDialog by mutableStateOf(false)

    private fun start(skipKeepAliveHint: Boolean = false) {
        if (!canDrawOverlays()) {
            openOverlaySettings()
            return
        }
        if (SettingsStore.apiKey.value.isNotEmpty() && !SettingsStore.settings.value.forceOffline && !SettingsStore.onlineConsent) {
            consentDialog = true
            return
        }
        if (!skipKeepAliveHint && !KeepAlive.isIgnoringBatteryOptimizations(this) && !KeepAlive.isDone(this, KeepAlive.KEY_ASKED)) {
            KeepAlive.markDone(this, KeepAlive.KEY_ASKED)
            keepAliveDialog = true
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
        // 模型状态变化（下载完成、被删除）时重新检查，resumeTick 管回到前台
        val packStates by ModelManager.states.collectAsState()
        val missing = remember(packStates, settings.sourceLang, resumeTick) { Models(this).missing(settings.sourceLang) }
        val apiKey by SettingsStore.apiKey.collectAsState()
        val hasKey = apiKey.isNotEmpty()
        val limit = settings.dailyLimitYuan
        val overLimit = limit > 0 && usage.estimatedYuan >= limit

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
                        !hasKey -> Triple("离线模式", "填写阿里云 API Key 后可用在线翻译", Warn)
                        overLimit -> Triple("离线模式", "今日在线用量已达上限，可在设置里调高", Warn)
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

            if (!hasKey && !settings.forceOffline) {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("开启在线翻译", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text(
                            "在线翻译更准、更连贯。填一次阿里云百炼 API Key 就行：先点「去获取 Key」，在网页里复制，回到这里会自动识别。",
                            fontSize = 12.sp, color = Quiet,
                        )
                        KeyActions()
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
                    if (limit > 0) {
                        LinearProgressIndicator(
                            progress = { (usage.estimatedYuan / limit).toFloat().coerceIn(0f, 1f) },
                            color = if (overLimit) Warn else Accent,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        Text(
                            if (overLimit) "已达每日上限 ¥%.0f，在线翻译暂停，使用离线翻译".format(limit) else "每日上限 ¥%.0f，超过后自动切到离线".format(limit),
                            fontSize = 12.sp, color = if (overLimit) Warn else Quiet,
                        )
                    }
                    Text("按阿里云百炼北京地域列表价估算，以控制台账单为准", fontSize = 11.sp, color = Quiet)
                }
            }

            val historyCount = remember(resumeTick, screen) { History.sessions().size }
            if (historyCount > 0) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("翻译记录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                            Text("共 $historyCount 次，漏看的句子可以回看、复制", fontSize = 12.sp, color = Quiet)
                        }
                        OutlinedButton(onClick = { screen = "history" }) { Text("查看", fontSize = 12.sp) }
                    }
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
                    // resumeTick 让回到前台时重新检查
                    val batteryOk = resumeTick >= 0 && KeepAlive.isIgnoringBatteryOptimizations(this@MainActivity)
                    CheckRow(
                        "后台不被系统杀掉", batteryOk, if (batteryOk) "已允许" else "去允许",
                        if (batteryOk) null else ({ KeepAlive.requestIgnoreBatteryOptimizations(this@MainActivity) }),
                    )
                    if (KeepAlive.isXiaomi) {
                        val policyDone = resumeTick >= 0 && KeepAlive.isDone(this@MainActivity, KeepAlive.KEY_XIAOMI_BATTERY)
                        CheckRow("小米省电策略：无限制", if (policyDone) true else null, if (policyDone) "已设置过" else "去设置") {
                            KeepAlive.markDone(this@MainActivity, KeepAlive.KEY_XIAOMI_BATTERY)
                            KeepAlive.openXiaomiBatteryPolicy(this@MainActivity)
                        }
                        val autoDone = resumeTick >= 0 && KeepAlive.isDone(this@MainActivity, KeepAlive.KEY_XIAOMI_AUTOSTART)
                        CheckRow("小米自启动：打开", if (autoDone) true else null, if (autoDone) "已设置过" else "去设置") {
                            KeepAlive.markDone(this@MainActivity, KeepAlive.KEY_XIAOMI_AUTOSTART)
                            KeepAlive.openXiaomiAutostart(this@MainActivity)
                        }
                    }
                    CheckRow(
                        "离线模型（${settings.sourceLang.label}）",
                        missing.isEmpty(),
                        if (missing.isEmpty()) "已就绪" else "去下载",
                        if (missing.isEmpty()) null else ({ screen = "models" }),
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

    @Composable
    private fun ConsentDialog() {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("在线翻译会上传声音") },
            text = {
                Text(
                    "开启在线翻译后，B站 直播里的声音会实时上传到阿里云百炼（中国大陆服务器）完成翻译，没人说话时不会上传。" +
                        "使用的是你自己的 API Key，费用由你的阿里云账号承担；阿里云如何处理数据，以它的服务条款为准。\n\n" +
                        "不想让声音离开手机，可以选「只用离线翻译」，之后也能在设置里改。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    consentDialog = false
                    SettingsStore.onlineConsent = true
                    start()
                }) { Text("同意，使用在线翻译") }
            },
            dismissButton = {
                TextButton(onClick = {
                    consentDialog = false
                    SettingsStore.update { it.copy(forceOffline = true) }
                    start()
                }) { Text("只用离线翻译") }
            },
        )
    }

    @Composable
    private fun KeepAliveDialog() {
        AlertDialog(
            onDismissRequest = { keepAliveDialog = false },
            title = { Text("先让翻译能在后台持续运行") },
            text = {
                Text(
                    "看直播时以南译在后台工作。手机的省电功能可能中途把它关掉，字幕就停了。" +
                        "建议现在允许它在后台运行" + (if (KeepAlive.isXiaomi) "，小米手机还需要把省电策略设为「无限制」、打开自启动（首页「准备工作」里有入口）。" else "。"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    keepAliveDialog = false
                    KeepAlive.requestIgnoreBatteryOptimizations(this)
                }) { Text("去允许") }
            },
            dismissButton = {
                TextButton(onClick = {
                    keepAliveDialog = false
                    start(skipKeepAliveHint = true)
                }) { Text("先不用，直接开始") }
            },
        )
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
        val packStates by ModelManager.states.collectAsState()
        val missing = remember(packStates, s.sourceLang) { Models(this).missing(s.sourceLang) }
        val apiKey by SettingsStore.apiKey.collectAsState()
        val glossary by SettingsStore.glossary.collectAsState()
        var page by rememberSaveable { mutableStateOf<String?>(null) }
        BackHandler(enabled = page != null) { page = null }
        val title = when (page) {
            "translate" -> "翻译与识别"
            "online" -> "在线翻译"
            "style" -> "字幕外观"
            "glossary" -> "术语表"
            "history" -> "翻译记录"
            "about" -> "关于"
            else -> "设置"
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { if (page != null) page = null else onBack() },
                    modifier = Modifier.semantics { contentDescription = "返回" },
                ) { Text("‹ 返回", color = Ink) }
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }

            if (page == null) {
                SettingsRow("翻译与识别", "源语言、离线模式、录音范围、快捷开关") { page = "translate" }
                SettingsRow("在线翻译", if (apiKey.isEmpty()) "未填写 API Key · 每日用量上限" else "API Key 已设置 · 每日用量上限") { page = "online" }
                SettingsRow("字幕外观", "预设、颜色、字号、描边") { page = "style" }
                SettingsRow("术语表", if (glossary.isEmpty()) "主播名、游戏名的固定译法" else "已有 ${glossary.size} 条固定译法") { page = "glossary" }
                SettingsRow("翻译记录", if (s.saveHistory) "保存在手机上，可回看、分享" else "已关闭保存") { page = "history" }
                SettingsRow("关于", "版本 ${BuildConfig.VERSION_NAME}、许可声明、新手引导") { page = "about" }
            }

            if (page == "translate") {
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

                Section("识别与翻译")
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow("始终使用离线模式", "关闭时默认在线，断网自动切离线", s.forceOffline) { v ->
                            SettingsStore.update { it.copy(forceOffline = v) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("离线模型（${s.sourceLang.label}）", fontSize = 15.sp, color = Ink)
                                Text(
                                    if (missing.isEmpty()) "已就绪" else "缺少：" + missing.joinToString(),
                                    fontSize = 12.sp, color = if (missing.isEmpty()) Good else Warn,
                                )
                            }
                            OutlinedButton(onClick = { screen = "models" }) { Text("管理", fontSize = 12.sp) }
                        }
                    }
                }

                Section("录音范围")
                Card {
                    SwitchRow("只录 B站 的声音", "避免把微信提示音、别的 App 的声音混进来；下次开始翻译时生效", s.onlyBili) { v ->
                        SettingsStore.update { it.copy(onlyBili = v) }
                    }
                }

                Section("快速开关")
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("把「以南译」加到下拉通知栏，一拉就能开始或停止翻译，不用先打开 App。", fontSize = 12.sp, color = Quiet)
                        OutlinedButton(onClick = ::addTile) { Text(if (Build.VERSION.SDK_INT >= 33) "添加到快捷设置" else "怎么添加") }
                    }
                }

            }

            if (page == "online") {
                Section("在线翻译密钥")
                Card { ApiKeyEditor() }

                Section("在线用量上限")
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("每天在线翻译的估算费用超过上限，就自动切到离线翻译，防止挂机一整天烧钱。", fontSize = 12.sp, color = Quiet)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.0 to "不限", 2.0 to "¥2", 5.0 to "¥5", 10.0 to "¥10", 20.0 to "¥20", 50.0 to "¥50").forEach { (v, label) ->
                                val on = s.dailyLimitYuan == v
                                val shape = RoundedCornerShape(10.dp)
                                Box(
                                    Modifier.weight(1f).height(40.dp).clip(shape)
                                        .background(if (on) Accent else Color.White)
                                        .border(1.dp, if (on) Accent else Color(0xFFC9CDD4), shape)
                                        .semantics { contentDescription = "每日上限 $label" + if (on) "，已选" else "" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextButton(onClick = { SettingsStore.update { it.copy(dailyLimitYuan = v) } }, modifier = Modifier.fillMaxSize()) {
                                        Text(label, fontSize = 13.sp, color = if (on) Color.White else Ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }

            }

            if (page == "style") {
                Section("字幕外观")
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("快速预设", fontSize = 15.sp, color = Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StylePresets.forEach { p ->
                                val on = p.apply(s) == s
                                val shape = RoundedCornerShape(10.dp)
                                Column(
                                    Modifier.weight(1f).clip(shape)
                                        .background(if (on) Accent else Color.White)
                                        .border(1.dp, if (on) Accent else Color(0xFFC9CDD4), shape)
                                        .clickable { SettingsStore.update { p.apply(it) } }
                                        .padding(vertical = 8.dp)
                                        .semantics { contentDescription = "字幕预设 ${p.name}，${p.detail}" + if (on) "，已选" else "" },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else Ink)
                                    Text(p.detail, fontSize = 10.sp, color = if (on) Color(0xFFDCE3FA) else Quiet)
                                }
                            }
                        }
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
                        SwitchRow("文字描边", "亮画面上字幕也看得清", s.textOutline) { v -> SettingsStore.update { it.copy(textOutline = v) } }
                    }
                }

            }

            if (page == "glossary") {
                Section("术语表")
                Card { GlossaryEditor() }

            }

            if (page == "history") {
                Section("翻译记录")
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SwitchRow("保存翻译记录", "只存在这台手机上，最多保留最近 30 次", s.saveHistory) { v -> SettingsStore.update { it.copy(saveHistory = v) } }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { screen = "history" }, modifier = Modifier.weight(1f)) { Text("查看记录") }
                            OutlinedButton(
                                onClick = {
                                    History.clearAll()
                                    Toast.makeText(this@MainActivity, "已清空翻译记录", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("清空记录", color = Warn) }
                        }
                    }
                }

            }

            if (page == "about") {
                Section("关于")
                Card {
                    Text(
                        "以南译是个人开发的非官方工具，与腾讯、阿里云、哔哩哔哩没有关联，也未获得它们的赞助或背书。\n\n" +
                            "离线翻译使用腾讯混元 HY-MT1.5 模型，按 Tencent HY Community License 使用；" +
                            "该许可不适用于欧盟、英国和韩国。模型从腾讯官方仓库下载，本 App 不分发它。\n\n" +
                            "日语识别模型 ReazonSpeech 来自 reazon-ja-en 项目，由本项目转存到 ModelScope 供下载。\n\n" +
                            "详细的第三方组件和许可见项目主页的 NOTICE。",
                        fontSize = 12.sp, color = Quiet,
                    )
                }

                OutlinedButton(onClick = { screen = "onboarding" }, modifier = Modifier.fillMaxWidth()) { Text("重新查看新手引导") }
                Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Quiet, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }

        }
    }

    // ---------------- 翻译记录 ----------------

    @Composable
    private fun HistoryScreen(onBack: () -> Unit) {
        var open by remember { mutableStateOf<HistorySession?>(null) }
        var tick by remember { mutableIntStateOf(0) }
        val sessions = remember(tick) { History.sessions() }
        val session = open
        BackHandler { if (session != null) open = null else onBack() }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (session == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("‹ 返回", color = Ink) }
                    Text("翻译记录", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                if (sessions.isEmpty()) {
                    Text("还没有记录。开始一次翻译后，字幕会自动保存在这里（设置里可以关闭）。", fontSize = 13.sp, color = Quiet, modifier = Modifier.padding(4.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sessions) { se ->
                        Box(Modifier.clickable { open = se }) {
                            Card {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(History.title(se), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
                                    Text("${se.count} 句 ›", fontSize = 13.sp, color = Quiet)
                                }
                            }
                        }
                    }
                }
            } else {
                val entries = remember(session) { History.read(session.file) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { open = null }, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("‹ 返回", color = Ink) }
                    Text(History.title(session), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "以南译翻译记录 ${History.title(session)}")
                            .putExtra(Intent.EXTRA_TEXT, History.toText(entries))
                        startActivity(Intent.createChooser(send, "分享翻译记录"))
                    }) { Text("分享") }
                    TextButton(onClick = {
                        History.delete(session.file)
                        open = null
                        tick++
                    }) { Text("删除", color = Warn) }
                }
                Text("长按文字可以选择、复制；「分享」会把整场导出成文本。", fontSize = 12.sp, color = Quiet)
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(entries) { e ->
                            Column {
                                Text(e.translation.ifEmpty { e.original }, fontSize = 15.sp, color = Ink)
                                if (e.translation.isNotEmpty() && e.original.isNotEmpty()) {
                                    Text(e.original, fontSize = 12.sp, color = Quiet)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 新手引导 ----------------

    /** 四步：悬浮窗权限 → 在线翻译 Key → 离线模型 → 后台运行。每步都能跳过，首页「准备工作」里随时能补。 */
    @Composable
    private fun OnboardingScreen() {
        val total = 4
        var step by remember { mutableIntStateOf(0) }
        val settings by SettingsStore.settings.collectAsState()
        val states by ModelManager.states.collectAsState()
        val apiKey by SettingsStore.apiKey.collectAsState()
        val check by ApiKeyCheck.state.collectAsState()
        val models = remember { Models(this) }
        androidx.compose.runtime.LaunchedEffect(resumeTick) { ModelManager.refresh(models.dir) }
        BackHandler(enabled = step > 0) { step-- }

        fun finish() {
            SettingsStore.onboarded = true
            screen = "home"
        }

        val overlayOk = resumeTick >= 0 && canDrawOverlays()
        val needed = ModelCatalog.packsFor(settings.sourceLang).filter { states[it.id] != ModelManager.State.Installed }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${step + 1} / $total", fontSize = 13.sp, color = Quiet, modifier = Modifier.weight(1f))
                TextButton(onClick = ::finish) { Text("跳过引导", color = Quiet) }
            }
            LinearProgressIndicator(progress = { (step + 1f) / total }, modifier = Modifier.fillMaxWidth())

            when (step) {
                0 -> {
                    Text("欢迎使用以南译", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("看 B站 外语直播和视频时，把声音实时翻成中文字幕，浮在画面上。先做几项准备，大约一分钟。", fontSize = 14.sp, color = Quiet)
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("第一步：允许显示悬浮窗", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                            Text("字幕要盖在 B站 上面，需要「显示在其他应用上层」的权限。", fontSize = 13.sp, color = Quiet)
                            if (overlayOk) {
                                Text("✓ 已开启", color = Good, fontSize = 14.sp)
                            } else {
                                Button(
                                    onClick = ::openOverlaySettings, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                ) { Text("去开启") }
                            }
                        }
                    }
                }
                1 -> {
                    Text("在线翻译（推荐）", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("在线翻译更准、更连贯。需要你自己的阿里云百炼 API Key，费用按用量算，首页能看到当天估算，设置里可以设上限。", fontSize = 14.sp, color = Quiet)
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("第二步：填写 API Key", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                            Text("点「去获取 Key」，在网页里登录并创建、复制，回到这里点「粘贴 Key」。", fontSize = 13.sp, color = Quiet)
                            KeyActions()
                            when {
                                apiKey.isEmpty() -> {}
                                check == ApiKeyCheck.State.Checking -> Text("正在测试……", fontSize = 13.sp, color = Quiet)
                                check == ApiKeyCheck.State.Ok -> Text("✓ 可用", fontSize = 14.sp, color = Good)
                                check is ApiKeyCheck.State.Bad -> Text((check as ApiKeyCheck.State.Bad).message, fontSize = 13.sp, color = Warn)
                                else -> Text("已设置", fontSize = 14.sp, color = Good)
                            }
                        }
                    }
                    Text("不想花钱或暂时没有 Key，可以直接跳到下一步，只用离线翻译。", fontSize = 12.sp, color = Quiet)
                }
                2 -> {
                    Text("离线模型", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("离线翻译在手机上完成，断网也能用。在线翻译出错时也会自动切到它，所以建议下载你常看的语言。", fontSize = 14.sp, color = Quiet)
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("第三步：选择主播常用的语言", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SourceLang.entries.forEach { lang ->
                                    val on = settings.sourceLang == lang
                                    val shape = RoundedCornerShape(12.dp)
                                    Box(
                                        Modifier.weight(1f).height(44.dp).clip(shape)
                                            .background(if (on) Accent else Color.White)
                                            .border(1.dp, if (on) Accent else Color(0xFFC9CDD4), shape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        TextButton(onClick = { SettingsStore.update { it.copy(sourceLang = lang) } }, modifier = Modifier.fillMaxSize()) {
                                            Text(lang.label, color = if (on) Color.White else Ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                            if (needed.isEmpty()) {
                                Text("✓ ${settings.sourceLang.label}离线模型已就绪", color = Good, fontSize = 14.sp)
                            } else {
                                val downloading = needed.filter { states[it.id] is ModelManager.State.Downloading }
                                if (downloading.isEmpty()) {
                                    Button(
                                        onClick = { requestDownloads(needed) }, modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                    ) { Text("下载（约 ${sizeText(needed.sumOf { it.downloadBytes })}）") }
                                    needed.mapNotNull { (states[it.id] as? ModelManager.State.Failed)?.message }.firstOrNull()?.let {
                                        Text(it, fontSize = 12.sp, color = Warn)
                                    }
                                } else {
                                    val done = downloading.sumOf { (states[it.id] as ModelManager.State.Downloading).done }
                                    val all = downloading.sumOf { (states[it.id] as ModelManager.State.Downloading).total }
                                    LinearProgressIndicator(progress = { if (all > 0) done.toFloat() / all else 0f }, modifier = Modifier.fillMaxWidth())
                                    Text("下载中 ${sizeText(done)} / ${sizeText(all)}，可以先继续下一步，会在后台下载", fontSize = 12.sp, color = Quiet)
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text("后台持续运行", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("看直播时以南译在后台工作，手机的省电功能可能中途把它关掉，字幕就停了。", fontSize = 14.sp, color = Quiet)
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("第四步：允许后台运行", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                            val batteryOk = resumeTick >= 0 && KeepAlive.isIgnoringBatteryOptimizations(this@MainActivity)
                            CheckRow(
                                "后台不被系统杀掉", batteryOk, if (batteryOk) "已允许" else "去允许",
                                if (batteryOk) null else ({ KeepAlive.requestIgnoreBatteryOptimizations(this@MainActivity) }),
                            )
                            if (KeepAlive.isXiaomi) {
                                val policyDone = resumeTick >= 0 && KeepAlive.isDone(this@MainActivity, KeepAlive.KEY_XIAOMI_BATTERY)
                                CheckRow("小米省电策略：无限制", if (policyDone) true else null, if (policyDone) "已设置过" else "去设置") {
                                    KeepAlive.markDone(this@MainActivity, KeepAlive.KEY_XIAOMI_BATTERY)
                                    KeepAlive.openXiaomiBatteryPolicy(this@MainActivity)
                                }
                                val autoDone = resumeTick >= 0 && KeepAlive.isDone(this@MainActivity, KeepAlive.KEY_XIAOMI_AUTOSTART)
                                CheckRow("小米自启动：打开", if (autoDone) true else null, if (autoDone) "已设置过" else "去设置") {
                                    KeepAlive.markDone(this@MainActivity, KeepAlive.KEY_XIAOMI_AUTOSTART)
                                    KeepAlive.openXiaomiAutostart(this@MainActivity)
                                }
                            }
                        }
                    }
                    Text("以后可以在首页「准备工作」里随时修改。", fontSize = 12.sp, color = Quiet)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(48.dp)) { Text("上一步") }
                }
                Button(
                    onClick = { if (step < total - 1) step++ else finish() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    val skip = when (step) {
                        0 -> !overlayOk
                        1 -> apiKey.isEmpty()
                        2 -> needed.isNotEmpty()
                        else -> false
                    }
                    Text(if (step == total - 1) "完成，开始使用" else if (skip) "跳过" else "下一步")
                }
            }
        }
    }

    // ---------------- 离线模型 ----------------

    private fun sizeText(bytes: Long): String =
        if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "${bytes / 1048576} MB"

    private fun isMetered(): Boolean =
        getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered

    /** 要下载的包；移动数据下先确认。 */
    private var pendingDownload by mutableStateOf<List<ModelPack>>(emptyList())

    private fun startDownloads(packs: List<ModelPack>) {
        packs.forEach { DownloadService.start(this, it.id) }
    }

    private fun requestDownloads(packs: List<ModelPack>) {
        if (packs.isEmpty()) return
        if (isMetered()) pendingDownload = packs else startDownloads(packs)
    }

    /** 移动数据下载前的确认，首页、引导和模型页共用。 */
    @Composable
    private fun MeteredDialog() {
        if (pendingDownload.isNotEmpty()) {
            val packs = pendingDownload
            AlertDialog(
                onDismissRequest = { pendingDownload = emptyList() },
                title = { Text("现在是移动数据") },
                text = { Text("将下载约 ${sizeText(packs.sumOf { it.downloadBytes })}，会消耗这么多流量。建议连上 Wi-Fi 再下载。") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDownload = emptyList()
                        startDownloads(packs)
                    }) { Text("继续下载") }
                },
                dismissButton = { TextButton(onClick = { pendingDownload = emptyList() }) { Text("取消") } },
            )
        }
    }

    @Composable
    private fun ModelsScreen(onBack: () -> Unit) {
        val settings by SettingsStore.settings.collectAsState()
        val states by ModelManager.states.collectAsState()
        val models = remember { Models(this) }
        androidx.compose.runtime.LaunchedEffect(resumeTick) { ModelManager.refresh(models.dir) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("‹ 返回", color = Ink) }
                Text("离线模型", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Text(
                "离线翻译在手机上完成，断网也能用，但要先下载模型。文件下载后会校验，内容不对会自动换下载源。",
                fontSize = 13.sp, color = Quiet, modifier = Modifier.padding(horizontal = 4.dp),
            )

            val needed = ModelCatalog.packsFor(settings.sourceLang).filter { states[it.id] != ModelManager.State.Installed }
            if (needed.isNotEmpty()) {
                Button(
                    onClick = { requestDownloads(needed.filter { states[it.id] !is ModelManager.State.Downloading }) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("下载${settings.sourceLang.label}离线翻译所需的模型（约 ${sizeText(needed.sumOf { it.downloadBytes })}）") }
            }

            ModelCatalog.all.forEach { pack ->
                val state = states[pack.id] ?: ModelManager.State.Missing
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pack.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                                Text("${pack.description} · 下载约 ${sizeText(pack.downloadBytes)}", fontSize = 12.sp, color = Quiet)
                            }
                            when (state) {
                                is ModelManager.State.Installed ->
                                    TextButton(onClick = {
                                        pack.delete(models.dir)
                                        ModelManager.refresh(models.dir)
                                    }) { Text("删除", color = Warn) }
                                is ModelManager.State.Downloading ->
                                    TextButton(onClick = { DownloadService.cancel(this@MainActivity, pack.id) }) { Text("取消", color = Warn) }
                                else ->
                                    OutlinedButton(onClick = { requestDownloads(listOf(pack)) }) {
                                        Text(if (state is ModelManager.State.Failed) "重试" else "下载", fontSize = 13.sp)
                                    }
                            }
                        }
                        when (state) {
                            is ModelManager.State.Installed -> Text("✓ 已安装", fontSize = 13.sp, color = Good)
                            is ModelManager.State.Downloading -> {
                                LinearProgressIndicator(
                                    progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${sizeText(state.done)} / ${sizeText(state.total)}", fontSize = 12.sp, color = Quiet)
                            }
                            is ModelManager.State.Failed -> Text(state.message, fontSize = 12.sp, color = Warn)
                            ModelManager.State.Missing -> {}
                        }
                    }
                }
            }

            Text(
                "语音检测模型（0.6 MB）已经内置在 App 里，不用下载。\n手动安装：也可以用 adb 把文件放到 /sdcard/Android/data/com.yinanyi.app/files/models/ 下，目录结构见 README。",
                fontSize = 12.sp, color = Quiet, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    /** 「去获取 Key」和「粘贴 Key」两个按钮，首页和设置页共用。 */
    @Composable
    private fun KeyActions() {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = ::openKeyPage, modifier = Modifier.weight(1f)) { Text("去获取 Key") }
            Button(
                onClick = ::pasteKey, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("粘贴 Key") }
        }
    }

    /** 回到 App 时发现剪贴板里有 Key，问一下是否使用。 */
    @Composable
    private fun ClipKeyDialog(key: String) {
        AlertDialog(
            onDismissRequest = { clipDismissed = key; clipKey = null },
            title = { Text("发现 API Key") },
            text = { Text("剪贴板里有一个 API Key（末 4 位 ${key.takeLast(4)}），要用它开启在线翻译吗？保存后会自动测试能不能用。") },
            confirmButton = {
                TextButton(onClick = {
                    clipKey = null
                    ApiKeyCheck.save(key)
                }) { Text("使用并测试") }
            },
            dismissButton = {
                TextButton(onClick = { clipDismissed = key; clipKey = null }) { Text("忽略") }
            },
        )
    }

    /** 阿里云百炼 API Key：只存本机，界面上只显示末 4 位。 */
    @Composable
    private fun ApiKeyEditor() {
        val key by SettingsStore.apiKey.collectAsState()
        val check by ApiKeyCheck.state.collectAsState()
        var input by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = when {
                    key.isEmpty() -> "未设置，只能用离线翻译" to Warn
                    check == ApiKeyCheck.State.Checking -> "已设置（末 4 位 ${key.takeLast(4)}），正在测试……" to Quiet
                    check == ApiKeyCheck.State.Ok -> "可用（末 4 位 ${key.takeLast(4)}）" to Good
                    check is ApiKeyCheck.State.Bad -> (check as ApiKeyCheck.State.Bad).message to Warn
                    else -> "已设置（末 4 位 ${key.takeLast(4)}）" to Good
                }
                Text(label, fontSize = 15.sp, color = color, modifier = Modifier.weight(1f))
                if (key.isNotEmpty()) {
                    TextButton(onClick = { ApiKeyCheck.verify() }, enabled = check != ApiKeyCheck.State.Checking) { Text("测试") }
                    TextButton(onClick = { ApiKeyCheck.clear() }) { Text("清除", color = Warn) }
                }
            }
            KeyActions()
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text("或手动输入 Key（sk- 开头）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val k = ApiKeyCheck.extract(input)
                    if (k == null) {
                        Toast.makeText(this@MainActivity, "这不像 API Key，应以 sk- 开头", Toast.LENGTH_SHORT).show()
                    } else {
                        ApiKeyCheck.save(k)
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("保存并测试") }
            Text(
                "获取方法：打开百炼控制台 → API-KEY → 创建（北京地域），复制后回到这里点「粘贴 Key」。" +
                    "Key 只保存在这台手机上，不会上传到别处，也不在安装包里。" +
                    "开启在线翻译后，直播声音会上传到阿里云百炼翻译（没人说话时不上传）；想让声音不出手机，在「翻译与识别」里选「始终使用离线模式」。",
                fontSize = 12.sp, color = Quiet,
            )
        }
    }

    /** 术语表：主播名、游戏名、常见梗的固定译法；在线和离线翻译都会用上。 */
    @Composable
    private fun GlossaryEditor() {
        val terms by SettingsStore.glossary.collectAsState()
        var source by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) }
        var bulk by remember { mutableStateOf("") }
        if (importing) {
            AlertDialog(
                onDismissRequest = { importing = false },
                title = { Text("批量导入术语") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("每行一条，原文和译文用 = 、→ 或逗号隔开，例如：\nKuro=黑\nMio → 澪", fontSize = 13.sp, color = Quiet)
                        OutlinedTextField(value = bulk, onValueChange = { bulk = it }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = GlossaryText.parse(bulk)
                        if (parsed.isEmpty()) {
                            Toast.makeText(this@MainActivity, "没有识别到术语，每行要写成「原文=译文」", Toast.LENGTH_LONG).show()
                        } else {
                            SettingsStore.putTerms(parsed)
                            Toast.makeText(this@MainActivity, "已导入 ${parsed.size} 条术语", Toast.LENGTH_SHORT).show()
                            bulk = ""
                            importing = false
                        }
                    }) { Text("导入") }
                },
                dismissButton = { TextButton(onClick = { importing = false }) { Text("取消") } },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("指定固定译法，比如主播名、游戏名、常见梗。翻译时修改会自动重新连接后生效。", fontSize = 12.sp, color = Quiet)
            terms.forEach { term ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${term.source}  →  ${term.target}", fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { SettingsStore.removeTerm(term) }) { Text("删除", color = Warn) }
                }
            }
            if (terms.isEmpty()) Text("还没有术语", fontSize = 13.sp, color = Quiet)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { importing = true }, modifier = Modifier.weight(1f)) { Text("批量导入") }
                OutlinedButton(
                    onClick = {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("术语表", GlossaryText.format(terms)))
                        Toast.makeText(this@MainActivity, "已复制 ${terms.size} 条术语", Toast.LENGTH_SHORT).show()
                    },
                    enabled = terms.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("复制全部") }
            }
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

    /** 设置首页的一行入口。 */
    @Composable
    private fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
        Box(Modifier.clickable(onClick = onClick)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text(detail, fontSize = 12.sp, color = Quiet)
                    }
                    Text("›", fontSize = 20.sp, color = Quiet)
                }
            }
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
