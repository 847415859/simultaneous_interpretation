package com.yinanyi.app.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.yinanyi.app.MainActivity
import com.yinanyi.app.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 点「译成X」直接替换：只显示一个小的「翻译中」卡片，译完就把结果交回去。 */
class QuickTranslateActivity : TranslateActivity() {
    override val quick = true
}

/** 点「译…」：打开面板，可以换语言、语气、看备选说法。 */
class PanelTranslateActivity : TranslateActivity() {
    override val quick = false
}

private enum class Phase { CONSENT, TOO_LONG, WORKING, RESULT, SETUP, NEED_MODEL, FAILED }

/**
 * 系统「选中文字」菜单里的入口（ACTION_PROCESS_TEXT）。
 * 可编辑的文字译完能替换；别人发来的、不能编辑的文字只能复制。
 */
abstract class TranslateActivity : ComponentActivity() {
    abstract val quick: Boolean

    private var source = ""
    private var readOnly = false
    private var reverse = false
    private var detected = ""

    private var phase by mutableStateOf(Phase.WORKING)
    private var target by mutableStateOf(Langs.all.first())
    private var tone by mutableStateOf(Tones.builtin.first())
    private var result by mutableStateOf("")
    private var meta by mutableStateOf("")
    private var notice by mutableStateOf<String?>(null)
    private var failure by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var alts by mutableStateOf<List<String>>(emptyList())
    private var altsOpen by mutableStateOf(false)
    private var altsLoading by mutableStateOf(false)
    private var picked by mutableStateOf(-1)
    private var copied by mutableStateOf(false)
    private var menu by mutableStateOf("")
    private var moreLangs by mutableStateOf(false)

