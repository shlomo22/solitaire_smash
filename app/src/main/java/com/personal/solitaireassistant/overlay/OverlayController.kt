package com.personal.solitaireassistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.personal.solitaireassistant.game.BoardRegion
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MoveOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    @Volatile
    private var fromX = 0f
    @Volatile
    private var fromY = 0f
    @Volatile
    private var toX = 0f
    @Volatile
    private var toY = 0f
    @Volatile
    private var visibleArrow = false

    private val arrowAlpha = 210
    private val sourceColor = ColorUtils.setAlphaComponent(Color.RED, arrowAlpha)
    private val targetColor = ColorUtils.setAlphaComponent(Color.GREEN, arrowAlpha)

    @Suppress("UNUSED_PARAMETER")
    fun setArrowColor(argb: Int) {
        // Arrow uses a fixed red (source) → green (target) gradient.
        invalidate()
    }

    fun showArrow(from: BoardRegion, to: BoardRegion) {
        fromX = from.centerX
        fromY = from.centerY
        toX = to.centerX
        toY = to.centerY
        visibleArrow = true
        postInvalidateOnAnimation()
    }

    fun clearArrow() {
        visibleArrow = false
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleArrow) return

        val angle = atan2(toY - fromY, toX - fromX)
        val length = hypot(toX - fromX, toY - fromY)
        if (length < 8f) return

        val tipX = toX
        val tipY = toY
        val shaftEndX = tipX - cos(angle).toFloat() * 38f
        val shaftEndY = tipY - sin(angle).toFloat() * 38f

        paint.shader = LinearGradient(
            fromX,
            fromY,
            tipX,
            tipY,
            intArrayOf(sourceColor, targetColor),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(fromX, fromY, shaftEndX, shaftEndY, paint)
        paint.shader = null

        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(
            tipX - cos(angle - 0.45).toFloat() * 46f,
            tipY - sin(angle - 0.45).toFloat() * 46f
        )
        path.lineTo(
            tipX - cos(angle + 0.45).toFloat() * 46f,
            tipY - sin(angle + 0.45).toFloat() * 46f
        )
        path.close()
        fillPaint.color = targetColor
        canvas.drawPath(path, fillPaint)
    }
}

