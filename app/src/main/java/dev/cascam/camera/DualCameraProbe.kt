package dev.cascam.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
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
import kotlin.math.tan

/**
 * Descobre empiricamente quais duas câmeras o aparelho entrega ao mesmo tempo.
 *
 * A varredura tem duas fases, porque as duas perguntas têm APIs diferentes:
 *
 * 1. **Camera2 direto** para dois sensores físicos da mesma câmera lógica — ultra-wide + tele,
 *    que é o par que interessa para quadra + placar. É a única via com garantia documentada:
 *    uma sessão só, dois streams YUV de mesmo tamanho, cada um amarrado a um sensor físico.
 * 2. **CameraX** para pares de câmeras lógicas em modo simultâneo, que na prática é traseira +
 *    frontal e é o caminho que o CameraX de fato suporta.
 *
 * Em nenhuma das duas basta o bind não estourar: o par só passa se os dois fluxos entregarem
 * imagens comprovadamente diferentes, medidas por [LumaSignature] e só em quadros com contraste.
 */
class DualCameraProbe(
    private val context: Context,
    private val capabilities: CameraCapabilities,
    private val onProgress: (String) -> Unit,
    private val onReport: (String) -> Unit,
) {
    private enum class Kind(val description: String) {
        PHYSICAL_PAIR("dois sensores físicos da mesma câmera lógica, via Camera2"),
        CONCURRENT_LOGICAL("duas câmeras lógicas em modo simultâneo, via CameraX"),
    }

    private data class Candidate(val kind: Kind, val first: CameraInfo, val second: CameraInfo) {
        val label: String get() = "${first.id} + ${second.id}"
        val fieldOfViewGap: Float
            get() {
                val a = first.horizontalFieldOfView ?: return 0f
                val b = second.horizontalFieldOfView ?: return 0f
                return abs(a - b)
            }
    }

    private data class Attempt(val size: Size, val approved: Boolean, val detail: String)

    private data class Finding(val candidate: Candidate, val attempts: MutableList<Attempt> = mutableListOf()) {
        val best: Attempt? get() = attempts.firstOrNull { it.approved }
        val worked: Boolean get() = best != null
    }

    private class Sampler {
        @Volatile var frames = 0
        @Volatile var last: LumaSignature? = null
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var firstFrameAt = 0L
        @Volatile var lastFrameAt = 0L

        val framesPerSecond: Float
            get() {
                val span = lastFrameAt - firstFrameAt
                return if (frames > 1 && span > 0) (frames - 1) * 1_000f / span else 0f
            }
        val describe: String get() = if (frames == 0) "sem quadros" else "${width}x$height @ %.0f fps".format(framesPerSecond)
    }

    /** Distâncias válidas de uma tentativa: só quadros com contraste entram. */
    private class Comparison {
        private val distances = mutableListOf<Int>()

        @Synchronized fun add(distance: Int) { distances += distance }

        @Synchronized fun median(): Int? = distances.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

        @Synchronized fun count(): Int = distances.size
    }

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newFixedThreadPool(2)
    private val camera2Probe = Camera2DualStreamProbe(context.getSystemService(CameraManager::class.java))
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
            // O Camera2 precisa da câmera livre: se o CameraX ainda estiver segurando a lógica,
            // o openCamera volta com ERROR_CAMERA_IN_USE e o par honesto seria reprovado à toa.
            runCatching { provider?.unbindAll() }
            owner = ProbeLifecycleOwner().also { it.start() }
            handler.postDelayed({ if (isRunning) runAttempt() }, SETTLE_MS)
        }, ContextCompat.getMainExecutor(context))
    }

    fun cancel() {
        if (!isRunning) return
        finish("Teste interrompido.")
    }

    fun shutdown() {
        cancel()
        analysisExecutor.shutdown()
        camera2Probe.close()
    }

    // ---------------------------------------------------------------- candidatos

    private fun buildCandidates(): List<Candidate> {
        val result = mutableListOf<Candidate>()
        capabilities.physicalSensorsByLogical().forEach { (_, sensors) ->
            val ordered = sensors.sortedByDescending { it.horizontalFieldOfView ?: 0f }
            for (i in ordered.indices) for (j in i + 1 until ordered.size) {
                result += Candidate(Kind.PHYSICAL_PAIR, ordered[i], ordered[j])
            }
        }
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

    private fun resolutionsFor(candidate: Candidate): List<Size> = when (candidate.kind) {
        // Os dois streams físicos precisam ter exatamente o mesmo tamanho, e o tamanho precisa
        // existir nos dois sensores; pedir 1920x1080 num sensor que só oferece 1920x1088 reprova
        // o par por um detalhe de tabela, não por limite real.
        Kind.PHYSICAL_PAIR -> runCatching {
            camera2Probe.commonSizes(
                candidate.first.physicalCameraId.orEmpty(), candidate.second.physicalCameraId.orEmpty(), CEILINGS,
            )
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: CEILINGS
        Kind.CONCURRENT_LOGICAL -> CEILINGS
    }

    // ---------------------------------------------------------------- execução

    @ExperimentalCamera2Interop
    private fun runAttempt() {
        if (candidateIndex >= candidates.size) {
            finish(null)
            return
        }
        val candidate = candidates[candidateIndex]
        val resolutions = resolutionsFor(candidate)
        if (resolutionIndex >= resolutions.size) {
            candidateIndex++
            resolutionIndex = 0
            handler.post { if (isRunning) runAttempt() }
            return
        }
        val size = resolutions[resolutionIndex]
        onProgress("Par ${candidateIndex + 1}/${candidates.size}: ${candidate.label} a ${size.width}x${size.height}…")
        when (candidate.kind) {
            Kind.PHYSICAL_PAIR -> runCamera2Attempt(candidate, size)
            Kind.CONCURRENT_LOGICAL -> runCameraXAttempt(candidate, size)
        }
    }

    private fun runCamera2Attempt(candidate: Candidate, size: Size) {
        val token = ++attemptToken
        camera2Probe.run(
            logicalId = candidate.first.logicalCameraId,
            physicalA = candidate.first.physicalCameraId.orEmpty(),
            physicalB = candidate.second.physicalCameraId.orEmpty(),
            size = size,
            observationMs = OBSERVATION_MS,
        ) { result ->
            handler.post {
                if (token != attemptToken || !isRunning) return@post
                record(candidate, size, result.approved, result.detail)
                advance()
            }
        }
    }

    @ExperimentalCamera2Interop
    private fun runCameraXAttempt(candidate: Candidate, size: Size) {
        val provider = provider ?: return
        val owner = owner ?: return
        runCatching { provider.unbindAll() }
        val court = Sampler()
        val scoreboard = Sampler()
        val comparison = Comparison()
        val courtAnalysis = CameraXSupport.imageAnalysis(candidate.first, size)
        val scoreboardAnalysis = CameraXSupport.imageAnalysis(candidate.second, size)
        val start = System.currentTimeMillis()
        courtAnalysis.setAnalyzer(analysisExecutor) { image -> sample(court, image, start) }
        scoreboardAnalysis.setAnalyzer(analysisExecutor) { image ->
            sample(scoreboard, image, start)
            val theirs = court.last
            val ours = scoreboard.last
            if (theirs != null && ours != null && theirs.hasContrast && ours.hasContrast) {
                comparison.add(theirs.distanceTo(ours))
            }
        }

        val token = ++attemptToken
        val failure = runCatching { bindConcurrent(provider, owner, candidate, courtAnalysis, scoreboardAnalysis) }.exceptionOrNull()
        if (failure != null) {
            record(candidate, size, false, rootCause(failure))
            advance()
            return
        }
        handler.postDelayed({
            if (token == attemptToken && isRunning) evaluateCameraX(candidate, size, court, scoreboard, comparison)
        }, WARMUP_MS + OBSERVATION_MS)
    }

    @ExperimentalCamera2Interop
    private fun bindConcurrent(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        candidate: Candidate,
        court: ImageAnalysis,
        scoreboard: ImageAnalysis,
    ) {
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

    private fun sample(sampler: Sampler, image: ImageProxy, startedAt: Long) {
        try {
            if (System.currentTimeMillis() - startedAt < WARMUP_MS) return
            val now = System.currentTimeMillis()
            if (sampler.frames == 0) sampler.firstFrameAt = now
            sampler.lastFrameAt = now
            sampler.width = image.width
            sampler.height = image.height
            val plane = image.planes[0]
            sampler.last = LumaSignature.of(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
            sampler.frames++
        } catch (_: RuntimeException) {
            // Um quadro problemático não invalida a tentativa; o que conta é o conjunto.
        } finally {
            image.close()
        }
    }

    private fun evaluateCameraX(candidate: Candidate, size: Size, court: Sampler, scoreboard: Sampler, comparison: Comparison) {
        val detail = "${candidate.first.id} ${court.describe}; ${candidate.second.id} ${scoreboard.describe}"
        val median = comparison.median()
        when {
            court.frames == 0 || scoreboard.frames == 0 -> record(candidate, size, false, "$detail — um dos lados não entregou quadro nenhum")
            median == null -> record(candidate, size, false, "$detail — só chegaram quadros sem contraste; aponte as câmeras para cenas iluminadas e diferentes e repita")
            median > DISTINCT_DISTANCE -> record(candidate, size, true, "$detail — imagens distintas (distância mediana $median em ${comparison.count()} comparações)")
            else -> record(candidate, size, false, "$detail — mesma imagem nos dois fluxos (distância mediana $median em ${comparison.count()} comparações)")
        }
        advance()
    }

    private fun record(candidate: Candidate, size: Size, approved: Boolean, detail: String) {
        val finding = findings.firstOrNull { it.candidate == candidate } ?: Finding(candidate).also { findings += it }
        finding.attempts += Attempt(size, approved, detail)
    }

    @ExperimentalCamera2Interop
    private fun advance() {
        val approved = findings.lastOrNull()?.attempts?.lastOrNull()?.approved == true
        if (approved) {
            candidateIndex++
            resolutionIndex = 0
        } else {
            resolutionIndex++
        }
        runCatching { provider?.unbindAll() }
        handler.postDelayed({ if (isRunning) runAttempt() }, SETTLE_MS)
    }

    @ExperimentalCamera2Interop
    private fun finish(message: String?) {
        attemptToken++
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        runCatching { provider?.unbindAll() }
        owner?.stop()
        owner = null
        onProgress(message ?: "Teste concluído.")
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
            if (logical.logicalMultiCamera) {
                appendLine("       CONTROLES INDEPENDENTES POR SENSOR")
                appendLine("         zoom: ${logical.perPhysicalZoom.label}")
                appendLine("         exposição: ${if (logical.independentExposure) "sim" else "não — os dois dividem a mesma medição"}")
                appendLine("         foco: ${if (logical.independentFocus) "sim" else "não"}")
                if (logical.physicalRequestKeys.isEmpty()) {
                    appendLine("         nenhuma chave por sensor declarada")
                } else {
                    appendLine("         ${logical.physicalRequestKeys.size} chaves aceitas por sensor:")
                    wrap(logical.physicalRequestKeys.joinToString(", "), 62).forEach { appendLine("           $it") }
                }
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
            appendLine("  ${if (finding.worked) "✓" else "✗"} ${finding.candidate.label} — $header")
            appendLine("       ${finding.candidate.kind.description}")
            appendLine("       ${finding.candidate.first.describe}")
            appendLine("       ${finding.candidate.second.describe}")
            finding.attempts.forEach { attempt ->
                appendLine("       ${if (attempt.approved) "✓" else "✗"} ${attempt.size.width}x${attempt.size.height}: ${attempt.detail}")
            }
        }
        if (!finished) appendLine("  (varredura interrompida antes do fim)")
        appendLine()

        appendLine("4) COMO SEGUIR")
        appendLine(recommendation())
    }

    private fun recommendation(): String {
        // Dois sensores da traseira valem mais que traseira + frontal: o placar está atrás do
        // aparelho junto com a quadra. Depois disso vale o contraste de enquadramento.
        val winner = findings.filter { it.worked }.maxWithOrNull(
            compareBy<Finding>(
                { it.candidate.kind == Kind.PHYSICAL_PAIR },
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
                if (winner.candidate.kind == Kind.PHYSICAL_PAIR) {
                    appendLine("  O par passou pela via Camera2 com os dois streams no mesmo tamanho; a captura da")
                    appendLine("  transmissão segue esse formato, e não o caminho do CameraX.")
                    appendLine()
                    appendLine("  ONDE APONTAR O TRIPÉ")
                    val gap = ratioBetween(court, scoreboard)
                    appendLine("  As duas lentes olham para o mesmo lado, então ${scoreboard.id} enxerga só o miolo do")
                    appendLine("  quadro de ${court.id}${gap?.let { " — cerca de %.0f%% da largura".format(it * 100) } ?: ""}.")
                    appendLine("  Aponte primeiro para o placar e só depois confira se a quadra inteira cabe em volta:")
                    append("  placar na lateral do quadro aberto fica fora do alcance do outro sensor, e aí não é")
                    append(" distância, é direção — zoom nenhum resolve.")
                } else {
                    append("  Este par usa o modo simultâneo do Android, que costuma travar a resolução por regra da plataforma.")
                }
            }
        }
        val flat = findings.any { finding -> finding.attempts.any { it.detail.contains("sem contraste") } }
        return buildString {
            appendLine("  Nenhum par entregou duas imagens distintas neste teste.")
            if (flat) {
                appendLine("  Parte das tentativas só recebeu quadros sem contraste, o que não prova nada sobre o aparelho:")
                appendLine("  aponte as duas lentes para cenas iluminadas e visivelmente diferentes e rode de novo.")
            }
            appendLine("  Caminhos que sobram, em ordem de esforço:")
            appendLine("   a) alternar uma câmera só entre quadra e placar, cortando o placar por zoom digital;")
            appendLine("   b) usar o zoom da câmera lógica para o placar e aceitar perder o enquadramento aberto no corte;")
            append("   c) um segundo aparelho enviando o placar pela rede.")
        }
    }

    /**
     * Fração da largura do quadro aberto que o sensor fechado cobre. Compara tangentes de meio
     * ângulo, não os ângulos: 29° dentro de 104° não é 28% da largura, é bem menos.
     */
    private fun ratioBetween(wide: CameraInfo, narrow: CameraInfo): Float? {
        val wideAngle = wide.horizontalFieldOfView ?: return null
        val narrowAngle = narrow.horizontalFieldOfView ?: return null
        if (wideAngle <= 0f || narrowAngle <= 0f) return null
        val wideHalf = tan(Math.toRadians(wideAngle / 2.0))
        val narrowHalf = tan(Math.toRadians(narrowAngle / 2.0))
        if (wideHalf <= 0.0) return null
        return (narrowHalf / wideHalf).toFloat()
    }

    /** Quebra uma lista longa em linhas que cabem na largura do painel, sem cortar palavra. */
    private fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.split(" ").forEach { word ->
            if (current.isNotEmpty() && current.length + 1 + word.length > width) {
                lines += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

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
        private val CEILINGS = listOf(Size(1920, 1080), Size(1280, 720), Size(640, 480))
        private const val OBSERVATION_MS = 2_500L
        private const val WARMUP_MS = 1_200L
        private const val SETTLE_MS = 600L
        private const val MAXIMUM_CANDIDATES = 10
        private const val DISTINCT_DISTANCE = 6
    }
}
