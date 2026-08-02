package dev.cascam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
import dev.cascam.camera.MultiCameraEngine
import dev.cascam.config.CompositionEngine
import dev.cascam.config.FrameRotation
import dev.cascam.config.OverlayLayer
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
import kotlin.math.atan2
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private enum class Screen { BROADCAST, COURT, SCOREBOARD, CLOCK, DIAGNOSTICS }

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
        compositionConfiguration = configuration
        cameraIds = capabilities.cameras.map { it.id }
        val labels = capabilities.cameras.map {
            "${if (it.physicalCameraId == null) "Lógica" else "Física"} ${it.id} · ${it.lensFacing.label} · ${it.minimumFocalLength?.let { focal -> "$focal mm" } ?: "focal ?"}"
        }
        binding.courtCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.scoreboardCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.clockCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.courtCamera.setSelection(cameraIds.indexOf(configuration.courtCameraId).takeIf { it >= 0 } ?: 0)
        binding.scoreboardCamera.setSelection(cameraIds.indexOf(configuration.scoreboardCameraId).takeIf { it >= 0 } ?: 0)
        binding.clockCamera.setSelection(cameraIds.indexOf(configuration.cameraIdFor(OverlayLayer.CLOCK)).takeIf { it >= 0 } ?: 0)
        binding.scoreboardEnabled.isChecked = configuration.scoreboardEnabled
        binding.clockEnabled.isChecked = configuration.clockEnabled
        binding.courtOverlay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, OverlayLayer.entries.map { it.label })
        updateCaptureOptions(configuration.courtCameraId, null, configuration.captureWidth to configuration.captureHeight, configuration.captureFps)
        updateCaptureOptions(configuration.cameraIdFor(OverlayLayer.SCOREBOARD), OverlayLayer.SCOREBOARD, configuration.scoreboardCaptureWidth to configuration.scoreboardCaptureHeight, configuration.scoreboardCaptureFps)
        updateCaptureOptions(configuration.cameraIdFor(OverlayLayer.CLOCK), OverlayLayer.CLOCK, configuration.clockCaptureWidth to configuration.clockCaptureHeight, configuration.clockCaptureFps)
        binding.courtCaptureZoom.progress = zoomProgress(configuration.captureZoom)
        binding.scoreboardCaptureZoom.progress = zoomProgress(configuration.scoreboardCaptureZoom)
        binding.clockCaptureZoom.progress = zoomProgress(configuration.clockCaptureZoom)
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
        binding.compositionOverlay.setClockCorners(configuration.clockCorners)
        binding.compositionOverlay.setClockDestination(configuration.clockDestination)
        binding.compositionOverlay.setEnabledOverlays(configuration.scoreboardEnabled, configuration.clockEnabled)
        updateZoomLabels()
    }

    private fun configureActions() {
        binding.navBroadcast.setOnClickListener { showScreen(Screen.BROADCAST) }
        binding.navCourt.setOnClickListener { showScreen(Screen.COURT) }
        binding.navScoreboard.setOnClickListener { showScreen(Screen.SCOREBOARD) }
        binding.navClock.setOnClickListener { showScreen(Screen.CLOCK) }
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
            applyCompositionConfiguration()
        }
        binding.scoreboardEnabled.setOnClickListener(enabledChanged)
        binding.clockEnabled.setOnClickListener(enabledChanged)

        binding.scoreboardViewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyScoreboardViewZoom()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.clockViewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyScoreboardViewZoom()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        listOf(binding.courtCaptureZoom, binding.scoreboardCaptureZoom, binding.clockCaptureZoom).forEach { seekBar ->
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateZoomLabels()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    compositionConfiguration = readForm()
                    if (screen == Screen.COURT || screen == Screen.SCOREBOARD || screen == Screen.CLOCK) {
                        startCamera(cameraIdFor(screen))
                    }
                }
            })
        }
        val cameraListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAllCaptureOptions()
                if (screen != Screen.BROADCAST) startCamera(cameraIdFor(screen))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.courtCamera.onItemSelectedListener = cameraListener
        binding.scoreboardCamera.onItemSelectedListener = cameraListener
        binding.clockCamera.onItemSelectedListener = cameraListener
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

    private fun showScreen(target: Screen) {
        if (target != Screen.BROADCAST && publisher != null) stopBroadcast()
        if (target != Screen.BROADCAST) { dualCameraEngine?.stop(); multiCameraEngine?.stop(); releaseCompositor() }
        if (target != Screen.DIAGNOSTICS) stopProbe()
        if (target == Screen.COURT) startLevelSensor() else stopLevelSensor()
        screen = target
        binding.panelBroadcast.visibility = if (target == Screen.BROADCAST) View.VISIBLE else View.GONE
        binding.panelCourt.visibility = if (target == Screen.COURT) View.VISIBLE else View.GONE
        binding.panelScoreboard.visibility = if (target == Screen.SCOREBOARD) View.VISIBLE else View.GONE
        binding.panelClock.visibility = if (target == Screen.CLOCK) View.VISIBLE else View.GONE
        binding.panelDiagnostics.visibility = if (target == Screen.DIAGNOSTICS) View.VISIBLE else View.GONE
        when (target) {
            Screen.BROADCAST -> CompositionOverlayView.Mode.COMPOSITION
            Screen.COURT -> CompositionOverlayView.Mode.COURT
            Screen.SCOREBOARD -> CompositionOverlayView.Mode.SCOREBOARD
            Screen.CLOCK -> CompositionOverlayView.Mode.CLOCK
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
        val sizes = listOf("Automática" to (0 to 0)) + camera?.yuvSizes.orEmpty().map {
            "${it.width}×${it.height}" to (it.width to it.height)
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
        fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsValues.map { if (it == 0) "Automático" else "$it fps" })
        fpsSpinner.setSelection(fpsValues.indexOf(preservedFps).coerceAtLeast(0))
    }

    private fun automaticCaptureSize(ids: Set<String>): android.util.Size {
        val common = ids.mapNotNull(capabilities::camera).map { it.yuvSizes.toSet() }
            .reduceOrNull { result, sizes -> result intersect sizes }.orEmpty()
            .sortedByDescending { it.width.toLong() * it.height }
        return common.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: common.firstOrNull()
            ?: CameraXSupport.DEFAULT_ANALYSIS_SIZE
    }

    private fun captureSizeFor(configuration: BroadcastConfiguration, cameraId: String): android.util.Size {
        val settings = configuration.resolvedCaptureSettings(cameraId)
        return if (settings.hasSize) android.util.Size(settings.width, settings.height)
        else automaticCaptureSize(setOf(cameraId))
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
            cropZoom = cropZoom, cropPanX = cropPanX, cropPanY = cropPanY,
            scoreboardCorners = binding.compositionOverlay.scoreboardCorners(),
            scoreboardDestination = binding.compositionOverlay.scoreboardDestination(),
            clockCameraId = cameraIds.getOrNull(binding.clockCamera.selectedItemPosition).orEmpty(),
            clockEnabled = binding.clockEnabled.isChecked,
            clockCorners = binding.compositionOverlay.clockCorners(),
            clockDestination = binding.compositionOverlay.clockDestination(),
            captureWidth = captureSize.first,
            captureHeight = captureSize.second,
            captureFps = captureFpsOptions.getOrElse(binding.captureFps.selectedItemPosition) { 0 },
            captureZoom = captureZoom(binding.courtCaptureZoom.progress),
            scoreboardCaptureWidth = scoreboardCaptureSize.first,
            scoreboardCaptureHeight = scoreboardCaptureSize.second,
            scoreboardCaptureFps = scoreboardCaptureFpsOptions.getOrElse(binding.scoreboardCaptureFps.selectedItemPosition) { 0 },
            scoreboardCaptureZoom = captureZoom(binding.scoreboardCaptureZoom.progress),
            clockCaptureWidth = clockCaptureSize.first,
            clockCaptureHeight = clockCaptureSize.second,
            clockCaptureFps = clockCaptureFpsOptions.getOrElse(binding.clockCaptureFps.selectedItemPosition) { 0 },
            clockCaptureZoom = captureZoom(binding.clockCaptureZoom.progress),
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

    private fun applyCompositionConfiguration() {
        val configuration = readForm()
        compositionConfiguration = configuration
        binding.composedOutput.configure(configuration)
        compositor?.configure(configuration)
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
        compositionConfiguration = configuration
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
    private fun clockViewZoom() = 1f + binding.clockViewZoom.progress / 10f

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
        val zoom = when (screen) {
            Screen.SCOREBOARD -> scoreboardViewZoom()
            Screen.CLOCK -> clockViewZoom()
            else -> 1f
        }
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
        binding.scoreboardViewZoomStatus.text = if (zoom > 1.01f) {
            "Tela: %.1f× · arraste fora do quadrilátero para deslocar.".format(zoom)
        } else {
            "Tela: 1.0× · amplie para posicionar os cantos com precisão."
        }
        binding.clockViewZoomStatus.text = binding.scoreboardViewZoomStatus.text
    }

    /**
     * O arrasto chega em coordenadas locais da view, que já estão divididas pela ampliação; para o
     * conteúdo acompanhar o dedo na tela, o deslocamento volta a ser multiplicado por ela.
     */
    private fun panScoreboardView(dx: Float, dy: Float) {
        val zoom = if (screen == Screen.CLOCK) clockViewZoom() else scoreboardViewZoom()
        if (screen != Screen.SCOREBOARD && screen != Screen.CLOCK || zoom <= 1.01f) return
        if (screen == Screen.CLOCK) { clockPanX += dx * zoom; clockPanY += dy * zoom }
        else { scoreboardPanX += dx * zoom; scoreboardPanY += dy * zoom }
        applyScoreboardViewZoom()
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

    private fun resetCourtTransform() {
        binding.preview.scaleX = 1f; binding.preview.scaleY = 1f
        binding.preview.translationX = 0f; binding.preview.translationY = 0f
    }

    private fun validatedBroadcast(): BroadcastConfiguration? {
        val configuration = readForm()
        val selectedCameras = configuration.requiredCameraIds().mapNotNull(capabilities::camera)
        return when {
            selectedCameras.size != configuration.requiredCameraIds().size -> null.also { toast("Uma das câmeras selecionadas não existe mais") }
            !capabilities.supportsSimultaneous(configuration.requiredCameraIds()) -> null.also {
                toast("O aparelho não anunciou captura simultânea para as câmeras escolhidas")
            }
            selectedCameras.any { camera -> configuration.resolvedCaptureSettings(camera.id).let { requested ->
                requested.hasSize && camera.yuvSizes.none { it.width == requested.width && it.height == requested.height }
            } } -> null.also { toast("Uma fonte não suporta a resolução escolhida") }
            selectedCameras.any { camera -> configuration.resolvedCaptureSettings(camera.id).let { requested ->
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
            fps = configuration.captureFps.takeIf { it > 0 } ?: YoutubePublisher.FPS,
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
                    stopService(Intent(this, BroadcastService::class.java))
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
        stopService(Intent(this, BroadcastService::class.java))
        binding.startButton.text = "▶ INICIAR TRANSMISSÃO"
        setCaptureControlsEnabled(true)
    }

    private fun setCaptureControlsEnabled(enabled: Boolean) {
        listOf<View>(
            binding.courtCamera, binding.scoreboardCamera, binding.clockCamera,
            binding.scoreboardEnabled, binding.clockEnabled,
            binding.captureResolution, binding.captureFps, binding.compositionEngine, binding.frameRotation,
            binding.scoreboardCaptureResolution, binding.scoreboardCaptureFps,
            binding.clockCaptureResolution, binding.clockCaptureFps,
            binding.courtCaptureZoom, binding.scoreboardCaptureZoom, binding.clockCaptureZoom,
        ).forEach { it.isEnabled = enabled }
    }

    private fun updateZoomLabels() {
        binding.courtCropStatus.text = "Zoom do recorte: %.1f×".format(binding.compositionOverlay.crop().first)
        binding.courtCaptureZoomStatus.text = "Captura: %.1f× · aplicado antes da saída 1080p.".format(captureZoom(binding.courtCaptureZoom.progress))
        binding.scoreboardCaptureZoomStatus.text = "Captura: %.1f× · aplicado antes da saída 1080p.".format(captureZoom(binding.scoreboardCaptureZoom.progress))
        binding.clockCaptureZoomStatus.text = "Captura: %.1f× · aplicado antes da saída 1080p.".format(captureZoom(binding.clockCaptureZoom.progress))
    }

    private fun captureZoom(progress: Int): Float = 1f + progress.coerceIn(0, 70) / 10f
    private fun zoomProgress(zoom: Float): Int = ((zoom.coerceIn(1f, 8f) - 1f) * 10f).toInt()
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
            val previewZoom = when (screen) {
                Screen.COURT -> captureZoom(binding.courtCaptureZoom.progress)
                Screen.SCOREBOARD -> captureZoom(binding.scoreboardCaptureZoom.progress)
                Screen.CLOCK -> captureZoom(binding.clockCaptureZoom.progress)
                else -> 1f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && previewZoom > 1f) {
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, previewZoom)
            }
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
        if (configuration.compositionEngine == CompositionEngine.GPU || hasDifferentSourceProfiles) {
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
            MultiCameraEngine.Source(
                id, camera.logicalCameraId, camera.physicalCameraId,
                captureSizeFor(configuration, id), settings.fps, configuration.resolvedCaptureZoom(id),
            )
        } }
        if (sources.size != ids.size) return
        val rotations = sources.associate { it.id to rotationFor(it.logicalId) }
        val engine = multiCameraEngine ?: MultiCameraEngine(
            getSystemService(CameraManager::class.java),
            onFrame = { id, bitmap -> submitSourceFrame(id, bitmap, compositionConfiguration) },
            onStatus = { status -> runOnUiThread { binding.broadcastStatus.text = status } },
        ).also { multiCameraEngine = it }
        val plan = MultiCameraEngine.Plan(sources)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            future.get().unbindAll()
            if (configuration.compositionEngine == CompositionEngine.GPU) {
                val gpuRotations = sources.associate { source ->
                    source.id to (configuration.frameRotation.degrees ?: rotationFor(source.logicalId))
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
        created.configureSourceIds(ids, rotations)
        created.start(plan.sources.map { it.size }, rotations[ids.first()] ?: 0) {
            runOnUiThread {
                val available = listOfNotNull(created.courtSurface, created.scoreboardSurface, created.clockSurface)
                if (available.size < ids.size) return@runOnUiThread
                attachGpuPreview(created)
                engine.start(plan, rotations, ids.mapIndexed { index, id -> id to available[index] }.toMap())
            }
        }
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
        val ceiling = if (configured.captureWidth > 0 && configured.captureHeight > 0) {
            android.util.Size(configured.captureWidth, configured.captureHeight)
        } else DUAL_SENSOR_CEILING
        val plan = engine.planFor(capabilities, court, scoreboard, ceiling, configured.captureFps) ?: return false
        val useGpu = CompositionEngine.entries[binding.compositionEngine.selectedItemPosition] == CompositionEngine.GPU
        // Só abrir a lógica depois que o CameraX largou, senão o openCamera volta com CAMERA_IN_USE.
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching { future.get().unbindAll() }
            if (useGpu) startGpuComposition(engine, plan) else engine.start(plan, compositionRotation(engine, plan, gpu = false))
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
        probe?.shutdown()
        probe = null
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
        /** Teto de captura dos dois sensores; o teste confirmou 1920x1080 neste aparelho. */
        val DUAL_SENSOR_CEILING = android.util.Size(1920, 1080)
    }
}
