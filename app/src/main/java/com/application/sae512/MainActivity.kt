package com.application.sae512

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.application.sae512.R // <- s'assurer que c'est bien importé
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import fi.iki.elonen.NanoHTTPD
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    // ------------------- PERMISSIONS & REQUEST CODES -------------------
    private val CAMERA_PERMISSION_CODE = 101
    private val LOCATION_PERMISSION_CODE = 102
    private val READ_CONTACTS_PERMISSION_REQUEST_CODE = 103
    private val CALL_PHONE_PERMISSION_REQUEST_CODE = 104

    // ------------------- CAMERA / HLS (App1) -------------------
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraOn = false

    // MediaCodec & pipes
    private var mediaCodec: MediaCodec? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    private var imageWidth: Int = 640
    private var imageHeight: Int = 480
    private var supportedColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    private var hlsServer: HlsServer? = null

    // ------------------- SENSORS (App2) -------------------
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // ------------------- LOCATION (App2) -------------------
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager

    // ------------------- MQTT (App2) -------------------
    private val brokerUrl = "ssl://93d3a2a51c644bdc9494775b947db74c.s1.eu.hivemq.cloud:8883"
    private val clientId = MqttClient.generateClientId()
    private val topicChute = "Utilisateur1/Chute"
    private val topic = "Utilisateur1/Coordonnees"
    private val mqttUsername = "SAE512"
    private val mqttPassword: String = "Passroot31"

    // ------------------- UI ELEMENTS -------------------
    private lateinit var previewView: PreviewView
    private lateinit var cameraToggleButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var coordinatesTextView: TextView
    private lateinit var connectPublishButton: Button
    private lateinit var selectContactButton: Button
    private lateinit var contactTextView: TextView
    private lateinit var fallDetectionTextView: TextView
    private lateinit var callContactButton: Button
    private lateinit var accelerometerTextView: TextView
    private lateinit var gyroscopeTextView: TextView
    private lateinit var countdownTextView: TextView
    private lateinit var cancelButton: Button

    // ------------------- CONTACT / APPEL (App2) -------------------
    private val PICK_CONTACT_REQUEST = 1
    private var contactNumber: String? = null
    private var timer: CountDownTimer? = null
    private var isCountdownRunning = false

    // ------------------- ACTIVITY LIFECYCLE -------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Récupération des vues du layout
        previewView = findViewById(R.id.previewView)
        cameraToggleButton = findViewById(R.id.cameraToggleButton)
        statusTextView = findViewById(R.id.statusTextView)
        coordinatesTextView = findViewById(R.id.coordinatesTextView)
        connectPublishButton = findViewById(R.id.connectPublishButton)
        selectContactButton = findViewById(R.id.selectContactButton)
        contactTextView = findViewById(R.id.contactTextView)
        fallDetectionTextView = findViewById(R.id.fallDetectionTextView)
        callContactButton = findViewById(R.id.callContactButton)
        accelerometerTextView = findViewById(R.id.accelerometerTextView)
        gyroscopeTextView = findViewById(R.id.gyroscopeTextView)
        countdownTextView = findViewById(R.id.countdownTextView)
        cancelButton = findViewById(R.id.cancelButton)

        // Init sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Init fused location client & locationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Désactive l’UI tant que les permissions ne sont pas accordées
        disableUI()

        // Bouton onClick : (caméra) toggle
        cameraToggleButton.setOnClickListener {
            toggleCamera()
        }

        // Bouton onClick : MQTT connect & publish
        connectPublishButton.setOnClickListener {
            connectAndPublish()
            startLocationUpdates()
        }

        // Bouton onClick : sélection de contact
        selectContactButton.setOnClickListener {
            pickContact()
        }

        // Bouton onClick : appel du contact
        callContactButton.setOnClickListener {
            callContact()
        }

        // Bouton onClick : annuler le compte à rebours
        cancelButton.setOnClickListener {
            timer?.cancel()
            isCountdownRunning = false
            countdownTextView.text = "Call canceled"
            fallDetectionTextView.visibility = View.GONE
        }

        // Demande des permissions
        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Enregistrer les capteurs
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()

        // Ferme le flux MediaCodec
        stopHLSEncoding()
        stopHlsServer()
    }

    // ------------------- PERMISSIONS -------------------
    private fun requestAllPermissions() {
        val neededPermissions = mutableListOf<String>()

        // Caméra
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }
        // Localisation
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Contacts
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.READ_CONTACTS)
        }
        // Phone
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CALL_PHONE)
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 999)
        } else {
            // Déjà tout accordé
            enableUI()
            startLocationUpdates()
        }
    }

    private fun disableUI() {
        cameraToggleButton.isEnabled = false
        connectPublishButton.isEnabled = false
        selectContactButton.isEnabled = false
        callContactButton.isEnabled = false
        cancelButton.isEnabled = false
        statusTextView.text = "Waiting for permissions..."
    }

    private fun enableUI() {
        cameraToggleButton.isEnabled = true
        connectPublishButton.isEnabled = true
        selectContactButton.isEnabled = true
        callContactButton.isEnabled = true
        cancelButton.isEnabled = true
        statusTextView.text = "Permissions granted! You can interact with the app."
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 999) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                enableUI()
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Certaines permissions manquent.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------- CAMERA / HLS CODE (App1) -------------------
    private fun toggleCamera() {
        if (isCameraOn) {
            stopHLSEncoding()
            stopHlsServer()
            stopCamera()
            cameraToggleButton.text = "Allumer la caméra"
        } else {
            startCamera()
            cameraToggleButton.text = "Éteindre la caméra"
        }
        isCameraOn = !isCameraOn
    }

    private fun startCamera() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission == PackageManager.PERMISSION_DENIED) {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            if (previewView == null) {
                Log.e("MainActivity", "previewView is null!")
                return@addListener
            }

            val preview = Preview.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { cameraPreview ->
                    cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                }

            // On utilise un ImageAnalysis pour capturer les frames
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .apply {
                    setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
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
                // Démarre le serveur HLS
                startHlsServer()

            } catch (exc: Exception) {
                Log.e("MainActivity", "Erreur liaison caméra: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        previewView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
    }

    private fun processImage(image: ImageProxy) {
        if (mediaCodec == null) {
            imageWidth = image.width
            imageHeight = image.height
            startHLSEncoding()
        }

        val codec = mediaCodec ?: return
        val inputBufferIndex = codec.dequeueInputBuffer(0)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()

            val yuvData = yuv420888ToNv12(image)
            inputBuffer?.put(yuvData)

            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                yuvData.size,
                System.nanoTime() / 1000,
                0
            )
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outputBufferIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
            val outData = ByteArray(bufferInfo.size)
            outputBuffer?.get(outData)
            try {
                outputStream?.write(outData)
                outputStream?.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            codec.releaseOutputBuffer(outputBufferIndex, false)
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }

        image.close()
    }

    private fun yuv420888ToNv12(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv12 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(yPlane, nv12, width, height)
        interleaveUVPlanes(uPlane, vPlane, nv12, ySize, width, height)
        return nv12
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        output: ByteArray,
        width: Int,
        height: Int
    ) {
        val buffer = plane.buffer
        buffer.rewind()

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var pos = 0
        val row = ByteArray(rowStride)
        for (i in 0 until height) {
            buffer.position(i * rowStride)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
            var j = 0
            while (j < width) {
                output[pos++] = row[j * pixelStride]
                j++
            }
        }
    }

    private fun interleaveUVPlanes(
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        output: ByteArray,
        offset: Int,
        width: Int,
        height: Int
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        uBuffer.rewind()
        vBuffer.rewind()

        val rowStride = uPlane.rowStride
        val pixelStride = uPlane.pixelStride

        val rowU = ByteArray(rowStride)
        val rowV = ByteArray(rowStride)

        var pos = offset
        for (i in 0 until (height / 2)) {
            uBuffer.position(i * rowStride)
            vBuffer.position(i * rowStride)

            uBuffer.get(rowU, 0, minOf(rowStride, uBuffer.remaining()))
            vBuffer.get(rowV, 0, minOf(rowStride, vBuffer.remaining()))

            var j = 0
            while (j < width / 2) {
                output[pos++] = rowU[j * pixelStride]  // U
                output[pos++] = rowV[j * pixelStride]  // V
                j++
            }
        }
    }

    private fun startHLSEncoding() {
        val outputPath = "${filesDir.absolutePath}/hls_output"
        val outputDir = File(outputPath)
        if (!outputDir.exists()) outputDir.mkdirs()

        try {
            val codecName = selectCodec() ?: run {
                Log.e("MediaCodec", "Codec AVC non trouvé")
                return
            }
            supportedColorFormat = selectColorFormat(codecName)
            if (supportedColorFormat == 0) {
                Log.e("MediaCodec", "Format de couleur approprié non trouvé")
                return
            }
            val pipe = ParcelFileDescriptor.createPipe()
            pipeRead = pipe[0]
            pipeWrite = pipe[1]
            outputStream = FileOutputStream(pipeWrite?.fileDescriptor)

            val fd = pipeRead?.fd ?: throw IOException("Impossible d'obtenir le descriptor pipe.")

            val command = "-f h264 -i pipe:$fd -codec:v copy -f hls -hls_time 2 -hls_list_size 0 $outputPath/playlist.m3u8"
            FFmpegKit.executeAsync(command) { session ->
                val returnCode = session.returnCode
                if (ReturnCode.isSuccess(returnCode)) {
                    Log.i("FFmpeg", "Encodage HLS réussi!")
                } else {
                    Log.e("FFmpeg", "Erreur lors de l'encodage HLS.")
                    Log.e("FFmpeg", "Stacktrace : ${session.failStackTrace}")
                }
            }

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
            Log.e("FFmpeg", "Erreur pipe.")
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            Log.e("MediaCodec", "Erreur config MediaCodec: ${e.message}")
        } catch (e: MediaCodec.CodecException) {
            e.printStackTrace()
            Log.e("MediaCodec", "Erreur config MediaCodec: ${e.message}")
        }
    }

    private fun stopHLSEncoding() {
        FFmpegKit.cancel()
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

        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
    }

    private fun startHlsServer() {
        val outputPath = "${filesDir.absolutePath}/hls_output"
        try {
            hlsServer = HlsServer(8080, outputPath)
            hlsServer?.start()
            Log.i("HlsServer", "Serveur HLS démarré sur port 8080")
        } catch (e: Exception) {
            Log.e("HlsServer", "Erreur démarrage HLS: ${e.message}")
        }
    }

    private fun stopHlsServer() {
        hlsServer?.stop()
        Log.i("HlsServer", "Serveur HLS arrêté")
    }

    private fun selectCodec(): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        for (codecInfo in codecs) {
            if (!codecInfo.isEncoder) continue
            for (type in codecInfo.supportedTypes) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                    return codecInfo.name
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun selectColorFormat(codecName: String): Int {
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .find { it.name == codecName }
            ?: return 0
        val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        for (colorFormat in capabilities.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                return colorFormat
            }
        }
        for (colorFormat in capabilities.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            ) {
                return colorFormat
            }
        }
        return 0
    }

    // ------------------- HLS Server (App1) -------------------
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
                return newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_HTML,
                    "<html><body><a href=\"/playlist.m3u8\">Cliquez ici pour démarrer le flux HLS</a></body></html>"
                )
            }
            val filePath = outputPath + requestedUri
            val file = File(filePath)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
            }
            val mimeType = when {
                requestedUri.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                requestedUri.endsWith(".ts") -> "video/MP2T"
                else -> "application/octet-stream"
            }
            val inputStream = file.inputStream()
            return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        }
    }

    // ------------------- LOCATION & MQTT (App2) -------------------
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,  // 5s
                10f,
                locationListener
            )
            getLastKnownLocation()
        }
    }

    private fun getLastKnownLocation() {
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val latitude = location.latitude
                val longitude = location.longitude
                val altitude = location.altitude
                coordinatesTextView.text = "Latitude: $latitude\nLongitude: $longitude\nAltitude: $altitude m"
            } else {
                coordinatesTextView.text = "Unable to get location."
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val latitude = location.latitude
            val longitude = location.longitude
            val altitude = location.altitude
            sendGpsCoordinates(latitude, longitude, altitude)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { Log.d("Location", "Provider enabled: $provider") }
        override fun onProviderDisabled(provider: String) { Log.d("Location", "Provider disabled: $provider") }
    }

    private fun sendGpsCoordinates(latitude: Double, longitude: Double, altitude: Double) {
        val messageContent = "Latitude: $latitude, Longitude: $longitude, Altitude: $altitude"
        val mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
        val passwordArray = mqttPassword.toCharArray()
        val connOpts = MqttConnectOptions().apply {
            userName = mqttUsername
            password = passwordArray
            isCleanSession = true
            isAutomaticReconnect = true
        }
        try {
            mqttClient.connect(connOpts)
            Log.d("MQTT", "Connected to broker: $brokerUrl")
            val message = MqttMessage(messageContent.toByteArray()).apply {
                qos = 1
                isRetained = false
            }
            mqttClient.publish(topic, message)
            Log.d("MQTT", "Message published: $messageContent")
            mqttClient.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                statusTextView.text = "Error: ${e.message}"
            }
        }
    }

    // Méthode connect and publish (chute)
    private fun connectAndPublish() {
        val mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
        val passwordArray = mqttPassword.toCharArray()
        val connOpts = MqttConnectOptions().apply {
            userName = mqttUsername
            password = passwordArray
            isCleanSession = true
            isAutomaticReconnect = true
        }
        try {
            System.setProperty("javax.net.debug", "ssl,handshake")
            mqttClient.connect(connOpts)
            Log.d("MQTT", "Connected to broker: $brokerUrl")

            runOnUiThread {
                statusTextView.text = "Connected to Broker!"
            }

            val messageContentChute = "Chute détectée !"
            val message = MqttMessage(messageContentChute.toByteArray()).apply {
                qos = 1
                isRetained = false
            }
            mqttClient.publish(topicChute, message)
            Log.d("MQTT", "Message published: $messageContentChute")
            mqttClient.disconnect()
            Log.d("MQTT", "Disconnected from broker")
        } catch (e: Exception) {
            Log.e("MQTT", "Error: ${e.message}")
            e.printStackTrace()
            val errorMessage = "Connection Failed: ${e.message}\n${Log.getStackTraceString(e)}"
            runOnUiThread {
                statusTextView.text = errorMessage
            }
        }
    }

    // ------------------- CONTACT & APPEL (App2) -------------------
    private fun pickContact() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        startActivityForResult(intent, PICK_CONTACT_REQUEST)
    }

    private fun callContact() {
        val number = contactNumber ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$number")
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                CALL_PHONE_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_CONTACT_REQUEST && resultCode == Activity.RESULT_OK) {
            val contactUri = data?.data ?: return
            val projection = arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts._ID
            )
            val cursor = contentResolver.query(contactUri, projection, null, null, null)
            cursor?.apply {
                if (moveToFirst()) {
                    val nameIndex = getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val hasPhoneNumberIndex = getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                    val idIndex = getColumnIndex(ContactsContract.Contacts._ID)
                    val name = getString(nameIndex)
                    val hasPhoneNumber = getInt(hasPhoneNumberIndex)
                    val id = getString(idIndex)

                    if (hasPhoneNumber > 0) {
                        val phoneCursor = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            arrayOf(id),
                            null
                        )
                        phoneCursor?.apply {
                            if (moveToFirst()) {
                                val numberIndex = getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                contactNumber = getString(numberIndex)
                                contactTextView.text = "$name: $contactNumber"
                            }
                            close()
                        }
                    } else {
                        contactTextView.text = "$name: No phone number"
                    }
                }
                close()
            }
        }
    }

    // ------------------- FALL DETECTION (App2) -------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                accelerometerTextView.text = "Accelerometer: x=$x, y=$y, z=$z"
                val magnitude = sqrt(x * x + y * y + z * z)
                // Seuil de détection chute (exemple)
                if (magnitude < 5) {
                    fallDetectionTextView.text = "Fall detected!"
                    fallDetectionTextView.visibility = View.VISIBLE
                    startCountdown()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                gyroscopeTextView.text = "Gyroscope: x=$x, y=$y, z=$z"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Pas utilisé
    }

    private fun startCountdown() {
        if (isCountdownRunning) return
        isCountdownRunning = true

        timer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownTextView.text = "Calling in ${millisUntilFinished / 1000} seconds..."
            }

            override fun onFinish() {
                isCountdownRunning = false
                connectAndPublish() // Envoi MQTT
                callContact()       // Appel
            }
        }.start()
    }
}
