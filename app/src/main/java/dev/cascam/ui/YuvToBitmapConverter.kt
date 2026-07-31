package dev.cascam.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object YuvToBitmapConverter {
    fun convert(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val pixels = IntArray(width * height)
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yValue = yBuffer.get(y * planes[0].rowStride + x * planes[0].pixelStride).toInt() and 0xff
                val uvX = x / 2
                val uvY = y / 2
                val uValue = uBuffer.get(uvY * planes[1].rowStride + uvX * planes[1].pixelStride).toInt() and 0xff
                val vValue = vBuffer.get(uvY * planes[2].rowStride + uvX * planes[2].pixelStride).toInt() and 0xff
                val c = (yValue - 16).coerceAtLeast(0)
                val d = uValue - 128
                val e = vValue - 128
                val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                pixels[y * width + x] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { bitmap.recycle() }
    }
}
