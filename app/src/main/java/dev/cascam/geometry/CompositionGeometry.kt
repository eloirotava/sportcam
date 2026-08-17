package dev.cascam.geometry

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "Coordinates must be normalized" }
    }
}

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }

    companion object {
        fun centered16x9(containerWidth: Int, containerHeight: Int): NormalizedRect {
            require(containerWidth > 0 && containerHeight > 0)
            val target = 16f / 9f
            val containerRatio = containerWidth.toFloat() / containerHeight
            return if (containerRatio > target) {
                val normalizedWidth = target / containerRatio
                NormalizedRect((1f - normalizedWidth) / 2f, 0f, (1f + normalizedWidth) / 2f, 1f)
            } else {
                val normalizedHeight = containerRatio / target
                NormalizedRect(0f, (1f - normalizedHeight) / 2f, 1f, (1f + normalizedHeight) / 2f)
            }
        }

        fun adjustable16x9(
            containerWidth: Int,
            containerHeight: Int,
            zoom: Float,
            panX: Float,
            panY: Float,
        ): NormalizedRect {
            require(zoom in 1f..8f)
            require(panX in -1f..1f && panY in -1f..1f)
            val base = centered16x9(containerWidth, containerHeight)
            val width = base.width / zoom
            val height = base.height / zoom
            val centerX = .5f + panX * (1f - width) / 2f
            val centerY = .5f + panY * (1f - height) / 2f
            return NormalizedRect(
                centerX - width / 2f,
                centerY - height / 2f,
                centerX + width / 2f,
                centerY + height / 2f,
            )
        }
    }
}

data class ScoreboardQuad(val corners: List<NormalizedPoint>) {
    init {
        require(corners.size == 4) { "A scoreboard requires exactly four corners" }
        require(signedArea() > 0.0001f) { "Corners must be clockwise and non-degenerate" }
    }

    private fun signedArea(): Float = corners.indices.sumOf { index ->
        val current = corners[index]
        val next = corners[(index + 1) % corners.size]
        ((current.x * next.y - next.x * current.y) / 2f).toDouble()
    }.toFloat()
}

object LogoGeometry {
    /** Preserva a proporção do arquivo e impede que o ícone saia da área transmitida. */
    fun destination(
        outputWidth: Int,
        outputHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        requestedWidth: Float,
        requestedCenterX: Float,
        requestedCenterY: Float,
    ): NormalizedRect {
        require(outputWidth > 0 && outputHeight > 0 && imageWidth > 0 && imageHeight > 0)
        var width = requestedWidth.coerceIn(.05f, .5f)
        var height = width * outputWidth / outputHeight.toFloat() * imageHeight / imageWidth.toFloat()
        if (height > .9f) {
            width *= .9f / height
            height = .9f
        }
        val centerX = requestedCenterX.coerceIn(width / 2f, 1f - width / 2f)
        val centerY = requestedCenterY.coerceIn(height / 2f, 1f - height / 2f)
        return NormalizedRect(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }
}

object WhiteTransparency {
    /**
     * Remove branco puro e suaviza os 30 níveis próximos dele. Usar o menor canal mantém cores
     * saturadas opacas: amarelo claro, por exemplo, não some só porque dois canais são altos.
     */
    fun applyToColor(color: Int): Int {
        val originalAlpha = color ushr 24 and 0xff
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        val distanceFromWhite = 255 - minOf(red, green, blue)
        val keyedAlpha = (distanceFromWhite * 255 / FEATHER).coerceIn(0, 255)
        return (originalAlpha * keyedAlpha / 255 shl 24) or (color and 0x00ffffff)
    }

