package com.yinanyi.app.translate

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yinanyi.app.MainActivity
import com.yinanyi.app.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 输入框旁的「译」按钮（输入翻译第二阶段）。
 * 只在用户点按钮的那一刻读取当前输入框里的文字，译完替换回去，并留 8 秒可以撤销。
 * 密码框和银行、支付类 App 不显示；用户还可以逐个 App 关掉。不读取、不保存其他任何内容。
 */
class InputA11yService : AccessibilityService() {
    private enum class State { IDLE, WORKING, UNDO }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private var bar: LinearLayout? = null
    private var pill: TextView? = null
    private var menu: LinearLayout? = null

    private var state = State.IDLE
    private var node: AccessibilityNodeInfo? = null
    private var nodePkg = ""
    private var original = ""
    private var translated = ""
    private var job: Job? = null

    /** 最近一次焦点或文字变化事件里的可编辑来源。 */
    private var lastSource: AccessibilityNodeInfo? = null

    private val refresh = Runnable { refreshNow() }
    private val endUndo = Runnable {
        if (state == State.UNDO) {
            state = State.IDLE
            refreshNow()
        }
    }
    private val closeMenu = Runnable { hideMenu() }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "service connected, float=${SettingsStore.settings.value.inputFloat}")
    }

    override fun onInterrupt() {
        hideAll()
    }

    override fun onDestroy() {
        hideAll()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!SettingsStore.settings.value.inputFloat) {
            if (bar != null) hideAll()
            return
        }
        Log.i(TAG, "event type=${e.eventType} pkg=${e.packageName} cls=${e.className} src=${e.source != null} srcEditable=${e.source?.isEditable} srcCls=${e.source?.className}")
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            e.source?.takeIf { it.isEditable }?.let { lastSource = it }
        }
        // 撤销期间切到了别的 App，按钮就不用留着了
        if (state == State.UNDO && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.packageName?.toString() != nodePkg
        ) {
            state = State.IDLE
        }
        // 事件很密，只记一下，停 180 毫秒再刷新
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 180)
    }

    // ---------------- 找输入框、摆按钮 ----------------

    private fun refreshNow() {
        if (!SettingsStore.settings.value.inputFloat) {
            hideAll()
            return
        }
        val f = editableField()
        if (f == null) {
            Log.i(TAG, "no editable field")
            if (state != State.UNDO) hideAll()
            return
        }
        val pkg = f.packageName?.toString().orEmpty()
        val shownText = if (f.isShowingHintText) null else f.text
        val password = f.isPassword || isPasswordType(f.inputType)

        if (state == State.UNDO && shownText?.toString() != translated) state = State.IDLE
        if (state == State.WORKING) {
            node = f
            nodePkg = pkg
            place(f)
            return
        }
        val offer = state == State.UNDO ||
            A11yRules.shouldOffer(shownText, pkg, password, SettingsStore.settings.value.inputFloatOff)
        // 只记结果，不记输入的内容
        Log.i(TAG, "field pkg=$pkg cls=${f.className} hint=${f.isShowingHintText} len=${shownText?.length} chinese=${shownText?.let { A11yRules.hasChinese(it.toString()) }} password=$password off=${A11yRules.isOff(pkg, SettingsStore.settings.value.inputFloatOff)} offer=$offer")
        if (!offer) {
            hideAll()
            return
        }
        node = f
        nodePkg = pkg
        place(f)
    }

    /**
     * 找当前正在输入的输入框。微信等 App 的「输入焦点」指向的不是输入框本身，
     * 所以依次试：输入焦点本身、它下面的可编辑子节点、最近一次文字变化事件的来源。
     */
    private fun editableField(): AccessibilityNodeInfo? {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        if (focus != null) findEditableBelow(focus, 0)?.let { return it }
        // 都没有时，在整个窗口里找可编辑、并且有焦点或正在输入的节点
        val root = rootInActiveWindow
        if (root != null) {
            val found = findEditableBelow(root, 0, deep = true)
            Log.i(TAG, "scan window pkg=${root.packageName} editableFound=${found != null} cls=${found?.className} focused=${found?.isFocused}")
            if (found != null) return found
        }
        val src = lastSource ?: return null
        val activePkg = rootInActiveWindow?.packageName
        if (activePkg != null && src.packageName != activePkg) {
            lastSource = null
            return null
        }
        return if (src.refresh() && src.isEditable && src.isVisibleToUser) src else null
    }

    private fun findEditableBelow(n: AccessibilityNodeInfo, depth: Int, deep: Boolean = false): AccessibilityNodeInfo? {
        if (n.isEditable || n.className?.toString()?.contains("EditText") == true) return n
        if (depth >= if (deep) 25 else 4) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditableBelow(c, depth + 1, deep)?.let { return it }
        }
        return null
    }

    private fun isPasswordType(type: Int): Boolean {
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun ensureBar() {
        if (bar != null) return
        val ctx = this
        val more = TextView(ctx).apply {
            text = "⋯"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = round(0xFF3A3F47.toInt(), 22)
            contentDescription = "更多选项"
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { toggleMenu() }
        }
        val p = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) }
            setOnClickListener { onPillTap() }
            setOnLongClickListener { toggleMenu(); true }
        }
        pill = p
        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(more)
            addView(p)
        }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 不抢输入框的焦点，否则输入法会收起
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 把按钮摆在输入框的右上角上方；上面放不下就放到下方。 */
    private fun place(f: AccessibilityNodeInfo) {
        ensureBar()
        val b = bar ?: return
        val s = SettingsStore.settings.value
        val pl = pill ?: return
        when (state) {
            State.WORKING -> {
                pl.text = "翻译中…"
                pl.background = round(0xFF3A3F47.toInt(), 22)
            }
            State.UNDO -> {
                pl.text = "撤销"
                pl.background = round(0xFF3A3F47.toInt(), 22)
            }
            State.IDLE -> {
                pl.text = A11yRules.pillLabel(s.inputLang)
                pl.background = round(0xFF2448C8.toInt(), 22)
            }
        }
        pl.contentDescription = pl.text

        val r = Rect()
        f.getBoundsInScreen(r)
        if (r.isEmpty) {
            hideAll()
            return
        }
        b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = b.measuredWidth
        val h = b.measuredHeight
        val dm = resources.displayMetrics
        val x = (r.right - w - dp(8)).coerceIn(dp(8), maxOf(dp(8), dm.widthPixels - w - dp(8)))
        var y = r.top - h - dp(6)
        if (y < dp(28)) y = r.bottom + dp(6)
        y = y.coerceIn(0, maxOf(0, dm.heightPixels - h))

        val lp = (b.layoutParams as? WindowManager.LayoutParams) ?: overlayParams()
        lp.x = x
        lp.y = y
        if (b.parent == null) wm.addView(b, lp) else wm.updateViewLayout(b, lp)
    }

    private fun hideAll() {
        main.removeCallbacks(refresh)
        main.removeCallbacks(endUndo)
        hideMenu()
        bar?.let { if (it.parent != null) runCatching { wm.removeView(it) } }
        bar = null
        pill = null
        if (state != State.WORKING) {
            node = null
            if (state == State.UNDO) state = State.IDLE
        }
    }

    // ---------------- 翻译并替换 ----------------

    private fun onPillTap() {
        hideMenu()
        when (state) {
            State.WORKING -> return
            State.UNDO -> undo()
            State.IDLE -> translateNow()
        }
    }

    private fun translateNow() {
        val n = node ?: return
        n.refresh()
        val text = n.text?.toString().orEmpty()
        if (text.isBlank()) return
        val s = SettingsStore.settings.value
        if (s.inputOnline && SettingsStore.apiKey.value.isNotEmpty() && !SettingsStore.onlineConsent) {
            Toast.makeText(this, "请先在以南译「输入翻译」里确认使用在线翻译", Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        val target = Langs.byCode(s.inputLang)
        val tone = SettingsStore.tones.value.find(s.inputTone).let { if (it.appliesTo(target.code)) it else Tones.builtin.first() }
        state = State.WORKING
        place(n)
        job?.cancel()
        job = scope.launch {
            try {
                val out = TextTranslator.translate(
                    applicationContext, TranslateRequest(text, target, tone, s.inputKeepTokens, s.inputOnline),
                )
                out.notice?.let { Toast.makeText(this@InputA11yService, it, Toast.LENGTH_SHORT).show() }
                if (replaceText(n, out.text)) {
                    original = text
                    translated = out.text
                    state = State.UNDO
                    main.removeCallbacks(endUndo)
                    main.postDelayed(endUndo, UNDO_MS)
                } else {
                    // 这个输入框不让改：把译文放进剪贴板，让用户自己粘贴
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("译文", out.text))
                    Toast.makeText(this@InputA11yService, "这个输入框不能自动替换，译文已复制，请粘贴", Toast.LENGTH_LONG).show()
                    state = State.IDLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslateFailure) {
                Toast.makeText(this@InputA11yService, e.message ?: "翻译失败", Toast.LENGTH_LONG).show()
                state = State.IDLE
            }
            refreshNow()
        }
    }

    private fun undo() {
        val n = node ?: return
        main.removeCallbacks(endUndo)
        job?.cancel()
        job = scope.launch {
            replaceText(n, original)
            state = State.IDLE
            refreshNow()
        }
    }

    /** 等输入框更新完再读回内容（有的 App 反映到无障碍节点上要慢几百毫秒）。 */
    private suspend fun settle(n: AccessibilityNodeInfo, want: String? = null): String {
        // 输入法可能在几百毫秒后才把自己缓存的内容再提交一次，所以不是读一次就算：
        // 每 150 毫秒读一次，最多等 2 秒，读到想要的内容就提前结束；记下变化过程方便排查
        val timeline = StringBuilder()
        var cur = ""
        var previous: String? = null
        var stable = 0
        var waited = 0
        while (waited < 2000) {
            delay(150)
            waited += 150
            n.refresh()
            cur = n.text?.toString().orEmpty()
            if (timeline.isEmpty() || !timeline.endsWith("${cur.length}")) timeline.append("${waited}ms:${cur.length} ")
            if (want != null && cur == want) break
            // 连续 4 次（0.6 秒）没有变化就认为稳定了，不用傻等满 2 秒
            if (cur == previous) {
                stable++
                if (stable >= 4) break
            } else {
                stable = 0
                previous = cur
            }
        }
        if (want != null && cur == want) {
            // 刚好等于想要的内容也再等一会儿，防止输入法随后又追加
            delay(600)
            n.refresh()
            cur = n.text?.toString().orEmpty()
            timeline.append("+600ms:${cur.length}")
        }
        Log.i(TAG, "settle timeline $timeline")
        return cur
    }

    private fun selectAll(n: AccessibilityNodeInfo) {
        n.refresh()
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /**
     * 把输入框里的内容整个换成 [text]，不需要用户再做任何操作。
     * 不同 App 的输入框脾气不一样（B站 的会把「设置文本」当成追加，「粘贴」也只插在光标处），
     * 所以每一步都读回来核对，不对就换下一种办法：
     * 设置文本 → 全选再粘贴 → 先清空再设置文本 / 粘贴。
     * 临时借用剪贴板，成功后把用户原来的内容还回去。
     */
    private suspend fun replaceText(n: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val before = cm.primaryClip
        fun restoreClipboard() = runCatching {
            if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) {
                if (before != null) cm.setPrimaryClip(before) else if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            }
        }

        // 1. 直接设置文本
        n.refresh()
        val ok1 = setTextAction(n, text)
        var cur = settle(n, text)
        Log.i(TAG, "step1 set-text ok=$ok1 result=${if (cur == text) "match" else "len=${cur.length} want=${text.length}"}")
        if (cur == text) return true

        // 2. 全选再粘贴
        cm.setPrimaryClip(ClipData.newPlainText("译文", text))
        selectAll(n)
        delay(150)
        n.refresh()
        Log.i(TAG, "step2 selection=${n.textSelectionStart}..${n.textSelectionEnd} len=${n.text?.length}")
        val pasted = n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        cur = settle(n, text)
        Log.i(TAG, "step2 paste=$pasted result=${if (cur == text) "match" else "len=${cur.length}"}")
        if (cur == text) {
            restoreClipboard()
            return true
        }

        // 2b. 全选后「剪切」把内容清掉，再往空输入框里粘贴译文
        Log.i(TAG, "node actions=${n.actionList.joinToString { it.id.toString() }} maxLen=${n.maxTextLength} inputType=${n.inputType}")
        selectAll(n)
        delay(150)
        val cut = n.performAction(AccessibilityNodeInfo.ACTION_CUT)
        cur = settle(n, "")
        Log.i(TAG, "step2b cut=$cut -> len=${cur.length}")
        if (cur.isEmpty()) {
            cm.setPrimaryClip(ClipData.newPlainText("译文", text))
            n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            cur = settle(n, text)
            Log.i(TAG, "step2b paste -> ${if (cur == text) "match" else "len=${cur.length}"}")
            if (cur == text) {
                restoreClipboard()
                return true
            }
        }

        // 3. 先清空，再设置文本 / 粘贴
        setTextAction(n, "")
        cur = settle(n, "")
        Log.i(TAG, "step3 clear -> len=${cur.length}")
        if (cur.isEmpty()) {
            setTextAction(n, text)
            cur = settle(n)
            if (cur == text) {
                restoreClipboard()
                return true
            }
            if (cur.isEmpty()) {
                n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                cur = settle(n)
                Log.i(TAG, "step3 paste -> ${if (cur == text) "match" else "len=${cur.length}"}")
                if (cur == text) {
                    restoreClipboard()
                    return true
                }
            }
        }
        // 都不行：译文留在剪贴板里，由调用方提示用户
        return false
    }

    private fun setTextAction(n: AccessibilityNodeInfo, text: String): Boolean {
        n.refresh()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_PAGE, "input"),
        )
    }

    // ---------------- 「⋯」菜单：语言、语气、本 App 不再显示 ----------------

    private fun toggleMenu() {
        if (menu != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        val b = bar ?: return
        val s = SettingsStore.settings.value
        val cfg = SettingsStore.tones.value
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dp(8).toFloat()
        }
        fun row(label: String, on: Boolean = false, color: Int = 0xFF15181D.toInt(), click: () -> Unit) {
            list.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(if (on) 0xFF1B3AA8.toInt() else color)
                if (on) background = round(0xFFE8EDFB.toInt(), 8)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                minHeight = dp(44)
                setOnClickListener {
                    click()
                    hideMenu()
                    refreshNow()
                }
            }, LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        fun header(label: String) = list.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF555B66.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(2))
        })

        header("译成")
        (Langs.common + Langs.byCode(s.inputLang)).distinct().forEach { l ->
            row("${l.zh}${if (l.code == s.inputLang) " ✓" else ""}", l.code == s.inputLang) {
                com.yinanyi.app.SettingsStore.update { it.copy(inputLang = l.code) }
            }
        }
        header("语气")
        cfg.visible(s.inputLang).take(6).forEach { t ->
            row((if (t.builtin) t.name else "我的 · ${t.name}") + if (t.id == s.inputTone) " ✓" else "", t.id == s.inputTone) {
                com.yinanyi.app.SettingsStore.update { it.copy(inputTone = t.id) }
            }
        }
        row("本 App 不再显示「译」", color = 0xFF8A6100.toInt()) {
            if (nodePkg.isNotEmpty()) com.yinanyi.app.SettingsStore.update { it.copy(inputFloatOff = A11yRules.addOff(it.inputFloatOff, nodePkg)) }
            hideAll()
        }
        row("设置…", color = 0xFF2448C8.toInt()) { openApp() }

        val lp = overlayParams()
        val bl = b.layoutParams as? WindowManager.LayoutParams
        val dm = resources.displayMetrics
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val h = list.measuredHeight
        lp.x = ((bl?.x ?: 0) + b.measuredWidth - list.measuredWidth).coerceIn(dp(8), maxOf(dp(8), dm.widthPixels - list.measuredWidth - dp(8)))
        // 按钮上方放得下就放上方，否则放下方
        val above = (bl?.y ?: 0) - h - dp(6)
        lp.y = if (above >= dp(28)) above else ((bl?.y ?: 0) + b.measuredHeight + dp(6)).coerceAtMost(maxOf(0, dm.heightPixels - h))
        list.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) hideMenu()
            false
        }
        menu = list
        wm.addView(list, lp)
        main.removeCallbacks(closeMenu)
        main.postDelayed(closeMenu, 8000)
    }

    private fun hideMenu() {
        main.removeCallbacks(closeMenu)
        menu?.let { if (it.parent != null) runCatching { wm.removeView(it) } }
        menu = null
    }

    private companion object {
        const val TAG = "InputA11y"
        const val UNDO_MS = 8000L
    }
}
