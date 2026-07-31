package dev.cascam.camera

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Descobre empiricamente quais duas câmeras o aparelho entrega ao mesmo tempo.
 *
 * Não confia só no que o Android declara: para cada par candidato ele liga de fato os dois fluxos,
 * espera frames chegarem dos dois lados e compara as imagens para provar que são fontes distintas —
 * há aparelhos que aceitam o bind e devolvem a mesma imagem duplicada. Cada par é varrido da maior
 * para a menor resolução, então o relatório também responde até onde a resolução pode subir.
 */
class DualCameraProbe(
    private val context: Context,
    private val capabilities: CameraCapabilities,
    private val onProgress: (String) -> Unit,
    private val onReport: (String) -> Unit,
) {
    enum class Outcome(val symbol: String) {
        WORKS("✓"), SAME_IMAGE("≈"), NO_FRAMES("∅"), BIND_FAILED("✗"),
    }

    private enum class Kind(val description: String) {
        PHYSICAL_PAIR("dois sensores físicos da mesma câmera lógica"),
        LOGICAL_PLUS_PHYSICAL("câmera lógica + um sensor físico dela"),
        CONCURRENT_LOGICAL("duas câmeras lógicas em modo simultâneo"),
    }

    private data class Candidate(val kind: Kind, val first: CameraInfo, val second: CameraInfo) {
        val label: String get() = "${first.id} + ${second.id}"
        /** Quanto maior, mais diferentes são os enquadramentos — quadra aberta contra placar fechado. */
        val fieldOfViewGap: Float
            get() {
                val a = first.horizontalFieldOfView ?: return 0f
                val b = second.horizontalFieldOfView ?: return 0f
                return abs(a - b)
            }
    }

    private data class Attempt(val size: Size, val outcome: Outcome, val detail: String)

    private data class Finding(val candidate: Candidate, val attempts: MutableList<Attempt> = mutableListOf()) {
        val best: Attempt? get() = attempts.firstOrNull { it.outcome == Outcome.WORKS }
        val worked: Boolean get() = best != null
    }

    private class Sampler {
        @Volatile var frames = 0
        @Volatile var signature = 0L
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var firstFrameAt = 0L
        @Volatile var lastFrameAt = 0L

        val framesPerSecond: Float
            get() {
                val span = lastFrameAt - firstFrameAt
                return if (frames > 1 && span > 0) (frames - 1) * 1_000f / span else 0f
            }
        val size: String get() = if (frames == 0) "sem frames" else "${width}x$height"
    }

    /** Menor distância já vista entre as duas imagens, por tentativa — zero significa fonte duplicada. */
    private class Comparison {
        @Volatile var minimumDistance = Int.MAX_VALUE
    }

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newFixedThreadPool(2)
    private val findings = mutableListOf<Finding>()
    private var candidates = emptyList<Candidate>()
    private var candidateIndex = 0
    private var resolutionIndex = 0
    private var provider: ProcessCameraProvider? = null
    private var owner: ProbeLifecycleOwner? = null
    private var attemptToken = 0

    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        findings.clear()
        candidateIndex = 0
        resolutionIndex = 0
        candidates = buildCandidates()
        if (candidates.isEmpty()) {
            isRunning = false
            onProgress("Nenhum par candidato: o aparelho expõe apenas uma câmera utilizável.")
            onReport(report(finished = true))
            return
        }
        onProgress("Preparando ${candidates.size} pares candidatos…")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isRunning) return@addListener
            provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                finish("Não consegui abrir o CameraX neste aparelho.")
                return@addListener
            }
            owner = ProbeLifecycleOwner().also { it.start() }
            runAttempt()
        }, ContextCompat.getMainExecutor(context))
    }

    fun cancel() {
        if (!isRunning) return
        finish("Teste interrompido.")
    }

    fun shutdown() {
        cancel()
        analysisExecutor.shutdown()
    }

    // ---------------------------------------------------------------- candidatos

    private fun buildCandidates(): List<Candidate> {
        val result = mutableListOf<Candidate>()
        // 1. Dois sensores físicos da mesma câmera lógica: o caminho natural para ultra-wide + tele.
        capabilities.physicalSensorsByLogical().forEach { (_, sensors) ->
            sensors.sortedByDescending { it.horizontalFieldOfView ?: 0f }.let { ordered ->
                for (i in ordered.indices) for (j in i + 1 until ordered.size) {
                    result += Candidate(Kind.PHYSICAL_PAIR, ordered[i], ordered[j])
                }
            }
        }
        // 2. Fluxo da câmera lógica somado a um sensor físico dela: às vezes o HAL aceita este e recusa o par físico puro.
        capabilities.physicalSensorsByLogical().forEach { (logicalId, sensors) ->
            val logical = capabilities.camera(logicalId) ?: return@forEach
            sensors.forEach { sensor -> result += Candidate(Kind.LOGICAL_PLUS_PHYSICAL, logical, sensor) }
        }
        // 3. Pares que o próprio aparelho declara como simultâneos (quase sempre traseira + frontal).
        capabilities.concurrentPairs.forEach { pair ->
            val ordered = pair.sorted().mapNotNull { capabilities.camera(it) }
            for (i in ordered.indices) for (j in i + 1 until ordered.size) {
                result += Candidate(Kind.CONCURRENT_LOGICAL, ordered[i], ordered[j])
            }
        }
        return result
            .distinctBy { setOf(it.first.id, it.second.id) }
            .sortedWith(compareBy({ it.kind.ordinal }, { -it.fieldOfViewGap }))
            .take(MAXIMUM_CANDIDATES)
    }

    // ---------------------------------------------------------------- execução

    @ExperimentalCamera2Interop
    private fun runAttempt() {
        val provider = provider ?: return
        val owner = owner ?: return
        if (candidateIndex >= candidates.size) {
            finish(null)
            return
        }
        val candidate = candidates[candidateIndex]
        val size = RESOLUTIONS[resolutionIndex]
        onProgress("Par ${candidateIndex + 1}/${candidates.size}: ${candidate.label} a ${size.width}x${size.height}…")

        runCatching { provider.unbindAll() }
        val court = Sampler()
        val scoreboard = Sampler()
        val comparison = Comparison()
        val courtAnalysis = CameraXSupport.imageAnalysis(candidate.first, size)
        val scoreboardAnalysis = CameraXSupport.imageAnalysis(candidate.second, size)
        courtAnalysis.setAnalyzer(analysisExecutor) { image -> sample(court, image) }
        scoreboardAnalysis.setAnalyzer(analysisExecutor) { image ->
            sample(scoreboard, image)
            if (court.frames > 0) {
                val distance = java.lang.Long.bitCount(court.signature xor scoreboard.signature)
                if (distance < comparison.minimumDistance) comparison.minimumDistance = distance
            }
        }

        val token = ++attemptToken
        val failure = runCatching { bind(provider, owner, candidate, courtAnalysis, scoreboardAnalysis) }.exceptionOrNull()
        if (failure != null) {
            record(candidate, size, Outcome.BIND_FAILED, rootCause(failure))
            advance()
            return
        }
        handler.postDelayed({
            if (token == attemptToken && isRunning) evaluate(candidate, size, court, scoreboard, comparison)
        }, OBSERVATION_MS)
    }

    @ExperimentalCamera2Interop
    private fun bind(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        candidate: Candidate,
        court: ImageAnalysis,
        scoreboard: ImageAnalysis,
    ) {
        if (candidate.first.logicalCameraId == candidate.second.logicalCameraId) {
            provider.bindToLifecycle(owner, CameraXSupport.selectorFor(candidate.first.logicalCameraId), court, scoreboard)
            return
        }
        val requested = setOf(candidate.first.logicalCameraId, candidate.second.logicalCameraId)
        val group = provider.availableConcurrentCameraInfos.firstOrNull { infos ->
            infos.map { Camera2CameraInfo.from(it).cameraId }.toSet().containsAll(requested)
        } ?: error("o CameraX não oferece este par em modo simultâneo")
        val first = group.first { Camera2CameraInfo.from(it).cameraId == candidate.first.logicalCameraId }
        val second = group.first { Camera2CameraInfo.from(it).cameraId == candidate.second.logicalCameraId }
        provider.bindToLifecycle(listOf(
            ConcurrentCamera.SingleCameraConfig(
                CameraXSupport.selectorFor(Camera2CameraInfo.from(first).cameraId),
                UseCaseGroup.Builder().addUseCase(court).build(), owner,
            ),
            ConcurrentCamera.SingleCameraConfig(
                CameraXSupport.selectorFor(Camera2CameraInfo.from(second).cameraId),
                UseCaseGroup.Builder().addUseCase(scoreboard).build(), owner,
            ),
        ))
    }

    private fun sample(sampler: Sampler, image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (sampler.frames == 0) sampler.firstFrameAt = now
            sampler.lastFrameAt = now
            sampler.width = image.width
            sampler.height = image.height
            sampler.signature = signatureOf(image)
            sampler.frames++
        } catch (_: RuntimeException) {
            // Um frame problemático não invalida o teste; o que conta é o total recebido.
        } finally {
            image.close()
        }
    }

    private fun evaluate(candidate: Candidate, size: Size, court: Sampler, scoreboard: Sampler, comparison: Comparison) {
        val distance = comparison.minimumDistance
        val detail = "quadra ${court.size} @ %.0f fps, placar ${scoreboard.size} @ %.0f fps"
            .format(court.framesPerSecond, scoreboard.framesPerSecond)
        when {
            court.frames == 0 && scoreboard.frames == 0 -> record(candidate, size, Outcome.NO_FRAMES, "nenhum dos dois entregou frames")
            court.frames == 0 -> record(candidate, size, Outcome.NO_FRAMES, "${candidate.first.id} não entregou frames")
            scoreboard.frames == 0 -> record(candidate, size, Outcome.NO_FRAMES, "${candidate.second.id} não entregou frames")
            distance <= DUPLICATE_DISTANCE -> record(candidate, size, Outcome.SAME_IMAGE, "$detail, mas as duas imagens são iguais (distância $distance)")
            else -> record(candidate, size, Outcome.WORKS, "$detail, imagens distintas (distância $distance)")
        }
        advance()
    }

    private fun record(candidate: Candidate, size: Size, outcome: Outcome, detail: String) {
        val finding = findings.firstOrNull { it.candidate == candidate } ?: Finding(candidate).also { findings += it }
        finding.attempts += Attempt(size, outcome, detail)
    }

    private fun advance() {
        val finding = findings.lastOrNull()
        val last = finding?.attempts?.lastOrNull()?.outcome
        // Sucesso: já achamos a maior resolução deste par. Imagem duplicada: baixar a resolução não separa as fontes.
        val stopCandidate = last == Outcome.WORKS || last == Outcome.SAME_IMAGE || resolutionIndex >= RESOLUTIONS.lastIndex
        if (stopCandidate) {
            candidateIndex++
            resolutionIndex = 0
        } else {
            resolutionIndex++
        }
        runCatching { provider?.unbindAll() }
        handler.postDelayed({ if (isRunning) runAttempt() }, SETTLE_MS)
    }

    private fun finish(message: String?) {
        attemptToken++
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        runCatching { provider?.unbindAll() }
        owner?.stop()
        owner = null
        message?.let(onProgress)
        if (message == null) onProgress("Teste concluído.")
        onReport(report(finished = message == null))
    }

    // ---------------------------------------------------------------- relatório

    @ExperimentalCamera2Interop
    private fun report(finished: Boolean): String = buildString {
        appendLine("CASCAM · DIAGNÓSTICO DE DUAS CÂMERAS")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()

        appendLine("1) CÂMERAS DISPONÍVEIS")
        capabilities.cameras.filter { !it.isPhysical }.forEach { logical ->
            appendLine("  [${logical.id}] ${logical.describe}")
            appendLine("       nível ${logical.hardwareLevel}${if (logical.logicalMultiCamera) " · LOGICAL_MULTI_CAMERA" else ""}")
            capabilities.cameras.filter { it.isPhysical && it.logicalCameraId == logical.id }.forEach {
                appendLine("       └ ${it.describe}")
            }
        }
        appendLine()

        appendLine("2) O QUE O APARELHO DECLARA")
        appendLine("  FEATURE_CAMERA_CONCURRENT: ${if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)) "sim" else "não"}")
        appendLine("  getConcurrentCameraIds(): ${
            capabilities.concurrentPairs.takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { it.sorted().joinToString(" + ") } ?: "nenhum par"
        }")
        val cameraXGroups = provider?.availableConcurrentCameraInfos
            ?.map { group -> group.map { Camera2CameraInfo.from(it).cameraId }.sorted().joinToString(" + ") }
            ?.distinct()?.sorted().orEmpty()
        appendLine("  CameraX simultâneo: ${cameraXGroups.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "nenhum par"}")
        appendLine()

        appendLine("3) TESTE REAL (${findings.count { it.worked }} de ${findings.size} pares testados funcionaram)")
        if (findings.isEmpty()) appendLine("  nada testado ainda")
        findings.forEach { finding ->
            val header = finding.best?.let { "funciona até ${it.size.width}x${it.size.height}" } ?: "não funciona"
            appendLine("  ${if (finding.worked) Outcome.WORKS.symbol else Outcome.BIND_FAILED.symbol} ${finding.candidate.label} — $header")
            appendLine("       ${finding.candidate.kind.description}")
            appendLine("       ${finding.candidate.first.describe}")
            appendLine("       ${finding.candidate.second.describe}")
            finding.attempts.forEach { attempt ->
                appendLine("       ${attempt.outcome.symbol} ${attempt.size.width}x${attempt.size.height}: ${attempt.detail}")
            }
        }
        if (!finished) appendLine("  (varredura interrompida antes do fim)")
        appendLine()

        appendLine("4) COMO SEGUIR")
        appendLine(recommendation())
    }

    private fun recommendation(): String {
        // Um par de sensores físicos da traseira vale mais que traseira + frontal: o placar fica
        // atrás do aparelho junto com a quadra. Depois disso vale o contraste de enquadramento.
        val winner = findings.filter { it.worked }.maxWithOrNull(
            compareBy<Finding>(
                { it.candidate.kind != Kind.CONCURRENT_LOGICAL },
                { it.candidate.fieldOfViewGap },
                { it.best?.size?.width ?: 0 },
            ),
        )
        if (winner != null) {
            val best = winner.best!!
            val court = listOf(winner.candidate.first, winner.candidate.second)
                .maxByOrNull { it.horizontalFieldOfView ?: 0f }!!
            val scoreboard = if (court == winner.candidate.first) winner.candidate.second else winner.candidate.first
            return buildString {
                appendLine("  Use QUADRA = ${court.id} (${court.lensLabel}) e PLACAR = ${scoreboard.id} (${scoreboard.lensLabel}).")
                appendLine("  Resolução máxima confirmada para os dois fluxos juntos: ${best.size.width}x${best.size.height}.")
                if (best.size.width < 1920) {
                    appendLine("  Acima disso o par falhou, então a composição precisa ser montada nessa resolução")
                    appendLine("  e só depois escalada para a saída da transmissão.")
                }
                if (winner.candidate.kind == Kind.CONCURRENT_LOGICAL) {
                    appendLine("  Este par usa o modo simultâneo do Android, que costuma travar em 720p por regra da própria plataforma.")
                }
                append("  O zoom óptico do placar continua vindo do sensor escolhido; ajuste o resto no controle de zoom da aba PLACAR.")
            }
        }
        val sameImage = findings.any { finding -> finding.attempts.any { it.outcome == Outcome.SAME_IMAGE } }
        return buildString {
            appendLine("  Nenhum par entregou duas imagens distintas neste aparelho.")
            if (sameImage) {
                appendLine("  Algum par abriu mas devolveu a mesma imagem nos dois fluxos: o HAL aceitou o pedido")
                appendLine("  e ignorou o sensor físico, então não dá para confiar nele.")
            }
            appendLine("  Caminhos que sobram, em ordem de esforço:")
            appendLine("   a) alternar uma câmera só entre quadra e placar, cortando o placar por zoom digital;")
            appendLine("   b) usar o zoom da câmera lógica para o placar e aceitar perder o enquadramento aberto durante o corte;")
            append("   c) um segundo aparelho enviando o placar pela rede.")
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun rootCause(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message ?: "sem detalhe"}"
    }

    private class ProbeLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    companion object {
        private val RESOLUTIONS = listOf(Size(1920, 1080), Size(1280, 720), Size(640, 480))
        private const val OBSERVATION_MS = 2_500L
        private const val SETTLE_MS = 400L
        private const val MAXIMUM_CANDIDATES = 10
        private const val DUPLICATE_DISTANCE = 3

        /**
         * Hash perceptual de 64 bits lido direto do plano Y, sem converter para bitmap: duas fontes
         * diferentes dão distâncias altas, o mesmo buffer duplicado dá zero.
         */
        fun signatureOf(image: ImageProxy): Long {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val samples = IntArray(64)
            var average = 0L
            for (index in samples.indices) {
                val x = ((index % 8 + .5f) * image.width / 8f).toInt().coerceIn(0, image.width - 1)
                val y = ((index / 8 + .5f) * image.height / 8f).toInt().coerceIn(0, image.height - 1)
                val position = y * plane.rowStride + x * plane.pixelStride
                samples[index] = if (position < buffer.limit()) buffer.get(position).toInt() and 0xff else 0
                average += samples[index]
            }
            average /= samples.size
            var signature = 0L
            samples.forEachIndexed { index, value -> if (value >= average) signature = signature or (1L shl index) }
            return signature
        }
    }
}
