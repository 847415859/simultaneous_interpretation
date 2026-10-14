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
        val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f == null || !f.isEditable) {
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
        if (!offer) {
            hideAll()
            return
        }
        node = f
        nodePkg = pkg
        place(f)
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
                if (setText(n, out.text)) {
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
        setText(n, original)
        main.removeCallbacks(endUndo)
        state = State.IDLE
        refreshNow()
    }

    private fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
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
        const val UNDO_MS = 8000L
    }
}
