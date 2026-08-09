package dev.cascam.gl

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.ScoreboardSource
import dev.cascam.geometry.NormalizedRect
import dev.cascam.geometry.LogoGeometry
import dev.cascam.geometry.StillFrameGeometry
import dev.cascam.geometry.CaptureZoomGeometry
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
    private var stillProgram = 0
    private var stillUnitHandle = 0
    private var stillDestHandle = 0
    private var stillMapHandle = 0
    private var stillSamplerHandle = 0
    private val stillTextures = mutableMapOf<String, Int>()
    private val stillSizes = mutableMapOf<String, Size>()
    private var courtTexture = 0
    private var scoreboardTexture = 0
    private var clockTexture = 0
    private var logoTexture = 0
    private var courtStream: SurfaceTexture? = null
    private var scoreboardStream: SurfaceTexture? = null
    private var clockStream: SurfaceTexture? = null
    private var logoStream: SurfaceTexture? = null
    private var logoSurface: Surface? = null
    private val courtMatrix = FloatArray(16)
    private val scoreboardMatrix = FloatArray(16)
    private val clockMatrix = FloatArray(16)
    private val logoMatrix = FloatArray(16)
    private var courtPending = false
    private var scoreboardPending = false
    private var clockPending = false
    private var logoPending = false
    private var logoSize: Size? = null
    @Volatile private var requestedLogo: Bitmap? = null
    private var captureSize = Size(1920, 1080)
    private var rotationDegrees = 0

    @Volatile private var configuration = BroadcastConfiguration()
    @Volatile private var sourceIds: List<String> = emptyList()
    @Volatile private var sourceRotations: Map<String, Int> = emptyMap()
    @Volatile private var ready = false
    @Volatile private var previewEnabled = true

    var courtSurface: Surface? = null
        private set
    var scoreboardSurface: Surface? = null
        private set
    var clockSurface: Surface? = null
        private set

    val isReady: Boolean get() = ready

    fun start(size: Size, rotation: Int, onReady: () -> Unit) = start(listOf(size, size, size), rotation, onReady)

    fun start(sourceSizes: List<Size>, rotation: Int, onReady: () -> Unit) {
        val courtSize = sourceSizes.getOrElse(0) { Size(1920, 1080) }
        val scoreboardSize = sourceSizes.getOrElse(1) { courtSize }
        val clockSize = sourceSizes.getOrElse(2) { scoreboardSize }
        captureSize = courtSize
        rotationDegrees = rotation
        handler.post {
            runCatching {
                egl.setUp()
                offscreen = egl.createOffscreenSurface().also(egl::makeCurrent)
                program = buildProgram()
                stillProgram = buildStillProgram()
                courtTexture = createExternalTexture()
                scoreboardTexture = createExternalTexture()
                clockTexture = createExternalTexture()
                logoTexture = createExternalTexture()
                courtStream = SurfaceTexture(courtTexture).apply {
                    setDefaultBufferSize(courtSize.width, courtSize.height)
                    setOnFrameAvailableListener({ courtPending = true; handler.post(::render) }, handler)
                }
                scoreboardStream = SurfaceTexture(scoreboardTexture).apply {
                    setDefaultBufferSize(scoreboardSize.width, scoreboardSize.height)
                    setOnFrameAvailableListener({ scoreboardPending = true }, handler)
                }
                clockStream = SurfaceTexture(clockTexture).apply {
                    setDefaultBufferSize(clockSize.width, clockSize.height)
                    setOnFrameAvailableListener({ clockPending = true }, handler)
                }
                logoStream = SurfaceTexture(logoTexture)
                courtSurface = Surface(courtStream)
                scoreboardSurface = Surface(scoreboardStream)
                clockSurface = Surface(clockStream)
                logoSurface = Surface(logoStream)
                ready = true
                uploadLogo(requestedLogo)
            }.onSuccess { onReady() }.onFailure { onStatus("Composição GPU indisponível: ${it.message}") }
        }
    }

    fun configure(value: BroadcastConfiguration) {
        configuration = value
    }

    /** Copia o bitmap uma vez para uma textura; os quadros seguintes continuam inteiramente na GPU. */
    fun setLogo(bitmap: Bitmap?) {
        requestedLogo = bitmap
        handler.post { if (ready) uploadLogo(bitmap) }
    }

    /** Atualiza a textura 2D do placar e conserva a foto até a captura seguinte. */
    fun submitStill(sourceId: String, bitmap: Bitmap) {
        handler.post {
            try {
                if (!ready) return@post
                egl.makeCurrent(offscreen ?: return@post)
                val texture = stillTextures[sourceId] ?: createBitmapTexture().also { stillTextures[sourceId] = it }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                val previous = stillSizes[sourceId]
                if (previous?.width == bitmap.width && previous.height == bitmap.height) {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
                } else {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    stillSizes[sourceId] = Size(bitmap.width, bitmap.height)
                }
                render()
            } catch (error: Throwable) {
                onStatus("Falha ao atualizar foto do placar: ${error.message}")
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Ordem das texturas: quadra, auxiliar/placar e cronômetro distinto. */
    fun configureSourceIds(ids: List<String>, rotations: Map<String, Int> = emptyMap()) {
        sourceIds = ids; sourceRotations = rotations
    }

    /** O encoder continua desenhando; só o destino sem PTS, usado pela tela, é suspenso. */
    fun setPreviewEnabled(enabled: Boolean) { previewEnabled = enabled }

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
            courtSurface?.release(); scoreboardSurface?.release(); clockSurface?.release()
            logoSurface?.release()
            courtStream?.release(); scoreboardStream?.release(); clockStream?.release(); logoStream?.release()
            if (stillTextures.isNotEmpty()) GLES20.glDeleteTextures(stillTextures.size, stillTextures.values.toIntArray(), 0)
            stillTextures.clear(); stillSizes.clear()
            courtSurface = null; scoreboardSurface = null; clockSurface = null
            courtStream = null; scoreboardStream = null; clockStream = null
            logoSurface = null; logoStream = null
            offscreen?.let { runCatching { egl.releaseSurface(it) } }
            offscreen = null
            egl.release()
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- desenho

    private fun render() {
        if (!ready) return
        // A SurfaceView da prévia desaparece quando a tela apaga. Manter o contexto ancorado nela
        // criava uma corrida com surfaceDestroyed(): um quadro podia tentar usar a EGLSurface já
        // inválida e matar a thread GL, inclusive o destino independente do encoder.
        val anchor = offscreen ?: return
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
        if (clockPending) {
            clockPending = false
            clockStream?.updateTexImage()
            clockStream?.getTransformMatrix(clockMatrix)
        }
        if (logoPending) {
            logoPending = false
            logoStream?.updateTexImage()
            logoStream?.getTransformMatrix(logoMatrix)
        }
        if (targets.isEmpty()) return

        val config = configuration
        val courtDegrees = sourceRotations[sourceIds.getOrNull(0)] ?: rotationDegrees
        val rotation = Homography.inverseRotation(courtDegrees)
        // O recorte vale sobre a imagem **já girada**, como no caminho em CPU, que gira o bitmap
        // antes de recortar. Um quarto de volta troca largura por altura, e usar as dimensões
        // erradas estica a imagem para preencher o 16:9.
        //
        // O giro líquido não é só o pedido aqui: a SurfaceTexture pode entregar o quadro já girado,
        // e nesse caso a matriz dela troca os eixos. Quando os dois giram, um desfaz o outro e o
        // recorte volta a ser o da orientação original — daí o ou-exclusivo em vez do teste direto.
        val quarterTurn = (courtDegrees % 180 != 0) != streamSwapsAxes(courtMatrix)
        val cropWidth = if (quarterTurn) captureSize.height else captureSize.width
        val cropHeight = if (quarterTurn) captureSize.width else captureSize.height
        val configuredCrop = NormalizedRect.adjustable16x9(cropWidth, cropHeight, config.cropZoom, config.cropPanX, config.cropPanY)
        val crop = CaptureZoomGeometry.fromZoomedPreview(configuredCrop, config.captureZoom)
        val courtMap = Homography.multiply(rotation, Homography.unitSquareTo(crop.left, crop.top, crop.width, crop.height))
        val scoreboardSource = config.cameraIdFor(dev.cascam.config.OverlayLayer.SCOREBOARD)
        val scoreboardRotation = Homography.inverseRotation(sourceRotations[scoreboardSource] ?: courtDegrees)
        val stillSource = config.cameraIdFor(dev.cascam.config.OverlayLayer.SCOREBOARD)
        val stillSize = stillSizes[stillSource]
        val stillCorners = if (config.scoreboardSource == ScoreboardSource.PHOTO_EVERY_SECOND && stillSize != null) {
            StillFrameGeometry.fromVideoPreview(
                config.scoreboardCorners, stillSize.width, stillSize.height, config.scoreboardCaptureZoom,
            )
        } else config.scoreboardCorners.map { point ->
            CaptureZoomGeometry.fromZoomedPreview(point, config.scoreboardCaptureZoom)
        }
        // JPEG não recebe a correção manual; ela pertence apenas ao vídeo em SurfaceTexture.
        val scoreboardMap = if (config.scoreboardSource == ScoreboardSource.PHOTO_EVERY_SECOND) {
            Homography.unitSquareTo(stillCorners)
        } else Homography.multiply(scoreboardRotation, Homography.unitSquareTo(stillCorners))
        val clockSource = config.cameraIdFor(dev.cascam.config.OverlayLayer.CLOCK)
        val clockRotation = Homography.inverseRotation(sourceRotations[clockSource] ?: courtDegrees)
        val clockCorners = config.clockCorners.map { point ->
            CaptureZoomGeometry.fromZoomedPreview(point, config.clockCaptureZoom)
        }
        val clockMap = Homography.multiply(clockRotation, Homography.unitSquareTo(clockCorners))
        val destination = config.scoreboardDestination
        val clockDestination = config.clockDestination

        targets.toList().forEach { target ->
            if (target.presentationOriginNanos == null && !previewEnabled) return@forEach
            runCatching {
                egl.makeCurrent(target.eglSurface)
                GLES20.glViewport(0, 0, target.width, target.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                bindVideoProgram()

                drawQuad(courtTexture, courtMatrix, courtMap, -1f, 1f, 1f, -1f)
                if (config.scoreboardEnabled) {
                    val stillTexture = stillTextures[stillSource]
                    if (config.scoreboardSource == ScoreboardSource.PHOTO_EVERY_SECOND && stillTexture != null) {
                        drawStill(
                            stillTexture, scoreboardMap,
                            destination.left * 2f - 1f, 1f - destination.top * 2f,
                            destination.right * 2f - 1f, 1f - destination.bottom * 2f,
                        )
                        bindVideoProgram()
                    } else if (config.scoreboardSource == ScoreboardSource.VIDEO) {
                        val scoreboardIndex = sourceIds.indexOf(stillSource)
                        drawQuad(
                            when (scoreboardIndex) { 0 -> courtTexture; 2 -> clockTexture; else -> scoreboardTexture },
                            when (scoreboardIndex) { 0 -> courtMatrix; 2 -> clockMatrix; else -> scoreboardMatrix },
                            scoreboardMap,
                            destination.left * 2f - 1f, 1f - destination.top * 2f,
                            destination.right * 2f - 1f, 1f - destination.bottom * 2f,
                        )
                    }
                }
                if (config.clockEnabled) {
                    val clockCamera = config.cameraIdFor(dev.cascam.config.OverlayLayer.CLOCK)
                    val fromCourt = sourceIds.getOrNull(0) == clockCamera
                    val fromScoreboard = sourceIds.getOrNull(1) == clockCamera
                    drawQuad(
                        if (fromCourt) courtTexture else if (fromScoreboard) scoreboardTexture else clockTexture,
                        if (fromCourt) courtMatrix else if (fromScoreboard) scoreboardMatrix else clockMatrix,
                        clockMap,
                        clockDestination.left * 2f - 1f, 1f - clockDestination.top * 2f,
                        clockDestination.right * 2f - 1f, 1f - clockDestination.bottom * 2f,
                    )
                }
                val currentLogoSize = logoSize
                if (config.logoEnabled && currentLogoSize != null) {
                    val logoDestination = LogoGeometry.destination(
                        target.width, target.height, currentLogoSize.width, currentLogoSize.height,
                        config.logoWidth, config.logoCenterX, config.logoCenterY,
                    )
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    drawQuad(
                        logoTexture, logoMatrix, Homography.unitSquareTo(0f, 0f, 1f, 1f),
                        logoDestination.left * 2f - 1f, 1f - logoDestination.top * 2f,
                        logoDestination.right * 2f - 1f, 1f - logoDestination.bottom * 2f,
                    )
                    GLES20.glDisable(GLES20.GL_BLEND)
                }

                GLES20.glDisableVertexAttribArray(unitHandle)
                target.presentationOriginNanos?.let { origin ->
                    // Vídeo e áudio precisam usar o mesmo relógio. O compositor pode estar ativo desde
                    // que a aba AO VIVO foi aberta, mas o PTS da transmissão começa junto do encoder.
                    val presentationTimeNanos = (System.nanoTime() - origin).coerceAtLeast(0L)
                    egl.setPresentationTime(target.eglSurface, presentationTimeNanos)
                }
                check(egl.swapBuffers(target.eglSurface)) { "eglSwapBuffers falhou" }
            }.onFailure { error ->
                // Perder a superfície da tela não pode interromper a superfície do MediaCodec.
                runCatching { egl.releaseSurface(target.eglSurface) }
                targets -= target
                if (target.presentationOriginNanos != null) {
                    onStatus("Falha no destino GPU do encoder: ${error.message}")
                }
            }
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

    private fun bindVideoProgram() {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(unitHandle)
        vertices.position(0)
        GLES20.glVertexAttribPointer(unitHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
    }

    private fun drawStill(texture: Int, map: FloatArray, left: Float, top: Float, right: Float, bottom: Float) {
        GLES20.glDisableVertexAttribArray(unitHandle)
        GLES20.glUseProgram(stillProgram)
        GLES20.glEnableVertexAttribArray(stillUnitHandle)
        vertices.position(0)
        GLES20.glVertexAttribPointer(stillUnitHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(stillSamplerHandle, 0)
        GLES20.glUniform4f(stillDestHandle, left, top, right, bottom)
        GLES20.glUniformMatrix3fv(stillMapHandle, 1, false, map, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(stillUnitHandle)
    }

    private fun uploadLogo(bitmap: Bitmap?) {
        if (bitmap == null) {
            logoSize = null
            return
        }
        val stream = logoStream ?: return
        val surface = logoSurface ?: return
        stream.setDefaultBufferSize(bitmap.width, bitmap.height)
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        logoSize = Size(bitmap.width, bitmap.height)
        logoPending = true
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

    private fun createBitmapTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
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

    private fun buildStillProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, STILL_FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link do shader de foto falhou: ${GLES20.glGetProgramInfoLog(id)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        stillUnitHandle = GLES20.glGetAttribLocation(id, "aUnit")
        stillDestHandle = GLES20.glGetUniformLocation(id, "uDest")
        stillMapHandle = GLES20.glGetUniformLocation(id, "uMap")
        stillSamplerHandle = GLES20.glGetUniformLocation(id, "sTexture")
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

        val STILL_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUnit;
            uniform mat3 uMap;
            uniform sampler2D sTexture;
            void main() {
                vec3 mapped = uMap * vec3(vUnit, 1.0);
                vec2 image = mapped.xy / mapped.z;
                gl_FragColor = texture2D(sTexture, vec2(image.x, 1.0 - image.y));
            }
        """.trimIndent()
    }
}
