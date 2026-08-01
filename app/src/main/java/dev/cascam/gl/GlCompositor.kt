package dev.cascam.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.geometry.NormalizedRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Composição de quadra e placar na GPU.
 *
 * A câmera escreve em SurfaceTextures, a GPU desenha os dois quads e o resultado vai direto para os
 * destinos — a tela e, quando transmitindo, a surface de entrada do encoder. Nenhum pixel passa pela
 * CPU: a conversão YUV vira trabalho do sampler de textura externa, e o recorte projetivo do placar
 * vira o que a GPU já faz nativamente.
 */
class GlCompositor(private val onStatus: (String) -> Unit) {
    private class Target(
        val surface: Surface,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        val presentationOriginNanos: Long?,
    )

    private val thread = HandlerThread("cascam-gl").also { it.start() }
    private val handler = Handler(thread.looper)
    private val egl = EglCore()
    private val targets = mutableListOf<Target>()
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // Quadrado unitário com y para baixo: (0,0) é o canto superior esquerdo do destino.
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
    }

    private var offscreen: EGLSurface? = null
    private var program = 0
    private var unitHandle = 0
    private var destHandle = 0
    private var mapHandle = 0
    private var textureMatrixHandle = 0
    private var samplerHandle = 0
    private var courtTexture = 0
    private var scoreboardTexture = 0
    private var courtStream: SurfaceTexture? = null
    private var scoreboardStream: SurfaceTexture? = null
    private val courtMatrix = FloatArray(16)
    private val scoreboardMatrix = FloatArray(16)
    private var courtPending = false
    private var scoreboardPending = false
    private var captureSize = Size(1920, 1080)
    private var rotationDegrees = 0

    @Volatile private var configuration = BroadcastConfiguration()
    @Volatile private var ready = false

    var courtSurface: Surface? = null
        private set
    var scoreboardSurface: Surface? = null
        private set

    val isReady: Boolean get() = ready

    fun start(size: Size, rotation: Int, onReady: () -> Unit) {
        captureSize = size
        rotationDegrees = rotation
        handler.post {
            runCatching {
                egl.setUp()
                offscreen = egl.createOffscreenSurface().also(egl::makeCurrent)
                program = buildProgram()
                courtTexture = createExternalTexture()
                scoreboardTexture = createExternalTexture()
                courtStream = SurfaceTexture(courtTexture).apply {
                    setDefaultBufferSize(size.width, size.height)
                    setOnFrameAvailableListener({ courtPending = true; handler.post(::render) }, handler)
                }
                scoreboardStream = SurfaceTexture(scoreboardTexture).apply {
                    setDefaultBufferSize(size.width, size.height)
                    setOnFrameAvailableListener({ scoreboardPending = true }, handler)
                }
                courtSurface = Surface(courtStream)
                scoreboardSurface = Surface(scoreboardStream)
                ready = true
            }.onSuccess { onReady() }.onFailure { onStatus("Composição GPU indisponível: ${it.message}") }
        }
    }

    fun configure(value: BroadcastConfiguration) {
        configuration = value
    }

    fun addTarget(
        surface: Surface,
        width: Int,
        height: Int,
        presentationOriginNanos: Long? = null,
    ) {
        handler.post {
            if (!ready) return@post
            runCatching { egl.createWindowSurface(surface) }
                .onSuccess { targets += Target(surface, it, width, height, presentationOriginNanos) }
                .onFailure { onStatus("Não consegui usar um destino GPU: ${it.message}") }
        }
    }

    fun removeTarget(surface: Surface) {
        handler.post {
            targets.filter { it.surface == surface }.forEach {
                runCatching { egl.releaseSurface(it.eglSurface) }
                targets -= it
            }
        }
    }

    fun release() {
        handler.post {
            ready = false
            targets.forEach { runCatching { egl.releaseSurface(it.eglSurface) } }
            targets.clear()
            courtSurface?.release(); scoreboardSurface?.release()
            courtStream?.release(); scoreboardStream?.release()
            courtSurface = null; scoreboardSurface = null
            courtStream = null; scoreboardStream = null
            offscreen?.let { runCatching { egl.releaseSurface(it) } }
            offscreen = null
            egl.release()
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- desenho

    private fun render() {
        if (!ready) return
        val anchor = targets.firstOrNull()?.eglSurface ?: offscreen ?: return
        egl.makeCurrent(anchor)
        if (courtPending) {
            courtPending = false
            courtStream?.updateTexImage()
            courtStream?.getTransformMatrix(courtMatrix)
        }
        if (scoreboardPending) {
            scoreboardPending = false
            scoreboardStream?.updateTexImage()
            scoreboardStream?.getTransformMatrix(scoreboardMatrix)
        }
        if (targets.isEmpty()) return

        val config = configuration
        val rotation = Homography.inverseRotation(rotationDegrees)
        // O recorte vale sobre a imagem **já girada**, como no caminho em CPU, que gira o bitmap
        // antes de recortar. Um quarto de volta troca largura por altura, e usar as dimensões
        // erradas estica a imagem para preencher o 16:9.
        //
        // O giro líquido não é só o pedido aqui: a SurfaceTexture pode entregar o quadro já girado,
        // e nesse caso a matriz dela troca os eixos. Quando os dois giram, um desfaz o outro e o
        // recorte volta a ser o da orientação original — daí o ou-exclusivo em vez do teste direto.
        val quarterTurn = (rotationDegrees % 180 != 0) != streamSwapsAxes(courtMatrix)
        val cropWidth = if (quarterTurn) captureSize.height else captureSize.width
        val cropHeight = if (quarterTurn) captureSize.width else captureSize.height
        val crop = NormalizedRect.adjustable16x9(cropWidth, cropHeight, config.cropZoom, config.cropPanX, config.cropPanY)
        val courtMap = Homography.multiply(rotation, Homography.unitSquareTo(crop.left, crop.top, crop.width, crop.height))
        val scoreboardMap = Homography.multiply(rotation, Homography.unitSquareTo(config.scoreboardCorners))
        val destination = config.scoreboardDestination

        targets.forEach { target ->
            egl.makeCurrent(target.eglSurface)
            GLES20.glViewport(0, 0, target.width, target.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(unitHandle)
            vertices.position(0)
            GLES20.glVertexAttribPointer(unitHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)

            drawQuad(courtTexture, courtMatrix, courtMap, -1f, 1f, 1f, -1f)
            drawQuad(
                scoreboardTexture, scoreboardMatrix, scoreboardMap,
                destination.left * 2f - 1f, 1f - destination.top * 2f,
                destination.right * 2f - 1f, 1f - destination.bottom * 2f,
            )

            GLES20.glDisableVertexAttribArray(unitHandle)
            target.presentationOriginNanos?.let { origin ->
                // Vídeo e áudio precisam usar o mesmo relógio. O compositor pode estar ativo desde
                // que a aba AO VIVO foi aberta, mas o PTS da transmissão começa junto do encoder.
                val presentationTimeNanos = (System.nanoTime() - origin).coerceAtLeast(0L)
                egl.setPresentationTime(target.eglSurface, presentationTimeNanos)
            }
            egl.swapBuffers(target.eglSurface)
        }
    }

    /**
     * Detecta se a matriz da SurfaceTexture troca os eixos, ou seja, se o produtor já entregou o
     * quadro girado um quarto de volta. Numa matriz sem giro a diagonal domina; num giro de 90° ou
     * 270° ela zera e quem manda são os termos cruzados.
     */
    private fun streamSwapsAxes(matrix: FloatArray): Boolean =
        abs(matrix[0]) < 0.5f && abs(matrix[5]) < 0.5f && (abs(matrix[1]) > 0.5f || abs(matrix[4]) > 0.5f)

    private fun drawQuad(texture: Int, textureMatrix: FloatArray, map: FloatArray, left: Float, top: Float, right: Float, bottom: Float) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniform4f(destHandle, left, top, right, bottom)
        GLES20.glUniformMatrix3fv(mapHandle, 1, false, map, 0)
        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---------------------------------------------------------------- programa

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link do shader falhou: ${GLES20.glGetProgramInfoLog(id)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        unitHandle = GLES20.glGetAttribLocation(id, "aUnit")
        destHandle = GLES20.glGetUniformLocation(id, "uDest")
        mapHandle = GLES20.glGetUniformLocation(id, "uMap")
        textureMatrixHandle = GLES20.glGetUniformLocation(id, "uTextureMatrix")
        samplerHandle = GLES20.glGetUniformLocation(id, "sTexture")
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader não compilou: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }

    private companion object {
        val VERTEX_SHADER = """
            attribute vec2 aUnit;
            uniform vec4 uDest;
            varying vec2 vUnit;
            void main() {
                vUnit = aUnit;
                float x = mix(uDest.x, uDest.z, aUnit.x);
                float y = mix(uDest.y, uDest.w, aUnit.y);
                gl_Position = vec4(x, y, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUnit;
            uniform mat3 uMap;
            uniform mat4 uTextureMatrix;
            uniform samplerExternalOES sTexture;
            void main() {
                vec3 mapped = uMap * vec3(vUnit, 1.0);
                vec2 image = mapped.xy / mapped.z;
                vec4 coordinate = uTextureMatrix * vec4(image.x, 1.0 - image.y, 0.0, 1.0);
                gl_FragColor = texture2D(sTexture, coordinate.xy);
            }
        """.trimIndent()
    }
}
