package com.application.sae512

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.application.sae512.ui.theme.SAE512Theme
import com.arthenica.ffmpegkit.*
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

class MainActivity : ComponentActivity() {

    private val cameraPermissionCode = 101
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraOn = false
    private var hlsServer: HlsServer? = null

    // Variables pour MediaCodec et les pipes
    private var mediaCodec: MediaCodec? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    // Variables pour la résolution de l'image
    private var imageWidth: Int = 640
    private var imageHeight: Int = 480

    // Format de couleur supporté
    private var supportedColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Charger le layout XML contenant PreviewView
        setContentView(R.layout.activity_main)

        // Initialisation de l'exécuteur de la caméra
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Charger le bouton via Compose après avoir chargé le layout XML
        val composeView = findViewById<ComposeView>(R.id.compose_view)
        composeView.setContent {
            SAE512Theme {
                CameraControlButton(onCameraToggle = { toggleCamera() })
            }
        }
    }

    private fun toggleCamera() {
        if (isCameraOn) {
            stopHLSEncoding()
            stopHlsServer()
            stopCamera()
        } else {
            startCamera()
        }
        isCameraOn = !isCameraOn
    }

    private fun startCamera() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission == PackageManager.PERMISSION_DENIED) {
            requestCameraPermission()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val previewView = findViewById<PreviewView>(R.id.previewView)
            if (previewView == null) {
                Log.e("MainActivity", "PreviewView est null, impossible de démarrer la caméra.")
                return@addListener
            }

            val preview = CameraPreview.Builder()
                .setTargetResolution(Size(imageWidth, imageHeight)) // Définir la résolution cible
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { cameraPreview ->
                    cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Utiliser ImageAnalysis pour capturer les frames
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(imageWidth, imageHeight)) // Définir la résolution cible
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { image: ImageProxy ->
                processImage(image)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Démarrer le serveur HLS
                startHlsServer()

            } catch (exc: Exception) {
                Log.e("MainActivity", "Erreur lors de la liaison de la caméra : ${exc.message}", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(image: ImageProxy) {
        Log.d("processImage", "Image size: ${image.width} x ${image.height}")
        if (mediaCodec == null) {
            // Initialiser MediaCodec avec les dimensions de l'image
            imageWidth = image.width
            imageHeight = image.height
            startHLSEncoding()
        }

        val mediaCodec = mediaCodec ?: return

        val inputBufferIndex = mediaCodec.dequeueInputBuffer(0)
        if (inputBufferIndex >= 0) {
            val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()

            val yuvData = YUV_420_888toNV12(image)
            inputBuffer?.put(yuvData)

            mediaCodec.queueInputBuffer(
                inputBufferIndex,
                0,
                yuvData.size,
                System.nanoTime() / 1000,
                0
            )
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
        while (outputBufferIndex >= 0) {
            val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
            val outData = ByteArray(bufferInfo.size)
            outputBuffer?.get(outData)

            // Écrire les données encodées dans le pipe
            try {
                outputStream?.let { os ->
                    os.write(outData)
                    os.flush()
                } ?: run {
                    Log.e("FFmpeg", "Le OutputStream n'est pas initialisé.")
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }

            mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
            outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
        }

        image.close()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun YUV_420_888toNV12(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val ySize = width * height
        val uvSize = width * height / 2
        val nv12 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(yPlane, nv12, 0, width, height)
        interleaveUVPlanes(uPlane, vPlane, nv12, ySize, width, height)

        return nv12
    }

    private fun copyPlane(plane: ImageProxy.PlaneProxy, output: ByteArray, offset: Int, width: Int, height: Int) {
        val buffer = plane.buffer
        buffer.rewind()

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var pos = offset
        val row = ByteArray(rowStride)

        for (i in 0 until height) {
            buffer.position(i * rowStride)
            buffer.get(row, 0, Math.min(rowStride, buffer.remaining()))

            var j = 0
            while (j < width) {
                output[pos++] = row[j * pixelStride]
                j++
            }
        }
    }

    private fun interleaveUVPlanes(uPlane: ImageProxy.PlaneProxy, vPlane: ImageProxy.PlaneProxy, output: ByteArray, offset: Int, width: Int, height: Int) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        uBuffer.rewind()
        vBuffer.rewind()

        val rowStride = uPlane.rowStride
        val pixelStride = uPlane.pixelStride

        val row = ByteArray(rowStride)
        val rowV = ByteArray(rowStride)

        var pos = offset

        for (i in 0 until height / 2) {
            uBuffer.position(i * rowStride)
            vBuffer.position(i * rowStride)
            uBuffer.get(row, 0, Math.min(rowStride, uBuffer.remaining()))
            vBuffer.get(rowV, 0, Math.min(rowStride, vBuffer.remaining()))

            var j = 0
            while (j < width / 2) {
                output[pos++] = row[j * pixelStride]   // U
                output[pos++] = rowV[j * pixelStride]  // V
                j++
            }
        }
    }

    private fun stopCamera() {
        if (cameraProvider != null) {
            cameraProvider?.unbindAll()
            cameraProvider = null

            val previewView = findViewById<PreviewView>(R.id.previewView)
            previewView?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        } else {
            Log.w("MainActivity", "Aucune caméra à arrêter.")
        }
    }

    private fun startHLSEncoding() {
        val outputPath = "${filesDir.absolutePath}/hls_output"

        // Créer le répertoire de sortie s'il n'existe pas
        val outputDir = File(outputPath)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        try {
            // Sélectionner le codec et le format de couleur supporté
            val codecName = selectCodec(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (codecName == null) {
                Log.e("MediaCodec", "Codec AVC non trouvé")
                return
            }

            supportedColorFormat = selectColorFormat(codecName, MediaFormat.MIMETYPE_VIDEO_AVC)
            if (supportedColorFormat == 0) {
                Log.e("MediaCodec", "Format de couleur approprié non trouvé")
                return
            }

            // Créer le pipe
            val pipe = ParcelFileDescriptor.createPipe()
            pipeRead = pipe[0]
            pipeWrite = pipe[1]

            // Ouvrir l'OutputStream pour écrire dans le pipe
            outputStream = FileOutputStream(pipeWrite?.fileDescriptor)

            // Récupérer le descripteur de fichier (fd) pour FFmpeg
            val fd = pipeRead?.fd ?: throw IOException("Impossible d'obtenir le descripteur de fichier.")

            // Commande FFmpeg pour lire depuis le pipe et générer le HLS
            val command = "-f h264 -i pipe:$fd -codec:v copy -f hls -hls_time 2 -hls_list_size 0 $outputPath/playlist.m3u8"

            // Démarrer FFmpeg avec FFmpegKit
            FFmpegKit.executeAsync(command,
                { session ->
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        Log.i("FFmpeg", "Encodage HLS réussi!")
                    } else {
                        Log.e("FFmpeg", "Erreur lors de l'encodage HLS.")
                        val failStackTrace = session.failStackTrace
                        Log.e("FFmpeg", "Stacktrace : $failStackTrace")
                    }
                }
            )

            // Configurer MediaCodec pour encoder en H264
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, imageWidth, imageHeight)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1250000)
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, supportedColorFormat)
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            mediaCodec = MediaCodec.createByCodecName(codecName)
            mediaCodec?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("FFmpeg", "Erreur lors de la configuration du pipe.")
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            Log.e("MediaCodec", "Erreur lors de la configuration du MediaCodec : ${e.message}")
        } catch (e: MediaCodec.CodecException) {
            e.printStackTrace()
            Log.e("MediaCodec", "Erreur lors de la configuration du MediaCodec : ${e.message}")
        }
    }

    private fun stopHLSEncoding() {
        FFmpegKit.cancel()

        // Fermer l'OutputStream et les ParcelFileDescriptor
        try {
            outputStream?.close()
            pipeWrite?.close()
            pipeRead?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            outputStream = null
            pipeWrite = null
            pipeRead = null
        }

        // Arrêter et libérer le MediaCodec
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
    }

    private fun startHlsServer() {
        val outputPath = "${filesDir.absolutePath}/hls_output"
        try {
            hlsServer = HlsServer(port = 8080, outputPath = outputPath)
            hlsServer?.start()
            Log.i("HlsServer", "Serveur HLS démarré à l'adresse : http://<adresse_ip_de_votre_appareil>:8080/playlist.m3u8")
        } catch (e: Exception) {
            Log.e("HlsServer", "Erreur lors du démarrage du serveur HLS : ${e.message}")
        }
    }

    private fun stopHlsServer() {
        hlsServer?.stop()
        Log.i("HlsServer", "Serveur HLS arrêté")
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), cameraPermissionCode)
    }

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
        cameraExecutor.shutdown()
        stopHLSEncoding()
        stopHlsServer()
    }

    // Classe HlsServer incluse dans le même fichier
    class HlsServer(port: Int, private val outputPath: String) : NanoHTTPD(port) {

        override fun serve(
            uri: String?,
            method: Method?,
            headers: MutableMap<String, String>?,
            parms: MutableMap<String, String>?,
            files: MutableMap<String, String>?
        ): Response {
            val requestedUri = uri ?: "/"
            if (requestedUri == "/") {
                // Redirige vers le fichier playlist.m3u8
                return newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_HTML,
                    "<html><body><a href=\"/playlist.m3u8\">Cliquez ici pour démarrer le flux HLS</a></body></html>"
                )
            }

            // Détermine le chemin absolu du fichier sur le stockage local
            val filePath = outputPath + requestedUri
            val file = File(filePath)

            // Vérifie si le fichier existe
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
            }

            // Détermine le type MIME du fichier à partir de son extension
            val mimeType = determineMimeType(requestedUri)

            // Crée une réponse HTTP en diffusant le fichier trouvé
            val inputStream = file.inputStream()
            return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        }

        // Détermine le type MIME du fichier
        private fun determineMimeType(uri: String): String {
            return when {
                uri.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                uri.endsWith(".ts") -> "video/MP2T"
                else -> "application/octet-stream"
            }
        }
    }

    // Fonctions utilitaires pour sélectionner le codec et le format de couleur
    private fun selectCodec(mimeType: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        for (codecInfo in codecs) {
            if (!codecInfo.isEncoder) continue
            val types = codecInfo.supportedTypes
            for (type in types) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    return codecInfo.name
                }
            }
        }
        return null
    }

    private fun selectColorFormat(codecName: String, mimeType: String): Int {
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.find { it.name == codecName }
        codecInfo?.let {
            val capabilities = it.getCapabilitiesForType(mimeType)
            for (colorFormat in capabilities.colorFormats) {
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    return colorFormat
                }
            }
        }
        return 0
    }
}

// Composables pour l'interface utilisateur
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

@ComposePreview(showBackground = true)
@Composable
fun CameraControlButtonPreview() {
    SAE512Theme {
        CameraControlButton(onCameraToggle = {})
    }
}

