package com.application.sae512

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.application.sae512.ui.theme.SAE512Theme
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class MainActivity : ComponentActivity() {
    private val cameraPermissionCode = 101
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraOn = false // Variable pour suivre l'état de la caméra

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Charger le layout XML contenant PreviewView
        setContentView(R.layout.activity_main)

        // Initialisation de l'exécuteur de la caméra
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Charger le bouton via Compose après avoir chargé le layout XML
        val composeView = findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view)
        composeView.setContent {
            SAE512Theme {
                CameraControlButton(onCameraToggle = { toggleCamera() })
            }
        }
    }

    // Fonction pour démarrer ou arrêter la caméra
    private fun toggleCamera() {
        if (isCameraOn) {
            stopCamera()
        } else {
            startCamera()
        }
        isCameraOn = !isCameraOn
    }

    // Fonction pour démarrer la caméra et capturer le flux vidéo
    private fun startCamera() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission == PackageManager.PERMISSION_DENIED) {
            requestCameraPermission()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Assurez-vous que la vue est chargée correctement
            val previewView = findViewById<PreviewView>(R.id.previewView)
            if (previewView == null) {
                Log.e("MainActivity", "PreviewView est null, impossible de démarrer la caméra.")
                return@addListener
            }

            val preview = androidx.camera.core.Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll() // Débind avant de lier
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Erreur lors de la liaison de la caméra : ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Fonction pour arrêter la caméra
// Fonction pour arrêter la caméra
// Fonction pour arrêter la caméra
    private fun stopCamera() {
        if (cameraProvider != null) {
            // Déliée toutes les caméras actives
            cameraProvider?.unbindAll()
            cameraProvider = null

            // Assurez-vous que le PreviewView est réinitialisé
            val previewView = findViewById<PreviewView>(R.id.previewView)
            if (previewView != null) {
                // Retirer le PreviewView de son parent pour le remplacer
                val parent = previewView.parent as? ViewGroup
                val index = parent?.indexOfChild(previewView)
                parent?.removeView(previewView)

                // Créer un nouveau PreviewView
                val newPreviewView = PreviewView(this).apply {
                    id = R.id.previewView
                    layoutParams = previewView.layoutParams
                }

                // Ajouter le nouveau PreviewView au même endroit
                if (index != null && index != -1) {
                    parent?.addView(newPreviewView, index)
                }

                Log.d("MainActivity", "Caméra arrêtée et PreviewView réinitialisé.")
            } else {
                Log.w("MainActivity", "Aucun PreviewView trouvé pour réinitialiser.")
            }
        } else {
            Log.w("MainActivity", "Aucune caméra à arrêter.")
        }
    }



    // Fonction pour demander la permission de la caméra
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), cameraPermissionCode)
    }

    // Gestion du résultat de la demande de permissions
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Permission de la caméra refusée. Veuillez vérifier les paramètres.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown() // Ferme l'exécuteur de caméra
    }
}

@Composable
fun CameraControlButton(modifier: Modifier = Modifier, onCameraToggle: () -> Unit) {
    var isCameraOn by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Button(
            onClick = {
                onCameraToggle()
                isCameraOn = !isCameraOn
            }
        ) {
            Text(text = if (isCameraOn) "Éteindre la caméra" else "Allumer la caméra")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraControlButtonPreview() {
    SAE512Theme {
        CameraControlButton(onCameraToggle = {})
    }
}
