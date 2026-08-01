package dev.cascam.gl

import dev.cascam.geometry.NormalizedPoint

/**
 * Homografia que leva o quadrado unitário nos quatro cantos do placar.
 *
 * Um quad com quatro coordenadas de textura não resolve isso: a rasterização interpola de forma
 * afim dentro de cada triângulo, e o placar inclinado sai com um vinco na diagonal. Mandando a
 * matriz projetiva para o shader e dividindo por w no fragmento, o mapeamento fica correto em todo
 * o quad — inclusive melhor que o `setPolyToPoly` do Canvas, que é o que o caminho em CPU usa.
 *
 * Forma fechada de Heckbert para o caso quadrado unitário → quadrilátero, que evita resolver um
 * sistema 8x8 a cada quadro.
 */
object Homography {
    /** Coluna-maior, pronto para `glUniformMatrix3fv` com transpose = false. */
    fun unitSquareTo(corners: List<NormalizedPoint>): FloatArray {
        require(corners.size == 4) { "a homografia precisa de exatamente quatro cantos" }
        val (x0, y0) = corners[0].x to corners[0].y
        val (x1, y1) = corners[1].x to corners[1].y
        val (x2, y2) = corners[2].x to corners[2].y
        val (x3, y3) = corners[3].x to corners[3].y

        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val dy3 = y0 - y1 + y2 - y3

        val a: Float
        val b: Float
        val d: Float
        val e: Float
        val g: Float
        val h: Float
        val determinant = dx1 * dy2 - dx2 * dy1
        if (dx3 == 0f && dy3 == 0f || determinant == 0f) {
            // Quadrilátero é um paralelogramo: o mapeamento é afim e não há divisão por w.
            a = x1 - x0; b = x2 - x1; d = y1 - y0; e = y2 - y1; g = 0f; h = 0f
        } else {
            g = (dx3 * dy2 - dx2 * dy3) / determinant
            h = (dx1 * dy3 - dx3 * dy1) / determinant
            a = x1 - x0 + g * x1
            b = x3 - x0 + h * x3
            d = y1 - y0 + g * y1
            e = y3 - y0 + h * y3
        }
        return floatArrayOf(a, d, g, b, e, h, x0, y0, 1f)
    }

    /** Retângulo alinhado aos eixos: caso afim do mesmo mapeamento, também em coluna-maior. */
    fun unitSquareTo(left: Float, top: Float, width: Float, height: Float): FloatArray =
        floatArrayOf(width, 0f, 0f, 0f, height, 0f, left, top, 1f)

    /**
     * Rotação **inversa**, para compor com os mapeamentos acima.
     *
     * O shader recebe um mapeamento de destino para origem, então girar a coordenada por θ faz a
     * imagem aparecer girada por −θ. Como o caminho em CPU usa `postRotate(θ)`, que gira a imagem
     * por θ no sentido horário, aqui as entradas de 90 e 270 são trocadas: assim os dois caminhos
     * entendem o mesmo número da mesma forma, e trocar de CPU para GPU não vira o vídeo.
     */
    fun inverseRotation(degrees: Int): FloatArray = when (((degrees % 360) + 360) % 360) {
        90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f)
        180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, 1f)
        270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    /** Produto de duas matrizes 3x3 em coluna-maior: aplica [second] depois de [first]. */
    fun multiply(second: FloatArray, first: FloatArray): FloatArray {
        val result = FloatArray(9)
        for (column in 0..2) for (row in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += second[k * 3 + row] * first[column * 3 + k]
            result[column * 3 + row] = sum
        }
        return result
    }
}
