package dev.cascam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.geometry.NormalizedRect

class ComposedOutputView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var courtFrame: Bitmap? = null
    private var scoreboardFrame: Bitmap? = null
    @Volatile private var configuration = BroadcastConfiguration()

    fun configure(value: BroadcastConfiguration) { configuration = value; invalidate() }
    fun submitCourt(bitmap: Bitmap) = post {
        val previous = courtFrame
        courtFrame = bitmap
        previous?.recycle()
        invalidate()
    }
    fun submitScoreboard(bitmap: Bitmap) = post {
        val previous = scoreboardFrame
        scoreboardFrame = bitmap
        previous?.recycle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val config = configuration
        courtFrame?.let { drawCourt(canvas, it, config) }
        scoreboardFrame?.let { drawScoreboard(canvas, it, config) }
    }

    private fun drawCourt(canvas: Canvas, bitmap: Bitmap, config: BroadcastConfiguration) {
        val crop = NormalizedRect.adjustable16x9(bitmap.width, bitmap.height, config.cropZoom, config.cropPanX, config.cropPanY)
        val source = Rect(
            (crop.left * bitmap.width).toInt(), (crop.top * bitmap.height).toInt(),
            (crop.right * bitmap.width).toInt(), (crop.bottom * bitmap.height).toInt(),
        )
        canvas.drawBitmap(bitmap, source, Rect(0, 0, width, height), paint)
    }

    private fun drawScoreboard(canvas: Canvas, bitmap: Bitmap, config: BroadcastConfiguration) {
        val configured = config.scoreboardDestination
        val destination = RectF(configured.left * width, configured.top * height, configured.right * width, configured.bottom * height)
        val sourcePoints = config.scoreboardCorners.flatMap { listOf(it.x * bitmap.width, it.y * bitmap.height) }.toFloatArray()
        val destinationPoints = floatArrayOf(
            destination.left, destination.top, destination.right, destination.top,
            destination.right, destination.bottom, destination.left, destination.bottom,
        )
        val homography = Matrix()
        if (!homography.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) return
        canvas.save()
        canvas.clipRect(destination)
        canvas.concat(homography)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawRect(destination, paint)
        paint.style = Paint.Style.FILL
    }
}
