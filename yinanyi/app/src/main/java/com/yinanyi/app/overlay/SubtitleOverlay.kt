package com.yinanyi.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.yinanyi.app.AppSettings
import com.yinanyi.app.EngineMode
import com.yinanyi.app.OverlayGeometry
import com.yinanyi.app.SettingsStore
import com.yinanyi.app.SubtitleLine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 盖在 B站 上的悬浮字幕窗（原型「B站竖屏 / 横屏 · 悬浮字幕」「字幕已收起」）。
 * 顶部横条拖动位置，右下角拖拽改变大小，「—」收起成屏幕右边的小圆点。
 */
class SubtitleOverlay(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit,
    private val onCycleLanguage: () -> Unit,
) {
    data class State(
        val settings: AppSettings,
        val lines: List<SubtitleLine>,
        val partial: String,
        val mode: EngineMode,
        val paused: Boolean,
        val status: String,
    )

    private val wm = context.getSystemService(WindowManager::class.java)
    private var expanded = true
    private var showing = false
    private var last: State? = null

    // ---------- 视图 ----------
    private val panel = FrameLayout(context)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val handle = View(context)
    private val langChip = TextView(context)
    private val modeBadge = TextView(context)
    private val collapseBtn = roundButton("—", "收起字幕")
    private val pauseBtn = roundButton("❚❚", "暂停翻译")
    private val closeBtn = roundButton("✕", "停止并关闭字幕")
    private val linesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val partialView = TextView(context)
    private val statusView = TextView(context)
    private val grip = TextView(context)

    private val bubble = FrameLayout(context)
    private val bubbleText = TextView(context)
    private val bubbleDot = View(context)

    private val panelParams = baseParams()
    // 收起后的小圆点尽量小，只占屏幕边上一点点
    private val bubbleParams = baseParams().apply {
        width = dp(38)
        height = dp(34)
    }

    init {
        buildPanel()
        buildBubble()
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    // 顶部控制条尽量做小，少挡画面：按钮 28dp、无底色，状态只用一个小圆点表示
    private fun roundButton(label: String, description: String) = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(2) }
    }

    private fun buildPanel() {
        handle.layoutParams = LinearLayout.LayoutParams(dp(18), dp(3)).apply { marginEnd = dp(6) }
        langChip.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(7), 0, dp(7), 0)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20))
            contentDescription = "切换源语言"
            setOnClickListener { onCycleLanguage() }
        }
        modeBadge.apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(6) }
        }
        val spacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        collapseBtn.setOnClickListener { collapse() }
        pauseBtn.setOnClickListener { onTogglePause() }
        closeBtn.setOnClickListener { onStop() }
        listOf(handle, langChip, modeBadge, spacer, collapseBtn, pauseBtn, closeBtn).forEach { topBar.addView(it) }

        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        content.setPadding(dp(10), dp(2), dp(10), dp(8))
        content.addView(topBar)
        content.addView(statusView)
        content.addView(linesBox)
        content.addView(partialView)

        grip.apply {
            text = "◢"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(4), dp(3))
            gravity = Gravity.CENTER
            contentDescription = "拖动调整大小"
        }
        panel.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(grip, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END))

        topBar.setOnTouchListener(DragListener(panelParams, panel, horizontal = true))
        grip.setOnTouchListener(ResizeListener())
    }

    private fun buildBubble() {
        bubbleText.apply {
            text = "译"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, 0, 0)
        }
        bubble.background = GradientDrawable().apply {
            setColor(0xDB101217.toInt())
            cornerRadii = floatArrayOf(dp(17).toFloat(), dp(17).toFloat(), 0f, 0f, 0f, 0f, dp(17).toFloat(), dp(17).toFloat())
        }
        bubble.contentDescription = "展开字幕，翻译仍在进行"
        bubble.addView(bubbleText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bubble.addView(bubbleDot, FrameLayout.LayoutParams(dp(6), dp(6)).apply { leftMargin = dp(5); topMargin = dp(5) })
        bubble.setOnTouchListener(DragListener(bubbleParams, bubble, horizontal = false, onTap = { expand() }))
    }

    // ---------- 显示 / 收起 ----------

    private val landscape get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun show() {
        if (showing) return
        applyGeometry()
        wm.addView(panel, panelParams)
        showing = true
        expanded = true
    }

    fun hide() {
        if (!showing) return
        runCatching { wm.removeView(if (expanded) panel else bubble) }
        showing = false
    }

    private fun collapse() {
        if (!expanded) return
        wm.removeView(panel)
        val screenW = context.resources.displayMetrics.widthPixels
        bubbleParams.x = screenW - bubbleParams.width
        bubbleParams.y = panelParams.y
        wm.addView(bubble, bubbleParams)
        expanded = false
    }

    private fun expand() {
        if (expanded) return
        wm.removeView(bubble)
        wm.addView(panel, panelParams)
        expanded = true
    }

    /** 屏幕旋转后按横竖屏各自保存的位置重新摆放。 */
    fun onConfigurationChanged() {
        if (!showing) return
        applyGeometry()
        if (expanded) wm.updateViewLayout(panel, panelParams) else {
            bubbleParams.x = context.resources.displayMetrics.widthPixels - bubbleParams.width
            wm.updateViewLayout(bubble, bubbleParams)
        }
    }

    private fun applyGeometry() {
        val dm = context.resources.displayMetrics
        val g = SettingsStore.geometry(landscape)
        if (landscape) {
            val w = minOf(dp(640), (dm.widthPixels * 0.75).roundToInt())
            panelParams.width = if (g.width > 0) g.width else w
            panelParams.x = if (g.x >= 0) g.x else (dm.widthPixels - panelParams.width) / 2
            panelParams.y = if (g.y >= 0) g.y else dm.heightPixels - dp(170)
        } else {
            // 默认贴在 B站 竖屏视频（16:9）下方
            panelParams.width = if (g.width > 0) g.width else dm.widthPixels - dp(24)
            panelParams.x = if (g.x >= 0) g.x else dp(12)
            panelParams.y = if (g.y >= 0) g.y else dm.widthPixels * 9 / 16 + dp(40)
        }
        panelParams.height = if (g.height > 0) g.height else WindowManager.LayoutParams.WRAP_CONTENT

        // 保存的位置可能来自旧版本或另一种屏幕方向，放回屏幕内
        panelParams.width = panelParams.width.coerceAtMost(dm.widthPixels)
        panelParams.x = panelParams.x.coerceIn(0, max(0, dm.widthPixels - panelParams.width))
        panelParams.y = panelParams.y.coerceIn(0, max(0, dm.heightPixels - dp(80)))
    }

    private fun saveGeometry() {
        SettingsStore.saveGeometry(
            landscape,
            OverlayGeometry(panelParams.x, panelParams.y, panelParams.width, panelParams.height),
        )
    }

    // ---------- 渲染 ----------

    fun render(state: State) {
        last = state
        val s = state.settings
        val bgAlpha = ((100 - s.transparency) * 255 / 100).coerceIn(0, 255)
        val lightBg = ColorUtils.calculateLuminance(s.bgColor) > 0.5
        val control = if (lightBg) 0xFF15181D.toInt() else Color.WHITE
        val quiet = if (lightBg) 0xFF555B66.toInt() else 0xFFB4BAC4.toInt()

        panel.background = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(s.bgColor, bgAlpha))
            cornerRadius = dp(12).toFloat()
        }
        handle.background = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(control, 90))
            cornerRadius = dp(2).toFloat()
        }
        langChip.text = "${s.sourceLang.short}→中"
        langChip.setTextColor(control)
        langChip.background = GradientDrawable().apply {
            setStroke(dp(1), ColorUtils.setAlphaComponent(control, 80))
            cornerRadius = dp(10).toFloat()
        }

        // 绿点在线，橙点离线，灰点暂停
        val (badgeLabel, badgeColor) = when {
            state.paused -> "已暂停" to 0xFF9AA0AA.toInt()
            state.mode == EngineMode.ONLINE -> "在线" to 0xFF4BD385.toInt()
            else -> "离线" to 0xFFE0A23A.toInt()
        }
        modeBadge.contentDescription = badgeLabel
        modeBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(badgeColor)
        }
        bubbleDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(badgeColor)
        }

        for (b in listOf(collapseBtn, pauseBtn, closeBtn)) {
            b.setTextColor(ColorUtils.setAlphaComponent(control, 190))
        }
        pauseBtn.text = if (state.paused) "▶" else "❚❚"
        pauseBtn.contentDescription = if (state.paused) "继续翻译" else "暂停翻译"
        grip.setTextColor(ColorUtils.setAlphaComponent(control, 160))

        statusView.setTextColor(quiet)
        statusView.text = state.status
        statusView.visibility = if (state.lines.isEmpty() && state.status.isNotEmpty()) View.VISIBLE else View.GONE

        linesBox.removeAllViews()
        for (line in state.lines.takeLast(s.maxLines)) {
            if (s.showOriginal && line.original.isNotEmpty()) {
                linesBox.addView(TextView(context).apply {
                    text = line.original
                    setTextColor(quiet)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(6), 0, 0)
                })
            }
            val translation = line.translation
            if (translation == null || translation.isNotEmpty()) {
                linesBox.addView(TextView(context).apply {
                    text = translation ?: "……"
                    setTextColor(s.textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSize.toFloat())
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    setLineSpacing(0f, 1.15f)
                    setShadowLayer(dp(2).toFloat(), 0f, 0f, ColorUtils.setAlphaComponent(s.bgColor, 200))
                })
            }
        }

        partialView.text = state.partial
        partialView.visibility = if (state.partial.isEmpty()) View.GONE else View.VISIBLE
        partialView.setTextColor(ColorUtils.setAlphaComponent(s.textColor, 150))
        partialView.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSize.toFloat())
    }

    // ---------- 手势 ----------

    /** 拖动移动窗口；onTap 不为空时，几乎没移动的点击当作点按。 */
    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
        private val view: View,
        private val horizontal: Boolean,
        private val onTap: (() -> Unit)? = null,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dx * dx + dy * dy > dp(6) * dp(6)) moved = true
                    // 整个窗口保持在屏幕内，否则右下角的拖拽角会跑到屏幕外
                    val dm = context.resources.displayMetrics
                    val w = if (view.width > 0) view.width else params.width
                    val h = if (view.height > 0) view.height else 0
                    if (horizontal) params.x = (startX + dx.roundToInt()).coerceIn(0, max(0, dm.widthPixels - w))
                    params.y = (startY + dy.roundToInt()).coerceIn(0, max(0, dm.heightPixels - h))
                    wm.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    if (view === panel) saveGeometry() else panelParams.y = params.y
                }
            }
            return true
        }
    }

    private inner class ResizeListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startW = 0
        private var startH = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = panel.width
                    startH = panel.height
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = context.resources.displayMetrics
                    panelParams.width = (startW + (e.rawX - downX).roundToInt())
                        .coerceIn(dp(200), max(dp(200), dm.widthPixels - panelParams.x))
                    panelParams.height = (startH + (e.rawY - downY).roundToInt())
                        .coerceIn(dp(70), max(dp(70), dm.heightPixels - panelParams.y))
                    wm.updateViewLayout(panel, panelParams)
                }
                MotionEvent.ACTION_UP -> saveGeometry()
            }
            return true
        }
    }
}