    private const val FEATHER = 30
}

object StillFrameGeometry {
    /**
     * Converte pontos marcados no preview 16:9 para o JPEG cheio, normalmente 4:3.
     *
     * Esta é a única reinterpretação que sobra sobre os cantos marcados. A ampliação da tela não
     * entra aqui: ela é transformação de View, não muda as coordenadas locais do toque, e por isso
     * o que fica salvo já está em espaço de quadro cheio. Era diferente quando existia zoom digital
     * — o valor do slider era aplicado no desenho, então mexer nele depois movia a marcação.
     */
    fun fromVideoPreview(
        corners: List<NormalizedPoint>,
        stillWidth: Int,
        stillHeight: Int,
    ): List<NormalizedPoint> {
        val videoCrop = NormalizedRect.centered16x9(stillWidth, stillHeight)
        return corners.map { point ->
            NormalizedPoint(
                videoCrop.left + point.x * videoCrop.width,
                videoCrop.top + point.y * videoCrop.height,
            )
        }
    }
}

/**
 * Quanta resolução a composição realmente consome de cada fonte.
 *
 * Capturar acima da resolução transmitida só faz sentido se os pixels a mais sobreviverem até o
 * recorte — e no caminho de CPU eles morrem antes, porque converter YUV para bitmap custa por pixel
 * e o recorte vem depois. Estas contas dizem, ao contrário, qual é o menor bitmap que ainda entrega
 * pixel real ao destino: assim o sensor pode entregar o quadro inteiro sem que a conversão cresça
 * junto. Na GPU o recorte é coordenada de textura e nada disso é necessário.
 */
object CompositionResolution {
    /**
     * Folga sobre o mínimo exato. Cobre o arredondamento da amostragem por vizinho mais próximo e
     * pequenos ajustes de enquadramento feitos depois que a transmissão começou. O valor é uma
     * fração exata em binário de propósito: assim a conta não depende de arredondamento de float.
     */
    const val HEADROOM = 1.25f

    /** Menor largura de bitmap exibido em que o recorte 16:9 da quadra ainda não estica pixel. */
    fun courtBitmapWidth(
        outputWidth: Int,
        displayedWidth: Int,
        displayedHeight: Int,
        cropZoom: Float,
    ): Int {
        require(outputWidth > 0 && displayedWidth > 0 && displayedHeight > 0)
        val base = NormalizedRect.centered16x9(displayedWidth, displayedHeight).width
        val fraction = base / cropZoom.coerceAtLeast(1f)
        return ceilToInt(outputWidth * HEADROOM / fraction)
    }

    /**
     * O mesmo para uma sobreposição: o quadrilátero marcado ocupa uma fração da largura do quadro e
     * é esticado até o retângulo de destino. Quanto menor o quadrilátero, mais bitmap ele exige.
     */
    fun overlayBitmapWidth(
        outputWidth: Int,
        destination: NormalizedRect,
        corners: List<NormalizedPoint>,
    ): Int {
        require(outputWidth > 0)
        // Sem quadrilátero não há o que estimar; a sobreposição não desenha e a fonte não precisa
        // de mais que a saída. Isso é caminho de configuração salva por uma versão antiga.
        if (corners.isEmpty()) return outputWidth
        val span = (corners.maxOf { it.x } - corners.minOf { it.x }).coerceAtLeast(MINIMUM_SPAN)
        return ceilToInt(destination.width * outputWidth * HEADROOM / span)
    }

    /**
     * Largura em coordenadas de captura para que o bitmap *já girado* tenha [displayedWidth]. O giro
     * é aplicado depois da escala, então em 90° e 270° a largura exibida vem da altura convertida.
     *
     * O resultado não é limitado pelo que o sensor entrega: é exatamente esse excedente que diz que
     * o tamanho de captura escolhido ficou pequeno para o enquadramento. Quem usa o valor para
     * converter é que o corta no tamanho real do quadro.
     */
    fun conversionWidth(displayedWidth: Int, captureWidth: Int, captureHeight: Int, rotationDegrees: Int): Int {
        require(captureWidth > 0 && captureHeight > 0)
        val normalized = ((rotationDegrees % 360) + 360) % 360
        val quarterTurn = (normalized % 180) != 0
        val requested = if (quarterTurn) {
            displayedWidth.toLong() * captureWidth / captureHeight
        } else displayedWidth.toLong()
        return requested.coerceAtLeast(MINIMUM_WIDTH.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ceilToInt(value: Float): Int = kotlin.math.ceil(value.toDouble()).toInt()

    private const val MINIMUM_SPAN = .01f
    private const val MINIMUM_WIDTH = 16
}

