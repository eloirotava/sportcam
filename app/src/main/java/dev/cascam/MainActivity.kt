package dev.cascam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
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
import dev.cascam.camera.MultiCameraEngine
import dev.cascam.camera.PhotoScoreboardProbe
import dev.cascam.camera.ThermalPhotoPolicy
import dev.cascam.camera.ThermalPressure
import dev.cascam.config.CompositionEngine
import dev.cascam.config.FrameRotation
import dev.cascam.config.OverlayLayer
import dev.cascam.config.OutputResolution
import dev.cascam.config.OverlaySource
import dev.cascam.gl.GlCompositor
import android.view.SurfaceHolder
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.BroadcastProtocol
import dev.cascam.config.VideoCodec
import dev.cascam.config.BitratePreset
import dev.cascam.config.LiveLatency
import dev.cascam.config.LivePrivacy
import dev.cascam.config.PHOTO_INTERVAL_OPTIONS_MILLIS
import dev.cascam.youtube.YoutubeLiveApi
import dev.cascam.config.BroadcastConfigurationStore
import dev.cascam.databinding.ActivityMainBinding
import dev.cascam.ui.CompositionOverlayView
import dev.cascam.ui.YuvToBitmapConverter
import dev.cascam.geometry.CompositionResolution
import dev.cascam.geometry.WhiteTransparency
import dev.cascam.stream.YoutubePublisher
import dev.cascam.remote.HttpServer
import dev.cascam.remote.RemoteBridge
import dev.cascam.remote.RemoteRoutes
import dev.cascam.tunnel.CfpTunnel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.atan2
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private enum class Screen { BROADCAST, YOUTUBE, VIDEO, COURT, SCOREBOARD, CLOCK, LOGO, DIAGNOSTICS, REMOTE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var capabilities: CameraCapabilities
    private lateinit var store: BroadcastConfigurationStore
    private var cameraIds: List<String> = emptyList()
    private var captureSizeOptions: List<Pair<String, Pair<Int, Int>>> = listOf("Automática" to (0 to 0))
    private var captureFpsOptions: List<Int> = listOf(0)
    private var scoreboardCaptureSizeOptions: List<Pair<String, Pair<Int, Int>>> = listOf("Automática" to (0 to 0))
    private var scoreboardCaptureFpsOptions: List<Int> = listOf(0)
    private var clockCaptureSizeOptions: List<Pair<String, Pair<Int, Int>>> = listOf("Automática" to (0 to 0))
    private var clockCaptureFpsOptions: List<Int> = listOf(0)
    private var updatingCaptureOptions = false
    private var screen = Screen.BROADCAST
    private var boundCamera: Camera? = null
    private val courtAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val scoreboardAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val clockAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val courtFrameSignature = AtomicLong()
    private val repeatedFrameCount = AtomicInteger()
    private val distinctSourcesConfirmed = AtomicBoolean()
    private var publisher: YoutubePublisher? = null
    private lateinit var youtubeApi: YoutubeLiveApi
    private var deviceAuthorization: YoutubeLiveApi.DeviceAuthorization? = null
    private var probe: DualCameraProbe? = null
    private var probeReport: String = ""
    private var photoProbe: PhotoScoreboardProbe? = null
    private var photoProbeReport: String = ""
    private var ingestion: YoutubeLiveApi.Ingestion? = null
    private var dualCameraEngine: DualCameraEngine? = null
    private var multiCameraEngine: MultiCameraEngine? = null
    private var compositor: GlCompositor? = null
    private var encoderSurface: Surface? = null
    @Volatile private var compositionConfiguration = BroadcastConfiguration()
    @Volatile private var auxiliaryCameraId = ""
    private var sensorManager: SensorManager? = null
    private var scoreboardPanX = 0f
    private var scoreboardPanY = 0f
    private var clockPanX = 0f
    private var clockPanY = 0f
    private var logoUri = ""
    private var originalLogoBitmap: Bitmap? = null
    private var logoBitmap: Bitmap? = null
    private val broadcastLifecycle = BroadcastLifecycleOwner()
    private var powerManager: PowerManager? = null
    private var httpServer: HttpServer? = null
    private var tunnel: CfpTunnel? = null
    /** O que a tela Remoto mostra; também vai para o /api/status do site. */
    @Volatile private var remoteStatus = "Acesso remoto desligado."
    private var appliedRemoteSignature: String? = null
    private val remoteConfigurationListener: (BroadcastConfiguration) -> Unit = { configuration ->
        runOnUiThread {
            configureForm(configuration)
            applyCompositionConfiguration()
            applyRemoteConfiguration(configuration)
        }
    }
    @Volatile private var thermalPhotoMinimumIntervalMillis = 0L
    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status -> applyThermalStatus(status) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true || hasCameraPermission()) showScreen(screen)
        else toast("Permissão da câmera necessária")
    }

    private val logoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        logoUri = uri.toString()
        binding.logoEnabled.isChecked = true
        loadLogo(logoUri)
        applyCompositionConfiguration()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        broadcastLifecycle.start()
        store = BroadcastConfigurationStore(this)
        youtubeApi = YoutubeLiveApi(this)
        capabilities = CameraCapabilitiesReader.read(this)
        val configuration = store.load()
        configureForm(configuration)
        configureActions()
        startThermalMonitoring()
        store.addListener(remoteConfigurationListener)
        applyRemoteConfiguration(configuration)
        showScreen(Screen.BROADCAST)
        requestCameraIfNeeded()
    }

    private fun configureForm(configuration: BroadcastConfiguration) {
        compositionConfiguration = configuration
        cameraIds = capabilities.cameras.map { it.id }
        // Ângulo em vez de focal: milímetro de sensor de celular não diz nada sem a equivalência de
        // 35 mm, e o que interessa aqui é quanto de quadra cabe no quadro.
        val labels = capabilities.cameras.map {
            "${if (it.physicalCameraId == null) "Lógica" else "Física"} ${it.id} · ${it.lensFacing.label} · ${it.lensLabel}"
        }
        binding.courtCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.scoreboardCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.clockCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.courtCamera.setSelection(cameraIds.indexOf(configuration.courtCameraId).takeIf { it >= 0 } ?: 0)
        binding.scoreboardCamera.setSelection(cameraIds.indexOf(configuration.scoreboardCameraId).takeIf { it >= 0 } ?: 0)
        binding.clockCamera.setSelection(cameraIds.indexOf(configuration.cameraIdFor(OverlayLayer.CLOCK)).takeIf { it >= 0 } ?: 0)
        binding.scoreboardEnabled.isChecked = configuration.scoreboardEnabled
        binding.scoreboardSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, OverlaySource.entries.map { it.label })
        binding.scoreboardSource.setSelection(OverlaySource.entries.indexOf(configuration.scoreboardSource))
        binding.clockSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, OverlaySource.entries.map { it.label })
        binding.clockSource.setSelection(OverlaySource.entries.indexOf(configuration.clockSource))
        binding.clockPhotoInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            PHOTO_INTERVAL_OPTIONS_MILLIS.map(::photoIntervalLabel),
        )
        binding.clockPhotoInterval.setSelection(
            PHOTO_INTERVAL_OPTIONS_MILLIS.indexOf(configuration.clockPhotoIntervalMillis).coerceAtLeast(0),
        )
        binding.scoreboardPhotoInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            PHOTO_INTERVAL_OPTIONS_MILLIS.map(::photoIntervalLabel),
        )
        binding.scoreboardPhotoInterval.setSelection(
            PHOTO_INTERVAL_OPTIONS_MILLIS.indexOf(configuration.scoreboardPhotoIntervalMillis).coerceAtLeast(0),
        )
        binding.clockEnabled.isChecked = configuration.clockEnabled
        logoUri = configuration.logoUri
        binding.logoEnabled.isChecked = configuration.logoEnabled
        binding.logoWhiteTransparent.isChecked = configuration.logoWhiteTransparent
        binding.logoSize.progress = (configuration.logoWidth * 100f).toInt()
        binding.logoPositionX.progress = (configuration.logoCenterX * 100f).toInt()
        binding.logoPositionY.progress = (configuration.logoCenterY * 100f).toInt()
        loadLogo(logoUri)
        binding.courtOverlay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, OverlayLayer.entries.map { it.label })
        updateCaptureOptions(configuration.courtCameraId, null, configuration.captureWidth to configuration.captureHeight, configuration.captureFps)
        updateCaptureOptions(configuration.cameraIdFor(OverlayLayer.SCOREBOARD), OverlayLayer.SCOREBOARD, configuration.scoreboardCaptureWidth to configuration.scoreboardCaptureHeight, configuration.scoreboardCaptureFps)
        updateCaptureOptions(configuration.cameraIdFor(OverlayLayer.CLOCK), OverlayLayer.CLOCK, configuration.clockCaptureWidth to configuration.clockCaptureHeight, configuration.clockCaptureFps)
        binding.broadcastProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, BroadcastProtocol.entries.map { it.label })
        binding.broadcastProtocol.setSelection(BroadcastProtocol.entries.indexOf(configuration.protocol))
        binding.videoCodec.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VideoCodec.entries.map { it.label })
        binding.videoCodec.setSelection(VideoCodec.entries.indexOf(configuration.videoCodec))
        binding.videoCodec.isEnabled = configuration.protocol == BroadcastProtocol.HLS
        binding.outputResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, OutputResolution.entries.map { it.label })
        binding.outputResolution.setSelection(OutputResolution.entries.indexOf(configuration.outputResolution))
        binding.outputFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, OUTPUT_FPS_OPTIONS.map { "$it fps" })
        binding.outputFps.setSelection(OUTPUT_FPS_OPTIONS.indexOf(configuration.outputFps).coerceAtLeast(0))
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
        binding.compositionOverlay.setClockCorners(configuration.clockCorners)
        binding.compositionOverlay.setClockDestination(configuration.clockDestination)
        binding.compositionOverlay.setEnabledOverlays(configuration.scoreboardEnabled, configuration.clockEnabled)
        binding.remoteEnabled.isChecked = configuration.remoteEnabled
        binding.remotePort.setText(configuration.remotePort.toString())
        binding.remoteUser.setText(configuration.remoteUser)
        binding.remotePassword.setText(configuration.remotePassword)
        binding.tunnelUrl.setText(configuration.tunnelUrl)
        binding.tunnelToken.setText(configuration.tunnelToken)
        binding.remoteStatus.text = remoteStatus
        updateZoomLabels()
        updateLogoLabels()
        updateOutputQualityHint()
    }

    private fun configureActions() {
        binding.navBroadcast.setOnClickListener { showScreen(Screen.BROADCAST) }
        binding.navYoutube.setOnClickListener { showScreen(Screen.YOUTUBE) }
        binding.navVideo.setOnClickListener { showScreen(Screen.VIDEO) }
        binding.navCourt.setOnClickListener { showScreen(Screen.COURT) }
        binding.navScoreboard.setOnClickListener { showScreen(Screen.SCOREBOARD) }
        binding.navClock.setOnClickListener { showScreen(Screen.CLOCK) }
        binding.navLogo.setOnClickListener { showScreen(Screen.LOGO) }
        binding.navDiagnostics.setOnClickListener { showScreen(Screen.DIAGNOSTICS) }
        binding.navRemote.setOnClickListener { showScreen(Screen.REMOTE) }
        binding.saveRemote.setOnClickListener {
            saveConfiguration()
            // Force: quem apertou o botão está justamente tentando de novo depois de um erro.
            applyRemoteConfiguration(compositionConfiguration, force = true)
            toast("Acesso remoto salvo")
        }
        binding.tunnelUrl.setOnFocusChangeListener { _, focused -> if (!focused) splitPastedTunnelCommand() }
        binding.runProbe.setOnClickListener { toggleProbe() }
        binding.copyProbeReport.setOnClickListener { copyProbeReport() }
        binding.runPhotoProbe.setOnClickListener { togglePhotoProbe() }
        binding.copyPhotoProbeReport.setOnClickListener { copyPhotoProbeReport() }
        binding.saveButton.setOnClickListener { saveConfiguration(); toast("Configuração salva") }
        binding.startButton.setOnClickListener { toggleBroadcast() }
        binding.privacyButton.setOnClickListener { showPrivacyNotice() }
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
        binding.selectLogo.setOnClickListener { logoPicker.launch(arrayOf("image/png", "image/*")) }
        binding.removeLogo.setOnClickListener {
            logoUri = ""
            binding.logoEnabled.isChecked = false
            setOriginalLogoBitmap(null)
            updateLogoLabels()
            applyCompositionConfiguration()
        }
        binding.saveLogo.setOnClickListener { saveConfiguration(); toast("Ícone salvo") }
        binding.saveVideo.setOnClickListener { saveConfiguration(); toast("Saída salva") }
        binding.saveYoutube.setOnClickListener { saveConfiguration(); toast("Conta do YouTube salva") }
        binding.logoEnabled.setOnClickListener { applyCompositionConfiguration() }
        binding.logoWhiteTransparent.setOnClickListener {
            refreshLogoBitmap()
            applyCompositionConfiguration()
        }
        listOf(binding.logoSize, binding.logoPositionX, binding.logoPositionY).forEach { seekBar ->
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateLogoLabels()
                    if (fromUser) applyCompositionConfiguration()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val outputListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateOutputQualityHint()
                applyCompositionConfiguration()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.outputResolution.onItemSelectedListener = outputListener
        binding.outputFps.onItemSelectedListener = outputListener
        binding.bitratePreset.onItemSelectedListener = outputListener
        binding.cropLarger.setOnClickListener { binding.compositionOverlay.changeCropZoom(-.25f) }
        binding.cropSmaller.setOnClickListener { binding.compositionOverlay.changeCropZoom(.25f) }
        binding.compositionOverlay.onCropChanged = { _, _, _ ->
            updateZoomLabels()
            applyCompositionConfiguration()
        }
        binding.compositionOverlay.onPanRequested = { dx, dy -> panScoreboardView(dx, dy) }
        binding.courtOverlay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.compositionOverlay.selectDestination(OverlayLayer.entries[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val enabledChanged = View.OnClickListener {
            binding.compositionOverlay.setEnabledOverlays(binding.scoreboardEnabled.isChecked, binding.clockEnabled.isChecked)
            updateAllCaptureOptions()
            updateOverlaySourceHints()
            applyCompositionConfiguration()
        }
        binding.scoreboardEnabled.setOnClickListener(enabledChanged)
        binding.clockEnabled.setOnClickListener(enabledChanged)
        val overlaySourceListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateOverlaySourceHints()
                applyCompositionConfiguration()
                if (screen == Screen.BROADCAST && publisher == null) showScreen(Screen.BROADCAST)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        listOf(
            binding.scoreboardSource, binding.scoreboardPhotoInterval,
            binding.clockSource, binding.clockPhotoInterval,
        ).forEach { it.onItemSelectedListener = overlaySourceListener }

        binding.scoreboardViewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyViewZoom()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.clockViewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyViewZoom()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.courtViewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyViewZoom()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val cameraListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAllCaptureOptions()
                updateOverlaySourceHints()
                if (screen != Screen.BROADCAST && screen != Screen.VIDEO && screen != Screen.LOGO) startCamera(cameraIdFor(screen))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.courtCamera.onItemSelectedListener = cameraListener
        binding.scoreboardCamera.onItemSelectedListener = cameraListener
        binding.clockCamera.onItemSelectedListener = cameraListener
        updateOverlaySourceHints()
        val captureListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingCaptureOptions && screen == Screen.BROADCAST && publisher == null) showScreen(Screen.BROADCAST)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.captureResolution.onItemSelectedListener = captureListener
        binding.captureFps.onItemSelectedListener = captureListener
        binding.scoreboardCaptureResolution.onItemSelectedListener = captureListener
        binding.scoreboardCaptureFps.onItemSelectedListener = captureListener
        binding.clockCaptureResolution.onItemSelectedListener = captureListener
        binding.clockCaptureFps.onItemSelectedListener = captureListener
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

    /**
     * Marca a aba aberta. Antes não havia estado nenhum: a tela ativa só era reconhecível pelo
     * painel que aparecia embaixo, o que com ícone no lugar do texto viraria adivinhação.
     */
    private fun markSelectedTab(target: Screen) {
        mapOf(
            Screen.BROADCAST to binding.navBroadcast,
            Screen.YOUTUBE to binding.navYoutube,
            Screen.VIDEO to binding.navVideo,
            Screen.COURT to binding.navCourt,
            Screen.SCOREBOARD to binding.navScoreboard,
            Screen.CLOCK to binding.navClock,
            Screen.LOGO to binding.navLogo,
            Screen.DIAGNOSTICS to binding.navDiagnostics,
            Screen.REMOTE to binding.navRemote,
        ).forEach { (screen, tab) -> tab.isSelected = screen == target }
    }

    private fun showScreen(target: Screen) {
        if (target != Screen.BROADCAST && publisher != null) stopBroadcast()
        if (target != Screen.BROADCAST) { dualCameraEngine?.stop(); multiCameraEngine?.stop(); releaseCompositor() }
        if (target != Screen.DIAGNOSTICS) { stopProbe(); stopPhotoProbe() }
        if (target == Screen.COURT) startLevelSensor() else stopLevelSensor()
        screen = target
        binding.panelBroadcast.visibility = if (target == Screen.BROADCAST) View.VISIBLE else View.GONE
        binding.panelYoutube.visibility = if (target == Screen.YOUTUBE) View.VISIBLE else View.GONE
        binding.panelVideo.visibility = if (target == Screen.VIDEO) View.VISIBLE else View.GONE
        binding.panelCourt.visibility = if (target == Screen.COURT) View.VISIBLE else View.GONE
        binding.panelScoreboard.visibility = if (target == Screen.SCOREBOARD) View.VISIBLE else View.GONE
        binding.panelClock.visibility = if (target == Screen.CLOCK) View.VISIBLE else View.GONE
        binding.panelLogo.visibility = if (target == Screen.LOGO) View.VISIBLE else View.GONE
        binding.panelDiagnostics.visibility = if (target == Screen.DIAGNOSTICS) View.VISIBLE else View.GONE
        binding.panelRemote.visibility = if (target == Screen.REMOTE) View.VISIBLE else View.GONE
        markSelectedTab(target)
        when (target) {
            Screen.BROADCAST -> CompositionOverlayView.Mode.COMPOSITION
            Screen.YOUTUBE -> CompositionOverlayView.Mode.COMPOSITION
            Screen.VIDEO -> CompositionOverlayView.Mode.COMPOSITION
            Screen.COURT -> CompositionOverlayView.Mode.COURT
            Screen.SCOREBOARD -> CompositionOverlayView.Mode.SCOREBOARD
            Screen.CLOCK -> CompositionOverlayView.Mode.CLOCK
            Screen.LOGO -> CompositionOverlayView.Mode.COMPOSITION
            // A tela Remoto mantém a composição rodando de propósito: é ela que alimenta as
            // prévias do site, e é nela que o telefone fica quando o ajuste é feito de longe.
            Screen.REMOTE -> CompositionOverlayView.Mode.COMPOSITION
            Screen.DIAGNOSTICS -> null
        }?.let(binding.compositionOverlay::setMode)
        binding.compositionOverlay.visibility = if (target == Screen.DIAGNOSTICS || target == Screen.LOGO || target == Screen.VIDEO || target == Screen.YOUTUBE || target == Screen.REMOTE) View.GONE else View.VISIBLE
        val gpuComposition = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition] == CompositionEngine.GPU
        val compositionScreen = target == Screen.BROADCAST || target == Screen.LOGO || target == Screen.VIDEO || target == Screen.YOUTUBE || target == Screen.REMOTE
        binding.composedOutput.visibility = if (compositionScreen && !gpuComposition) View.VISIBLE else View.GONE
        binding.gpuOutput.visibility = if (compositionScreen && gpuComposition) View.VISIBLE else View.GONE
        binding.preview.visibility = if (compositionScreen || target == Screen.DIAGNOSTICS) View.GONE else View.VISIBLE
        binding.scoreboardPreviewContainer.visibility = View.GONE
        resetCourtTransform()
        applyViewZoom()
        if (hasCameraPermission()) when (target) {
            Screen.BROADCAST -> startCompositionPreview()
            Screen.YOUTUBE -> startCompositionPreview()
            Screen.VIDEO -> startCompositionPreview()
            Screen.LOGO -> startCompositionPreview()
            Screen.REMOTE -> startCompositionPreview()
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
        Screen.BROADCAST, Screen.YOUTUBE, Screen.VIDEO, Screen.COURT, Screen.LOGO, Screen.REMOTE ->
            cameraIds.getOrNull(binding.courtCamera.selectedItemPosition)
        Screen.SCOREBOARD -> cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition)
        Screen.CLOCK -> cameraIds.getOrNull(binding.clockCamera.selectedItemPosition)
        Screen.DIAGNOSTICS -> null
    }

    private fun updateAllCaptureOptions() {
        val configuration = readForm()
        updatingCaptureOptions = true
        try {
            updateCaptureOptions(configuration.courtCameraId, null)
            updateCaptureOptions(configuration.cameraIdFor(OverlayLayer.SCOREBOARD), OverlayLayer.SCOREBOARD)
            updateCaptureOptions(configuration.cameraIdFor(OverlayLayer.CLOCK), OverlayLayer.CLOCK)
        } finally {
            updatingCaptureOptions = false
        }
    }

    private fun updateCaptureOptions(
        cameraId: String,
        layer: OverlayLayer?,
        selectedSize: Pair<Int, Int>? = null,
        selectedFps: Int? = null,
    ) {
        val camera = capabilities.camera(cameraId)
        // Nenhum tamanho é escondido: o aparelho lista, a etiqueta informa o custo em taxa e a
        // escolha é do operador. É o mesmo app rodando em S22 e em S25 Ultra, com sensores
        // diferentes; qualquer teto fixo aqui seria chute sobre o aparelho do outro.
        val sizes = listOf("Automática" to (0 to 0)) + camera?.captureSizes.orEmpty().map {
            it.label to (it.size.width to it.size.height)
        }
        val fpsValues = listOf(0) + camera?.fpsRanges.orEmpty().map { it.upper }.distinct().sorted()
        val (resolutionSpinner, fpsSpinner) = when (layer) {
            null -> binding.captureResolution to binding.captureFps
            OverlayLayer.SCOREBOARD -> binding.scoreboardCaptureResolution to binding.scoreboardCaptureFps
            OverlayLayer.CLOCK -> binding.clockCaptureResolution to binding.clockCaptureFps
        }
        val oldSizes = when (layer) { null -> captureSizeOptions; OverlayLayer.SCOREBOARD -> scoreboardCaptureSizeOptions; OverlayLayer.CLOCK -> clockCaptureSizeOptions }
        val oldFps = when (layer) { null -> captureFpsOptions; OverlayLayer.SCOREBOARD -> scoreboardCaptureFpsOptions; OverlayLayer.CLOCK -> clockCaptureFpsOptions }
        val preservedSize = selectedSize ?: oldSizes.getOrNull(resolutionSpinner.selectedItemPosition)?.second ?: (0 to 0)
        val preservedFps = selectedFps ?: oldFps.getOrElse(fpsSpinner.selectedItemPosition) { 0 }
        when (layer) {
            null -> { captureSizeOptions = sizes; captureFpsOptions = fpsValues }
            OverlayLayer.SCOREBOARD -> { scoreboardCaptureSizeOptions = sizes; scoreboardCaptureFpsOptions = fpsValues }
            OverlayLayer.CLOCK -> { clockCaptureSizeOptions = sizes; clockCaptureFpsOptions = fpsValues }
        }
        resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizes.map { it.first })
        resolutionSpinner.setSelection(sizes.indexOfFirst { it.second == preservedSize }.coerceAtLeast(0))
        fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsValues.map {
            if (it == 0) if (layer == null) "Seguir saída" else "Automático" else "$it fps"
        })
        fpsSpinner.setSelection(fpsValues.indexOf(preservedFps).coerceAtLeast(0))
    }

    /**
     * O automático perdeu o teto de 1080p e ganhou um critério em vez dele: o menor tamanho que
     * ainda entrega pixel real ao enquadramento atual, entre os que sustentam a taxa pedida.
     *
     * Teto fixo era chute sobre o aparelho dos outros — 1080p foi o que um S22 aprovou, e um S25
     * Ultra herdaria o limite à toa. Já "o maior que couber" gastaria calor com pixel que o recorte
     * descarta. Recortar 2× a quadra para sair em 720p precisa de 3200 px de largura; abaixo disso a
     * imagem estica, acima disso não melhora. Quem quiser outro valor escolhe na lista, que continua
     * mostrando tudo o que o aparelho oferece.
     */
    private fun automaticCaptureSize(
        configuration: BroadcastConfiguration,
        cameraId: String,
        targetFps: Int,
    ): android.util.Size {
        val camera = capabilities.camera(cameraId) ?: return CameraXSupport.DEFAULT_ANALYSIS_SIZE
        val ascending = camera.yuvSizes.sortedBy { it.width.toLong() * it.height }
        val sustained = ascending.filter { camera.sustains(it, targetFps) }.ifEmpty { ascending }
        val rotation = rotationFor(camera.logicalCameraId)
        return sustained.firstOrNull { size ->
            val required = requiredCaptureWidth(configuration, cameraId, size, rotation)
            required in 1..size.width
        } ?: sustained.lastOrNull() ?: CameraXSupport.DEFAULT_ANALYSIS_SIZE
    }

    private fun captureSizeFor(configuration: BroadcastConfiguration, cameraId: String): android.util.Size {
        val settings = configuration.resolvedCaptureSettings(cameraId)
        return if (settings.hasSize) android.util.Size(settings.width, settings.height)
        else automaticCaptureSize(configuration, cameraId, settings.fps.takeIf { it > 0 } ?: configuration.outputFps)
    }

    private fun readForm(): BroadcastConfiguration {
        val (cropZoom, cropPanX, cropPanY) = binding.compositionOverlay.crop()
        val captureSize = captureSizeOptions.getOrNull(binding.captureResolution.selectedItemPosition)?.second ?: (0 to 0)
        val scoreboardCaptureSize = scoreboardCaptureSizeOptions.getOrNull(binding.scoreboardCaptureResolution.selectedItemPosition)?.second ?: (0 to 0)
        val clockCaptureSize = clockCaptureSizeOptions.getOrNull(binding.clockCaptureResolution.selectedItemPosition)?.second ?: (0 to 0)
        return BroadcastConfiguration(
            courtCameraId = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty(),
            scoreboardCameraId = cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition).orEmpty(),
            scoreboardEnabled = binding.scoreboardEnabled.isChecked,
            scoreboardSource = OverlaySource.entries.getOrElse(binding.scoreboardSource.selectedItemPosition) { OverlaySource.VIDEO },
            scoreboardPhotoIntervalMillis = PHOTO_INTERVAL_OPTIONS_MILLIS.getOrElse(
                binding.scoreboardPhotoInterval.selectedItemPosition,
            ) { 1_000L },
            cropZoom = cropZoom, cropPanX = cropPanX, cropPanY = cropPanY,
            scoreboardCorners = binding.compositionOverlay.scoreboardCorners(),
            scoreboardDestination = binding.compositionOverlay.scoreboardDestination(),
            clockCameraId = cameraIds.getOrNull(binding.clockCamera.selectedItemPosition).orEmpty(),
            clockEnabled = binding.clockEnabled.isChecked,
            clockSource = OverlaySource.entries.getOrElse(binding.clockSource.selectedItemPosition) { OverlaySource.VIDEO },
            clockPhotoIntervalMillis = PHOTO_INTERVAL_OPTIONS_MILLIS.getOrElse(
                binding.clockPhotoInterval.selectedItemPosition,
            ) { 1_000L },
            clockCorners = binding.compositionOverlay.clockCorners(),
            clockDestination = binding.compositionOverlay.clockDestination(),
            captureWidth = captureSize.first,
            captureHeight = captureSize.second,
            captureFps = captureFpsOptions.getOrElse(binding.captureFps.selectedItemPosition) { 0 },
            scoreboardCaptureWidth = scoreboardCaptureSize.first,
            scoreboardCaptureHeight = scoreboardCaptureSize.second,
            scoreboardCaptureFps = scoreboardCaptureFpsOptions.getOrElse(binding.scoreboardCaptureFps.selectedItemPosition) { 0 },
            clockCaptureWidth = clockCaptureSize.first,
            clockCaptureHeight = clockCaptureSize.second,
            clockCaptureFps = clockCaptureFpsOptions.getOrElse(binding.clockCaptureFps.selectedItemPosition) { 0 },
            logoUri = logoUri,
            logoEnabled = binding.logoEnabled.isChecked && logoBitmap != null,
            logoWhiteTransparent = binding.logoWhiteTransparent.isChecked,
            logoWidth = (binding.logoSize.progress.coerceIn(5, 50) / 100f),
            logoCenterX = binding.logoPositionX.progress.coerceIn(0, 100) / 100f,
            logoCenterY = binding.logoPositionY.progress.coerceIn(0, 100) / 100f,
            protocol = BroadcastProtocol.entries[binding.broadcastProtocol.selectedItemPosition],
            videoCodec = VideoCodec.entries[binding.videoCodec.selectedItemPosition],
            outputResolution = OutputResolution.entries[binding.outputResolution.selectedItemPosition],
            outputFps = OUTPUT_FPS_OPTIONS.getOrElse(binding.outputFps.selectedItemPosition) { 20 },
            bitratePreset = BitratePreset.entries[binding.bitratePreset.selectedItemPosition],
            youtubeServerUrl = binding.youtubeServer.text.toString().trim().removeSuffix("/"),
            youtubeStreamKey = binding.youtubeKey.text.toString().trim(),
            youtubeOAuthClientId = binding.youtubeClientId.text.toString().trim(),
            youtubeOAuthClientSecret = binding.youtubeClientSecret.text.toString().trim(),
            liveTitle = binding.liveTitle.text.toString().trim().ifBlank { "SportCam ao vivo" },
            livePrivacy = LivePrivacy.entries[binding.livePrivacy.selectedItemPosition],
            liveLatency = LiveLatency.entries[binding.liveLatency.selectedItemPosition],
            compositionEngine = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition],
            frameRotation = FrameRotation.entries[binding.frameRotation.selectedItemPosition],
            remoteEnabled = binding.remoteEnabled.isChecked,
            remotePort = binding.remotePort.text.toString().trim().toIntOrNull()?.takeIf { it in 1024..65535 } ?: 8080,
            remoteUser = binding.remoteUser.text.toString().trim().ifBlank { "sportcam" },
            remotePassword = binding.remotePassword.text.toString(),
            tunnelUrl = binding.tunnelUrl.text.toString().trim(),
            tunnelToken = binding.tunnelToken.text.toString().trim(),
        )
    }

    private fun applyCompositionConfiguration() {
        val configuration = readForm()
        compositionConfiguration = configuration
        binding.composedOutput.configure(configuration)
        compositor?.configure(configuration)
    }

    private fun loadLogo(encodedUri: String) {
        if (encodedUri.isBlank()) {
            setOriginalLogoBitmap(null)
            updateLogoLabels()
            return
        }
        runCatching {
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(encodedUri))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > 2048) {
                    val scale = 2048f / largest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        }.onSuccess(::setOriginalLogoBitmap).onFailure {
            setOriginalLogoBitmap(null)
            binding.logoFileStatus.text = "Não consegui abrir a imagem selecionada. Escolha o arquivo novamente."
        }
        updateLogoLabels()
    }

    private fun setOriginalLogoBitmap(bitmap: Bitmap?) {
        originalLogoBitmap = bitmap
        refreshLogoBitmap()
    }

    private fun refreshLogoBitmap() {
        val original = originalLogoBitmap
        val rendered = if (original != null && binding.logoWhiteTransparent.isChecked) {
            val pixels = IntArray(original.width * original.height)
            original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
            for (index in pixels.indices) pixels[index] = WhiteTransparency.applyToColor(pixels[index])
            Bitmap.createBitmap(pixels, original.width, original.height, Bitmap.Config.ARGB_8888)
        } else original
        setLogoBitmap(rendered)
    }

    private fun setLogoBitmap(bitmap: Bitmap?) {
        logoBitmap = bitmap
        binding.logoPreview.setImageBitmap(bitmap)
        binding.composedOutput.setLogo(bitmap)
        compositor?.setLogo(bitmap)
    }

    private fun updateLogoLabels() {
        binding.logoFileStatus.text = when {
            logoBitmap != null -> "Imagem pronta · ${logoBitmap!!.width}×${logoBitmap!!.height} px"
            logoUri.isBlank() -> "Nenhuma imagem selecionada."
            else -> binding.logoFileStatus.text
        }
        binding.removeLogo.isEnabled = logoUri.isNotBlank()
        binding.logoSizeStatus.text = "Largura: ${binding.logoSize.progress.coerceIn(5, 50)}% do vídeo"
        binding.logoPositionStatus.text = "Posição: ${binding.logoPositionX.progress}% horizontal · ${binding.logoPositionY.progress}% vertical"
    }

    private fun saveConfiguration() {
        val configuration = readForm()
        compositionConfiguration = configuration
        store.save(configuration)
        binding.composedOutput.configure(configuration)
        compositor?.configure(configuration)
    }

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
                    "Sem conexão com o Google (${error.javaClass.simpleName}). Confira se o SportCam pode usar dados em segundo plano: Ajustes › Apps › SportCam › Dados móveis, com Economia de dados desligada ou o app liberado."
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
        compositionConfiguration = configuration
        if (configuration.youtubeOAuthClientId.isBlank()) { binding.youtubeClientId.error = "Informe e autorize o Client ID"; return }
        binding.broadcastStatus.text = "Criando e vinculando a live no YouTube…"
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
                    binding.broadcastStatus.text = "Live criada e vinculada. Iniciando envio…"
                    saveConfiguration(); toggleBroadcast()
                }
            }.onFailure { error -> runOnUiThread { binding.broadcastStatus.text = "Falha ao criar live: ${error.message}" } }
        }.start()
    }

    @ExperimentalCamera2Interop
    private fun toggleProbe() {
        if (probe?.isRunning == true) { stopProbe(); return }
        if (!hasCameraPermission()) { requestCameraIfNeeded(); return }
        stopPhotoProbe()
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico de câmeras SportCam", probeReport))
        toast("Relatório copiado")
    }

    private fun togglePhotoProbe() {
        if (photoProbe?.isRunning == true) { stopPhotoProbe(); return }
        if (!hasCameraPermission()) { requestCameraIfNeeded(); return }
        val court = cameraFor(cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty())
        val scoreboard = cameraFor(cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition).orEmpty())
        if (court == null || scoreboard == null) { toast("Selecione as câmeras da quadra e do placar primeiro"); return }
        stopProbe()
        binding.photoProbeReport.text = ""
        binding.photoProbeStatus.text = "Liberando as câmeras para o teste…"
        photoProbeReport = ""
        binding.copyPhotoProbeReport.isEnabled = false
        binding.runPhotoProbe.text = "■ PARAR TESTE GPU + FOTO"
        val created = PhotoScoreboardProbe(
            manager = getSystemService(CameraManager::class.java),
            onProgress = { message -> runOnUiThread { binding.photoProbeStatus.text = message } },
            onReport = { report ->
                runOnUiThread {
                    photoProbeReport = report
                    binding.photoProbeReport.text = report
                    binding.copyPhotoProbeReport.isEnabled = report.isNotBlank()
                    binding.runPhotoProbe.text = "▶ TESTAR GPU + FOTO"
                    photoProbe = null
                }
            },
        )
        photoProbe = created
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching { future.get().unbindAll() }
            if (photoProbe === created) created.start(court, scoreboard)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPhotoProbe() {
        photoProbe?.shutdown()
        photoProbe = null
        binding.runPhotoProbe.text = "▶ TESTAR GPU + FOTO"
    }

    private fun copyPhotoProbeReport() {
        if (photoProbeReport.isBlank()) { toast("Rode o teste GPU + foto primeiro"); return }
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) { toast("Área de transferência indisponível"); return }
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico GPU + foto SportCam", photoProbeReport))
        toast("Relatório GPU + foto copiado")
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

    private fun viewZoom(): Float = when (screen) {
        Screen.COURT -> 1f + binding.courtViewZoom.progress / 10f
        Screen.SCOREBOARD -> 1f + binding.scoreboardViewZoom.progress / 10f
        Screen.CLOCK -> 1f + binding.clockViewZoom.progress / 10f
        else -> 1f
    }

    /**
     * Ampliação só da visualização, para posicionar com precisão o que ocupa poucos pixels do
     * quadro: os quatro cantos de um placar distante, ou o retângulo 16:9 da quadra num sensor
     * grande. Nada aqui muda a captura nem o que é transmitido — as coordenadas continuam
     * normalizadas, e o Android entrega o toque em coordenadas locais da view, então ampliar a view
     * multiplica a precisão do arrasto sem tocar na matemática do overlay.
     *
     * É esta a lupa que substituiu o antigo zoom digital. Aquele recortava o quadro e era gravado
     * separado das coordenadas, então mexer no slider depois de marcar movia a marcação; este é
     * transformação de tela e não é persistido.
     *
     * O pivô fica no centro da moldura e o deslocamento é livre, porque o alvo quase nunca está no
     * meio do quadro — ampliar em cima de um ponto fixo só resolveria o caso centrado.
     */
    private fun applyViewZoom() {
        val zoom = viewZoom()
        if (zoom <= 1.01f) {
            if (screen == Screen.CLOCK) { clockPanX = 0f; clockPanY = 0f }
            else { scoreboardPanX = 0f; scoreboardPanY = 0f }
        }
        clampScoreboardPan(zoom)
        val panX = if (screen == Screen.CLOCK) clockPanX else scoreboardPanX
        val panY = if (screen == Screen.CLOCK) clockPanY else scoreboardPanY
        listOf<View>(binding.preview, binding.compositionOverlay).forEach { view ->
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
            view.scaleX = zoom
            view.scaleY = zoom
            view.translationX = panX
            view.translationY = panY
        }
        binding.compositionOverlay.setDisplayScale(zoom)
        val label = if (zoom > 1.01f) {
            "Tela: %.1f× · arraste fora da área marcada para deslocar.".format(zoom)
        } else {
            "Tela: 1.0× · amplie para posicionar com precisão. Não muda o que é transmitido."
        }
        binding.scoreboardViewZoomStatus.text = label
        binding.clockViewZoomStatus.text = label
        binding.courtViewZoomStatus.text = label
    }

    /**
     * O arrasto chega em coordenadas locais da view, que já estão divididas pela ampliação; para o
     * conteúdo acompanhar o dedo na tela, o deslocamento volta a ser multiplicado por ela.
     */
    private fun panScoreboardView(dx: Float, dy: Float) {
        val zoom = viewZoom()
        if (zoom <= 1.01f) return
        if (screen == Screen.CLOCK) { clockPanX += dx * zoom; clockPanY += dy * zoom }
        else { scoreboardPanX += dx * zoom; scoreboardPanY += dy * zoom }
        applyViewZoom()
    }

    /** Impede que a imagem seja arrastada para fora da moldura, deixando faixa preta na tela. */
    private fun clampScoreboardPan(zoom: Float) {
        val limitX = (zoom - 1f) * binding.compositionOverlay.width / 2f
        val limitY = (zoom - 1f) * binding.compositionOverlay.height / 2f
        if (screen == Screen.CLOCK) {
            clockPanX = clockPanX.coerceIn(-limitX, limitX)
            clockPanY = clockPanY.coerceIn(-limitY, limitY)
        } else {
            scoreboardPanX = scoreboardPanX.coerceIn(-limitX, limitX)
            scoreboardPanY = scoreboardPanY.coerceIn(-limitY, limitY)
        }
    }

    /**
     * Zera a transformação da prévia ao trocar de tela. O deslocamento da lupa é por tela, e sem
     * este reset a quadra herdaria o arrasto feito no placar — as duas usam o mesmo par de campos.
     */
    private fun resetCourtTransform() {
        binding.preview.scaleX = 1f; binding.preview.scaleY = 1f
        binding.preview.translationX = 0f; binding.preview.translationY = 0f
        scoreboardPanX = 0f; scoreboardPanY = 0f
        clockPanX = 0f; clockPanY = 0f
    }

    private fun validatedBroadcast(): BroadcastConfiguration? {
        val configuration = readForm()
        val selectedCameras = configuration.requiredCameraIds().mapNotNull(capabilities::camera)
        return when {
            selectedCameras.size != configuration.requiredCameraIds().size -> null.also { toast("Uma das câmeras selecionadas não existe mais") }
            !capabilities.supportsSimultaneous(configuration.requiredCameraIds()) -> null.also {
                toast("O aparelho não anunciou captura simultânea para as câmeras escolhidas")
            }
            OverlayLayer.entries.any {
                configuration.sourceFor(it) == OverlaySource.PHOTO_EVERY_SECOND &&
                    configuration.enabled(it) && !configuration.canUsePhoto(it)
            } -> null.also {
                toast("Foto alta exige uma câmera exclusiva para a camada; as outras não podem compartilhar essa câmera")
            }
            OverlayLayer.entries.any { layer ->
                configuration.usesPhoto(layer) &&
                    capabilities.camera(configuration.cameraIdFor(layer))?.maximumJpegSize == null
            } -> null.also {
                toast("A câmera escolhida para foto não anunciou captura JPEG")
            }
            selectedCameras.any { camera -> configuration.resolvedCaptureSettings(camera.id).let { requested ->
                if (configuration.stillIntervalFor(camera.id) > 0L) return@let false
                requested.hasSize && camera.yuvSizes.none { it.width == requested.width && it.height == requested.height }
            } } -> null.also { toast("Uma fonte não suporta a resolução escolhida") }
            selectedCameras.any { camera -> configuration.resolvedCaptureSettings(camera.id).let { requested ->
                if (configuration.stillIntervalFor(camera.id) > 0L) return@let false
                requested.fps > 0 && camera.fpsRanges.none { it.upper == requested.fps }
            } } -> null.also { toast("Uma fonte não suporta o FPS escolhido") }
            configuration.protocol == BroadcastProtocol.RTMPS && !configuration.youtubeServerUrl.startsWith("rtmps://") -> null.also { binding.youtubeServer.error = "Use uma URL RTMPS" }
            configuration.protocol == BroadcastProtocol.HLS && !configuration.youtubeServerUrl.startsWith("https://") -> null.also { binding.youtubeServer.error = "Use a URL HTTPS de ingestão HLS do YouTube Studio" }
            configuration.protocol == BroadcastProtocol.RTMPS && configuration.videoCodec != VideoCodec.H264 -> null.also { toast("RTMPS usa H.264; selecione HLS para H.265") }
            configuration.youtubeStreamKey.isBlank() -> null.also { binding.youtubeKey.error = "Informe a chave do YouTube Studio" }
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> null.also {
                toast("Autorize o microfone para transmitir com áudio")
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            else -> configuration.also { warnAboutSustainedRate(it, selectedCameras) }
        }
    }

    /**
     * Aviso, não bloqueio. O aparelho declara até onde cada tamanho sustenta taxa, mas declaração
     * erra nos dois sentidos e quem confere é o jogo. Então o app avisa e transmite mesmo assim; a
     * taxa medida de verdade aparece no status durante a captura.
     */
    private fun warnAboutSustainedRate(configuration: BroadcastConfiguration, cameras: List<CameraInfo>) {
        val target = configuration.captureFps.takeIf { it > 0 } ?: configuration.outputFps
        val declared = cameras.mapNotNull { camera ->
            if (configuration.stillIntervalFor(camera.id) > 0L) return@mapNotNull null
            val requested = configuration.resolvedCaptureSettings(camera.id)
            if (!requested.hasSize) return@mapNotNull null
            val size = android.util.Size(requested.width, requested.height)
            camera.maxFpsFor(size).takeIf { it in 1 until target }
        }
        val worst = declared.minOrNull() ?: return
        toast("Nesta resolução o aparelho declara cerca de $worst fps, e a captura pediu $target fps")
    }

    private fun toggleBroadcast() {
        if (publisher != null) {
            stopBroadcast()
            return
        }
        val configuration = validatedBroadcast() ?: return
        val bitrate = configuration.bitratePreset.bitsPerSecond
            ?: configuration.outputResolution.recommendedBitrate(configuration.outputFps)
        store.save(configuration)
        ContextCompat.startForegroundService(this, Intent(this, BroadcastService::class.java))
        // Em GPU o encoder recebe os quadros pela própria surface dele, que entra como mais um
        // destino do compositor; em CPU continua recebendo bitmaps já compostos.
        val gpu = configuration.compositionEngine == CompositionEngine.GPU && compositor?.isReady == true
        publisher = YoutubePublisher(
            configuration.protocol, configuration.videoCodec, bitrate,
            configuration.youtubeServerUrl, configuration.youtubeStreamKey, configuration.liveLatency,
            videoWidth = configuration.outputResolution.width,
            videoHeight = configuration.outputResolution.height,
            fps = configuration.outputFps,
            useSurfaceInput = gpu,
            onInputSurface = { surface, presentationOriginNanos ->
                runOnUiThread {
                    val active = compositor ?: return@runOnUiThread
                    val encoder = publisher
                    if (surface == null) encoderSurface?.let(active::removeTarget)
                    else if (encoder != null && presentationOriginNanos != null) {
                        active.addTarget(surface, encoder.videoWidth, encoder.videoHeight, presentationOriginNanos)
                    }
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
                    setCaptureControlsEnabled(true)
                    updateForegroundService()
                    Thread { failedPublisher?.close() }.start()
                }
            }
        }.also { active ->
            if (!gpu) binding.composedOutput.onComposedFrame = active::offer
            active.start()
        }
        binding.startButton.text = "■ ENCERRAR TRANSMISSÃO"
        setCaptureControlsEnabled(false)
    }

    private fun stopBroadcast() {
        val active = publisher ?: return
        publisher = null
        binding.composedOutput.onComposedFrame = null
        encoderSurface?.let { surface -> compositor?.removeTarget(surface) }
        encoderSurface = null
        active.close()
        updateForegroundService()
        binding.startButton.text = "▶ INICIAR TRANSMISSÃO"
        setCaptureControlsEnabled(true)
    }

    private fun setCaptureControlsEnabled(enabled: Boolean) {
        listOf<View>(
            binding.courtCamera, binding.scoreboardCamera, binding.clockCamera,
            binding.scoreboardEnabled, binding.scoreboardSource, binding.scoreboardPhotoInterval, binding.clockEnabled,
            binding.captureResolution, binding.captureFps, binding.compositionEngine, binding.frameRotation,
            binding.outputResolution, binding.outputFps, binding.videoCodec, binding.bitratePreset,
            binding.scoreboardCaptureResolution, binding.scoreboardCaptureFps,
            binding.clockCaptureResolution, binding.clockCaptureFps,
            binding.clockSource, binding.clockPhotoInterval,
        ).forEach { it.isEnabled = enabled }
    }

    private fun updateZoomLabels() {
        binding.courtCropStatus.text = "Recorte: %.1f×".format(binding.compositionOverlay.crop().first)
        updateCourtResolutionHint()
    }

    /**
     * O que a resolução escolhida entrega ao enquadramento atual. Sem isso o operador não tem como
     * saber se o recorte está esticando pixel ou se sobrou margem — a captura agora pode passar da
     * resolução enviada, e essa margem é justamente o motivo de ela poder passar.
     */
    private fun updateCourtResolutionHint() {
        if (binding.captureResolution.adapter == null || binding.courtCamera.adapter == null) return
        val output = OutputResolution.entries.getOrNull(binding.outputResolution.selectedItemPosition) ?: OutputResolution.HD
        val cameraId = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty()
        val chosen = captureSizeOptions.getOrNull(binding.captureResolution.selectedItemPosition)?.second ?: (0 to 0)
        val camera = capabilities.camera(cameraId)
        val size = if (chosen.first > 0 && chosen.second > 0) {
            android.util.Size(chosen.first, chosen.second)
        } else camera?.let {
            val fps = captureFpsOptions.getOrElse(binding.captureFps.selectedItemPosition) { 0 }
                .takeIf { value -> value > 0 } ?: OUTPUT_FPS_OPTIONS.getOrElse(binding.outputFps.selectedItemPosition) { 20 }
            automaticCaptureSize(compositionConfiguration, cameraId, fps)
        }
        val cropZoom = binding.compositionOverlay.crop().first
        binding.courtResolutionHint.text = when {
            size == null -> "Escolha a câmera da quadra para ver o que a resolução entrega."
            else -> {
                val lossless = size.width.toFloat() / output.width
                val automatic = if (chosen.first > 0) "" else "Automática: ${size.width}×${size.height} · "
                if (lossless >= cropZoom) {
                    "$automatic recorte de %.1f× em ${output.height}p ainda sai de pixel real (limpo até %.1f×).".format(cropZoom, lossless)
                } else {
                    "$automatic recorte de %.1f× em ${output.height}p estica a imagem; pixel real acaba em %.1f×.".format(cropZoom, lossless)
                }
            }
        }
        updateShareHints()
    }

    /**
     * Diz quando a resolução e o FPS escolhidos nesta tela não vão valer. Camadas que dividem a
     * câmera produzem um stream só, e [BroadcastConfiguration.resolvedCaptureSettings] atende a
     * exigência maior entre elas — o campo continuava na tela mostrando um valor que mentia.
     */
    private fun updateShareHints() {
        val configuration = runCatching { readForm() }.getOrNull() ?: return
        listOf(
            OverlayLayer.SCOREBOARD to (binding.scoreboardShareHint to binding.scoreboardCaptureResolution),
            OverlayLayer.CLOCK to (binding.clockShareHint to binding.clockCaptureResolution),
        ).forEach { (layer, views) ->
            val (hint, spinner) = views
            val sharing = configuration.layersSharingCameraWith(layer)
            val shares = configuration.enabled(layer) && sharing.isNotEmpty()
            hint.visibility = if (shares) View.VISIBLE else View.GONE
            spinner.alpha = if (shares) .5f else 1f
            if (!shares) return@forEach
            val resolved = configuration.resolvedCaptureSettings(configuration.cameraIdFor(layer))
            val profile = if (resolved.hasSize) "${resolved.width}×${resolved.height}" else "resolução automática"
            val rate = resolved.fps.takeIf { it > 0 }?.let { " · $it fps" }.orEmpty()
            hint.text = "Divide a câmera com ${sharing.joinToString(" e ")}: vale o perfil mais exigente, $profile$rate."
        }
    }

    private fun updateOverlaySourceHints() {
        updateOverlaySourceHint(OverlayLayer.SCOREBOARD)
        updateOverlaySourceHint(OverlayLayer.CLOCK)
        updateShareHints()
    }

    private fun updateOverlaySourceHint(layer: OverlayLayer) {
        val sourceSpinner = if (layer == OverlayLayer.SCOREBOARD) binding.scoreboardSource else binding.clockSource
        val cameraSpinner = if (layer == OverlayLayer.SCOREBOARD) binding.scoreboardCamera else binding.clockCamera
        if (sourceSpinner.adapter == null || cameraSpinner.adapter == null) return
        val intervalSpinner = if (layer == OverlayLayer.SCOREBOARD) binding.scoreboardPhotoInterval else binding.clockPhotoInterval
        val intervalLabel = if (layer == OverlayLayer.SCOREBOARD) binding.scoreboardPhotoIntervalLabel else binding.clockPhotoIntervalLabel
        val hint = if (layer == OverlayLayer.SCOREBOARD) binding.scoreboardSourceHint else binding.clockSourceHint
        val other = OverlayLayer.entries.first { it != layer }
        val otherSpinner = if (other == OverlayLayer.SCOREBOARD) binding.scoreboardCamera else binding.clockCamera
        val otherEnabled = if (other == OverlayLayer.SCOREBOARD) binding.scoreboardEnabled else binding.clockEnabled

        val source = OverlaySource.entries.getOrElse(sourceSpinner.selectedItemPosition) { OverlaySource.VIDEO }
        val cameraId = cameraIds.getOrNull(cameraSpinner.selectedItemPosition).orEmpty()
        val courtId = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty()
        val otherId = cameraIds.getOrNull(otherSpinner.selectedItemPosition).orEmpty()
        val camera = capabilities.camera(cameraId)
        val photoSelected = source == OverlaySource.PHOTO_EVERY_SECOND
        intervalLabel.visibility = if (photoSelected) View.VISIBLE else View.GONE
        intervalSpinner.visibility = if (photoSelected) View.VISIBLE else View.GONE
        val interval = PHOTO_INTERVAL_OPTIONS_MILLIS.getOrElse(intervalSpinner.selectedItemPosition) { 1_000L }
        val name = layer.label.lowercase()
        hint.text = when {
            !photoSelected -> "Vídeo acompanha imediatamente qualquer mudança do $name."
            cameraId == courtId -> "Escolha para o $name uma câmera diferente da quadra."
            otherEnabled.isChecked && otherId == cameraId ->
                "A câmera da foto precisa ser exclusiva; escolha outra para ${other.label.lowercase()}."
            camera?.maximumJpegSize == null -> "Esta câmera não anunciou resolução JPEG para o modo foto."
            else -> camera.maximumJpegSize.let { size ->
                "Foto ${size.width}×${size.height} a cada ${photoIntervalLabel(interval)} · permanece na tela até a próxima. Se aquecer, o intervalo aumenta temporariamente."
            }
        }
    }

    private fun photoIntervalLabel(intervalMillis: Long): String = when (intervalMillis) {
        1_000L -> "1 segundo"
        else -> "${intervalMillis / 1_000L} segundos"
    }

    private fun startThermalMonitoring() {
        val manager = getSystemService(PowerManager::class.java).also { powerManager = it }
        manager.addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalStatusListener)
        applyThermalStatus(manager.currentThermalStatus)
    }

    private fun applyThermalStatus(status: Int) {
        val pressure = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalPressure.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalPressure.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalPressure.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalPressure.SEVERE
            else -> ThermalPressure.CRITICAL
        }
        val minimum = ThermalPhotoPolicy.minimumIntervalMillis(pressure)
        thermalPhotoMinimumIntervalMillis = minimum
        multiCameraEngine?.setThermalStillMinimumInterval(minimum)
        binding.thermalStatus.text = when (pressure) {
            ThermalPressure.NORMAL -> "Temperatura: normal · fotos no intervalo configurado."
            ThermalPressure.LIGHT -> "Temperatura: aquecimento leve · fotos no intervalo configurado."
            ThermalPressure.MODERATE -> "⚠ Aquecimento moderado · fotos no mínimo a cada 2 segundos."
            ThermalPressure.SEVERE -> "⚠ Aquecimento severo · fotos no mínimo a cada 5 segundos."
            ThermalPressure.CRITICAL -> "⚠ Aquecimento crítico · fotos no mínimo a cada 10 segundos."
        }
    }

    private fun updateOutputQualityHint() {
        if (binding.outputResolution.adapter == null || binding.outputFps.adapter == null || binding.bitratePreset.adapter == null) return
        val resolution = OutputResolution.entries.getOrNull(binding.outputResolution.selectedItemPosition) ?: OutputResolution.HD
        val fps = OUTPUT_FPS_OPTIONS.getOrElse(binding.outputFps.selectedItemPosition) { 20 }
        val preset = BitratePreset.entries.getOrNull(binding.bitratePreset.selectedItemPosition) ?: BitratePreset.AUTO
        val bitrate = preset.bitsPerSecond ?: resolution.recommendedBitrate(fps)
        val recommendation = resolution.recommendedBitrate(fps)
        val warning = if (bitrate < recommendation) " · abaixo dos ${recommendation / 1_000_000f} Mbps sugeridos pelo SportCam" else ""
        binding.outputQualityHint.text = "${resolution.height}p$fps · ${bitrate / 1_000f} kbps$warning"
        updateZoomLabels()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraIfNeeded() {
        val requested = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requested += Manifest.permission.POST_NOTIFICATIONS
        val missing = requested.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun showPrivacyNotice() {
        AlertDialog.Builder(this)
            .setTitle("Privacidade do SportCam")
            .setMessage(
                "O SportCam acessa câmera e microfone somente para compor a transmissão iniciada por você. " +
                    "O vídeo e o áudio são enviados diretamente ao destino configurado, como o YouTube; " +
                    "não passam por servidor próprio do SportCam. Configurações, credenciais e o ícone escolhido " +
                    "ficam apenas neste aparelho. O app não usa anúncios nem ferramentas de análise.\n\n" +
                    "Durante uma transmissão, câmera, microfone, codificação e rede continuam ativos em primeiro " +
                    "plano mesmo com a tela apagada, sempre indicados por uma notificação persistente."
            )
            .setNeutralButton("Política completa") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
            }
            .setPositiveButton("Entendi", null)
            .show()
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
            // Sem zoom de sensor no preview: com o zoom digital fora, a prévia mostra exatamente o
            // quadro que a composição recebe. Antes ela pedia CONTROL_ZOOM_RATIO enquanto a
            // transmissão recortava na composição, e as duas imagens não batiam.
            camera?.physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
            val preview = previewBuilder.build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, selector, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalCamera2Interop
    private fun startCompositionPreview() {
        dualCameraEngine?.stop()
        multiCameraEngine?.stop()
        releaseCompositor()
        repeatedFrameCount.set(0)
        distinctSourcesConfirmed.set(false)
        val configuration = readForm()
        compositionConfiguration = configuration
        binding.composedOutput.configure(configuration)
        val courtKey = cameraIdFor(Screen.COURT) ?: return
        val scoreboardKey = cameraIdFor(Screen.SCOREBOARD) ?: return
        val clockKey = cameraIdFor(Screen.CLOCK) ?: courtKey
        val courtInfo = cameraFor(courtKey) ?: return
        val scoreboardInfo = cameraFor(scoreboardKey) ?: return
        val requiredIds = configuration.requiredCameraIds()
        val auxiliaryKey = requiredIds.firstOrNull { it != courtKey }
        val auxiliaryInfo = auxiliaryKey?.let(::cameraFor)
        val hasDifferentSourceProfiles = requiredIds.map(configuration::resolvedCaptureSettings).distinct().size > 1
        val hasStillSource = requiredIds.any { configuration.stillIntervalFor(it) > 0L }
        if (configuration.compositionEngine == CompositionEngine.GPU || hasDifferentSourceProfiles || hasStillSource) {
            startMultiCameraComposition(configuration)
            return
        }
        if (requiredIds.size > 2) {
            startMultiCameraComposition(configuration)
            return
        }
        if (requiredIds.size == 2 && auxiliaryInfo != null) {
            auxiliaryCameraId = auxiliaryKey
            if (startDualSensorCapture(courtInfo, auxiliaryInfo)) return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            val sourceDescription = configuration.requiredCameraIds().joinToString { it }
            val analyses = configuration.requiredCameraIds().mapNotNull { key ->
                val camera = cameraFor(key) ?: return@mapNotNull null
                key to imageAnalysis(camera, key).also { analysis ->
                    val executor = when (key) {
                        courtKey -> courtAnalysisExecutor
                        scoreboardKey -> scoreboardAnalysisExecutor
                        else -> clockAnalysisExecutor
                    }
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            val bitmap = YuvToBitmapConverter.convert(image)
                            submitSourceFrame(key, bitmap, configuration)
                        } finally { image.close() }
                    }
                }
            }.toMap()
            try {
                val byLogical = analyses.entries.groupBy { cameraFor(it.key)!!.logicalCameraId }
                if (byLogical.size == 1) {
                    val logical = byLogical.keys.single()
                    boundCamera = provider.bindToLifecycle(
                        broadcastLifecycle, selectorFor(logical), *byLogical.values.single().map { it.value }.toTypedArray(),
                    )
                } else {
                    val requestedLogical = byLogical.keys
                    val advertisedGroup = provider.availableConcurrentCameraInfos.firstOrNull { group ->
                        group.map { Camera2CameraInfo.from(it).cameraId }.toSet().containsAll(requestedLogical)
                    } ?: error("CameraX não anunciou o grupo ${requestedLogical.sorted().joinToString(" + ")}")
                    val configurations = byLogical.map { (logical, entries) ->
                        val advertised = advertisedGroup.first { Camera2CameraInfo.from(it).cameraId == logical }
                        val useCases = UseCaseGroup.Builder().apply { entries.forEach { entry -> addUseCase(entry.value) } }.build()
                        ConcurrentCamera.SingleCameraConfig(selectorFor(Camera2CameraInfo.from(advertised).cameraId), useCases, broadcastLifecycle)
                    }
                    val concurrent = provider.bindToLifecycle(configurations)
                    boundCamera = concurrent.cameras.lastOrNull()
                }
                binding.broadcastStatus.text = "Composição ativa · ${analyses.size} câmera(s): $sourceDescription"
                } catch (error: RuntimeException) {
                provider.unbindAll()
                val courtAnalysis = analyses[courtKey] ?: return@addListener
                boundCamera = provider.bindToLifecycle(broadcastLifecycle, selectorFor(courtInfo.logicalCameraId), courtAnalysis)
                val reason = generateSequence<Throwable>(error) { it.cause }.last()
                    .let { "${it.javaClass.simpleName}: ${it.message}" }
                binding.broadcastStatus.text = "Combinação de câmeras incompatível ($sourceDescription): $reason. Somente quadra."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun submitSourceFrame(key: String, bitmap: android.graphics.Bitmap, configuration: BroadcastConfiguration) {
        val roles = buildList {
            if (key == configuration.courtCameraId) add(0)
            if (configuration.scoreboardEnabled && key == configuration.cameraIdFor(OverlayLayer.SCOREBOARD)) add(1)
            if (configuration.clockEnabled && key == configuration.cameraIdFor(OverlayLayer.CLOCK)) add(2)
        }
        roles.forEachIndexed { index, role ->
            val delivered = if (index == roles.lastIndex) bitmap else bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
            when (role) {
                0 -> { courtFrameSignature.set(frameSignature(delivered)); binding.composedOutput.submitCourt(delivered) }
                1 -> binding.composedOutput.submitScoreboard(delivered)
                else -> binding.composedOutput.submitClock(delivered)
            }
        }
        if (roles.isEmpty()) bitmap.recycle()
    }

    private fun startMultiCameraComposition(configuration: BroadcastConfiguration) {
        val ids = configuration.requiredCameraIds().toList()
        val sources = ids.mapNotNull { id -> cameraFor(id)?.let { camera ->
            val settings = configuration.resolvedCaptureSettings(id)
            val stillInterval = configuration.stillIntervalFor(id)
            val size = if (stillInterval > 0L) camera.maximumJpegSize ?: return@let null
            else captureSizeFor(configuration, id)
            MultiCameraEngine.Source(
                id = id,
                logicalId = camera.logicalCameraId,
                physicalId = camera.physicalCameraId,
                size = size,
                fps = settings.fps,
                stillIntervalMillis = stillInterval,
                conversionWidth = if (stillInterval > 0L) 0
                else conversionWidthFor(configuration, id, size, rotationFor(camera.logicalCameraId)),
            )
        } }
        if (sources.size != ids.size) return
        val rotations = sources.associate { it.id to if (it.isStill) 0 else rotationFor(it.logicalId) }
        val engine = multiCameraEngine ?: MultiCameraEngine(
            getSystemService(CameraManager::class.java),
            onFrame = { id, bitmap -> deliverCompositionFrame(id, bitmap) },
            onStatus = { status -> runOnUiThread { binding.broadcastStatus.text = status } },
        ).also { multiCameraEngine = it }
        engine.setThermalStillMinimumInterval(thermalPhotoMinimumIntervalMillis)
        val plan = MultiCameraEngine.Plan(sources)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            future.get().unbindAll()
            if (configuration.compositionEngine == CompositionEngine.GPU) {
                val gpuRotations = sources.associate { source ->
                    source.id to if (source.isStill) 0
                    else (configuration.frameRotation.degrees ?: rotationFor(source.logicalId))
                }
                startMultiCameraGpu(engine, plan, gpuRotations, configuration)
            } else engine.start(plan, rotations)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startMultiCameraGpu(
        engine: MultiCameraEngine,
        plan: MultiCameraEngine.Plan,
        rotations: Map<String, Int>,
        configuration: BroadcastConfiguration,
    ) {
        releaseCompositor()
        val created = GlCompositor { status -> runOnUiThread { binding.broadcastStatus.text = status } }
        compositor = created
        val ids = plan.sources.map { it.id }
        created.configure(configuration)
        created.setLogo(logoBitmap)
        created.configureSourceIds(ids, rotations)
        val videoSize = plan.sources.firstOrNull { !it.isStill }?.size ?: plan.sources.first().size
        created.start(plan.sources.map { if (it.isStill) videoSize else it.size }, rotations[ids.first()] ?: 0) {
            runOnUiThread {
                val available = listOfNotNull(created.courtSurface, created.scoreboardSurface, created.clockSurface)
                if (available.size < ids.size) return@runOnUiThread
                attachGpuPreview(created)
                engine.start(plan, rotations, ids.mapIndexed { index, id -> id to available[index] }.toMap())
            }
        }
    }

    private fun deliverCompositionFrame(id: String, bitmap: Bitmap) {
        val configuration = compositionConfiguration
        if (configuration.stillIntervalFor(id) > 0L && configuration.compositionEngine == CompositionEngine.GPU) {
            val active = compositor
            if (active?.isReady == true) active.submitStill(id, bitmap) else bitmap.recycle()
        } else submitSourceFrame(id, bitmap, configuration)
    }

    /**
     * Em quanto o quadro desta câmera é convertido para bitmap. Uma câmera pode servir quadra,
     * placar e cronômetro ao mesmo tempo; vale a exigência de quem precisa de mais pixel. Sem isso,
     * escolher o sensor inteiro faria a conversão em CPU converter dezenas de megapixels por quadro
     * para depois jogar quase tudo fora no recorte — e a resolução alta pareceria "não funcionar"
     * por um motivo que não é o do sensor.
     */
    private fun conversionWidthFor(
        configuration: BroadcastConfiguration,
        cameraId: String,
        size: android.util.Size,
        rotationDegrees: Int,
    ): Int = requiredCaptureWidth(configuration, cameraId, size, rotationDegrees).coerceAtMost(size.width)

    /**
     * Largura de captura que a composição consome, sem cortar pelo que o sensor entrega. Passar do
     * tamanho do quadro é informação útil, não erro: significa que o enquadramento pedido gostaria
     * de mais pixel do que a resolução escolhida tem — é assim que o automático sabe subir.
     */
    private fun requiredCaptureWidth(
        configuration: BroadcastConfiguration,
        cameraId: String,
        size: android.util.Size,
        rotationDegrees: Int,
    ): Int {
        val outputWidth = configuration.outputResolution.width
        val quarterTurn = ((rotationDegrees % 360) + 360) % 360 % 180 != 0
        val displayedWidth = if (quarterTurn) size.height else size.width
        val displayedHeight = if (quarterTurn) size.width else size.height
        val required = buildList {
            if (configuration.courtCameraId == cameraId) add(
                CompositionResolution.courtBitmapWidth(
                    outputWidth, displayedWidth, displayedHeight, configuration.cropZoom,
                ),
            )
            if (configuration.scoreboardEnabled && configuration.cameraIdFor(OverlayLayer.SCOREBOARD) == cameraId) add(
                CompositionResolution.overlayBitmapWidth(
                    outputWidth, configuration.scoreboardDestination, configuration.scoreboardCorners,
                ),
            )
            if (configuration.clockEnabled && configuration.cameraIdFor(OverlayLayer.CLOCK) == cameraId) add(
                CompositionResolution.overlayBitmapWidth(
                    outputWidth, configuration.clockDestination, configuration.clockCorners,
                ),
            )
        }
        val displayed = required.maxOrNull() ?: return 0
        return CompositionResolution.conversionWidth(displayed, size.width, size.height, rotationDegrees)
    }

    /**
     * Teto do par de sensores físicos quando o operador deixou a resolução em automática. Era fixo
     * em 1080p porque foi o que um S22 aprovou; virou consulta ao próprio aparelho, senão um S25
     * Ultra herdaria o limite de outro telefone. Escolhe o maior tamanho comum aos dois sensores que
     * ainda sustenta a taxa; sem nenhum tamanho comum declarado, cai no 1080p de antes.
     */
    private fun dualSensorCeiling(court: CameraInfo, scoreboard: CameraInfo, fps: Int): android.util.Size {
        val common = (court.yuvSizes.toSet() intersect scoreboard.yuvSizes.toSet())
            .sortedByDescending { it.width.toLong() * it.height }
        return common.firstOrNull { size -> court.sustains(size, fps) && scoreboard.sustains(size, fps) }
            ?: common.firstOrNull()
            ?: FALLBACK_DUAL_SENSOR_CEILING
    }

    private fun rotationFor(logicalId: String): Int {
        val sensor = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(logicalId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 0
        return ((sensor - displayRotationDegrees()) % 360 + 360) % 360
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
                val configuration = compositionConfiguration
                val key = when (role) {
                    DualCameraEngine.Role.COURT -> configuration.courtCameraId
                    DualCameraEngine.Role.SCOREBOARD -> auxiliaryCameraId
                }
                submitSourceFrame(key, bitmap, configuration)
            },
            onStatus = { status -> runOnUiThread { binding.broadcastStatus.text = status } },
        ).also { dualCameraEngine = it }
        val configured = compositionConfiguration
        val captureFps = configured.captureFps.takeIf { it > 0 } ?: configured.outputFps
        val ceiling = if (configured.captureWidth > 0 && configured.captureHeight > 0) {
            android.util.Size(configured.captureWidth, configured.captureHeight)
        } else dualSensorCeiling(court, scoreboard, captureFps)
        val plan = engine.planFor(capabilities, court, scoreboard, ceiling, captureFps) ?: return false
        val useGpu = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition] == CompositionEngine.GPU
        // Só abrir a lógica depois que o CameraX largou, senão o openCamera volta com CAMERA_IN_USE.
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching { future.get().unbindAll() }
            if (useGpu) startGpuComposition(engine, plan) else {
                val rotation = compositionRotation(engine, plan, gpu = false)
                engine.start(
                    plan, rotation,
                    conversionWidths = conversionWidthFor(configured, court.id, plan.size, rotation) to
                        conversionWidthFor(configured, scoreboard.id, plan.size, rotation),
                )
            }
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
        created.setLogo(logoBitmap)
        val rotation = compositionRotation(engine, plan, gpu = true)
        created.configureSourceIds(
            listOf(compositionConfiguration.courtCameraId, auxiliaryCameraId),
            mapOf(compositionConfiguration.courtCameraId to rotation, auxiliaryCameraId to rotation),
        )
        created.start(plan.size, rotation) {
            runOnUiThread {
                val courtSurface = created.courtSurface
                val scoreboardSurface = created.scoreboardSurface
                if (courtSurface == null || scoreboardSurface == null) {
                    binding.broadcastStatus.text = "Composição GPU não inicializou; volte para CPU."
                    return@runOnUiThread
                }
                attachGpuPreview(created)
                engine.start(plan, compositionRotation(engine, plan, gpu = true), courtSurface to scoreboardSurface)
            }
        }
    }

    private fun attachGpuPreview(created: GlCompositor) {
        val holder = binding.gpuOutput.holder
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            created.addTarget(surface, binding.gpuOutput.width.coerceAtLeast(1), binding.gpuOutput.height.coerceAtLeast(1))
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                compositor?.removeTarget(holder.surface)
                compositor?.addTarget(holder.surface, width, height)
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

    /**
     * A conta automática acerta o caminho em CPU, que recebe o buffer cru do ImageReader. O caminho
     * em GPU recebe o quadro por SurfaceTexture, e o produtor pode entregá-lo já girado — por isso
     * só ele aceita a correção manual, e o CPU segue sempre no automático.
     */
    private fun compositionRotation(engine: DualCameraEngine, plan: DualCameraEngine.Plan, gpu: Boolean): Int {
        val automatic = engine.rotationFor(plan.logicalId, displayRotationDegrees())
        if (!gpu) return automatic
        return FrameRotation.entries[binding.frameRotation.selectedItemPosition].degrees ?: automatic
    }

    private fun displayRotationDegrees() = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    @ExperimentalCamera2Interop
    private fun selectorFor(cameraId: String) = CameraXSupport.selectorFor(cameraId)

    @ExperimentalCamera2Interop
    private fun imageAnalysis(camera: CameraInfo, cameraId: String): ImageAnalysis {
        val configuration = readForm()
        val settings = configuration.resolvedCaptureSettings(cameraId)
        val size = captureSizeFor(configuration, cameraId)
        return CameraXSupport.imageAnalysis(
            camera, size, settings.fps,
        )
    }

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

    /**
     * Nível a partir do vetor gravidade. Com a tela travada em paisagem, estar nivelado quer dizer
     * estar a noventa graus da orientação natural do aparelho, então o desvio é medido contra o
     * múltiplo de noventa mais próximo — assim vale para os dois lados em que dá para deitar o
     * celular no tripé.
     */
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (screen != Screen.COURT) return
            val angle = Math.toDegrees(atan2(event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
            val deviation = if (angle >= 0f) angle - 90f else angle + 90f
            binding.compositionOverlay.setLevelDegrees(deviation)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startLevelSensor() {
        val manager = sensorManager ?: getSystemService(SensorManager::class.java)?.also { sensorManager = it } ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        manager.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopLevelSensor() {
        sensorManager?.unregisterListener(levelListener)
        binding.compositionOverlay.setLevelDegrees(null)
    }

    // ---------------------------------------------------------------- acesso remoto

    /**
     * Sobe ou derruba o site e o túnel conforme a configuração. Os dois andam juntos: o site
     * escuta para o túnel alcançá-lo, e o túnel sem site não teria o que publicar.
     *
     * Só reinicia quando algo do próprio acesso remoto mudou. Sem essa comparação, salvar o
     * recorte da quadra pelo navegador derrubaria o servidor no meio da resposta — o site
     * cortaria a própria conexão a cada ajuste.
     */
    private fun applyRemoteConfiguration(configuration: BroadcastConfiguration, force: Boolean = false) {
        val signature = with(configuration) {
            listOf(remoteEnabled, remotePort, remoteUser, remotePassword, tunnelUrl, tunnelToken).joinToString("|")
        }
        if (!force && signature == appliedRemoteSignature) return
        appliedRemoteSignature = signature
        httpServer?.close()
        httpServer = null
        tunnel?.close()
        tunnel = null
        if (!configuration.remoteEnabled) {
            updateRemoteStatus("Acesso remoto desligado.")
            updateForegroundService()
            return
        }
        if (!configuration.remoteReady) {
            updateRemoteStatus("Defina uma senha e uma porta entre 1024 e 65535 para ligar o site.")
            updateForegroundService()
            return
        }
        val server = HttpServer(
            configuration.remotePort,
            RemoteRoutes(assets, RemoteBridgeImpl())::handle,
        ) { status -> updateRemoteStatus(status) }
        val opened = runCatching { server.start() }
        if (opened.isFailure) {
            updateRemoteStatus("Não consegui abrir a porta ${configuration.remotePort}: ${opened.exceptionOrNull()?.message}")
            updateForegroundService()
            return
        }
        httpServer = server
        updateForegroundService()
        if (!configuration.tunnelReady) {
            updateRemoteStatus("Site no ar na porta ${configuration.remotePort}. Sem endereço wss:// e token do cf-p ele só responde nesta rede.")
            return
        }
        tunnel = CfpTunnel(
            configuration.tunnelUrl, configuration.tunnelToken, configuration.remotePort,
        ) { status -> updateRemoteStatus(status) }.also { it.start() }
    }

    private fun updateRemoteStatus(status: String) {
        remoteStatus = status
        runOnUiThread { binding.remoteStatus.text = status }
    }

    /**
     * O serviço em primeiro plano agora tem dois motivos para existir: a transmissão e o acesso
     * remoto. Sem ele, com a tela apagada o sistema pode recolher o processo — e o site pararia
     * de responder justamente quando o telefone já está no tripé e ninguém quer tocá-lo.
     */
    private fun updateForegroundService() {
        if (publisher != null || httpServer != null) {
            ContextCompat.startForegroundService(this, Intent(this, BroadcastService::class.java))
        } else {
            stopService(Intent(this, BroadcastService::class.java))
        }
    }

    /**
     * O painel do cf-p mostra a linha de comando pronta com as duas variáveis. Colá-la inteira no
     * campo do endereço é mais rápido — e bem menos sujeito a erro — do que digitar 64 caracteres
     * hexadecimais no teclado do celular.
     */
    private fun splitPastedTunnelCommand() {
        val pasted = binding.tunnelUrl.text.toString()
        if (!pasted.contains("CFP_TOKEN")) return
        val server = TUNNEL_SERVER_PATTERN.find(pasted)?.groupValues?.get(1)
        val token = TUNNEL_TOKEN_PATTERN.find(pasted)?.groupValues?.get(1)
        if (server == null && token == null) return
        server?.let(binding.tunnelUrl::setText)
        token?.let(binding.tunnelToken::setText)
        toast("Endereço e token separados da linha colada")
    }

    /**
     * O servidor HTTP responde nas próprias threads, e a composição só existe na thread da
     * interface. Este salto é o que deixa servir uma prévia sem tocar em view fora dela. O tempo
     * limite existe para uma interface ocupada não segurar a conexão do navegador para sempre.
     */
    private fun <T> onUiThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val slot = ArrayBlockingQueue<Result<T>>(1)
        runOnUiThread { slot.offer(runCatching(block)) }
        val result = slot.poll(UI_ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: error("A tela do aparelho não respondeu a tempo")
        return result.getOrThrow()
    }

    private fun sourceSnapshot(source: String): Bitmap? = when (source) {
        "court" -> binding.composedOutput.sourceSnapshot(null)
        "scoreboard" -> binding.composedOutput.sourceSnapshot(OverlayLayer.SCOREBOARD)
        "clock" -> binding.composedOutput.sourceSnapshot(OverlayLayer.CLOCK)
        else -> null
    }

    /**
     * Em CPU a composição já existe num bitmap próprio. Em GPU ela só existe na superfície do
     * compositor, que não tem leitura de volta — `PixelCopy` sobre a SurfaceView é o caminho que
     * não exige abrir uma superfície offscreen no GlCompositor.
     *
     * A cópia é pedida fora da thread da interface de propósito: o retorno do PixelCopy chega por
     * ela, e esperar nela pelo próprio retorno seria travar uma na outra.
     */
    private fun composedSnapshot(): Bitmap? {
        if (onUiThread { compositionConfiguration.compositionEngine } != CompositionEngine.GPU) {
            return onUiThread { binding.composedOutput.compositionSnapshot() }
        }
        val prepared = onUiThread {
            val view = binding.gpuOutput
            if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) null
            else view to Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        } ?: return null
        val (view, bitmap) = prepared
        val done = CountDownLatch(1)
        val copied = AtomicBoolean()
        PixelCopy.request(view, bitmap, { result ->
            copied.set(result == PixelCopy.SUCCESS)
            done.countDown()
        }, Handler(Looper.getMainLooper()))
        if (!done.await(UI_ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !copied.get()) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private inner class RemoteBridgeImpl : RemoteBridge {
        override fun configuration(): BroadcastConfiguration = store.load()

        override fun applyConfiguration(configuration: BroadcastConfiguration) =
            store.saveFromRemote(configuration)

        override fun capabilitiesJson(): JSONObject {
            val cameras = JSONArray()
            val sizes = JSONObject()
            val rates = JSONObject()
            capabilities.cameras.forEach { camera ->
                val kind = if (camera.physicalCameraId == null) "Lógica" else "Física"
                cameras.put(
                    JSONObject()
                        .put("id", camera.id)
                        .put("label", "$kind ${camera.id} · ${camera.lensFacing.label} · ${camera.lensLabel}"),
                )
                sizes.put(camera.id, JSONArray().apply {
                    camera.captureSizes.forEach {
                        put(JSONObject().put("width", it.size.width).put("height", it.size.height).put("label", it.label))
                    }
                })
                rates.put(camera.id, JSONArray().apply {
                    camera.fpsRanges.map { it.upper }.distinct().sorted().forEach { put(it) }
                })
            }
            val enums = JSONObject()
                .put("protocol", options(BroadcastProtocol.entries.map { it.name to it.label }))
                .put("videoCodec", options(VideoCodec.entries.map { it.name to it.label }))
                .put("outputResolution", options(OutputResolution.entries.map { it.name to it.label }))
                .put("bitratePreset", options(BitratePreset.entries.map { it.name to it.label }))
                .put("compositionEngine", options(CompositionEngine.entries.map { it.name to it.label }))
                .put("frameRotation", options(FrameRotation.entries.map { it.name to it.label }))
                .put("livePrivacy", options(LivePrivacy.entries.map { it.name to it.label }))
                .put("liveLatency", options(LiveLatency.entries.map { it.name to it.label }))
                .put("overlaySource", options(OverlaySource.entries.map { it.name to it.label }))
                .put("outputFps", options(OUTPUT_FPS_OPTIONS.map { it to "$it fps" }))
                .put("photoInterval", options(PHOTO_INTERVAL_OPTIONS_MILLIS.map { it to photoIntervalLabel(it) }))
            return JSONObject()
                .put("cameras", cameras)
                .put("captureSizes", sizes)
                .put("captureFps", rates)
                .put("enums", enums)
        }

        private fun options(values: List<Pair<Any, String>>) = JSONArray().apply {
            values.forEach { (value, label) -> put(JSONObject().put("value", value).put("label", label)) }
        }

        override fun statusJson(): JSONObject = onUiThread {
            JSONObject()
                .put("transmitindo", publisher != null)
                .put("live", ingestion?.watchUrl.orEmpty())
                .put("remoto", remoteStatus)
                .put(
                    "mensagem",
                    listOf(binding.broadcastStatus.text, binding.liveHealth.text, remoteStatus)
                        .map(CharSequence::toString).filter(String::isNotBlank).joinToString("\n"),
                )
        }

        override fun previewJpeg(source: String): ByteArray? {
            val bitmap = if (source == "composed") composedSnapshot() else onUiThread { sourceSnapshot(source) }
            if (bitmap == null) return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, stream)
            bitmap.recycle()
            return stream.toByteArray()
        }

        override fun command(name: String): String = onUiThread {
            when (name) {
                "live/create" -> { createLiveAndBroadcast(); "Criando a live no YouTube…" }
                "live/toggle" -> { toggleBroadcast(); if (publisher != null) "Transmissão iniciada." else "Transmissão encerrada." }
                "live/status" -> { checkLiveHealth(); binding.liveHealth.text.toString() }
                "youtube/authorize" -> { authorizeYoutube(); binding.oauthStatus.text.toString() }
                "probe/cameras" -> { toggleProbe(); binding.probeStatus.text.toString() }
                "probe/photo" -> { togglePhotoProbe(); binding.photoProbeStatus.text.toString() }
                "probe/report" -> listOf(probeReport, photoProbeReport)
                    .filter(String::isNotBlank).joinToString("\n\n")
                    .ifBlank { "Nenhum teste executado ainda." }
                else -> "Comando desconhecido: $name"
            }
        }

        override fun replaceLogo(bytes: ByteArray): String {
            val file = File(filesDir, "remote-logo.png")
            val written = runCatching { file.writeBytes(bytes) }
            if (written.isFailure) return "Não consegui gravar a imagem: ${written.exceptionOrNull()?.message}"
            return onUiThread {
                logoUri = Uri.fromFile(file).toString()
                binding.logoEnabled.isChecked = true
                loadLogo(logoUri)
                saveConfiguration()
                logoBitmap?.let { "Ícone atualizado · ${it.width}×${it.height} px" }
                    ?: "Recebi o arquivo, mas não consegui decodificar a imagem."
            }
        }
    }

    override fun onPause() {
        stopLevelSensor()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        compositor?.setPreviewEnabled(true)
    }

    override fun onStop() {
        // Tela apagada ou app em segundo plano: mantém câmera/encoder, mas não gasta GPU no preview.
        compositor?.setPreviewEnabled(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.COURT) startLevelSensor()
    }

    override fun onDestroy() {
        stopLevelSensor()
        store.removeListener(remoteConfigurationListener)
        httpServer?.close()
        httpServer = null
        tunnel?.close()
        tunnel = null
        powerManager?.removeThermalStatusListener(thermalStatusListener)
        powerManager = null
        probe?.shutdown()
        probe = null
        photoProbe?.shutdown()
        photoProbe = null
        dualCameraEngine?.release()
        dualCameraEngine = null
        multiCameraEngine?.release()
        multiCameraEngine = null
        releaseCompositor()
        stopBroadcast()
        stopService(Intent(this, BroadcastService::class.java))
        broadcastLifecycle.stop()
        super.onDestroy()
        courtAnalysisExecutor.shutdown()
        scoreboardAnalysisExecutor.shutdown()
        clockAnalysisExecutor.shutdown()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private class BroadcastLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object {
        /** Último recurso quando os dois sensores não declaram nenhum tamanho em comum. */
        val FALLBACK_DUAL_SENSOR_CEILING = android.util.Size(1920, 1080)
        val OUTPUT_FPS_OPTIONS = listOf(15, 20, 24, 30, 60)
        const val PRIVACY_POLICY_URL = "https://github.com/eloirotava/sportcam/blob/main/docs/privacy-policy.md"

        /** Prévia é para conferir enquadramento, não para julgar qualidade; 80 já é generoso. */
        const val PREVIEW_JPEG_QUALITY = 80
        const val UI_ANSWER_TIMEOUT_SECONDS = 10L
        val TUNNEL_SERVER_PATTERN = Regex("""CFP_SERVER=["']?([^"'\s]+)""")
        val TUNNEL_TOKEN_PATTERN = Regex("""CFP_TOKEN=["']?([^"'\s]+)""")
    }
}
