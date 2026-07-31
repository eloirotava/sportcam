package dev.cascam.camera

import java.nio.ByteBuffer

/**
 * Assinatura perceptual de 64 bits lida do plano Y, com a informação de contraste junto.
 *
 * O contraste não é enfeite: uma imagem chapada — sensor ainda no escuro logo depois de abrir,
 * lente tampada, parede lisa — produz a mesma assinatura degenerada em qualquer câmera, porque
 * todas as amostras empatam com a própria média. Comparar dois quadros chapados dava distância
 * zero e fazia duas câmeras distintas parecerem a mesma fonte. Quem compara precisa poder
 * descartar esses quadros, então [spread] viaja junto com [hash].
 */
data class LumaSignature(val hash: Long, val mean: Int, val spread: Int) {
    val hasContrast: Boolean get() = spread >= MINIMUM_SPREAD

    fun distanceTo(other: LumaSignature): Int = java.lang.Long.bitCount(hash xor other.hash)

    companion object {
        /** Abaixo disso o quadro é chapado demais para dizer qualquer coisa sobre a fonte. */
        const val MINIMUM_SPREAD = 12

        private const val GRID = 8

        fun of(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): LumaSignature {
            val samples = IntArray(GRID * GRID)
            var total = 0L
            for (index in samples.indices) {
                val x = ((index % GRID + .5f) * width / GRID).toInt().coerceIn(0, width - 1)
                val y = ((index / GRID + .5f) * height / GRID).toInt().coerceIn(0, height - 1)
                val position = y * rowStride + x * pixelStride
                samples[index] = if (position >= 0 && position < buffer.limit()) {
                    buffer.get(position).toInt() and 0xff
                } else {
                    0
                }
                total += samples[index]
            }
            val mean = (total / samples.size).toInt()
            var hash = 0L
            samples.forEachIndexed { index, value -> if (value > mean) hash = hash or (1L shl index) }
            val spread = (samples.maxOrNull() ?: 0) - (samples.minOrNull() ?: 0)
            return LumaSignature(hash, mean, spread)
        }
    }
}
