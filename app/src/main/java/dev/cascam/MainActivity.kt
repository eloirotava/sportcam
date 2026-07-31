package dev.cascam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dev.cascam.camera.CameraCapabilities
import dev.cascam.camera.CameraCapabilitiesReader
import dev.cascam.config.BroadcastConfiguration
import dev.cascam.config.BroadcastConfigurationStore
import dev.cascam.config.ScoreboardPlacement
import dev.cascam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var capabilities: CameraCapabilities
    private lateinit var store: BroadcastConfigurationStore
    private var cameraIds: List<String> = emptyList()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera(selectedCameraId()) else binding.cameraStatus.text = "Permissão da câmera necessária"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BroadcastConfigurationStore(this)
        capabilities = CameraCapabilitiesReader.read(this)
        configureForm(store.load())
        showCapabilities()

        binding.saveButton.setOnClickListener {
            store.save(readForm())
            Toast.makeText(this, "Configuração salva neste aparelho", Toast.LENGTH_SHORT).show()
        }
        binding.startButton.setOnClickListener {
            val configuration = readForm()
            if (!configuration.youtubeServerUrl.startsWith("rtmp")) {
                binding.youtubeServer.error = "Use uma URL RTMP ou RTMPS nesta primeira versão"
            } else if (configuration.youtubeStreamKey.isBlank()) {
                binding.youtubeKey.error = "Informe a chave criada no YouTube Studio"
            } else {
                store.save(configuration)
                Toast.makeText(this, "Configuração pronta; encoder RTMPS é o próximo passo", Toast.LENGTH_LONG).show()
            }
        }
        requestCameraIfNeeded()
    }

    private fun configureForm(configuration: BroadcastConfiguration) {
        cameraIds = capabilities.cameras.map { it.id }
        val cameraLabels = capabilities.cameras.map {
            "ID ${it.id} · ${it.lensFacing.label} · ${it.minimumFocalLength?.let { focal -> "$focal mm" } ?: "focal desconhecida"}"
        }
        val cameraAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraLabels)
        binding.courtCamera.adapter = cameraAdapter
        binding.scoreboardCamera.adapter = cameraAdapter
        binding.courtCamera.setSelection(cameraIds.indexOf(configuration.courtCameraId).takeIf { it >= 0 } ?: 0)
        binding.scoreboardCamera.setSelection(cameraIds.indexOf(configuration.scoreboardCameraId).takeIf { it >= 0 } ?: 0)

        val placements = ScoreboardPlacement.entries
        binding.scoreboardPlacement.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            placements.map { it.label },
        )
        binding.scoreboardPlacement.setSelection(placements.indexOf(configuration.scoreboardPlacement))
        binding.cropZoom.progress = ((configuration.cropZoom - 1f) * 10f).toInt()
        binding.cropPanX.progress = ((configuration.cropPanX + 1f) * 100f).toInt()
        binding.cropPanY.progress = ((configuration.cropPanY + 1f) * 100f).toInt()
        binding.youtubeServer.setText(configuration.youtubeServerUrl)
        binding.youtubeKey.setText(configuration.youtubeStreamKey)
        binding.compositionOverlay.setScoreboardCorners(configuration.scoreboardCorners)
        updateCropOverlay()

        val cropListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateCropOverlay()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        binding.cropZoom.setOnSeekBarChangeListener(cropListener)
        binding.cropPanX.setOnSeekBarChangeListener(cropListener)
        binding.cropPanY.setOnSeekBarChangeListener(cropListener)
    }

    private fun readForm() = BroadcastConfiguration(
        courtCameraId = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition).orEmpty(),
        scoreboardCameraId = cameraIds.getOrNull(binding.scoreboardCamera.selectedItemPosition).orEmpty(),
        cropZoom = 1f + binding.cropZoom.progress / 10f,
        cropPanX = binding.cropPanX.progress / 100f - 1f,
        cropPanY = binding.cropPanY.progress / 100f - 1f,
        scoreboardCorners = binding.compositionOverlay.scoreboardCorners(),
        scoreboardPlacement = ScoreboardPlacement.entries[binding.scoreboardPlacement.selectedItemPosition],
        youtubeServerUrl = binding.youtubeServer.text.toString().trim().removeSuffix("/"),
        youtubeStreamKey = binding.youtubeKey.text.toString().trim(),
    )

    private fun updateCropOverlay() {
        binding.compositionOverlay.setCrop(
            1f + binding.cropZoom.progress / 10f,
            binding.cropPanX.progress / 100f - 1f,
            binding.cropPanY.progress / 100f - 1f,
        )
    }

    private fun showCapabilities() {
        val ultraWide = capabilities.likelyUltraWide?.let { "ID ${it.id} (${it.minimumFocalLength ?: "?"} mm)" } ?: "não encontrada"
        val concurrent = if (capabilities.supportsConcurrentCameras) "sim" else "não anunciado"
        binding.cameraStatus.text = "Câmeras: ${capabilities.cameras.size} · Ultra-wide provável: $ultraWide · Par simultâneo: $concurrent"
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(selectedCameraId())
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun selectedCameraId() = cameraIds.getOrNull(binding.courtCamera.selectedItemPosition)

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera(cameraId: String?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = cameraId?.let { requestedId ->
                CameraSelector.Builder().addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == requestedId }
                }.build()
            } ?: CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview)
        }, ContextCompat.getMainExecutor(this))
    }
}
