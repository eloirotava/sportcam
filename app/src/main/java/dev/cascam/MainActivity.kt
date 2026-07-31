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
import dev.cascam.camera.CameraCapabilities
import dev.cascam.camera.CameraCapabilitiesReader
import dev.cascam.camera.CameraInfo
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.BroadcastConfigurationStore
import dev.cascam.config.ScoreboardPlacement
import dev.cascam.databinding.ActivityMainBinding
import dev.cascam.ui.CompositionOverlayView
import dev.cascam.ui.YuvToBitmapConverter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

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
    private val courtFrameSignature = AtomicLong()
    private val repeatedFrameCount = AtomicInteger()
    private val distinctSourcesConfirmed = AtomicBoolean()

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
            "${if (it.physicalCameraId == null) "Lógica" else "Física"} ${it.id} · ${it.lensFacing.label} · ${it.minimumFocalLength?.let { focal -> "$focal mm" } ?: "focal ?"}"
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

    @OptIn(ExperimentalCamera2Interop::class)
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
            val scoreboardSelector = selectorFor(scoreboardInfo.logicalCameraId)
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
                        provider.bindToLifecycle(this, courtSelector, courtAnalysis)
                    } else {
                        provider.bindToLifecycle(this, courtSelector, courtAnalysis, scoreboardAnalysis)
                    }
                } else {
                    val requestedPair = setOf(courtInfo.logicalCameraId, scoreboardInfo.logicalCameraId)
                    require(capabilities.concurrentPairs.any { it.containsAll(requestedPair) }) {
                        "Par lógico não anunciado em concurrentCameraIds"
                    }
                    val configurations = listOf(
                        ConcurrentCamera.SingleCameraConfig(courtSelector, UseCaseGroup.Builder().addUseCase(courtAnalysis).build(), this),
                        ConcurrentCamera.SingleCameraConfig(scoreboardSelector, UseCaseGroup.Builder().addUseCase(scoreboardAnalysis).build(), this),
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
                boundCamera = provider.bindToLifecycle(this, courtSelector, courtAnalysis)
                binding.broadcastStatus.text = "Incompatível com duas fontes ($sourceDescription): ${error.message}. Somente quadra."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(cameraId: String) = CameraSelector.Builder().addCameraFilter { cameras ->
        cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
    }.build()

    @OptIn(ExperimentalCamera2Interop::class)
    private fun imageAnalysis(camera: CameraInfo): ImageAnalysis {
        val builder = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        camera.physicalCameraId?.let { Camera2Interop.Extender(builder).setPhysicalCameraId(it) }
        return builder.build()
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

    override fun onDestroy() {
        super.onDestroy()
        courtAnalysisExecutor.shutdown()
        scoreboardAnalysisExecutor.shutdown()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
