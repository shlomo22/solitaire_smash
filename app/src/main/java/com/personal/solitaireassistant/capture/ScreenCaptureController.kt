package com.personal.solitaireassistant.capture

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureController(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onFrame: (Bitmap) -> Unit,
    private val onStopped: () -> Unit,
    private val onBlackFrame: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private val intervalMs = AtomicLong(750L)
    private val lastEmitMs = AtomicLong(0L)
    private val latestBitmap = AtomicReference<Bitmap?>(null)

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stop()
            onStopped()
        }
    }

    fun start(captureIntervalMs: Long) {
        if (!running.compareAndSet(false, true)) return
        intervalMs.set(captureIntervalMs.coerceIn(250L, 3000L))

        val metrics = currentMetrics()
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        workerThread = HandlerThread("screen-capture").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)

        mediaProjection.registerCallback(projectionCallback, workerHandler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            drainLatest(reader)
        }, workerHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "SolitaireCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            workerHandler
        )
        Log.i(TAG, "VirtualDisplay started ${width}x$height@$densityDpi")
    }

    fun updateInterval(ms: Long) {
        intervalMs.set(ms.coerceIn(250L, 3000L))
    }

    fun resizeIfNeeded() {
        if (!running.get()) return
        val metrics = currentMetrics()
        if (metrics.widthPixels == width && metrics.heightPixels == height) return
        Log.i(TAG, "Resizing capture ${width}x$height -> ${metrics.widthPixels}x${metrics.heightPixels}")
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi
        virtualDisplay?.resize(width, height, densityDpi)
        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ drainLatest(it) }, workerHandler)
            virtualDisplay?.setSurface(reader.surface)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mediaProjection.stop()
        } catch (_: Exception) {
        }
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        latestBitmap.getAndSet(null)?.recycle()
    }

    private fun drainLatest(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val now = System.currentTimeMillis()
            val last = lastEmitMs.get()
            if (now - last < intervalMs.get()) {
                return
            }
            if (!lastEmitMs.compareAndSet(last, now) && now - lastEmitMs.get() < intervalMs.get()) {
                return
            }
            val bitmap = imageToBitmap(image) ?: return
            if (isMostlyBlack(bitmap)) {
                onBlackFrame()
            }
            latestBitmap.getAndSet(bitmap)?.let { old ->
                if (!old.isRecycled) old.recycle()
            }
            onFrame(bitmap)
        } catch (t: Throwable) {
            Log.w(TAG, "Frame drain failed", t)
        } finally {
            image?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (bitmap.width == width) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (cropped !== bitmap) bitmap.recycle()
            cropped
        }
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var dark = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r + g + b < 30) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && dark.toFloat() / total > 0.92f
    }

    private fun currentMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Prefer portrait game layouts; still capture actual orientation.
        val orientation = context.resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE && metrics.widthPixels < metrics.heightPixels) {
            val tmp = metrics.widthPixels
            metrics.widthPixels = metrics.heightPixels
            metrics.heightPixels = tmp
        }
        return metrics
    }

    companion object {
        private const val TAG = "ScreenCapture"
    }
}
