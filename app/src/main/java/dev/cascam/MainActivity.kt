package dev.cascam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.cascam.camera.CameraCapabilities
import dev.cascam.camera.CameraCapabilitiesReader
import dev.cascam.camera.CameraInfo
import dev.cascam.camera.CameraXSupport
import dev.cascam.camera.DualCameraEngine
import dev.cascam.camera.DualCameraProbe
import dev.cascam.config.CompositionEngine
import dev.cascam.config.FrameRotation
import dev.cascam.gl.GlCompositor
import android.view.SurfaceHolder
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.BroadcastProtocol
import dev.cascam.config.VideoCodec
import dev.cascam.config.BitratePreset
import dev.cascam.config.LiveLatency
import dev.cascam.config.LivePrivacy
import dev.cascam.youtube.YoutubeLiveApi
import dev.cascam.config.BroadcastConfigurationStore
import dev.cascam.databinding.ActivityMainBinding
import dev.cascam.ui.CompositionOverlayView
import dev.cascam.ui.YuvToBitmapConverter
import dev.cascam.stream.YoutubePublisher
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private enum class Screen { BROADCAST, COURT, SCOREBOARD, DIAGNOSTICS }

    private lateinit var binding: ActivityMainBinding
    private lateinit var capabilities: CameraCapabilities
    private lateinit var store: BroadcastConfigurationStore
    private var cameraIds: List<String> = emptyList()
    private var screen = Screen.BROADCAST
    private var boundCamera: Camera? = null
    private val courtAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val scoreboardAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val courtFrameSignature = AtomicLong()
    private val repeatedFrameCount = AtomicInteger()
    private val distinctSourcesConfirmed = AtomicBoolean()
    private var publisher: YoutubePublisher? = null
    private lateinit var youtubeApi: YoutubeLiveApi
    private var deviceAuthorization: YoutubeLiveApi.DeviceAuthorization? = null
    private var probe: DualCameraProbe? = null
    private var probeReport: String = ""
    private var ingestion: YoutubeLiveApi.Ingestion? = null
    private var dualCameraEngine: DualCameraEngine? = null
    private var compositor: GlCompositor? = null
    private var encoderSurface: Surface? = null
    private var scoreboardPanX = 0f
    private var scoreboardPanY = 0f
    private val broadcastLifecycle = BroadcastLifecycleOwner()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true || hasCameraPermission()) showScreen(screen)
        else toast("Permissão da câmera necessária")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        broadcastLifecycle.start()
        store = BroadcastConfigurationStore(this)
        youtubeApi = YoutubeLiveApi(this)
        capabilities = CameraCapabilitiesReader.read(this)
        configureForm(store.load())
        configureActions()
        showScreen(Screen.BROADCAST)
        requestCameraIfNeeded()
    }

    private fun configureForm(configuration: BroadcastConfiguration) {
        cameraIds = capabilities.cameras.map { it.id }
        val labels = capabilities.cameras.map {
            "${if (it.physicalCameraId == null) "Lógica" else "Física"} ${it.id} · ${it.lensFacing.label} · ${it.minimumFocalLength?.let { focal -> "$focal mm" } ?: "focal ?"}"
        }
        binding.courtCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.scoreboardCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.courtCamera.setSelection(cameraIds.indexOf(configuration.courtCameraId).takeIf { it >= 0 } ?: 0)
        binding.scoreboardCamera.setSelection(cameraIds.indexOf(configuration.scoreboardCameraId).takeIf { it >= 0 } ?: 0)
        binding.broadcastProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, BroadcastProtocol.entries.map { it.label })
        binding.broadcastProtocol.setSelection(BroadcastProtocol.entries.indexOf(configuration.protocol))
        binding.videoCodec.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VideoCodec.entries.map { it.label })
        binding.videoCodec.setSelection(VideoCodec.entries.indexOf(configuration.videoCodec))
        binding.videoCodec.isEnabled = configuration.protocol == BroadcastProtocol.HLS
        binding.compositionEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CompositionEngine.entries.map { it.label })
        binding.compositionEngine.setSelection(CompositionEngine.entries.indexOf(configuration.compositionEngine))
        binding.compositionEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (screen == Screen.BROADCAST) showScreen(Screen.BROADCAST)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.frameRotation.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FrameRotation.entries.map { it.label })
        binding.frameRotation.setSelection(FrameRotation.entries.indexOf(configuration.frameRotation))
        binding.frameRotation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (screen == Screen.BROADCAST) showScreen(Screen.BROADCAST)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.bitratePreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, BitratePreset.entries.map { it.label })
        binding.bitratePreset.setSelection(BitratePreset.entries.indexOf(configuration.bitratePreset))
        binding.livePrivacy.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LivePrivacy.entries.map { it.label })
        binding.livePrivacy.setSelection(LivePrivacy.entries.indexOf(configuration.livePrivacy))
        binding.liveLatency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LiveLatency.entries.map { it.label })
        binding.liveLatency.setSelection(LiveLatency.entries.indexOf(configuration.liveLatency))
        binding.liveLatency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyLatencyRestrictions(BroadcastProtocol.entries[binding.broadcastProtocol.selectedItemPosition])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.youtubeClientId.setText(configuration.youtubeOAuthClientId)
        binding.youtubeClientSecret.setText(configuration.youtubeOAuthClientSecret)
        binding.liveTitle.setText(configuration.liveTitle)
        binding.oauthStatus.text = if (youtubeApi.hasRefreshToken()) "Conta autorizada; pronta para criar lives." else "OAuth ainda não autorizado."
        binding.youtubeServer.setText(configuration.youtubeServerUrl)
        binding.youtubeKey.setText(configuration.youtubeStreamKey)
        binding.compositionOverlay.setCrop(configuration.cropZoom, configuration.cropPanX, configuration.cropPanY)
        binding.compositionOverlay.setScoreboardCorners(configuration.scoreboardCorners)
        binding.compositionOverlay.setScoreboardDestination(configuration.scoreboardDestination)
        updateZoomLabels()
    }

    private fun configureActions() {
        binding.navBroadcast.setOnClickListener { showScreen(Screen.BROADCAST) }
        binding.navCourt.setOnClickListener { showScreen(Screen.COURT) }
        binding.navScoreboard.setOnClickListener { showScreen(Screen.SCOREBOARD) }
        binding.navDiagnostics.setOnClickListener { showScreen(Screen.DIAGNOSTICS) }
        binding.runProbe.setOnClickListener { toggleProbe() }
        binding.copyProbeReport.setOnClickListener { copyProbeReport() }
        binding.saveButton.setOnClickListener { saveConfiguration(); toast("Configuração salva") }
        binding.startButton.setOnClickListener { toggleBroadcast() }
        binding.authorizeYoutube.setOnClickListener { authorizeYoutube() }
        binding.copyOauthCode.setOnClickListener {
            val code = deviceAuthorization?.userCode
            if (code == null) toast("Toque em AUTORIZAR YOUTUBE para gerar um código")
            else if (copyDeviceCode()) toast("Código $code copiado") else toast("Não consegui copiar; selecione o código na tela")
        }
        binding.openOauthPage.setOnClickListener { openVerificationPage() }
        binding.createLive.setOnClickListener { createLiveAndBroadcast() }
        binding.openLive.setOnClickListener {
            val url = ingestion?.watchUrl ?: return@setOnClickListener
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("Abra $url") }
        }
        binding.checkLive.setOnClickListener { checkLiveHealth() }
        binding.cropLarger.setOnClickListener { binding.compositionOverlay.changeCropZoom(-.25f) }
        binding.cropSmaller.setOnClickListener { binding.compositionOverlay.changeCropZoom(.25f) }
        binding.compositionOverlay.onCropChanged = { _, _, _ -> updateZoomLabels() }
        binding.compositionOverlay.onPanRequested = { dx, dy -> panScoreboardView(dx, dy) }

        binding.scoreboardViewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyScoreboardViewZoom()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val cameraListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (screen != Screen.BROADCAST) startCamera(cameraIdFor(screen))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.courtCamera.onItemSelectedListener = cameraListener
        binding.scoreboardCamera.onItemSelectedListener = cameraListener
        binding.broadcastProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val protocol = BroadcastProtocol.entries[position]
                binding.videoCodec.isEnabled = protocol == BroadcastProtocol.HLS
                if (protocol == BroadcastProtocol.RTMPS) binding.videoCodec.setSelection(VideoCodec.entries.indexOf(VideoCodec.H264))
                applyLatencyRestrictions(protocol)
                val current = binding.youtubeServer.text.toString()
                if (protocol == BroadcastProtocol.HLS && current.startsWith("rtmp")) {
                    binding.youtubeServer.setText("https://a.upload.youtube.com/http_upload_hls?cid=&copy=0&file=")
                } else if (protocol == BroadcastProtocol.RTMPS && current.startsWith("http")) {
                    binding.youtubeServer.setText("rtmps://a.rtmps.youtube.com/live2")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showScreen(target: Screen) {
        if (target != Screen.BROADCAST && publisher != null) stopBroadcast()
        if (target != Screen.BROADCAST) { dualCameraEngine?.stop(); releaseCompositor() }
        if (target != Screen.DIAGNOSTICS) stopProbe()
        screen = target
        binding.panelBroadcast.visibility = if (target == Screen.BROADCAST) View.VISIBLE else View.GONE
        binding.panelCourt.visibility = if (target == Screen.COURT) View.VISIBLE else View.GONE
        binding.panelScoreboard.visibility = if (target == Screen.SCOREBOARD) View.VISIBLE else View.GONE
        binding.panelDiagnostics.visibility = if (target == Screen.DIAGNOSTICS) View.VISIBLE else View.GONE
        when (target) {
            Screen.BROADCAST -> CompositionOverlayView.Mode.COMPOSITION
            Screen.COURT -> CompositionOverlayView.Mode.COURT
            Screen.SCOREBOARD -> CompositionOverlayView.Mode.SCOREBOARD
            Screen.DIAGNOSTICS -> null
        }?.let(binding.compositionOverlay::setMode)
        binding.compositionOverlay.visibility = if (target == Screen.DIAGNOSTICS) View.GONE else View.VISIBLE
        val gpuComposition = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition] == CompositionEngine.GPU
        binding.composedOutput.visibility = if (target == Screen.BROADCAST && !gpuComposition) View.VISIBLE else View.GONE
        binding.gpuOutput.visibility = if (target == Screen.BROADCAST && gpuComposition) View.VISIBLE else View.GONE
        binding.preview.visibility = if (target == Screen.BROADCAST || target == Screen.DIAGNOSTICS) View.GONE else View.VISIBLE
        binding.scoreboardPreviewContainer.visibility = View.GONE
        resetCourtTransform()
        applyScoreboardViewZoom()
        if (hasCameraPermission()) when (target) {
            Screen.BROADCAST -> startCompositionPreview()
            // O teste precisa das câmeras livres: qualquer bind anterior seria confundido com falha do par.
            Screen.DIAGNOSTICS -> releaseCameras()
            else -> startCamera(cameraIdFor(target))
        }
    }

    private fun releaseCameras() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ runCatching { future.get().unbindAll() } }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraIdFor(target: Screen): String? = when (target) {
        Screen.BROADCAST, Screen.COURT -> cameraIds.getOrNull(binding.courtCamera.selectedItemPosition)
        Screen.SCOREBOARD -> cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition)
        Screen.DIAGNOSTICS -> null
    }

    private fun readForm(): BroadcastConfiguration {
        val (cropZoom, cropPanX, cropPanY) = binding.compositionOverlay.crop()
        return BroadcastConfiguration(
            courtCameraId = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty(),
            scoreboardCameraId = cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition).orEmpty(),
            cropZoom = cropZoom, cropPanX = cropPanX, cropPanY = cropPanY,
            scoreboardCorners = binding.compositionOverlay.scoreboardCorners(),
            scoreboardDestination = binding.compositionOverlay.scoreboardDestination(),
            protocol = BroadcastProtocol.entries[binding.broadcastProtocol.selectedItemPosition],
            videoCodec = VideoCodec.entries[binding.videoCodec.selectedItemPosition],
            bitratePreset = BitratePreset.entries[binding.bitratePreset.selectedItemPosition],
            youtubeServerUrl = binding.youtubeServer.text.toString().trim().removeSuffix("/"),
            youtubeStreamKey = binding.youtubeKey.text.toString().trim(),
            youtubeOAuthClientId = binding.youtubeClientId.text.toString().trim(),
            youtubeOAuthClientSecret = binding.youtubeClientSecret.text.toString().trim(),
            liveTitle = binding.liveTitle.text.toString().trim().ifBlank { "CasCam ao vivo" },
            livePrivacy = LivePrivacy.entries[binding.livePrivacy.selectedItemPosition],
            liveLatency = LiveLatency.entries[binding.liveLatency.selectedItemPosition],
            compositionEngine = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition],
            frameRotation = FrameRotation.entries[binding.frameRotation.selectedItemPosition],
        )
    }

    private fun saveConfiguration() = store.save(readForm()).also { compositor?.configure(readForm()) }

    private fun authorizeYoutube() {
        val clientId = binding.youtubeClientId.text.toString().trim()
        val clientSecret = binding.youtubeClientSecret.text.toString().trim()
        if (clientId.isBlank()) { binding.youtubeClientId.error = "Informe o Client ID OAuth"; return }
        binding.oauthStatus.text = "Solicitando código ao Google…"
        binding.oauthCodeCard.visibility = View.GONE
        deviceAuthorization = null
        Thread {
            runCatching {
                val authorization = youtubeApi.beginDeviceAuthorization(clientId)
                runOnUiThread { showDeviceCode(authorization); openVerificationPage() }
                youtubeApi.finishDeviceAuthorization(clientId, clientSecret, authorization) { status ->
                    runOnUiThread { binding.oauthStatus.text = status }
                }
            }.onSuccess {
                runOnUiThread {
                    deviceAuthorization = null
                    binding.oauthCodeCard.visibility = View.GONE
                    binding.oauthStatus.text = "Conta do YouTube autorizada."
                    saveConfiguration()
                }
            }.onFailure { error ->
                val explanation = if (error is IOException) {
                    "Sem conexão com o Google (${error.javaClass.simpleName}). Confira se o CasCam pode usar dados em segundo plano: Ajustes › Apps › CasCam › Dados móveis, com Economia de dados desligada ou o app liberado."
                } else {
                    "Falha OAuth: ${error.message}"
                }
                runOnUiThread { binding.oauthStatus.text = explanation }
            }
        }.start()
    }

    /** Mostra o código na tela e já o deixa na área de transferência, antes de abrir o navegador. */
    private fun showDeviceCode(authorization: YoutubeLiveApi.DeviceAuthorization) {
        deviceAuthorization = authorization
        binding.oauthCode.text = authorization.userCode
        binding.oauthCodeCard.visibility = View.VISIBLE
        binding.oauthStatus.text = if (copyDeviceCode()) {
            toast("Código ${authorization.userCode} copiado")
            "Código ${authorization.userCode} copiado. Cole na página do Google e confirme."
        } else {
            "Código ${authorization.userCode}. Toque em COPIAR CÓDIGO e cole na página do Google."
        }
    }

    private fun copyDeviceCode(): Boolean {
        val code = deviceAuthorization?.userCode ?: return false
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Código YouTube", code)) }.isSuccess
    }

    private fun openVerificationPage() {
        val authorization = deviceAuthorization ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorization.bestVerificationUrl))) }
            .onFailure { toast("Abra ${authorization.verificationUrl} e cole o código") }
    }

    private fun createLiveAndBroadcast() {
        val configuration = readForm()
        if (configuration.youtubeOAuthClientId.isBlank()) { binding.youtubeClientId.error = "Informe e autorize o Client ID"; return }
        binding.oauthStatus.text = "Criando e vinculando a live no YouTube…"
        Thread {
            runCatching {
                youtubeApi.createAndBindBroadcast(
                    configuration.youtubeOAuthClientId, configuration.youtubeOAuthClientSecret, configuration.liveTitle,
                    configuration.livePrivacy.apiValue, configuration.protocol, configuration.liveLatency,
                )
            }.onSuccess { created ->
                runOnUiThread {
                    val server = if (configuration.protocol == BroadcastProtocol.RTMPS) {
                        created.serverUrl.replace("rtmp://a.rtmp.youtube.com", "rtmps://a.rtmps.youtube.com")
                    } else created.serverUrl
                    ingestion = created
                    binding.youtubeServer.setText(server)
                    binding.youtubeKey.setText(created.streamKey)
                    binding.liveLink.text = created.watchUrl
                    binding.liveLinkCard.visibility = View.VISIBLE
                    binding.liveHealth.text = "Live criada como ${configuration.livePrivacy.label.lowercase()}. Ela só entra no ar depois que o YouTube receber vídeo."
                    binding.oauthStatus.text = "Live criada e vinculada. Iniciando envio…"
                    saveConfiguration(); toggleBroadcast()
                }
            }.onFailure { error -> runOnUiThread { binding.oauthStatus.text = "Falha ao criar live: ${error.message}" } }
        }.start()
    }

    @ExperimentalCamera2Interop
    private fun toggleProbe() {
        if (probe?.isRunning == true) { stopProbe(); return }
        if (!hasCameraPermission()) { requestCameraIfNeeded(); return }
        binding.probeReport.text = ""
        probeReport = ""
        binding.copyProbeReport.isEnabled = false
        binding.runProbe.text = "■ PARAR TESTE"
        probe = DualCameraProbe(
            context = this,
            capabilities = capabilities,
            onProgress = { message -> runOnUiThread { binding.probeStatus.text = message } },
            onReport = { report ->
                runOnUiThread {
                    probeReport = report
                    binding.probeReport.text = report
                    binding.copyProbeReport.isEnabled = report.isNotBlank()
                    binding.runProbe.text = "▶ TESTAR PARES DE CÂMERA"
                    probe = null
                }
            },
        ).also { it.start() }
    }

    private fun stopProbe() {
        probe?.cancel()
        probe = null
        binding.runProbe.text = "▶ TESTAR PARES DE CÂMERA"
    }

    private fun copyProbeReport() {
        if (probeReport.isBlank()) { toast("Rode o teste primeiro"); return }
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) { toast("Área de transferência indisponível"); return }
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico de câmeras CasCam", probeReport))
        toast("Relatório copiado")
    }

    /** Sem filtro do app: o YouTube decide se aceita a combinação, e a recusa aparece na tela. */
    private fun applyLatencyRestrictions(protocol: BroadcastProtocol) {
        val chosen = LiveLatency.entries[binding.liveLatency.selectedItemPosition]
        val segment = "segmentos de %.0f s".format(chosen.segmentMillis / 1_000f)
        binding.latencyHint.text = when {
            protocol == BroadcastProtocol.HLS ->
                "Em HLS o atraso carrega o tamanho do segmento junto, então esta escolha também encolhe o segmento: $segment."
            chosen == LiveLatency.ULTRA_LOW -> "Menor atraso possível, sem DVR: quem assiste não consegue voltar a fita."
            chosen == LiveLatency.LOW -> "Meio-termo do Studio, com DVR preservado."
            else -> "Mais buffer no YouTube: melhor para rede instável, pior para acompanhar o jogo ao vivo."
        }
    }

    private fun checkLiveHealth() {
        val created = ingestion ?: return
        val configuration = readForm()
        binding.liveHealth.text = "Consultando o YouTube…"
        Thread {
            runCatching {
                youtubeApi.broadcastHealth(configuration.youtubeOAuthClientId, configuration.youtubeOAuthClientSecret, created)
            }.onSuccess { health ->
                runOnUiThread { binding.liveHealth.text = health.summary }
            }.onFailure { error ->
                runOnUiThread { binding.liveHealth.text = "Não consegui consultar: ${error.message}" }
            }
        }.start()
    }

    private fun scoreboardViewZoom() = 1f + binding.scoreboardViewZoom.progress / 10f

    /**
     * Ampliação só da visualização, para colocar os quatro cantos num placar que ocupa poucos pixels
     * do quadro. Nada aqui muda a captura nem o que é transmitido: as coordenadas dos cantos
     * continuam normalizadas, e o Android já entrega o toque em coordenadas locais da view, então
     * ampliar a view multiplica a precisão do arrasto sem tocar na matemática do overlay.
     *
     * O pivô fica no centro da moldura e o deslocamento é livre, porque o placar quase nunca está
     * no meio do quadro — ampliar em cima de um ponto fixo só resolveria o caso centrado.
     */
    private fun applyScoreboardViewZoom() {
        val zoom = if (screen == Screen.SCOREBOARD) scoreboardViewZoom() else 1f
        if (zoom <= 1.01f) { scoreboardPanX = 0f; scoreboardPanY = 0f }
        clampScoreboardPan(zoom)
        listOf<View>(binding.preview, binding.compositionOverlay).forEach { view ->
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
            view.scaleX = zoom
            view.scaleY = zoom
            view.translationX = scoreboardPanX
            view.translationY = scoreboardPanY
        }
        binding.compositionOverlay.setDisplayScale(zoom)
        binding.scoreboardViewZoomStatus.text = if (zoom > 1.01f) {
            "Tela: %.1f× · arraste fora do quadrilátero para deslocar.".format(zoom)
        } else {
            "Tela: 1.0× · amplie para posicionar os cantos com precisão."
        }
    }

    /**
     * O arrasto chega em coordenadas locais da view, que já estão divididas pela ampliação; para o
     * conteúdo acompanhar o dedo na tela, o deslocamento volta a ser multiplicado por ela.
     */
    private fun panScoreboardView(dx: Float, dy: Float) {
        val zoom = scoreboardViewZoom()
        if (screen != Screen.SCOREBOARD || zoom <= 1.01f) return
        scoreboardPanX += dx * zoom
        scoreboardPanY += dy * zoom
        applyScoreboardViewZoom()
    }

    /** Impede que a imagem seja arrastada para fora da moldura, deixando faixa preta na tela. */
    private fun clampScoreboardPan(zoom: Float) {
        val limitX = (zoom - 1f) * binding.compositionOverlay.width / 2f
        val limitY = (zoom - 1f) * binding.compositionOverlay.height / 2f
        scoreboardPanX = scoreboardPanX.coerceIn(-limitX, limitX)
        scoreboardPanY = scoreboardPanY.coerceIn(-limitY, limitY)
    }

    private fun resetCourtTransform() {
        binding.preview.scaleX = 1f; binding.preview.scaleY = 1f
        binding.preview.translationX = 0f; binding.preview.translationY = 0f
    }

    private fun validatedBroadcast(): BroadcastConfiguration? {
        val configuration = readForm()
        return when {
            configuration.protocol == BroadcastProtocol.RTMPS && !configuration.youtubeServerUrl.startsWith("rtmps://") -> null.also { binding.youtubeServer.error = "Use uma URL RTMPS" }
            configuration.protocol == BroadcastProtocol.HLS && !configuration.youtubeServerUrl.startsWith("https://") -> null.also { binding.youtubeServer.error = "Use a URL HTTPS de ingestão HLS do YouTube Studio" }
            configuration.protocol == BroadcastProtocol.RTMPS && configuration.videoCodec != VideoCodec.H264 -> null.also { toast("RTMPS usa H.264; selecione HLS para H.265") }
            configuration.youtubeStreamKey.isBlank() -> null.also { binding.youtubeKey.error = "Informe a chave do YouTube Studio" }
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> null.also {
                toast("Autorize o microfone para transmitir com áudio")
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            else -> configuration
        }
    }

    private fun toggleBroadcast() {
        if (publisher != null) {
            stopBroadcast()
            return
        }
        val configuration = validatedBroadcast() ?: return
        val bitrate = configuration.bitratePreset.bitsPerSecond ?: if (
            getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        ) 350_000 else 3_000_000
        store.save(configuration)
        ContextCompat.startForegroundService(this, Intent(this, BroadcastService::class.java))
        // Em GPU o encoder recebe os quadros pela própria surface dele, que entra como mais um
        // destino do compositor; em CPU continua recebendo bitmaps já compostos.
        val gpu = configuration.compositionEngine == CompositionEngine.GPU && compositor?.isReady == true
        publisher = YoutubePublisher(
            configuration.protocol, configuration.videoCodec, bitrate,
            configuration.youtubeServerUrl, configuration.youtubeStreamKey, configuration.liveLatency,
            useSurfaceInput = gpu,
            onInputSurface = { surface ->
                runOnUiThread {
                    val active = compositor ?: return@runOnUiThread
                    val encoder = publisher
                    if (surface == null) encoderSurface?.let(active::removeTarget)
                    else if (encoder != null) active.addTarget(surface, encoder.videoWidth, encoder.videoHeight, isEncoder = true)
                    encoderSurface = surface
                }
            },
        ) { status ->
            runOnUiThread {
                binding.broadcastStatus.text = status
                if (status.startsWith("Falha")) {
                    val failedPublisher = publisher
                    publisher = null
                    binding.composedOutput.onComposedFrame = null
                    binding.startButton.text = "▶ INICIAR TRANSMISSÃO"
                    stopService(Intent(this, BroadcastService::class.java))
                    Thread { failedPublisher?.close() }.start()
                }
            }
        }.also { active ->
            if (!gpu) binding.composedOutput.onComposedFrame = active::offer
            active.start()
        }
        binding.startButton.text = "■ ENCERRAR TRANSMISSÃO"
    }

    private fun stopBroadcast() {
        val active = publisher ?: return
        publisher = null
        binding.composedOutput.onComposedFrame = null
        encoderSurface?.let { surface -> compositor?.removeTarget(surface) }
        encoderSurface = null
        active.close()
        stopService(Intent(this, BroadcastService::class.java))
        binding.startButton.text = "▶ INICIAR TRANSMISSÃO"
    }

    private fun updateZoomLabels() {
        binding.courtCropStatus.text = "Zoom do recorte: %.1f×".format(binding.compositionOverlay.crop().first)
    }
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraIfNeeded() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    @ExperimentalCamera2Interop
    private fun startCamera(cameraKey: String?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val camera = cameraKey?.let(::cameraFor)
            val selector = camera?.let { selectorFor(it.logicalCameraId) } ?: CameraSelector.DEFAULT_BACK_CAMERA
            val previewBuilder = Preview.Builder()
            // A prévia precisa enquadrar igual à transmissão; com estabilização só de um lado, o
            // recorte que ela cobra deixaria os dois enquadramentos diferentes.
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            camera?.physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
            val preview = previewBuilder.build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, selector, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalCamera2Interop
    private fun startCompositionPreview() {
        repeatedFrameCount.set(0)
        distinctSourcesConfirmed.set(false)
        val configuration = readForm()
        binding.composedOutput.configure(configuration)
        val courtKey = cameraIdFor(Screen.COURT) ?: return
        val scoreboardKey = cameraIdFor(Screen.SCOREBOARD) ?: return
        val courtInfo = cameraFor(courtKey) ?: return
        val scoreboardInfo = cameraFor(scoreboardKey) ?: return
        if (startDualSensorCapture(courtInfo, scoreboardInfo)) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            val courtSelector = selectorFor(courtInfo.logicalCameraId)
            val courtAnalysis = imageAnalysis(courtInfo)
            val scoreboardAnalysis = imageAnalysis(scoreboardInfo)
            val sourceDescription = "quadra=${courtInfo.id}, placar=${scoreboardInfo.id}"
            courtAnalysis.setAnalyzer(courtAnalysisExecutor) { image ->
                try {
                    val bitmap = YuvToBitmapConverter.convert(image)
                    courtFrameSignature.set(frameSignature(bitmap))
                    binding.composedOutput.submitCourt(bitmap)
                    if (courtKey == scoreboardKey) {
                        binding.composedOutput.submitScoreboard(bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false))
                    }
                } finally { image.close() }
            }
            scoreboardAnalysis.setAnalyzer(scoreboardAnalysisExecutor) { image ->
                try {
                    val bitmap = YuvToBitmapConverter.convert(image)
                    binding.composedOutput.submitScoreboard(bitmap)
                    detectRepeatedSource(frameSignature(bitmap), sourceDescription)
                } finally { image.close() }
            }
            try {
                if (courtInfo.logicalCameraId == scoreboardInfo.logicalCameraId) {
                    boundCamera = if (courtKey == scoreboardKey) {
                        provider.bindToLifecycle(broadcastLifecycle, courtSelector, courtAnalysis)
                    } else {
                        provider.bindToLifecycle(broadcastLifecycle, courtSelector, courtAnalysis, scoreboardAnalysis)
                    }
                } else {
                    val requestedPair = setOf(courtInfo.logicalCameraId, scoreboardInfo.logicalCameraId)
                    val advertisedGroup = provider.availableConcurrentCameraInfos.firstOrNull { group ->
                        group.map { Camera2CameraInfo.from(it).cameraId }.toSet().containsAll(requestedPair)
                    } ?: error("CameraX não anunciou o par ${requestedPair.sorted().joinToString(" + ")}")
                    val advertisedCourt = advertisedGroup.first { Camera2CameraInfo.from(it).cameraId == courtInfo.logicalCameraId }
                    val advertisedScoreboard = advertisedGroup.first { Camera2CameraInfo.from(it).cameraId == scoreboardInfo.logicalCameraId }
                    val configurations = listOf(
                        ConcurrentCamera.SingleCameraConfig(selectorFor(Camera2CameraInfo.from(advertisedCourt).cameraId), UseCaseGroup.Builder().addUseCase(courtAnalysis).build(), broadcastLifecycle),
                        ConcurrentCamera.SingleCameraConfig(selectorFor(Camera2CameraInfo.from(advertisedScoreboard).cameraId), UseCaseGroup.Builder().addUseCase(scoreboardAnalysis).build(), broadcastLifecycle),
                    )
                    val concurrent = provider.bindToLifecycle(configurations)
                    boundCamera = concurrent.cameras.getOrNull(1)
                }
                binding.broadcastStatus.text = if (courtKey == scoreboardKey) {
                    "⚠ A mesma fonte foi selecionada para quadra e placar ($sourceDescription)."
                } else {
                    "Composição ativa ($sourceDescription). Verificando se os fluxos são distintos…"
                }
                } catch (error: RuntimeException) {
                provider.unbindAll()
                boundCamera = provider.bindToLifecycle(broadcastLifecycle, courtSelector, courtAnalysis)
                val reason = generateSequence<Throwable>(error) { it.cause }.last()
                    .let { "${it.javaClass.simpleName}: ${it.message}" }
                binding.broadcastStatus.text = "Incompatível com duas fontes ($sourceDescription): $reason. Somente quadra."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Caminho preferido para quadra + placar: dois sensores físicos da mesma câmera lógica por
     * Camera2. Devolve false quando o par escolhido não é desses, e aí a composição segue pelo
     * CameraX como antes.
     */
    private fun startDualSensorCapture(court: CameraInfo, scoreboard: CameraInfo): Boolean {
        val engine = dualCameraEngine ?: DualCameraEngine(
            manager = getSystemService(CameraManager::class.java),
            onFrame = { role, bitmap ->
                when (role) {
                    DualCameraEngine.Role.COURT -> binding.composedOutput.submitCourt(bitmap)
                    DualCameraEngine.Role.SCOREBOARD -> binding.composedOutput.submitScoreboard(bitmap)
                }
            },
            onStatus = { status -> runOnUiThread { binding.broadcastStatus.text = status } },
        ).also { dualCameraEngine = it }
        val plan = engine.planFor(capabilities, court, scoreboard, DUAL_SENSOR_CEILING) ?: return false
        val useGpu = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition] == CompositionEngine.GPU
        // Só abrir a lógica depois que o CameraX largou, senão o openCamera volta com CAMERA_IN_USE.
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching { future.get().unbindAll() }
            if (useGpu) startGpuComposition(engine, plan) else engine.start(plan, compositionRotation(engine, plan))
        }, ContextCompat.getMainExecutor(this))
        return true
    }

    /**
     * Em GPU a ordem importa: o compositor precisa existir e ter criado as SurfaceTextures antes de
     * a câmera abrir, porque são elas os destinos da sessão de captura.
     */
    private fun startGpuComposition(engine: DualCameraEngine, plan: DualCameraEngine.Plan) {
        releaseCompositor()
        val created = GlCompositor { status -> runOnUiThread { binding.broadcastStatus.text = status } }
        compositor = created
        created.configure(readForm())
        created.start(plan.size, compositionRotation(engine, plan)) {
            runOnUiThread {
                val courtSurface = created.courtSurface
                val scoreboardSurface = created.scoreboardSurface
                if (courtSurface == null || scoreboardSurface == null) {
                    binding.broadcastStatus.text = "Composição GPU não inicializou; volte para CPU."
                    return@runOnUiThread
                }
                attachGpuPreview(created)
                engine.start(plan, compositionRotation(engine, plan), courtSurface to scoreboardSurface)
            }
        }
    }

    private fun attachGpuPreview(created: GlCompositor) {
        val holder = binding.gpuOutput.holder
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            created.addTarget(surface, binding.gpuOutput.width.coerceAtLeast(1), binding.gpuOutput.height.coerceAtLeast(1), isEncoder = false)
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                compositor?.removeTarget(holder.surface)
                compositor?.addTarget(holder.surface, width, height, isEncoder = false)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                compositor?.removeTarget(holder.surface)
            }
        })
    }

    private fun releaseCompositor() {
        compositor?.release()
        compositor = null
    }

    /** Escolha do usuário quando existe; senão a conta automática a partir da orientação do sensor. */
    private fun compositionRotation(engine: DualCameraEngine, plan: DualCameraEngine.Plan): Int =
        FrameRotation.entries[binding.frameRotation.selectedItemPosition].degrees
            ?: engine.rotationFor(plan.logicalId, displayRotationDegrees())

    private fun displayRotationDegrees() = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    @ExperimentalCamera2Interop
    private fun selectorFor(cameraId: String) = CameraXSupport.selectorFor(cameraId)

    @ExperimentalCamera2Interop
    private fun imageAnalysis(camera: CameraInfo): ImageAnalysis = CameraXSupport.imageAnalysis(camera)

    private fun cameraFor(key: String) = capabilities.cameras.firstOrNull { it.id == key }

    private fun frameSignature(bitmap: android.graphics.Bitmap): Long {
        var signature = 0L
        var average = 0L
        val samples = IntArray(64)
        for (index in samples.indices) {
            val x = ((index % 8 + .5f) * bitmap.width / 8f).toInt().coerceAtMost(bitmap.width - 1)
            val y = ((index / 8 + .5f) * bitmap.height / 8f).toInt().coerceAtMost(bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            samples[index] = ((color shr 16 and 0xff) * 3 + (color shr 8 and 0xff) * 6 + (color and 0xff)) / 10
            average += samples[index]
        }
        average /= samples.size
        samples.forEachIndexed { index, value -> if (value >= average) signature = signature or (1L shl index) }
        return signature
    }

    private fun detectRepeatedSource(scoreboardSignature: Long, description: String) {
        val courtSignature = courtFrameSignature.get()
        if (courtSignature == 0L) return
        val distance = java.lang.Long.bitCount(courtSignature xor scoreboardSignature)
        if (distance <= 3) {
            if (repeatedFrameCount.incrementAndGet() >= 5) runOnUiThread {
                binding.broadcastStatus.text = "⚠ O aparelho entregou a mesma imagem nas duas fontes ($description). Escolha sensores físicos distintos ou traseira + frontal."
            }
        } else {
            repeatedFrameCount.set(0)
            if (distinctSourcesConfirmed.compareAndSet(false, true)) runOnUiThread {
                binding.broadcastStatus.text = "✓ Duas fontes distintas confirmadas ($description)."
            }
        }
    }

    override fun onDestroy() {
        probe?.shutdown()
        probe = null
        dualCameraEngine?.release()
        dualCameraEngine = null
        releaseCompositor()
        stopBroadcast()
        stopService(Intent(this, BroadcastService::class.java))
        broadcastLifecycle.stop()
        super.onDestroy()
        courtAnalysisExecutor.shutdown()
        scoreboardAnalysisExecutor.shutdown()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private class BroadcastLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object {
        /** Teto de captura dos dois sensores; o teste confirmou 1920x1080 neste aparelho. */
        val DUAL_SENSOR_CEILING = android.util.Size(1920, 1080)
    }
}