class OverlayController(
    private val context: Context,
    private val onCancelHint: () -> Unit = {},
    private val onLabelBoard: () -> Unit = {}
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var arrowView: MoveOverlayView? = null
    private var cancelButton: TextView? = null
    private var labelButton: TextView? = null
    private var stuckIndicator: TextView? = null
    private var colorArgb: Int = 0xE6000000.toInt()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBlink: Runnable? = null
    @Volatile
    private var reviewMode = false

    fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(context)

    fun setReviewMode(active: Boolean) = runOnMain {
        reviewMode = active
        if (active) {
            hideOverlayChrome()
        } else if (arrowView != null) {
            showLabelButton()
        }
    }

    fun showIdle() = runOnMain {
        if (!canDrawOverlays()) return@runOnMain
        if (reviewMode) {
            hideOverlayChrome()
            return@runOnMain
        }
        ensureArrowView()
        arrowView?.clearArrow()
        hideCancelButton()
        showLabelButton()
    }

    fun setColor(argb: Int) = runOnMain {
        colorArgb = argb
        arrowView?.setArrowColor(argb)
    }

    fun showMove(from: BoardRegion, to: BoardRegion) = runOnMain {
        if (!canDrawOverlays()) return@runOnMain
        if (reviewMode) {
            hideOverlayChrome()
            return@runOnMain
        }
        cancelBlink()
        ensureArrowView()
        arrowView?.showArrow(from, to)
        showCancelButton()
        showLabelButton()
    }

    fun blinkMove(from: BoardRegion, to: BoardRegion) = runOnMain {
        if (!canDrawOverlays()) return@runOnMain
        if (reviewMode) {
            hideOverlayChrome()
            return@runOnMain
        }
        cancelBlink()
        ensureArrowView()
        arrowView?.clearArrow()
        showCancelButton()
        showLabelButton()
        val showAgain = Runnable {
            pendingBlink = null
            arrowView?.showArrow(from, to)
        }
        pendingBlink = showAgain
        mainHandler.postDelayed(showAgain, BLINK_OFF_MS)
    }

    fun hideArrowTemporarily() = runOnMain {
        cancelBlink()
        arrowView?.clearArrow()
        hideCancelButton()
        hideStuckIndicatorInternal()
        if (reviewMode) hideLabelButton()
    }

    /**
     * A separate badge alongside the arrow (never instead of it) for a
     * position the solver believes has no productive move at all - wasteCycleStuck
     * (a full stock lap produced zero progress) plus the best move still being
     * Draw/Recycle even after every "unstuck" bonus MoveSelector knows about.
     * The arrow itself is untouched by this: recognition can be wrong, so the
     * best-known move (even if it's just "draw") stays visible and actionable.
     */
    fun showStuckIndicator() = runOnMain {
        if (!canDrawOverlays()) return@runOnMain
        if (reviewMode) return@runOnMain
        if (stuckIndicator == null) {
            stuckIndicator = TextView(context).apply {
                text = context.getString(com.personal.solitaireassistant.R.string.overlay_stuck_indicator)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = roundedBackground(0xCCB71C1C.toInt())
                alpha = 0.92f
                isClickable = false
                isFocusable = false
            }
            windowManager.addView(stuckIndicator, stuckLayoutParams())
        }
        stuckIndicator?.visibility = View.VISIBLE
    }

    fun hideStuckIndicator() = runOnMain {
        hideStuckIndicatorInternal()
    }

    private fun hideStuckIndicatorInternal() {
        stuckIndicator?.visibility = View.GONE
    }

    /**
     * Full hide for frames where we're not actually looking at the game board at all
     * (NOT_PLAY_SCREEN / NO_BOARD) - unlike [hideArrowTemporarily], this also drops the
     * Label button. That button previously only got cleared via [reviewMode], so it kept
     * showing over the home screen / other apps / anything not recognized as live play,
     * since those frames don't set reviewMode - they're a completely separate signal.
     */
    fun hideAllChrome() = runOnMain {
        hideOverlayChrome()
    }

    private fun hideOverlayChrome() {
        cancelBlink()
        arrowView?.clearArrow()
        hideCancelButton()
        hideLabelButton()
        hideStuckIndicatorInternal()
    }

    fun hide() = runOnMain {
        cancelBlink()
        removeView(arrowView)
        arrowView = null
        removeView(cancelButton)
        cancelButton = null
        removeView(labelButton)
        labelButton = null
        removeView(stuckIndicator)
        stuckIndicator = null
    }

    /** Overlay views must only be mutated on the main thread. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun ensureArrowView() {
        if (arrowView != null) return
        val overlay = MoveOverlayView(context).also {
            it.setArrowColor(colorArgb)
        }
        windowManager.addView(overlay, overlayLayoutParams())
        arrowView = overlay
    }

    private fun showCancelButton() {
        if (cancelButton == null) {
            cancelButton = TextView(context).apply {
                text = context.getString(com.personal.solitaireassistant.R.string.overlay_cancel_hint)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = roundedBackground()
                alpha = 0.92f
                isClickable = true
                isFocusable = false
                setOnClickListener { onCancelHint() }
            }
            windowManager.addView(cancelButton, cancelLayoutParams())
        }
        cancelButton?.visibility = View.VISIBLE
    }

    private fun hideCancelButton() {
        cancelButton?.visibility = View.GONE
    }

    private fun showLabelButton() {
        if (reviewMode) return
        if (labelButton == null) {
            labelButton = TextView(context).apply {
                text = context.getString(com.personal.solitaireassistant.R.string.overlay_label_board)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = roundedBackground(0xCC1B5E20.toInt())
                alpha = 0.92f
                isClickable = true
                isFocusable = false
                setOnClickListener { onLabelBoard() }
            }
            windowManager.addView(labelButton, labelLayoutParams())
        }
        labelButton?.visibility = View.VISIBLE
    }

    private fun hideLabelButton() {
        labelButton?.visibility = View.GONE
    }

    private fun overlayLayoutParams(): WindowManager.LayoutParams {
        val type = overlayWindowType()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "SolitaireMoveOverlay"
        }
    }

    private fun cancelLayoutParams(): WindowManager.LayoutParams {
        val type = overlayWindowType()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(92)
            title = "SolitaireCancelOverlay"
        }
    }

    private fun labelLayoutParams(): WindowManager.LayoutParams {
        val type = overlayWindowType()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(20)
            y = dp(92)
            title = "SolitaireLabelOverlay"
        }
    }

    private fun stuckLayoutParams(): WindowManager.LayoutParams {
        val type = overlayWindowType()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Above the cancel button (also BOTTOM|CENTER_HORIZONTAL, y=dp(92))
            // so the two never overlap.
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(150)
            title = "SolitaireStuckOverlay"
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun removeView(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int = 0xCC4A148C.toInt()) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(color)
            setStroke(dp(1), 0x88FFFFFF.toInt())
        }

    private fun cancelBlink() {
        pendingBlink?.let(mainHandler::removeCallbacks)
        pendingBlink = null
    }

    companion object {
        private const val BLINK_OFF_MS = 80L
    }
}
