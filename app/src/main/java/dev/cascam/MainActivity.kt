package dev.cascam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dev.cascam.camera.CameraCapabilitiesReader
import dev.cascam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.cameraStatus.text = "Permissão da câmera necessária"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showCapabilities()
        binding.startButton.setOnClickListener {
            Toast.makeText(this, "Enquadramento salvo para o próximo passo", Toast.LENGTH_SHORT).show()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showCapabilities() {
        val capabilities = CameraCapabilitiesReader.read(this)
        val ultraWide = capabilities.likelyUltraWide?.let { "${it.id} (${it.minimumFocalLength ?: "?"} mm)" } ?: "não encontrada"
        val concurrent = if (capabilities.supportsConcurrentRearCameras) "sim" else "não anunciado pelo aparelho"
        binding.cameraStatus.text = "Traseiras: ${capabilities.rearCameras.size}\nUltra-wide provável: $ultraWide\nPar simultâneo: $concurrent"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }
}
