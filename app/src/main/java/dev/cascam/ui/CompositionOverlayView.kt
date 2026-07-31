package dev.cascam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.cascam.geometry.NormalizedPoint
import dev.cascam.geometry.NormalizedRect
import dev.cascam.config.DEFAULT_SCOREBOARD_CORNERS

class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(115, 226, 167); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 77); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 77) }
    private val corners = DEFAULT_SCOREBOARD_CORNERS.toMutableList()
    private var activeCorner: Int? = null
    private var cropZoom = 1f
    private var cropPanX = 0f
    private var cropPanY = 0f

    fun setCrop(zoom: Float, panX: Float, panY: Float) {
        cropZoom = zoom.coerceIn(1f, 8f)
        cropPanX = panX.coerceIn(-1f, 1f)
        cropPanY = panY.coerceIn(-1f, 1f)
        invalidate()
    }

    fun setScoreboardCorners(points: List<NormalizedPoint>) {
        require(points.size == 4)
        corners.clear()
        corners.addAll(points)
        invalidate()
    }

    fun scoreboardCorners(): List<NormalizedPoint> = corners.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val crop = NormalizedRect.adjustable16x9(width, height, cropZoom, cropPanX, cropPanY)
        canvas.drawRect(crop.left * width, crop.top * height, crop.right * width, crop.bottom * height, cropPaint)
        val path = Path()
        corners.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, 12f, handlePaint)
        }
        path.close()
        canvas.drawPath(path, quadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = corners.indices.minByOrNull { index ->
                    val dx = event.x - corners[index].x * width
                    val dy = event.y - corners[index].y * height
                    dx * dx + dy * dy
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> activeCorner?.let { index ->
                corners[index] = NormalizedPoint(
                    (event.x / width).coerceIn(0f, 1f),
                    (event.y / height).coerceIn(0f, 1f),
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCorner = null
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
