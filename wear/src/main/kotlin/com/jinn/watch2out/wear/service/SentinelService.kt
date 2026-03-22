// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.wear.data.DataLogger
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.presentation.IncidentAlertActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Safety-critical service for real-time accident detection.
 * v23.6: Immediate 2x10s Audio, Absolute EDR timestamps, and Persistent storage in /Download.
 */
class SentinelService : Service(), SensorEventListener, LocationListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _currentState = MutableStateFlow(IncidentState.IDLE)
    val currentState = _currentState.asStateFlow()
    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry = _telemetry.asStateFlow()

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var alertDispatcher: WearAlertDispatcher
    private val dataLogger = DataLogger()
    
    private var currentSettings = WatchSettings()
    private val currentReading = FloatArray(12) 
    private var currentGpsSpeed = 0f 
    private var prevGpsSpeed = 0f
    private var lastLocation: Location? = null
    
    private var pendingIncidentSnapshot: IncidentData? = null
    private var vInferenceState = VehicleInferenceState.IDLE
    private var detectedType = VehicleIncidentType.NONE
    private var stateEntryTime = 0L
    private var lastMoveTime = 0L
    private var sessionStartTime = 0L
    private var currentMode = DetectionMode.VEHICLE
    
    private var isSimulatingLocked = false
    private var isDashboardActive = false
    private val TAG = "SentinelFSM"

    private var mediaRecorder: MediaRecorder? = null
    private val recordedAudioFiles = mutableListOf<File>()
    private var audioRecordingJob: Job? = null

    private val explicitJson = Json { allowSpecialFloatingPointValues = true; ignoreUnknownKeys = true; encodeDefaults = true }

    // Dynamic Rate Control
    private var dynamicRateMs: Int = 400
    private var lastRateChangeTime: Long = 0L
    private val RATE_CHANGE_COOLDOWN_MS = 2000L

    // Internal Metrics
    private var baselinePressure = 0f
    private var totalRotation = 0f
    private var lastGyroTimeNs = 0L
    private var lastSignificantMotionTime = 0L
    private var lastStreamTime = 0L

    // Internal Peak States
    private var overallPeak = TelemetryState()
    private var windowPeak = TelemetryState()
    private var lastWindowResetTime = 0L
    private var currentWindowDurationMs = 600000L // 10m default

    inner class SentinelBinder : Binder() { fun getService(): SentinelService = this@SentinelService }
    private val binder = SentinelBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        settingsRepository = SettingsRepository(this)
        alertDispatcher = WearAlertDispatcher(this)

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        
        serviceScope.launch { settingsRepository.settingsFlow.collectLatest { handleSettingsChange(it) } }
        serviceScope.launch { 
            _currentState.collectLatest { 
                lastKnownState = it
                broadcastStatusToMobile(_telemetry.value)
                SentinelComplicationService.update(this@SentinelService) 
            } 
        }

        // Main processing loop
        serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                updateMonitoringRate(now)
                delay(dynamicRateMs.toLong())
                
                val snapshot: FloatArray
                synchronized(currentReading) { 
                    snapshot = currentReading.copyOf()
                    if (currentGpsSpeed < prevGpsSpeed) {
                        val drop = (prevGpsSpeed - currentGpsSpeed) * 3.6f
                        overallPeak = overallPeak.copy(maxSpeedDrop = maxOf(overallPeak.maxSpeedDrop, drop))
                        windowPeak = windowPeak.copy(wMaxSpeedDrop = maxOf(windowPeak.wMaxSpeedDrop, drop))
                    }
                    prevGpsSpeed = currentGpsSpeed
                }
                
                if (currentGpsSpeed < 0.55f && vInferenceState != VehicleInferenceState.IMPACT_DETECTED && vInferenceState != VehicleInferenceState.STILLNESS) {
                    if (now - lastSignificantMotionTime > 600000L) {
                        totalRotation = 0f
                        lastSignificantMotionTime = now
                    }
                } else {
                    lastSignificantMotionTime = now
                }

                if (_currentState.value == IncidentState.MONITORING || isDashboardActive) {
                    serviceScope.launch { dataLogger.addRecord(now, snapshot) }
                }
                
                val currentImpact = magnitude(floatArrayOf(snapshot[0], snapshot[1], snapshot[2])) / 9.81f
                val longG = snapshot[1] / 9.81f
                val latG = snapshot[0] / 9.81f
                
                val currentCrashScore = (currentImpact / 15f).coerceIn(0f, 1f)
                val currentGyroRatio = (magnitude(floatArrayOf(snapshot[3], snapshot[4], snapshot[5])) / 5.0f).coerceIn(0f, 10f)
                val currentPressDelta = if (baselinePressure > 0) snapshot[6] - baselinePressure else 0f

                if (now - lastWindowResetTime > currentWindowDurationMs) {
                    windowPeak = TelemetryState()
                    lastWindowResetTime = now
                }

                if (currentImpact > overallPeak.maxImpact) {
                    overallPeak = updateOverallSnapshot(currentImpact, currentCrashScore, currentGyroRatio, currentPressDelta, totalRotation, snapshot)
                } else {
                    overallPeak = overallPeak.copy(
                        maxLongitudinalG = if (abs(longG) > abs(overallPeak.maxLongitudinalG)) longG else overallPeak.maxLongitudinalG,
                        maxLateralG = if (abs(latG) > abs(overallPeak.maxLateralG)) latG else overallPeak.maxLateralG,
                        pCrashScore = maxOf(overallPeak.pCrashScore, currentCrashScore),
                        pGyroRatio = maxOf(overallPeak.pGyroRatio, currentGyroRatio),
                        pPressureDelta = if (abs(currentPressDelta) > abs(overallPeak.pPressureDelta)) currentPressDelta else overallPeak.pPressureDelta,
                        pRollSum = maxOf(overallPeak.pRollSum, totalRotation)
                    )
                }

                if (currentImpact > windowPeak.windowImpact) {
                    windowPeak = updateWindowSnapshot(currentImpact, currentCrashScore, currentGyroRatio, currentPressDelta, totalRotation, snapshot)
                } else {
                    windowPeak = windowPeak.copy(
                        wMaxLongitudinalG = if (abs(longG) > abs(windowPeak.wMaxLongitudinalG)) longG else windowPeak.wMaxLongitudinalG,
                        wMaxLateralG = if (abs(latG) > abs(windowPeak.wMaxLateralG)) latG else windowPeak.wMaxLateralG,
                        wCrashScore = maxOf(windowPeak.wCrashScore, currentCrashScore),
                        wGyroRatio = maxOf(windowPeak.wGyroRatio, currentGyroRatio),
                        wPressureDelta = if (abs(currentPressDelta) > abs(windowPeak.wPressureDelta)) currentPressDelta else windowPeak.wPressureDelta,
                        wRollSum = maxOf(windowPeak.wRollSum, totalRotation)
                    )
                }

                val newState = TelemetryState(
                    currentImpact = currentImpact, maxImpact = overallPeak.maxImpact,
                    peakCrashScore = (overallPeak.pCrashScore * 100).toInt(),
                    windowImpact = windowPeak.windowImpact,
                    windowCrashScore = (windowPeak.wCrashScore * 100).toInt(),
                    currentMode = currentMode, vehicleInferenceState = vInferenceState, 
                    detectedVehicleIncident = detectedType, lastUpdateTime = now,
                    sessionStartTime = sessionStartTime, accelX = snapshot[0], accelY = snapshot[1], accelZ = snapshot[2],
                    gyroX = snapshot[3], gyroY = snapshot[4], gyroZ = snapshot[5],
                    airPressure = snapshot[6], rotationSpeed = snapshot[3], gpsSpeed = currentGpsSpeed * 3.6f,
                    crashScore = currentCrashScore, gyroRatio = currentGyroRatio,
                    pressureDelta = currentPressDelta, rollSum = totalRotation,
                    pTimestamp = overallPeak.pTimestamp, pCrashScore = overallPeak.pCrashScore,
                    pGyroRatio = overallPeak.pGyroRatio, pRollSum = overallPeak.pRollSum,
                    pPressureDelta = overallPeak.pPressureDelta, maxLongitudinalG = overallPeak.maxLongitudinalG,
                    maxLateralG = overallPeak.maxLateralG, maxSpeedDrop = overallPeak.maxSpeedDrop,
                    wTimestamp = windowPeak.wTimestamp, wCrashScore = windowPeak.wCrashScore,
                    wGyroRatio = windowPeak.wGyroRatio, wRollSum = windowPeak.wRollSum,
                    wPressureDelta = windowPeak.wPressureDelta, wMaxLongitudinalG = windowPeak.wMaxLongitudinalG,
                    wMaxLateralG = windowPeak.wMaxLateralG, wMaxSpeedDrop = windowPeak.wMaxSpeedDrop
                )
                _telemetry.value = newState
                
                if (isDashboardActive || (now - lastStreamTime >= 100L)) {
                    broadcastStatusToMobile(newState)
                    lastStreamTime = now
                }
            }
        }
    }

    private fun updateMonitoringRate(now: Long) {
        val speedKmh = currentGpsSpeed * 3.6f
        var targetRate: Int = dynamicRateMs
        
        // v23.6: Fast monitoring during PRE_IMPACT or higher states
        if (vInferenceState == VehicleInferenceState.PRE_IMPACT ||
            vInferenceState == VehicleInferenceState.IMPACT_DETECTED || 
            vInferenceState == VehicleInferenceState.STILLNESS) {
            targetRate = 100
        } else {
            targetRate = when {
                speedKmh >= 80f -> 100
                speedKmh >= 30f && speedKmh < 75f -> 200
                speedKmh < 25f -> 400
                dynamicRateMs == 400 && speedKmh >= 30f -> 200
                dynamicRateMs == 200 && speedKmh >= 80f -> 100
                else -> dynamicRateMs
            }
        }
        if (targetRate != dynamicRateMs && (now - lastRateChangeTime > RATE_CHANGE_COOLDOWN_MS)) {
            dynamicRateMs = targetRate
            lastRateChangeTime = now
            if (_currentState.value == IncidentState.MONITORING || isDashboardActive) registerSensors()
        }
    }

    private fun updateOverallSnapshot(impact: Float, score: Float, gyro: Float, press: Float, roll: Float, data: FloatArray) = overallPeak.copy(
        maxImpact = impact, pTimestamp = System.currentTimeMillis(), pCrashScore = score,
        pGyroRatio = gyro, pRollSum = roll, maxLongitudinalG = data[1] / 9.81f, maxLateralG = data[0] / 9.81f
    )

    private fun updateWindowSnapshot(impact: Float, score: Float, gyro: Float, press: Float, roll: Float, data: FloatArray) = windowPeak.copy(
        windowImpact = impact, wTimestamp = System.currentTimeMillis(), wCrashScore = score,
        wGyroRatio = gyro, wRollSum = roll, wMaxLongitudinalG = data[1] / 9.81f, wMaxLateralG = data[0] / 9.81f
    )

    private fun setVInferenceState(newState: VehicleInferenceState) {
        if (vInferenceState != newState) {
            // CRITICAL: TRIGGER EVIDENCE CAPTURE ON STATE TRANSITION TO IMPACT
            if (newState == VehicleInferenceState.IMPACT_DETECTED) {
                Log.w(TAG, "🚨 IMPACT DETECTED! Triggering evidence capture immediately.")
                captureIncidentSnapshot("Vehicle Crash: $detectedType")
                startEvidenceRecording()
            }
            vInferenceState = newState
            stateEntryTime = System.currentTimeMillis()
            broadcastStatusToMobile(_telemetry.value)
        }
    }

    private fun updateVehicleInference(accel: FloatArray, gyro: FloatArray, speed: Float, pressure: Float) {
        val now = System.currentTimeMillis()
        val g = magnitude(accel) / 9.81f
        val speedKmh = speed * 3.6f

        when (vInferenceState) {
            VehicleInferenceState.IDLE -> {
                if (g > 4.5f) {
                    detectedType = VehicleIncidentType.NONE
                    classifyImpact(accel, gyro, speed)
                    setVInferenceState(VehicleInferenceState.IMPACT_DETECTED)
                } else if (speedKmh > 15f || isSimulatingLocked) {
                    setVInferenceState(VehicleInferenceState.DRIVING)
                    lastMoveTime = now
                }
            }
            VehicleInferenceState.DRIVING -> {
                if (speedKmh > 2f) lastMoveTime = now
                if (now - lastMoveTime > 300000L && !isSimulatingLocked) {
                    setVInferenceState(VehicleInferenceState.IDLE)
                    return
                }

                // Logic for PRE_IMPACT could be added here if braking or erratic motion is detected

                if (g > 4.0f) {
                    classifyImpact(accel, gyro, speed)
                    setVInferenceState(VehicleInferenceState.IMPACT_DETECTED) 
                }
            }
            VehicleInferenceState.IMPACT_DETECTED -> if (now - stateEntryTime > 1000L) setVInferenceState(VehicleInferenceState.STILLNESS)
            VehicleInferenceState.STILLNESS -> {
                val stillReq = if (isSimulatingLocked) 500L else 3000L
                if (magnitude(accel) < 11.0f && now - stateEntryTime > stillReq) onIncidentDetected()
            }
            else -> {}
        }
    }

    private fun startEvidenceRecording() {
        audioRecordingJob?.cancel()
        recordedAudioFiles.clear()
        audioRecordingJob = serviceScope.launch(Dispatchers.IO) {
            recordSegment(1)
            recordSegment(2)
        }
    }

    private suspend fun recordSegment(index: Int) {
        try {
            val file = File(cacheDir, "temp_evidence_$index.aac")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@SentinelService) else MediaRecorder()
            mediaRecorder = recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            Log.d(TAG, "🎙 Recording segment $index started (10s)")
            delay(10000)
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
            recordedAudioFiles.add(file)
        } catch (e: Exception) { Log.e(TAG, "Audio Error $index: ${e.message}") }
        mediaRecorder = null
    }

    private fun stopAudioRecording() {
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
    }

    private fun captureIncidentSnapshot(reason: String) {
        serviceScope.launch {
            val loc = fetchBestLocation()
            val now = System.currentTimeMillis()
            val localTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
            
            val sensorHistory = dataLogger.getOrderedSnapshot().map { (timestamp, data) ->
                TelemetryPoint(
                    t = timestamp,
                    offset = timestamp - now,
                    ax = data[0]/9.81f, ay = data[1]/9.81f, az = data[2]/9.81f,
                    gx = data[3], gy = data[4], gz = data[5], spd = data[11]*3.6f,
                    mag = magnitude(floatArrayOf(data[0], data[1], data[2]))/9.81f
                )
            }
            pendingIncidentSnapshot = IncidentData(
                type = reason, timestamp = now, utcTime = localTimeStr, 
                latitude = loc?.latitude, longitude = loc?.longitude, 
                maxG = overallPeak.maxImpact, speed = currentGpsSpeed * 3.6f, 
                sensorData = sensorHistory, isSimulation = isSimulatingLocked
            )
        }
    }

    fun finalizeEmergencyDispatch(type: String, t: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        alertDispatcher.dispatch(currentSettings, type, t, lat, lon, maxG, speed)
        serviceScope.launch {
            val snapshot = pendingIncidentSnapshot ?: return@launch
            val shortTypeName = type.split(":").last().trim().replace(" ", "")
            val timestampStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(t))
            val baseFileName = "$timestampStr-$shortTypeName"

            // 1. Save to Watch /Download folder (PERSISTENT)
            savePersistentEvidence(snapshot, baseFileName)

            // 2. Try Sync to mobile
            try {
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                val reportJson = explicitJson.encodeToString(snapshot)
                for (node in nodes) {
                    Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_REPORT, reportJson.toByteArray())
                    
                    serviceScope.launch {
                        audioRecordingJob?.join() // Wait for 20s recording to finish
                        recordedAudioFiles.forEachIndexed { idx, file ->
                            if (file.exists()) {
                                val asset = Asset.createFromBytes(file.readBytes())
                                val dataMap = PutDataMapRequest.create(ProtocolContract.Paths.INCIDENT_AUDIO_ASSET).apply {
                                    dataMap.putAsset(ProtocolContract.Keys.AUDIO_FILE, asset)
                                    dataMap.putString("file_name", "$baseFileName-part${idx + 1}")
                                    dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                                }.asPutDataRequest().setUrgent()
                                Wearable.getDataClient(this@SentinelService).putDataItem(dataMap).await()
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Sync failed, evidence preserved locally in /Download.") }
            resetFsmAfterDispatch()
        }
    }

    private fun savePersistentEvidence(snapshot: IncidentData, baseName: String) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watch2out")
            if (!dir.exists()) dir.mkdirs()
            
            val jsonFile = File(dir, "$baseName.json")
            FileOutputStream(jsonFile).use { it.write(explicitJson.encodeToString(snapshot).toByteArray()) }

            serviceScope.launch {
                audioRecordingJob?.join()
                recordedAudioFiles.forEachIndexed { idx, file ->
                    if (file.exists()) {
                        val persistentAudio = File(dir, "$baseName-part${idx + 1}.aac")
                        file.copyTo(persistentAudio, overwrite = true)
                    }
                }
                Log.i(TAG, "📁 Persistent evidence saved to /Download/watch2out")
            }
        } catch (e: Exception) { Log.e(TAG, "Storage Error: ${e.message}") }
    }

    private fun resetFsmAfterDispatch() {
        Log.w(TAG, "🏁 Incident handled. Resuming MONITORING.")
        _currentState.value = IncidentState.MONITORING
        vInferenceState = VehicleInferenceState.IDLE
        detectedType = VehicleIncidentType.NONE
        pendingIncidentSnapshot = null
        isSimulatingLocked = false
        currentGpsSpeed = 0f
        prevGpsSpeed = 0f
        lastMoveTime = System.currentTimeMillis()
        broadcastStatusToMobile(_telemetry.value)
    }

    private fun dismissIncidentInternal() {
        Log.w(TAG, "Dismissing incident alert.")
        audioRecordingJob?.cancel()
        try { mediaRecorder?.stop() } catch (e: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        recordedAudioFiles.forEach { it.delete() }
        resetFsmAfterDispatch()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(INCIDENT_NOTIFICATION_ID)
        sendBroadcast(Intent("com.jinn.watch2out.DISMISS_ALERT").apply { setPackage(packageName) })
        serviceScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_DISMISS, null)
            } catch (e: Exception) {}
        }
    }

    private fun resetPeaks() { 
        overallPeak = TelemetryState().copy(sessionStartTime = System.currentTimeMillis())
        windowPeak = TelemetryState()
        sessionStartTime = System.currentTimeMillis()
        totalRotation = 0f
        lastGyroTimeNs = 0L
        lastSignificantMotionTime = System.currentTimeMillis()
        vInferenceState = VehicleInferenceState.IDLE
        detectedType = VehicleIncidentType.NONE
        pendingIncidentSnapshot = null
        isSimulatingLocked = false
        currentGpsSpeed = 0f
        prevGpsSpeed = 0f
        lastMoveTime = System.currentTimeMillis()
        broadcastStatusToMobile(_telemetry.value)
    }

    override fun onStartCommand(intent: Intent?, f: Int, s: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> { _currentState.value = IncidentState.MONITORING; resetPeaks(); registerSensors() }
            ACTION_STOP_MONITORING -> { _currentState.value = IncidentState.IDLE; unregisterSensors() }
            ACTION_RESET_PEAKS -> resetPeaks()
            ACTION_DASHBOARD_START -> { isDashboardActive = true; registerSensors() }
            ACTION_DASHBOARD_STOP -> { isDashboardActive = false; if (_currentState.value != IncidentState.MONITORING) unregisterSensors() }
            ACTION_SIMULATE -> simulateIncident(intent.getStringExtra("path") ?: "")
            ACTION_INJECT_DATA -> injectCustomSensorData(intent.getStringExtra("csv") ?: "")
            ACTION_DISMISS_INCIDENT -> dismissIncidentInternal()
            ACTION_FINAL_DISPATCH -> finalizeEmergencyDispatch(intent.getStringExtra("type") ?: "Incident", intent.getLongExtra("timestamp", 0L), if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null, if (intent.hasExtra("lon")) intent.getDoubleExtra("lon", 0.0) else null, intent.getFloatExtra("maxG", 0f), intent.getFloatExtra("speed", 0f))
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun registerSensors() { 
        unregisterSensors()
        Handler(Looper.getMainLooper()).post {
            try { 
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this)
                }
                val rateUs = (dynamicRateMs * 1000).coerceAtLeast(10000)
                val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accel != null) sensorManager.registerListener(this, accel, rateUs)
                val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                if (gyro != null) sensorManager.registerListener(this, gyro, rateUs)
                val press = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
                if (press != null) sensorManager.registerListener(this, press, SensorManager.SENSOR_DELAY_UI)
                val rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (rot != null) sensorManager.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI)
            } catch (e: Exception) { Log.e(TAG, "Sensor registration failed: ${e.message}") } 
        }
    }
    
    private fun unregisterSensors() { sensorManager.unregisterListener(this); locationManager.removeUpdates(this) }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || isSimulatingLocked) return
        synchronized(currentReading) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    currentReading[0] = event.values[0]; currentReading[1] = event.values[1]; currentReading[2] = event.values[2]
                    if (_currentState.value == IncidentState.MONITORING) updateVehicleInference(event.values, floatArrayOf(currentReading[3],currentReading[4],currentReading[5]), currentGpsSpeed, currentReading[6])
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val dx = event.values[0]; val dy = event.values[1]; val dz = event.values[2]
                    currentReading[3] = dx; currentReading[4] = dy; currentReading[5] = dz
                    val now = System.nanoTime()
                    if (lastGyroTimeNs != 0L) {
                        val dt = (now - lastGyroTimeNs) / 1_000_000_000f
                        val mag = sqrt(dx*dx + dy*dy + dz*dz)
                        if (mag > 1.05f) { totalRotation += mag * dt * 57.2958f }
                        else { totalRotation *= 0.95f; if (totalRotation < 1.0f) totalRotation = 0f }
                    }
                    lastGyroTimeNs = now
                    if (totalRotation >= 360f) totalRotation = 0f
                }
                Sensor.TYPE_PRESSURE -> {
                    currentReading[6] = event.values[0]
                    if (baselinePressure == 0f) baselinePressure = event.values[0]
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    currentReading[7] = event.values[0]; currentReading[8] = event.values[1]; currentReading[9] = event.values[2]; currentReading[10] = event.values[3]
                }
            }
        }
    }
    
    override fun onLocationChanged(l: Location) { 
        lastLocation = l
        if (!isSimulatingLocked) {
            currentGpsSpeed = if (l.hasSpeed()) l.speed else 0f
            synchronized(currentReading) {
                currentReading[11] = currentGpsSpeed
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() { super.onDestroy() ; serviceScope.cancel() }

    private fun magnitude(v: FloatArray) = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])

    private fun fetchBestLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null && System.currentTimeMillis() - loc.time < 60000) return loc
            } catch (e: Exception) {}
        }
        return lastLocation
    }

    private fun classifyImpact(accel: FloatArray, gyro: FloatArray, speed: Float) {
        detectedType = if (accel[1] < 0) VehicleIncidentType.FRONTAL else VehicleIncidentType.REAR_END
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sentinel", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(INCIDENT_CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); setBypassDnd(true) })
    }

    private fun createForegroundNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Watch2Out Active").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()

    private fun broadcastStatusToMobile(telemetry: TelemetryState) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val request = PutDataMapRequest.create(ProtocolContract.Paths.STATUS_SYNC).apply {
            dataMap.putBoolean(ProtocolContract.Keys.IS_ACTIVE, _currentState.value == IncidentState.MONITORING)
            dataMap.putString(ProtocolContract.Keys.ACCEL_STATUS, getSensorStatus(sm, Sensor.TYPE_ACCELEROMETER, currentSettings.isAccelEnabled).name)
            dataMap.putString(ProtocolContract.Keys.GYRO_STATUS, getSensorStatus(sm, Sensor.TYPE_GYROSCOPE, currentSettings.isGyroEnabled).name)
            dataMap.putString(ProtocolContract.Keys.PRESS_STATUS, getSensorStatus(sm, Sensor.TYPE_PRESSURE, currentSettings.isPressureEnabled).name)
            dataMap.putString(ProtocolContract.Keys.ROT_STATUS, getSensorStatus(sm, Sensor.TYPE_ROTATION_VECTOR, true).name)
            try { dataMap.putString(ProtocolContract.Keys.TELEMETRY_JSON, explicitJson.encodeToString(TelemetryState.serializer(), telemetry)) } catch(e: Exception) {}
            dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
         Wearable.getDataClient(this).putDataItem(request)
    }

    private fun getSensorStatus(sm: SensorManager, type: Int, isEnabled: Boolean): SensorStatus {
        val s = sm.getDefaultSensor(type); return when { s == null -> SensorStatus.MISSING; !isEnabled -> SensorStatus.DISABLED; else -> SensorStatus.AVAILABLE }
    }

    private suspend fun handleSettingsChange(s: WatchSettings) { 
        currentSettings = s
        dataLogger.reconfigure(s)
        if (_currentState.value == IncidentState.MONITORING || isDashboardActive) registerSensors() 
    }

    private fun onIncidentDetected() {
        if (_currentState.value == IncidentState.TRIGGERED && !isSimulatingLocked) return
        val data = pendingIncidentSnapshot ?: return
        _currentState.value = IncidentState.TRIGGERED
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "WWOut:IncidentWake")
        wakeLock.acquire(30000)
        serviceScope.launch {
            try {
                val json = explicitJson.encodeToString(data)
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_START, json.toByteArray())
            } catch (e: Exception) {}
        }
        val intent = Intent(this, IncidentAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("reason", data.type); putExtra("timestamp", data.timestamp); putExtra("lat", data.latitude ?: 0.0); putExtra("lon", data.longitude ?: 0.0); putExtra("has_location", data.latitude != null); putExtra("maxG", data.maxG); putExtra("speed", data.speed)
        }
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
        val notification = NotificationCompat.Builder(this, INCIDENT_CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("CRASH DETECTED").setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(pendingIntent, true).setAutoCancel(true).setOngoing(true).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(INCIDENT_NOTIFICATION_ID, notification)
        try { startActivity(intent, options.toBundle()) } catch (e: Exception) {}
    }

    fun simulateIncident(path: String) {
        isSimulatingLocked = true; 
        captureIncidentSnapshot("Simulated Incident")
        startEvidenceRecording()
        serviceScope.launch { delay(500); onIncidentDetected(); isSimulatingLocked = false }
    }

    fun injectCustomSensorData(csv: String) {
        serviceScope.launch {
            isSimulatingLocked = true
            try {
                val p = csv.split(",").map { it.trim().toFloat() }
                if (p.size >= 6) {
                    val accel = floatArrayOf(p[0], p[1], p[2])
                    synchronized(currentReading) { 
                        currentReading[0] = p[0]; currentReading[1] = p[1]; currentReading[2] = p[2]
                        currentReading[6] = p[5]; currentGpsSpeed = p[4] / 3.6f; currentReading[11] = currentGpsSpeed
                    }
                    updateVehicleInference(accel, floatArrayOf(p[3], 0f, 0f), p[4] / 3.6f, p[5])
                    delay(1000)
                    for (i in 1..3) { updateVehicleInference(floatArrayOf(0f, 0f, 9.81f), floatArrayOf(0f, 0f, 0f), 0f, p[5]); delay(1000) }
                }
            } finally { isSimulatingLocked = false }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val INCIDENT_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "sentinel_channel"
        private const val INCIDENT_CHANNEL_ID = "incident_channel"
        const val ACTION_START_MONITORING = "ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING = "ACTION_STOP_MONITORING"
        const val ACTION_RESET_PEAKS = "ACTION_RESET_PEAKS"
        const val ACTION_DASHBOARD_START = "ACTION_DASHBOARD_START"
        const val ACTION_DASHBOARD_STOP = "ACTION_DASHBOARD_STOP"
        const val ACTION_SIMULATE = "ACTION_SIMULATE"
        const val ACTION_INJECT_DATA = "ACTION_INJECT_DATA"
        const val ACTION_DISMISS_INCIDENT = "ACTION_DISMISS_INCIDENT"
        const val ACTION_FINAL_DISPATCH = "ACTION_FINAL_DISPATCH"
        @Volatile var lastKnownState: IncidentState = IncidentState.IDLE; private set
    }
}
