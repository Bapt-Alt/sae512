package com.application.sae512

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.application.sae512.ui.theme.SAE512Theme

class MainActivity : ComponentActivity() {
    private val cameraPermissionCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SAE512Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CheckPermissionsButton(
                        modifier = Modifier.padding(innerPadding),
                        onCheckPermissions = { explainWhyNeedPermission() }
                    )
                }
            }
        }
    }

    // Fonction pour expliquer pourquoi on a besoin de la permission
    private fun explainWhyNeedPermission() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

        if (cameraPermission == PackageManager.PERMISSION_DENIED) {
            // Afficher une explication si nécessaire
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA).let {
                if (it) {
                    Log.d("MainActivity", "Montrer une explication pour la demande de permission.")
                }
                requestCameraPermission()
            }
        } else {
            // Permission déjà accordée
            startUsingCamera()
        }
    }

    // Fonction pour demander la permission de la caméra
    private fun requestCameraPermission() {
        Log.d("MainActivity", "Demande de la permission de la caméra - Permission non accordée, demande en cours...")
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), cameraPermissionCode)
    }

    // Fonction appelée après obtention de la permission
    private fun startUsingCamera() {
        Log.d("MainActivity", "Permission de la caméra accordée - Démarrage de la caméra")
        Toast.makeText(this, "Permission de la caméra accordée", Toast.LENGTH_SHORT).show()
        // Logique pour démarrer la caméra ici...
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
                Log.d("MainActivity", "Permission de la caméra accordée")
                startUsingCamera()
            } else {
                Log.e("MainActivity", "Permission de la caméra refusée")
                Toast.makeText(this, "Permission de la caméra refusée. Veuillez vérifier les paramètres.", Toast.LENGTH_LONG).show()
                // Ouvre les paramètres pour permettre à l'utilisateur de donner la permission
                openAppSettings()
            }
        }
    }

    // Ouvrir les paramètres de l'application pour permettre à l'utilisateur de donner la permission
    private fun openAppSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = android.net.Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }
}

@Composable
fun CheckPermissionsButton(modifier: Modifier = Modifier, onCheckPermissions: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Button(
            onClick = {
                // Vérifier si la permission est déjà accordée avant d'afficher le pop-up
                val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
                    // Permission déjà accordée, afficher un message approprié
                    Toast.makeText(context, "Toutes les permissions sont déjà accordées. Prêt à utiliser la caméra.", Toast.LENGTH_SHORT).show()
                } else {
                    // Demander la permission
                    showDialog = true
                }
            }
        ) {
            Text(text = "Vérifier la permission de la caméra")
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = {
                    showDialog = false
                },
                title = {
                    Text(text = "Permission Requise")
                },
                text = {
                    Text("Cette application a besoin d'accéder à la caméra pour capturer des vidéos. Merci d'accorder cette permission.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onCheckPermissions()
                            showDialog = false
                        }
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDialog = false
                        }
                    ) {
                        Text("Annuler")
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CheckPermissionsButtonPreview() {
    SAE512Theme {
        CheckPermissionsButton(onCheckPermissions = {})
    }
}
