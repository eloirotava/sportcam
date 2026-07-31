package dev.cascam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dev.cascam.camera.CameraCapabilities
import dev.cascam.camera.CameraCapabilitiesReader
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.BroadcastConfigurationStore
import dev.cascam.config.ScoreboardPlacement
import dev.cascam.databinding.ActivityMainBinding
import dev.cascam.ui.CompositionOverlayView
import dev.cascam.ui.YuvToBitmapConverter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private enum class Screen { BROADCAST, COURT, SCOREBOARD }

    private lateinit var binding: ActivityMainBinding
    private lateinit var capabilities: CameraCapabilities
    private lateinit var store: BroadcastConfigurationStore
    private var cameraIds: List<String> = emptyList()
    private var screen = Screen.BROADCAST
    private var boundCamera: Camera? = null
    private val courtAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val scoreboardAnalysisExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScreen(screen) else binding.cameraStatus.text = "Permissão da câmera necessária"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BroadcastConfigurationStore(this)
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
            "ID ${it.id} · ${it.lensFacing.label} · ${it.minimumFocalLength?.let { focal -> "$focal mm" } ?: "focal ?"}"
        }
        binding.courtCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.scoreboardCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.courtCamera.setSelection(cameraIds.indexOf(configuration.courtCameraId).takeIf { it >= 0 } ?: 0)
        binding.scoreboardCamera.setSelection(cameraIds.indexOf(configuration.scoreboardCameraId).takeIf { it >= 0 } ?: 0)
        binding.scoreboardPlacement.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ScoreboardPlacement.entries.map { it.label })
        binding.scoreboardPlacement.setSelection(ScoreboardPlacement.entries.indexOf(configuration.scoreboardPlacement))
        binding.youtubeServer.setText(configuration.youtubeServerUrl)
        binding.youtubeKey.setText(configuration.youtubeStreamKey)
        binding.compositionOverlay.setCrop(configuration.cropZoom, configuration.cropPanX, configuration.cropPanY)
        binding.compositionOverlay.setScoreboardCorners(configuration.scoreboardCorners)
        binding.scoreboardZoom.progress = ((configuration.scoreboardZoom - 1f) * 10f).toInt().coerceIn(0, 70)
        updateZoomLabels()
    }

    private fun configureActions() {
        binding.navBroadcast.setOnClickListener { showScreen(Screen.BROADCAST) }
        binding.navCourt.setOnClickListener { showScreen(Screen.COURT) }
        binding.navScoreboard.setOnClickListener { showScreen(Screen.SCOREBOARD) }
        binding.saveButton.setOnClickListener { saveConfiguration(); toast("Configuração salva") }
        binding.startButton.setOnClickListener { validateBroadcast() }
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
        binding.scoreboardPlacement.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (screen == Screen.BROADCAST) binding.composedOutput.configure(readForm())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showScreen(target: Screen) {
        screen = target
        binding.panelBroadcast.visibility = if (target == Screen.BROADCAST) View.VISIBLE else View.GONE
        binding.panelCourt.visibility = if (target == Screen.COURT) View.VISIBLE else View.GONE
        binding.panelScoreboard.visibility = if (target == Screen.SCOREBOARD) View.VISIBLE else View.GONE
        binding.compositionOverlay.setMode(when (target) {
            Screen.BROADCAST -> CompositionOverlayView.Mode.COMPOSITION
            Screen.COURT -> CompositionOverlayView.Mode.COURT
            Screen.SCOREBOARD -> CompositionOverlayView.Mode.SCOREBOARD
        })
        binding.composedOutput.visibility = if (target == Screen.BROADCAST) View.VISIBLE else View.GONE
        binding.preview.visibility = if (target == Screen.BROADCAST) View.GONE else View.VISIBLE
        binding.scoreboardPreviewContainer.visibility = View.GONE
        resetCourtTransform()
        if (hasCameraPermission()) {
            if (target == Screen.BROADCAST) startCompositionPreview() else startCamera(cameraIdFor(target))
        }
    }

    private fun cameraIdFor(target: Screen): String? = when (target) {
        Screen.BROADCAST, Screen.COURT -> cameraIds.getOrNull(binding.courtCamera.selectedItemPosition)
        Screen.SCOREBOARD -> cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition)
    }

    private fun readForm(): BroadcastConfiguration {
        val (cropZoom, cropPanX, cropPanY) = binding.compositionOverlay.crop()
        return BroadcastConfiguration(
            courtCameraId = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty(),
            scoreboardCameraId = cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition).orEmpty(),
            cropZoom = cropZoom, cropPanX = cropPanX, cropPanY = cropPanY,
            scoreboardCorners = binding.compositionOverlay.scoreboardCorners(),
            scoreboardZoom = scoreboardZoom(),
            scoreboardPlacement = ScoreboardPlacement.entries[binding.scoreboardPlacement.selectedItemPosition],
            youtubeServerUrl = binding.youtubeServer.text.toString().trim().removeSuffix("/"),
            youtubeStreamKey = binding.youtubeKey.text.toString().trim(),
        )
    }

    private fun saveConfiguration() = store.save(readForm())

    private fun resetCourtTransform() {
        binding.preview.scaleX = 1f; binding.preview.scaleY = 1f
        binding.preview.translationX = 0f; binding.preview.translationY = 0f
    }

    private fun validateBroadcast() {
        val configuration = readForm()
        when {
            !configuration.youtubeServerUrl.startsWith("rtmp") -> binding.youtubeServer.error = "Use uma URL RTMP ou RTMPS"
            configuration.youtubeStreamKey.isBlank() -> binding.youtubeKey.error = "Informe a chave do YouTube Studio"
            else -> {
                store.save(configuration)
                binding.broadcastStatus.text = "Configuração válida. O compositor/encoder RTMPS ainda não está implementado."
                toast("Configuração pronta; transmissão ainda não iniciada")
            }
        }
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

    private fun showCapabilities() {
        val concurrent = if (capabilities.supportsConcurrentCameras) "par simultâneo disponível" else "sem par simultâneo"
        binding.cameraStatus.text = "${capabilities.cameras.size} câmeras · $concurrent"
    }
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun requestCameraIfNeeded() { if (!hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA) }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera(cameraId: String?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = cameraId?.let { requestedId ->
                CameraSelector.Builder().addCameraFilter { cameras -> cameras.filter { Camera2CameraInfo.from(it).cameraId == requestedId } }.build()
            } ?: CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, selector, preview)
            applyScoreboardZoom()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCompositionPreview() {
        val configuration = readForm()
        binding.composedOutput.configure(configuration)
        val courtId = cameraIdFor(Screen.COURT) ?: return
        val scoreboardId = cameraIdFor(Screen.SCOREBOARD) ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            val courtSelector = selectorFor(courtId)
            val scoreboardSelector = selectorFor(scoreboardId)
            val courtAnalysis = imageAnalysis()
            val scoreboardAnalysis = imageAnalysis()
            courtAnalysis.setAnalyzer(courtAnalysisExecutor) { image ->
                try {
                    val bitmap = YuvToBitmapConverter.convert(image)
                    binding.composedOutput.submitCourt(bitmap)
                    if (courtId == scoreboardId) binding.composedOutput.submitScoreboard(bitmap.copy(bitmap.config, false))
                } finally { image.close() }
            }
            scoreboardAnalysis.setAnalyzer(scoreboardAnalysisExecutor) { image ->
                try { binding.composedOutput.submitScoreboard(YuvToBitmapConverter.convert(image)) } finally { image.close() }
            }
            try {
                if (courtId == scoreboardId) {
                    boundCamera = provider.bindToLifecycle(this, courtSelector, courtAnalysis)
                } else {
                    val configurations = listOf(
                        ConcurrentCamera.SingleCameraConfig(courtSelector, UseCaseGroup.Builder().addUseCase(courtAnalysis).build(), this),
                        ConcurrentCamera.SingleCameraConfig(scoreboardSelector, UseCaseGroup.Builder().addUseCase(scoreboardAnalysis).build(), this),
                    )
                    val concurrent = provider.bindToLifecycle(configurations)
                    boundCamera = concurrent.cameras.getOrNull(1)
                }
                binding.broadcastStatus.text = "Composição real ativa: crop 16:9 e homografia do placar aplicados aos frames."
                applyScoreboardZoom()
            } catch (error: RuntimeException) {
                boundCamera = provider.bindToLifecycle(this, courtSelector, courtAnalysis)
                binding.broadcastStatus.text = "Este par não pôde ser aberto junto; mostrando somente a quadra."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(cameraId: String) = CameraSelector.Builder().addCameraFilter { cameras ->
        cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
    }.build()

    private fun imageAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        courtAnalysisExecutor.shutdown()
        scoreboardAnalysisExecutor.shutdown()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
