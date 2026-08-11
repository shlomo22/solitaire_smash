package com.personal.solitaireassistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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

    fun setArrowColor(argb: Int) {
        // Keep alpha conservative for untrusted-touch / non-intrusive overlay.
        val capped = ColorUtils.setAlphaComponent(argb, 200)
        paint.color = capped
        fillPaint.color = capped
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

        canvas.drawLine(fromX, fromY, shaftEndX, shaftEndY, paint)

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
        canvas.drawPath(path, fillPaint)
    }
}

class OverlayController(private val context: Context) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MoveOverlayView? = null
    private var colorArgb: Int = 0xE6000000.toInt()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBlink: Runnable? = null

    fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(context)

    fun showIdle() {
        if (!canDrawOverlays()) return
        ensureView()
        view?.clearArrow()
    }

    fun setColor(argb: Int) {
        colorArgb = argb
        view?.setArrowColor(argb)
    }

    fun showMove(from: BoardRegion, to: BoardRegion) {
        if (!canDrawOverlays()) return
        cancelBlink()
        ensureView()
        view?.showArrow(from, to)
    }

    fun blinkMove(from: BoardRegion, to: BoardRegion) {
        if (!canDrawOverlays()) return
        cancelBlink()
        ensureView()
        view?.clearArrow()
        val showAgain = Runnable {
            pendingBlink = null
            view?.showArrow(from, to)
        }
        pendingBlink = showAgain
        mainHandler.postDelayed(showAgain, BLINK_OFF_MS)
    }

    fun hideArrowTemporarily() {
        cancelBlink()
        view?.clearArrow()
    }

    fun hide() {
        cancelBlink()
        val v = view ?: return
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
        view = null
    }

    private fun ensureView() {
        if (view != null) return
        val overlay = MoveOverlayView(context).also {
            it.setArrowColor(colorArgb)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
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
        windowManager.addView(overlay, params)
        view = overlay
    }

    private fun cancelBlink() {
        pendingBlink?.let(mainHandler::removeCallbacks)
        pendingBlink = null
    }

    companion object {
        private const val BLINK_OFF_MS = 160L
    }
}
