package dev.cascam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
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
import dev.cascam.camera.DualCameraProbe
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.BroadcastProtocol
import dev.cascam.config.VideoCodec
import dev.cascam.config.BitratePreset
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
    private val broadcastLifecycle = BroadcastLifecycleOwner()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true || hasCameraPermission()) showScreen(screen)
        else binding.cameraStatus.text = "Permissão da câmera necessária"
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
        showCapabilities()
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
        binding.bitratePreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, BitratePreset.entries.map { it.label })
        binding.bitratePreset.setSelection(BitratePreset.entries.indexOf(configuration.bitratePreset))
        binding.livePrivacy.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LivePrivacy.entries.map { it.label })
        binding.livePrivacy.setSelection(LivePrivacy.entries.indexOf(configuration.livePrivacy))
        binding.youtubeClientId.setText(configuration.youtubeOAuthClientId)
        binding.youtubeClientSecret.setText(configuration.youtubeOAuthClientSecret)
        binding.liveTitle.setText(configuration.liveTitle)
        binding.oauthStatus.text = if (youtubeApi.hasRefreshToken()) "Conta autorizada; pronta para criar lives." else "OAuth ainda não autorizado."
        binding.youtubeServer.setText(configuration.youtubeServerUrl)
        binding.youtubeKey.setText(configuration.youtubeStreamKey)
        binding.compositionOverlay.setCrop(configuration.cropZoom, configuration.cropPanX, configuration.cropPanY)
        binding.compositionOverlay.setScoreboardCorners(configuration.scoreboardCorners)
        binding.compositionOverlay.setScoreboardDestination(configuration.scoreboardDestination)
        binding.scoreboardZoom.progress = ((configuration.scoreboardZoom - 1f) * 10f).toInt().coerceIn(0, 70)
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
        binding.cropLarger.setOnClickListener { binding.compositionOverlay.changeCropZoom(-.25f) }
        binding.cropSmaller.setOnClickListener { binding.compositionOverlay.changeCropZoom(.25f) }
        binding.compositionOverlay.onCropChanged = { _, _, _ -> updateZoomLabels() }

        binding.scoreboardZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateZoomLabels()
                if (fromUser) applyScoreboardZoom()
            }
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
        binding.composedOutput.visibility = if (target == Screen.BROADCAST) View.VISIBLE else View.GONE
        binding.preview.visibility = if (target == Screen.BROADCAST || target == Screen.DIAGNOSTICS) View.GONE else View.VISIBLE
        binding.scoreboardPreviewContainer.visibility = View.GONE
        resetCourtTransform()
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
            scoreboardZoom = scoreboardZoom(),
            protocol = BroadcastProtocol.entries[binding.broadcastProtocol.selectedItemPosition],
            videoCodec = VideoCodec.entries[binding.videoCodec.selectedItemPosition],
            bitratePreset = BitratePreset.entries[binding.bitratePreset.selectedItemPosition],
            youtubeServerUrl = binding.youtubeServer.text.toString().trim().removeSuffix("/"),
            youtubeStreamKey = binding.youtubeKey.text.toString().trim(),
            youtubeOAuthClientId = binding.youtubeClientId.text.toString().trim(),
            youtubeOAuthClientSecret = binding.youtubeClientSecret.text.toString().trim(),
            liveTitle = binding.liveTitle.text.toString().trim().ifBlank { "CasCam ao vivo" },
            livePrivacy = LivePrivacy.entries[binding.livePrivacy.selectedItemPosition],
        )
    }

    private fun saveConfiguration() = store.save(readForm())

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
                    configuration.livePrivacy.apiValue, configuration.protocol,
                )
            }.onSuccess { ingestion ->
                runOnUiThread {
                    val server = if (configuration.protocol == BroadcastProtocol.RTMPS) {
                        ingestion.serverUrl.replace("rtmp://a.rtmp.youtube.com", "rtmps://a.rtmps.youtube.com")
                    } else ingestion.serverUrl
                    binding.youtubeServer.setText(server)
                    binding.youtubeKey.setText(ingestion.streamKey)
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
        publisher = YoutubePublisher(configuration.protocol, configuration.videoCodec, bitrate, configuration.youtubeServerUrl, configuration.youtubeStreamKey) { status ->
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
            binding.composedOutput.onComposedFrame = active::offer
            active.start()
        }
        binding.startButton.text = "■ ENCERRAR TRANSMISSÃO"
    }

    private fun stopBroadcast() {
        val active = publisher ?: return
        publisher = null
        binding.composedOutput.onComposedFrame = null
        active.close()
        stopService(Intent(this, BroadcastService::class.java))
        binding.startButton.text = "▶ INICIAR TRANSMISSÃO"
    }

    private fun scoreboardZoom() = 1f + binding.scoreboardZoom.progress / 10f
    private fun updateZoomLabels() {
        binding.scoreboardZoomStatus.text = "Zoom: %.1f×".format(scoreboardZoom())
        binding.courtCropStatus.text = "Zoom do recorte: %.1f×".format(binding.compositionOverlay.crop().first)
    }
    private fun applyScoreboardZoom() {
        if (screen != Screen.COURT) boundCamera?.let { camera ->
            val maximum = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: scoreboardZoom()
            camera.cameraControl.setZoomRatio(scoreboardZoom().coerceAtMost(maximum))
        }
    }

    @ExperimentalCamera2Interop
    private fun showCapabilities() {
        val logicalPairs = capabilities.concurrentPairs
            .map { pair -> pair.sorted().joinToString(" + ") }
            .sorted()
        val physicalGroups = capabilities.cameras
            .filter { it.physicalCameraId != null }
            .groupBy { it.logicalCameraId }
            .mapNotNull { (logical, cameras) ->
                cameras.takeIf { it.size > 1 }?.joinToString(prefix = "$logical: ", separator = ", ") { it.id }
            }
        val platformFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        val camera2Summary = buildString {
            append("${capabilities.cameras.size} opções detectadas")
            append("\nFEATURE_CAMERA_CONCURRENT: ${if (platformFeature) "sim" else "não"}")
            append(if (logicalPairs.isEmpty()) "\nPares simultâneos declarados: nenhum" else "\nPares simultâneos declarados: ${logicalPairs.joinToString("; ")}")
            if (physicalGroups.isNotEmpty()) {
                append("\nSensores físicos por câmera lógica: ${physicalGroups.joinToString("; ")}")
                append("\nSensores do mesmo grupo só funcionam juntos se o HAL aceitar dois fluxos físicos.")
            }
        }
        binding.cameraStatus.text = camera2Summary
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraXGroups = future.get().availableConcurrentCameraInfos.map { group ->
                group.map { Camera2CameraInfo.from(it).cameraId }.sorted().joinToString(" + ")
            }.distinct().sorted()
            binding.cameraStatus.text = camera2Summary + if (cameraXGroups.isEmpty()) {
                "\nPares oferecidos pelo CameraX: nenhum"
            } else {
                "\nPares oferecidos pelo CameraX: ${cameraXGroups.joinToString("; ")}"
            }
        }, ContextCompat.getMainExecutor(this))
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
            camera?.physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
            val preview = previewBuilder.build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, selector, preview)
            applyScoreboardZoom()
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
                applyScoreboardZoom()
            } catch (error: RuntimeException) {
                provider.unbindAll()
                boundCamera = provider.bindToLifecycle(broadcastLifecycle, courtSelector, courtAnalysis)
                val reason = generateSequence<Throwable>(error) { it.cause }.last()
                    .let { "${it.javaClass.simpleName}: ${it.message}" }
                binding.broadcastStatus.text = "Incompatível com duas fontes ($sourceDescription): $reason. Somente quadra."
            }
        }, ContextCompat.getMainExecutor(this))
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
}