    /** 实际要翻译的部分（太长时可能只取前面一段）和剩下原样保留的部分。 */
    private var work = ""
    private var tail = ""
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            finish()
            return
        }
        source = text
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val s = SettingsStore.settings.value
        reverse = !TextRules.isChinese(text)
        detected = detectLabel(text)
        target = if (reverse) Langs.chinese else Langs.byCode(s.inputLang)
        tone = pickTone(s.inputTone)
        work = text

        setContent { MaterialTheme { Screen() } }
        start()
    }

    private fun pickTone(id: String): ToneDef {
        val t = SettingsStore.tones.value.find(id)
        return if (t.appliesTo(target.code)) t else Tones.builtin.first()
    }

    private fun detectLabel(text: String): String = when {
        text.any { Character.UnicodeScript.of(it.code).let { u -> u == Character.UnicodeScript.HIRAGANA || u == Character.UnicodeScript.KATAKANA } } -> "日文"
        text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } -> "韩文"
        text.any { it.isLetter() && it.code < 0x250 } -> "英文"
        else -> "外文"
    }

    // ---------------- 流程 ----------------

    private fun start() {
        if (source.length > TextRules.MAX_CHARS) {
            phase = Phase.TOO_LONG
            return
        }
        checkConsentThenRun()
    }

    private fun checkConsentThenRun() {
        val s = SettingsStore.settings.value
        if (s.inputOnline && SettingsStore.apiKey.value.isNotEmpty() && !SettingsStore.onlineConsent) {
            phase = Phase.CONSENT
        } else {
            run()
        }
    }

    private fun request(text: String) = TranslateRequest(
        text = text,
        target = target,
        tone = tone,
        keepTokens = SettingsStore.settings.value.inputKeepTokens,
        onlineFirst = SettingsStore.settings.value.inputOnline,
    )

    /** 翻译 [work]。已有译文时（换语言、语气）译文先留着，变淡显示。 */
    private fun run() {
        job?.cancel()
        alts = emptyList()
        picked = -1
        copied = false
        menu = ""
        if (result.isEmpty()) phase = Phase.WORKING else busy = true
        job = lifecycleScope.launch {
            try {
                val out = TextTranslator.translate(applicationContext, request(work))
                result = out.text + tail
                notice = out.notice
                meta = (if (out.online) "在线" else "离线") + " · " + String.format("%.1f", out.millis / 1000.0) + " 秒 · " +
                    (if (out.online) tone.name else "离线暂不区分语气") +
                    (if (tail.isNotEmpty()) " · 后面 ${tail.length} 字保持原样" else "")
                busy = false
                if (quick && !readOnly) {
                    out.notice?.let { Toast.makeText(this@TranslateActivity, it, Toast.LENGTH_LONG).show() }
                    finishWith(result)
                } else {
                    phase = Phase.RESULT
                    if (out.online && SettingsStore.settings.value.inputAlts) loadAlts()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslateFailure) {
                busy = false
                failure = e.message.orEmpty()
                phase = when (e.kind) {
                    TranslateFailure.Kind.NEED_SETUP -> Phase.SETUP
                    TranslateFailure.Kind.NEED_MODEL -> Phase.NEED_MODEL
                    else -> Phase.FAILED
                }
            }
        }
    }

    private fun loadAlts() {
        altsLoading = true
        lifecycleScope.launch {
            alts = runCatching { TextTranslator.alternatives(request(work), result.removeSuffix(tail)) }.getOrDefault(emptyList())
            altsLoading = false
        }
    }

    private fun changeLang(lang: TargetLang) {
        target = lang
        tone = pickTone(tone.id)
        val recent = (listOf(lang.code) + SettingsStore.settings.value.inputRecent.split(",")).distinct().take(5).joinToString(",")
        // 只有把中文译成外文时的选择才记作默认；读外文时译成中文不改默认
        if (!reverse) SettingsStore.update { it.copy(inputLang = lang.code, inputRecent = recent) }
        menu = ""
        run()
    }

    private fun changeTone(t: ToneDef) {
        tone = t
        SettingsStore.update { it.copy(inputTone = t.id) }
        menu = ""
        run()
    }

    private fun finishWith(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }

    private fun current(): String = if (picked >= 0 && picked < alts.size) alts[picked] + tail else result

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("译文", current()))
        copied = true
    }

    private fun openApp(page: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            if (page != null) putExtra(MainActivity.EXTRA_PAGE, page)
        })
        finish()
    }

    // ---------------- 界面 ----------------

    private class Palette(dark: Boolean) {
        val bg = if (dark) Color(0xFF1E2128) else Color.White
        val fg = if (dark) Color(0xFFE8EAEE) else Color(0xFF15181D)
        val mut = if (dark) Color(0xFFA3AAB6) else Color(0xFF555B66)
        val card = if (dark) Color(0xFF272B34) else Color(0xFFF4F5F7)
        val line = if (dark) Color(0xFF383D48) else Color(0xFFDDE0E5)
        val acc = if (dark) Color(0xFF7C9BFF) else Color(0xFF2448C8)
        val onAcc = if (dark) Color(0xFF0E1530) else Color.White
        val accBg = if (dark) Color(0xFF2A3555) else Color(0xFFE8EDFB)
        val accTx = if (dark) Color(0xFFC9D6FF) else Color(0xFF1B3AA8)
        val warnBg = if (dark) Color(0xFF3A3220) else Color(0xFFFFF4D6)
        val warnTx = if (dark) Color(0xFFF0D590) else Color(0xFF6B4E00)
    }

    @Composable
    private fun Screen() {
        val p = Palette(isSystemInDarkTheme())
        val none = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = none, indication = null) { finish() },
        ) {
            if (quick && !readOnly && phase == Phase.WORKING) {
                QuickCard(p)
            } else {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(p.bg)
                        .clickable(interactionSource = none, indication = null) {}
                        .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 22.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { Sheet(p) }
            }
        }
        if (moreLangs) LangDialog(p)
    }

    @Composable
    private fun BoxScope.QuickCard(p: Palette) {
        Column(
            Modifier.align(Alignment.Center).padding(40.dp).clip(RoundedCornerShape(20.dp)).background(p.bg)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), color = p.acc)
            Text("正在译成${target.zh}……", color = p.fg, fontSize = 15.sp)
            TextButton(onClick = { finish() }) { Text("取消", color = p.mut) }
        }
    }

    @Composable
    private fun Sheet(p: Palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("以南译 · 输入翻译", color = p.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("×", color = p.mut, fontSize = 24.sp, modifier = Modifier.clickable { finish() }.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        if (reverse && phase != Phase.TOO_LONG) Banner(p, "检测到$detected，已译成中文。", warn = false)
        Text(
            source, color = p.mut, fontSize = 13.sp, maxLines = 3,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        when (phase) {
            Phase.CONSENT -> ConsentBody(p)
            Phase.TOO_LONG -> TooLongBody(p)
            Phase.WORKING -> {
                ResultBox(p, AnnotatedString(""), "正在翻译……", dim = true)
                BigButton(p, "取消", primary = false) { finish() }
            }
            Phase.RESULT -> ResultBody(p)
            Phase.SETUP -> SetupBody(p)
            Phase.NEED_MODEL -> {
                Banner(p, "你选了「只用离线」，但还没有下载离线翻译模型（约 1.1 GB）。", warn = true)
                BigButton(p, "去下载离线模型", primary = true) { openApp() }
                BigButton(p, "改回在线优先", primary = false) {
                    SettingsStore.update { it.copy(inputOnline = true) }
                    start()
                }
            }
            Phase.FAILED -> {
                Banner(p, failure.ifEmpty { "翻译失败" }, warn = true)
                BigButton(p, "重试", primary = true) { start() }
            }
        }
    }

    @Composable
    private fun Banner(p: Palette, text: String, warn: Boolean) {
        Text(
            text, fontSize = 13.sp, lineHeight = 20.sp, color = if (warn) p.warnTx else p.accTx,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (warn) p.warnBg else p.accBg).padding(12.dp),
        )
    }

    @Composable
    private fun BigButton(p: Palette, label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp))
                .background(if (primary) p.acc else p.bg)
                .border(BorderStroke(1.dp, if (primary) p.acc else p.line), RoundedCornerShape(26.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 16.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = if (primary) p.onAcc else p.fg)
        }
    }

    @Composable
    private fun ConsentBody(p: Palette) {
        Banner(
            p,
            "在线翻译会把你选中的文字上传到阿里云百炼完成翻译，用的是你自己的 API Key，费用由你的阿里云账号承担。" +
                "阿里云如何处理数据，以它的服务条款为准。不想让文字离开手机，可以选「只用离线翻译」，之后也能在设置里改。",
            warn = true,
        )
        BigButton(p, "同意，使用在线翻译", primary = true) {
            SettingsStore.onlineConsent = true
            run()
        }
        BigButton(p, "只用离线翻译", primary = false) {
            SettingsStore.update { it.copy(inputOnline = false) }
            run()
        }
    }

    @Composable
    private fun TooLongBody(p: Palette) {
        Banner(p, "你选中了 ${"%,d".format(source.length)} 字，一次最多翻译 ${"%,d".format(TextRules.MAX_CHARS)} 字。", warn = true)
        BigButton(p, "只译前 ${"%,d".format(TextRules.MAX_CHARS)} 字", primary = true) {
            val (head, rest) = TextRules.head(source)
            work = head
            tail = rest
            checkConsentThenRun()
        }
        BigButton(p, "分 ${TextRules.paragraphs(source).size} 段依次翻译，译完一起替换", primary = false) { translateInParts() }
    }

    /** 分段翻译：每段单独翻，全部译完拼起来。任何一段失败就整体按失败处理。 */
    private fun translateInParts() {
        job?.cancel()
        phase = Phase.WORKING
        job = lifecycleScope.launch {
            try {
                val parts = TextRules.paragraphs(source)
                val sb = StringBuilder()
                var online = false
                var ms = 0L
                for (part in parts) {
                    val out = TextTranslator.translate(applicationContext, request(part))
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(out.text)
                    online = out.online
                    ms += out.millis
                }
                result = sb.toString()
                tail = ""
                work = source
                meta = (if (online) "在线" else "离线") + " · " + String.format("%.1f", ms / 1000.0) + " 秒 · 分 ${parts.size} 段"
                phase = Phase.RESULT
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslateFailure) {
                failure = e.message.orEmpty()
                phase = if (e.kind == TranslateFailure.Kind.NEED_SETUP) Phase.SETUP else if (e.kind == TranslateFailure.Kind.NEED_MODEL) Phase.NEED_MODEL else Phase.FAILED
            }
        }
    }

    @Composable
    private fun SetupBody(p: Palette) {
        Banner(p, "还不能翻译：还没有填写在线翻译的 API Key，手机上也没有下载离线模型。任选一种就可以开始。", warn = true)
        BigButton(p, "去填写 API Key（推荐）", primary = true) { openApp() }
        BigButton(p, "去下载离线模型", primary = false) { openApp() }
    }

    @Composable
    private fun ResultBox(p: Palette, text: AnnotatedString, info: String, dim: Boolean) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(BorderStroke(2.dp, p.acc), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text, color = if (dim) p.fg.copy(alpha = 0.35f) else p.fg, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.heightIn(min = 26.dp),
            )
            Text(info, color = p.mut, fontSize = 12.sp)
        }
    }

    private fun highlight(p: Palette, text: String): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in TextRules.TOKEN.findAll(text)) {
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(background = p.accBg, color = p.accTx)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

    @Composable
    private fun ResultBody(p: Palette) {
        notice?.let { Banner(p, it, warn = true) }
        val shown = current()
        val kept = TextRules.TOKEN.findAll(source).map { it.value }.distinct().toList()
        ResultBox(
            p, highlight(p, shown),
            if (busy) "重新翻译中……" else meta + if (kept.isNotEmpty() && SettingsStore.settings.value.inputKeepTokens) " · 已保留 " + kept.take(3).joinToString("、") else "",
            dim = busy,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dropdown(p, "译成", target.zh, open = menu == "lang", modifier = Modifier.weight(1f)) { menu = if (menu == "lang") "" else "lang" }
            Dropdown(p, "语气", tone.name, open = menu == "tone", modifier = Modifier.weight(1f)) { menu = if (menu == "tone") "" else "tone" }
        }
        if (menu == "lang") {
            val recent = SettingsStore.settings.value.inputRecent.split(",").map { Langs.byCode(it) }.distinct()
            val list = (recent + Langs.common).distinct().take(5)
            MenuBox(p) {
                list.forEach { l -> MenuItem(p, l.zh, l.native, l.code == target.code) { changeLang(l) } }
                MenuItem(p, "更多语言…", "", false, accent = true) { menu = ""; moreLangs = true }
            }
        }
        if (menu == "tone") {
            MenuBox(p) {
                SettingsStore.tones.value.visible(target.code).forEach { t ->
                    MenuItem(p, if (t.builtin) t.name else "我的 · ${t.name}", Tones.exampleFor(t, target.code), t.id == tone.id) { changeTone(t) }
                }
                MenuItem(p, "管理语气…", "", false, accent = true) { openApp("tones") }
            }
        }

        if (!reverse) {
            Text(
                if (altsOpen) "收起其他说法 ▴" else "换个说法 ▾", color = p.acc, fontSize = 14.sp,
                modifier = Modifier.clickable {
                    altsOpen = !altsOpen
                    if (altsOpen && alts.isEmpty() && !altsLoading) loadAlts()
                }.padding(vertical = 8.dp),
            )
            if (altsOpen) {
                when {
                    altsLoading -> Text("正在想其他说法……", color = p.mut, fontSize = 13.sp)
                    alts.isEmpty() -> Text("离线翻译不能换说法，或这次没有生成备选。", color = p.mut, fontSize = 13.sp)
                    else -> alts.forEachIndexed { i, t ->
                        Text(
                            t, color = p.fg, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (picked == i) p.accBg else p.bg)
                                .border(BorderStroke(1.dp, if (picked == i) p.acc else p.line), RoundedCornerShape(12.dp))
                                .clickable { picked = if (picked == i) -1 else i; copied = false }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }

        if (readOnly) {
            Text("这段文字不能编辑，所以没有「替换」，只能复制。", color = p.mut, fontSize = 13.sp)
            BigButton(p, if (copied) "已复制" else "复制译文", primary = true) { copy() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { BigButton(p, "替换原文", primary = true, enabled = !busy) { finishWith(current()) } }
                Box(Modifier.size(width = 110.dp, height = 52.dp)) { BigButton(p, if (copied) "已复制" else "复制", primary = false) { copy() } }
            }
        }
    }

    @Composable
    private fun Dropdown(p: Palette, label: String, value: String, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Row(
            modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, if (open) p.acc else p.line), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, color = p.mut, fontSize = 12.sp)
            Text(value, color = p.fg, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text("▾", color = p.mut)
        }
    }

    @Composable
    private fun MenuBox(p: Palette, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, p.line), RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()),
        ) { content() }
    }

    @Composable
    private fun MenuItem(p: Palette, label: String, hint: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) p.accBg else p.bg).clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label, fontSize = 15.sp, modifier = Modifier.weight(1f),
                color = if (accent) p.acc else if (selected) p.accTx else p.fg,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (hint.isNotEmpty()) Text(hint, color = p.mut, fontSize = 12.sp, maxLines = 1)
        }
    }

    @Composable
    private fun LangDialog(p: Palette) {
        var q by remember { mutableStateOf("") }
        val list = Langs.all.filter { q.isBlank() || it.zh.contains(q) || it.en.contains(q, ignoreCase = true) || it.native.contains(q, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { moreLangs = false },
            title = { Text("译成哪种语言") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("搜索，比如 法语、French") }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list) { l ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { moreLangs = false; changeLang(l) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(l.zh, modifier = Modifier.weight(1f), fontWeight = if (l.code == target.code) FontWeight.Bold else FontWeight.Normal)
                                Text(l.native, color = p.mut, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moreLangs = false }) { Text("取消") } },
        )
    }
}
