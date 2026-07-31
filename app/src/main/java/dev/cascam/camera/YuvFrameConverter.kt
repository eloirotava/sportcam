package dev.cascam.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image

/**
 * YUV_420_888 para bitmap já no tamanho em que a imagem vai ser desenhada.
 *
 * Converter 1080p inteiro para depois encolher no canvas é trabalho jogado fora, e o custo aqui é
 * por pixel: dois fluxos 1080p a 20 fps dariam 83 milhões de pixels por segundo, mais do que um
 * núcleo aguenta em laço aritmético. Duas escolhas seguram isso: o destino define quantos pixels
 * são realmente convertidos, e os planos são copiados para arrays antes do laço, porque leitura
 * indexada em ByteBuffer direto custa muito mais que em ByteArray.
 *
 * A amostragem é vizinho mais próximo. Para quadra aberta e placar recortado isso não aparece, e
 * qualquer coisa melhor que isso pede GPU em vez de laço.
 */
object YuvFrameConverter {
    fun convert(image: Image, targetWidth: Int, rotationDegrees: Int): Bitmap {
        val sourceWidth = image.width
        val sourceHeight = image.height
        val width = targetWidth.coerceIn(16, sourceWidth)
        val height = (sourceHeight.toLong() * width / sourceWidth).toInt().coerceAtLeast(16)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBytes = bytesOf(yPlane)
        val uBytes = bytesOf(uPlane)
        val vBytes = bytesOf(vPlane)
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // Colunas de origem pré-calculadas: o mapeamento é o mesmo em toda linha.
        val columns = IntArray(width) { it * sourceWidth / width }
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val sourceRow = row * sourceHeight / height
            val yOffset = sourceRow * yRowStride
            val uOffset = (sourceRow / 2) * uRowStride
            val vOffset = (sourceRow / 2) * vRowStride
            val target = row * width
            for (column in 0 until width) {
                val sourceColumn = columns[column]
                val yIndex = yOffset + sourceColumn * yPixelStride
                val chromaColumn = sourceColumn / 2
                val uIndex = uOffset + chromaColumn * uPixelStride
                val vIndex = vOffset + chromaColumn * vPixelStride
                if (yIndex >= yBytes.size || uIndex >= uBytes.size || vIndex >= vBytes.size) continue
                val luma = ((yBytes[yIndex].toInt() and 0xff) - 16).coerceAtLeast(0)
                val u = (uBytes[uIndex].toInt() and 0xff) - 128
                val v = (vBytes[vIndex].toInt() and 0xff) - 128
                val red = ((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixels[target + column] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (rotationDegrees % 360 == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
            .also { bitmap.recycle() }
    }

    private fun bytesOf(plane: Image.Plane): ByteArray {
        val buffer = plane.buffer
        buffer.rewind()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
