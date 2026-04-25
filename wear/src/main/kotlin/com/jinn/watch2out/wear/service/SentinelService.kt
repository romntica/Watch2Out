// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.LifecycleService
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.shared.util.CrashScoreCalculator
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.wear.R
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.data.TelemetryLogStore
import com.jinn.watch2out.wear.presentation.IncidentAlertActivity
import com.jinn.watch2out.wear.presentation.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sentinel Service (v28.0): Watch GPS Only Architecture.
 * 
 * POLICY RE-DEFINITION:
 * - Primary Source: Watch Hardware GPS ONLY.
 * - Mobile GPS: Reserved/Excluded from active logic.
 * - FSM & Score: Driven by Watch GPS speed.
 * - Sync: Only user intent and status are real-time.
 */
class SentinelService : LifecycleService(), SensorEventListener, LocationListener {

    private val TAG = "SentinelFSM"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataClientMutex = Mutex()
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private lateinit var logStore: TelemetryLogStore
    private lateinit var settingsRepository: SettingsRepository
    private val logBuffer = mutableListOf<TelemetryLogEntry>()
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var lastBatchSaveTime = 0L
    private var lastLogUploadTime = 0L

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    
    private val _currentState = MutableStateFlow(IncidentState.IDLE)
    val currentState: StateFlow<IncidentState> = _currentState.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private var vInferenceState = VehicleInferenceState.IDLE
    private var isSimulatingLocked = false
    
    /**
     * GPS Processing Policy: Raw Real-time Direct Reception (v28.1).
     * - No tolerance, no smoothing, no filtering.
     * - Handled identically to SensorManager: Callback -> Raw Buffer -> Processing Loop.
     */
    private val currentReading = FloatArray(15) 
    private var lastWatchGpsTime = 0L
    private var lastLocationTime = 0L
    private var lastLocationInterval = 0L
    private var gnssSignalSeen = false
    private var gpsProviderEnabled = false
    
    // Stationary Gate State (v28.3)
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastDisplacement = 0f
    private var stationaryGateApplied = false
    private var currentSpeedReason = "RAW"

    // Spike & Stall State (v28.4)
    private val speedHistory = mutableListOf<Float>()
    private var lastBearing = 0f
    private var bearingChange = 0f
    private var currentSatelliteCount = 0
    private var isStalled = false
    private var spikeSuppressed = false
    private var stallDecaySpeedMps = 0f
    private var stallStartTime = 0L
    private var stallInitialSpeedMps = 0f

    private val gnssStatusCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            currentSatelliteCount = status.satelliteCount
            gnssSignalSeen = currentSatelliteCount > 0
        }
    }
    private var lastBatteryCheckTime = 0L
    private var lastBatteryLevel = -1
    private var batteryConsumptionPerHour = 0f
    private var lastHeartbeatTime = 0L

    private fun updateBatteryDiagnostics() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SystemClock.elapsedRealtime()
        
        if (lastBatteryLevel != -1 && now - lastBatteryCheckTime > 60000) { // Calculate every 1 minute
            val diff = lastBatteryLevel - level
            val hours = (now - lastBatteryCheckTime) / 3600000f
            if (hours > 0) {
                batteryConsumptionPerHour = diff / hours
            }
        }
        
        if (lastBatteryLevel == -1 || now - lastBatteryCheckTime > 3600000) {
            lastBatteryLevel = level
            lastBatteryCheckTime = now
        }
    }
    private var overallPeak = TelemetryState()
    private var baselinePressure = 0f
    private var currentSettings = WatchSettings()
    private var telemetryCounter = 0
    private var isHighSpeedSyncRequested = false
    private val THROTTLE_BATTERY_SAVE = 25 // ~5s at 200ms loop
    private val THROTTLE_HIGH_SPEED = 1    // Every cycle (~200ms)

    // Policy Flag: Compile-time isolation for Reserved Phone GPS
    private val USE_PHONE_GPS_RESERVED = false 

    var currentGpsMode = GpsMode.WATCH_ONLY
        private set

    private val G_EARTH = 9.80665f
    private val MPS_TO_KMH = 3.6f

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        logStore = TelemetryLogStore(this)
        settingsRepository = SettingsRepository(this)
        lastLogUploadTime = System.currentTimeMillis()
        
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Watch² Out Sentinel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            
            initHardwareIPC()
            startProcessingLoop()
        }
    }

    override fun onDestroy() {
        isRunning = false
        stopWatchGps()
        sensorManager?.unregisterListener(this)
        sensorThread?.quitSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        if (intent == null) {
            Log.w(TAG, "SentinelService restarted with NULL intent (Crash Recovery)")
            serviceScope.launch {
                val persistedState = settingsRepository.monitoringStateFlow.first()
                if (persistedState == IncidentState.MONITORING.name) {
                    Log.i(TAG, "Resuming monitoring after crash/restart")
                    withContext(Dispatchers.Main) {
                        startMonitoringLogic()
                    }
                }
            }
            return Service.START_STICKY
        }

        val action = intent.action ?: return Service.START_STICKY
        
        try {
            when (action) {
                ACTION_START_MONITORING -> {
                    startMonitoringLogic()
                }
                ACTION_STOP_MONITORING -> {
                    _currentState.value = IncidentState.IDLE
                    lastKnownState = IncidentState.IDLE
                    serviceScope.launch { settingsRepository.updateMonitoringState(IncidentState.IDLE.name) }
                    setVInferenceState(VehicleInferenceState.IDLE)
                    stopWatchGps()
                    stopAudioRecording(delete = true)

                    // Phase 7: Final Sync (v28.6) - Set status to AVAILABLE (actual hardware state) 
                    // instead of DISABLED, so Pull-to-Refresh can see Wear state while stopped.
                    val finalTelemetry = _telemetry.value.copy(
                        vehicleInferenceState = VehicleInferenceState.IDLE,
                        accelStatus = getSensorStatus(Sensor.TYPE_ACCELEROMETER),
                        gyroStatus = getSensorStatus(Sensor.TYPE_GYROSCOPE),
                        pressureStatus = getSensorStatus(Sensor.TYPE_PRESSURE),
                        rotationStatus = getSensorStatus(Sensor.TYPE_ROTATION_VECTOR),
                        isWatchGpsActive = false,
                        isGpsActive = false,
                        wearTimestamp = System.currentTimeMillis(),
                        gpsStatus = SensorStatus.UNAVAILABLE,
                        gpsStatusText = "Available"
                    )
                    _telemetry.value = finalTelemetry
                    
                    serviceScope.launch {
                        try {
                            val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.STATUS_SYNC).apply {
                                dataMap.putBoolean(ProtocolContract.Keys.IS_ACTIVE, false)
                                dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                                dataMap.putString(ProtocolContract.Keys.LOC_STATUS, currentGpsMode.name)
                                
                                dataMap.putString(ProtocolContract.Keys.ACCEL_STATUS, finalTelemetry.accelStatus.name)
                                dataMap.putString(ProtocolContract.Keys.GYRO_STATUS, finalTelemetry.gyroStatus.name)
                                dataMap.putString(ProtocolContract.Keys.PRESS_STATUS, finalTelemetry.pressureStatus.name)
                                dataMap.putString(ProtocolContract.Keys.ROT_STATUS, finalTelemetry.rotationStatus.name)
                                dataMap.putString(ProtocolContract.Keys.WATCH_GPS_STATUS, finalTelemetry.gpsStatus.name)
                                dataMap.putString(ProtocolContract.Keys.WATCH_GPS_TEXT, finalTelemetry.gpsStatusText)
                                
                                val json = Json.encodeToString(finalTelemetry)
                                dataMap.putString(ProtocolContract.Keys.TELEMETRY_JSON, json)
                                setUrgent()
                            }.asPutDataRequest()
                            
                            // Wait for delivery before killing service
                            Wearable.getDataClient(this@SentinelService).putDataItem(putDataReq).await()
                            Log.d(TAG, "Final Stop Sync Delivered")
                        } catch (e: Exception) {
                            Log.e(TAG, "Final Sync failed", e)
                        } finally {
                            withContext(Dispatchers.Main) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                    SentinelComplicationService.update(this)
                }
                ACTION_DISMISS_INCIDENT -> {
                    setVInferenceState(VehicleInferenceState.IDLE)
                    stopAudioRecording(delete = true)
                    SentinelComplicationService.update(this)
                }
                ACTION_FINAL_DISPATCH -> {
                    Log.w(TAG, "FINAL DISPATCH")
                    setVInferenceState(VehicleInferenceState.CONFIRMED_CRASH)
                }
                ACTION_UPDATE_HEARTBEAT -> {
                    val json = intent.getStringExtra("heartbeat_json") ?: ""
                    if (json.isNotEmpty()) {
                        val hb = Json.decodeFromString<Heartbeat>(json)
                        lastHeartbeatTime = SystemClock.elapsedRealtime()

                        // POLICY: Phone GPS is strictly Reserved. Do NOT update currentGpsSpeed.
                        if (USE_PHONE_GPS_RESERVED) {
                             Log.v(TAG, "Reserved Phone Speed: ${hb.phoneSpeedKmh} km/h")
                        }

                        _telemetry.value = _telemetry.value.copy(
                            isPhoneGpsActive = hb.phoneGpsStatus == PhoneGpsStatus.AVAILABLE,
                            phoneGpsAccuracy = hb.phoneGpsAccuracy,
                            isHintReliable = hb.isSpeedHintReliable,
                            gpsAgeMs = hb.elapsedMs,
                            hbAgeMs = 0L
                        )
                    }
                }
                ACTION_SIMULATE -> {
                    simulateIncident(intent.getStringExtra("path") ?: "")
                }
                ACTION_INJECT_DATA -> {
                    injectCustomSensorData(intent.getStringExtra("csv") ?: "")
                }
                ACTION_UPDATE_SYNC_POLICY -> {
                    val wasHighSpeed = isHighSpeedSyncRequested
                    isHighSpeedSyncRequested = intent.getBooleanExtra("high_speed", false)
                    Log.d(TAG, "Sync Policy Updated: HighSpeed=$isHighSpeedSyncRequested")
                    // Force immediate sync when switching to high speed or if specifically requested
                    if (isHighSpeedSyncRequested && !wasHighSpeed) {
                        telemetryCounter = 0 
                    }
                }
                ACTION_FORCE_SYNC -> {
                    Log.d(TAG, "Force sync requested")
                    sendTelemetryToPhone(_telemetry.value, fullSync = true)
                    // Immediate Log Upload (v28.6.3)
                    serviceScope.launch {
                        uploadPendingLogs()
                    }
                }
                ACTION_SENSOR_STATUS_SYNC -> {
                    Log.d(TAG, "Immediate sensor status sync requested")
                    // Immediate response with current hardware availability
                    val telemetry = _telemetry.value.copy(
                        accelStatus = getSensorStatus(Sensor.TYPE_ACCELEROMETER),
                        gyroStatus = getSensorStatus(Sensor.TYPE_GYROSCOPE),
                        pressureStatus = getSensorStatus(Sensor.TYPE_PRESSURE),
                        rotationStatus = getSensorStatus(Sensor.TYPE_ROTATION_VECTOR),
                        wearTimestamp = System.currentTimeMillis()
                    )
                    sendTelemetryToPhone(telemetry, fullSync = false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand for action: $action", e)
        }
        return Service.START_STICKY
    }

    private fun startMonitoringLogic() {
        _currentState.value = IncidentState.MONITORING
        lastKnownState = IncidentState.MONITORING
        serviceScope.launch { settingsRepository.updateMonitoringState(IncidentState.MONITORING.name) }
        baselinePressure = 0f
        startWatchGps()
        // Phase 7: Immediate sync on start to update Mobile UI (v28.6)
        sendTelemetryToPhone(_telemetry.value, fullSync = true)
        SentinelComplicationService.update(this)
    }

    private fun createNotificationChannel(): String {
        val channelId = "sentinel_service"
        val channel = NotificationChannel(channelId, "Sentinel Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
        return channelId
    }

    private fun initHardwareIPC() {
        if (sensorThread != null) return
        
        Log.i(TAG, "Initializing Hardware IPC & Registering Sensors")
        val thread = HandlerThread("SentinelSensorThread").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        val sm = sensorManager ?: return
        val sensors = listOf(
            Sensor.TYPE_ACCELEROMETER to "Accel",
            Sensor.TYPE_GYROSCOPE to "Gyro",
            Sensor.TYPE_PRESSURE to "Pressure",
            Sensor.TYPE_ROTATION_VECTOR to "Rotation"
        )
        
        sensors.forEach { (type, name) ->
            val sensor = sm.getDefaultSensor(type)
            if (sensor != null) {
                val success = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                val delay = sensor?.minDelay
                Log.i(TAG, "Sensor registration: $name -> $success (delay=$delay)")
            } else {
                Log.w(TAG, "Sensor missing: $name")
            }
        }
    }

    // IMU Logic (v28.5)
    private var lastAccelMag = 1.0f
    private var imuMotionState = "UNKNOWN"
    private var anomalyType = "NONE"
    private var decisionReason = "INITIALIZING"
    private var displayDecisionPath = "NONE"
    private var gpsConfidence = 1.0f
    private var callbackContinuityScore = 1.0f
    private var realEventConfidence = 0f

    // Diagnostic Weights (v28.5)
    private var currentGpsWeight = 0f
    private var currentImuWeight = 0f
    private var continuityWeight = 0f
    private var displacementWeight = 0f
    private var bearingWeight = 0f

    private var loopDelay = 500L

    private fun updateImuMotionState(impactG: Float, gyroRms: Float) {
        val motion = if (impactG > 1.2f || gyroRms > 15f) "ACTIVE_HIGH"
                    else if (impactG > 1.05f || gyroRms > 5f) "ACTIVE_LOW"
                    else "STATIONARY"
        imuMotionState = motion
    }

    private fun startProcessingLoop() {
        serviceScope.launch {
            while (isActive) {
                val startTime = SystemClock.elapsedRealtime()
                val impact = calculateCurrentImpact()
                
                val gx = currentReading[3]; val gy = currentReading[4]; val gz = currentReading[5]
                val gyroRms = Math.sqrt((gx*gx + gy*gy + gz*gz).toDouble()).toFloat()
                
                updateImuMotionState(impact, gyroRms)

                val currentPressure = currentReading[6]
                val pDelta = if (baselinePressure > 0f) currentPressure - baselinePressure else 0f
                
                val rotVec = currentReading.sliceArray(7..10)
                val rollDeg = calculateRoll(rotVec)

                // RAW GPS SPEED (Index 11) - Direct Hardware Value
                val rawGpsSpeedMps = currentReading[11]
                val rawGpsSpeedKmh = rawGpsSpeedMps * MPS_TO_KMH
                val gpsAccuracy = currentReading[12]
                val currentBearing = currentReading[13]
                
                // 1. Advanced Anomaly Detection (v28.5)
                val now = SystemClock.elapsedRealtime()
                val watchGpsAge = if (lastWatchGpsTime > 0) now - lastWatchGpsTime else -1L
                
                // Calculate Callback Continuity Score
                callbackContinuityScore = if (lastLocationInterval > 0) {
                    Math.max(0f, 1f - (Math.abs(lastLocationInterval - 1000f) / 3000f))
                } else 0.5f

                // Displacement Consistency: Check if reported speed matches actual movement
                val expectedDisplacement = rawGpsSpeedMps * (lastLocationInterval / 1000f)
                val displacementScore = if (expectedDisplacement > 0.5f) {
                    Math.min(1.2f, lastDisplacement / expectedDisplacement).coerceIn(0f, 1f)
                } else 1.0f

                // Calculate GPS Confidence with State-Dependent Weights (v28.5)
                val accScore = Math.max(0f, 1f - (gpsAccuracy / 80f))
                val ageScore = if (watchGpsAge >= 0) Math.max(0f, 1f - (watchGpsAge / 10000f)) else 0f
                val satScore = Math.min(1f, currentSatelliteCount / 6f)
                
                bearingChange = Math.abs(currentBearing - lastBearing)
                if (bearingChange > 180) bearingChange = 360 - bearingChange
                lastBearing = currentBearing
                val bearingScore = Math.max(0f, 1f - (bearingChange / 90f))

                if (rawGpsSpeedKmh > 20f) {
                    // Moving State: High Speed
                    continuityWeight = 0.15f
                    displacementWeight = 0.20f
                    bearingWeight = 0.25f
                    currentImuWeight = 0.20f
                    currentGpsWeight = 0.20f // Accuracy/Sat/Age combined
                    
                    val gpsInternal = (accScore * 0.4f + ageScore * 0.3f + satScore * 0.3f)
                    gpsConfidence = (gpsInternal * currentGpsWeight + 
                                     displacementScore * displacementWeight + 
                                     bearingScore * bearingWeight + 
                                     (1f - realEventConfidence) * currentImuWeight + 
                                     callbackContinuityScore * continuityWeight)
                } else {
                    // Low Speed / Stationary State
                    continuityWeight = 0.30f
                    displacementWeight = 0.40f
                    bearingWeight = 0.05f // Ignore bearing noise at low speed
                    currentImuWeight = 0.15f
                    currentGpsWeight = 0.10f
                    
                    val gpsInternal = (accScore * 0.4f + ageScore * 0.3f + satScore * 0.3f)
                    gpsConfidence = (gpsInternal * currentGpsWeight + 
                                     displacementScore * displacementWeight + 
                                     bearingScore * bearingWeight + 
                                     (1f - realEventConfidence) * currentImuWeight + 
                                     callbackContinuityScore * continuityWeight)
                }

                // IMU Real Event Confidence
                realEventConfidence = Math.min(1f, Math.max(0f, (impact - 1.2f) / 2.0f) + (gyroRms / 150f))

                if (rawGpsSpeedKmh > 0.1f) {
                    speedHistory.add(rawGpsSpeedKmh)
                    if (speedHistory.size > 25) speedHistory.removeAt(0)
                }
                val avgSpeedKmh = if (speedHistory.isNotEmpty()) speedHistory.average().toFloat() else 0f

                // Refined Anomaly Logic (v28.5)
                anomalyType = "NONE"
                
                // Enhanced Indoor Spike: Combined Criteria
                val isIndoorPossible = (imuMotionState == "STATIONARY") && (gpsAccuracy > 25f || currentSatelliteCount < 4 || watchGpsAge > 3000)
                val isIndoorSpike = isIndoorPossible && (rawGpsSpeedKmh > avgSpeedKmh + 25f) && (callbackContinuityScore < 0.7f)
                
                val isDriftSpike = (rawGpsSpeedKmh > 8f) && (displacementScore < 0.15f) && (imuMotionState == "STATIONARY")
                
                // Bearing Anomaly: Only at significant speed
                val isBearingAnomaly = (bearingChange > 100f) && (rawGpsSpeedKmh > 25f) && (imuMotionState != "ACTIVE_HIGH")
                
                if (isIndoorSpike) anomalyType = "INDOOR_SPIKE"
                else if (isDriftSpike) anomalyType = "DRIFT_SPIKE"
                else if (isBearingAnomaly) anomalyType = "BEARING_ANOMALY"

                // Real Event Override: Trust IMU more, GPS becomes auxiliary
                val isRealEventConfirmed = (rawGpsSpeedKmh > 3f) && (imuMotionState == "ACTIVE_HIGH")
                if (isRealEventConfirmed) anomalyType = "REAL_EVENT_CONFIRMED"

                spikeSuppressed = (anomalyType != "NONE" && anomalyType != "REAL_EVENT_CONFIRMED")

                // 2. Stall Detection & Decay (v28.5: 4s Stall, 5s Decay)
                val stallDetected = (watchGpsAge > 4000L && rawGpsSpeedMps > 0.5f) || 
                                   (currentSatelliteCount < 3 && rawGpsSpeedMps > 2.0f) || 
                                   (gpsAccuracy > 75f)
                
                if (stallDetected && !isStalled) {
                    isStalled = true
                    stallStartTime = now
                    stallInitialSpeedMps = rawGpsSpeedMps
                } else if (!stallDetected && watchGpsAge < 2000L) {
                    isStalled = false
                }

                if (isStalled) {
                    val stallDuration = now - stallStartTime
                    stallDecaySpeedMps = if (stallDuration >= 5000L) 0f 
                                         else stallInitialSpeedMps * (1f - (stallDuration / 5000f))
                }

                // 3. Output Policy (v28.5)
                val (displaySpeedMps, path) = when {
                    anomalyType == "REAL_EVENT_CONFIRMED" -> {
                        // GPS is auxiliary: Blend raw with average based on confidence
                        val blended = (rawGpsSpeedMps * gpsConfidence) + ((avgSpeedKmh/MPS_TO_KMH) * (1f - gpsConfidence))
                        blended to "REAL_EVENT_AUXILIARY"
                    }
                    isStalled -> stallDecaySpeedMps to "STALL_DECAY"
                    spikeSuppressed -> (avgSpeedKmh / MPS_TO_KMH) to "SPIKE_SUPPRESSED"
                    rawGpsSpeedMps < 0.5f || (imuMotionState == "STATIONARY" && rawGpsSpeedMps < 1.5f) -> 0f to "STATIONARY_GATE"
                    gpsAccuracy > 100f -> 0f to "ZERO_BY_ACCURACY"
                    else -> rawGpsSpeedMps to "PASS_THROUGH"
                }
                
                displayDecisionPath = path
                decisionReason = anomalyType.ifEmpty { path }
                stationaryGateApplied = (displaySpeedMps == 0f && rawGpsSpeedMps > 0.1f)
                val displaySpeedKmh = displaySpeedMps * MPS_TO_KMH
                
                // 4. Adaptive Sampling Logic (Rule 5)
                loopDelay = when {
                    displaySpeedKmh > 80f -> 50L
                    displaySpeedKmh > 60f -> 100L
                    displaySpeedKmh > 20f -> 200L
                    else -> 500L
                }

                val features = CrashScoreCalculator.Features(
                    peakG = impact,
                    deltaV = 0f, 
                    vPre = displaySpeedKmh,
                    gyroRms = gyroRms,
                    pressureDelta = pDelta,
                    lowG = impact < 0.5f,
                    pressureDrop = pDelta < -0.5f,
                    stillTimeSec = 0f,
                    userInput = false,
                    rollSumDeg = Math.abs(rollDeg),
                    hasAccel = true,
                    hasSpeed = true, 
                    hasGyro = gyroRms > 0.1f,
                    hasPressure = currentPressure > 0,
                    hasStill = true,
                    hasRoll = Math.abs(rollDeg) > 1.0f
                )
                val confidence = CrashScoreCalculator.SensorConfidence(
                    gps = gpsConfidence
                )
                val result = CrashScoreCalculator.computeCrashScore(features, currentSettings, confidence)
                
                if (result.finalScore > overallPeak.pCrashScore) {
                    overallPeak = overallPeak.copy(
                        pCrashScore = result.finalScore,
                        maxImpact = if (impact > overallPeak.maxImpact) impact else overallPeak.maxImpact,
                        pTimestamp = System.currentTimeMillis()
                    )
                }

                checkFsmTransitions(impact, displaySpeedMps, result.finalScore)
                
                lastKnownState = _currentState.value
                lastKnownVState = vInferenceState
                
                val currentUpdateNow = SystemClock.elapsedRealtime()
                val currentWatchGpsAge = if (lastWatchGpsTime > 0) currentUpdateNow - lastWatchGpsTime else -1L
                
                gpsProviderEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
                
                val gpsUiReason = when {
                    !gpsProviderEnabled -> "GPS_OFF"
                    currentWatchGpsAge in 0..4999 && currentSatelliteCount >= 4 -> {
                        if (currentReading[12] > 0 && currentReading[12] <= 25f) "FIX_3D" 
                        else "LOW_ACC"
                    }
                    currentWatchGpsAge > 15000L -> "STALE"
                    currentSatelliteCount > 0 -> "NO_FIX"
                    else -> "SEARCHING"
                }

                val currentGpsStatus = when (gpsUiReason) {
                    "FIX_3D" -> SensorStatus.FIX_3D
                    "LOW_ACC" -> SensorStatus.LOW_ACC
                    "NO_FIX" -> SensorStatus.NO_FIX
                    "SEARCHING" -> SensorStatus.NO_FIX
                    "GPS_OFF" -> SensorStatus.UNAVAILABLE
                    "STALE" -> SensorStatus.UNAVAILABLE
                    else -> SensorStatus.UNKNOWN
                }

                val isFix = currentGpsStatus == SensorStatus.FIX_3D || currentGpsStatus == SensorStatus.LOW_ACC
                val currentGpsStatusText = when {
                    !gpsProviderEnabled -> "Unavailable"
                    isFix -> String.format(java.util.Locale.US, "%.1fm", currentReading[12])
                    _currentState.value == IncidentState.MONITORING -> "Searching"
                    else -> "Unavailable"
                }

                val offlineR = if (gpsUiReason != "FIX_3D" && gpsUiReason != "LOW_ACC") gpsUiReason else ""

                updateBatteryDiagnostics()

                val isMonActive = _currentState.value != IncidentState.IDLE
                
                // Adaptive Throttle Logic (v28.6): Ensure ~5s interval on Main even at 500ms loop
                val throttleLimit = if (isHighSpeedSyncRequested) {
                    THROTTLE_HIGH_SPEED 
                } else {
                    // If loop is 500ms, we need 10 cycles for 5s. If 200ms, we need 25.
                    (5000 / loopDelay).toInt().coerceIn(1, 25)
                }
                
                val shouldSendFullData = (telemetryCounter % throttleLimit == 0)
                
                // v28.6.1: Throttled status updates. 
                // We only sync every loop in High Speed (Dash) or when Full Sync is due.
                val shouldSyncAny = shouldSendFullData || isHighSpeedSyncRequested
                
                val updatedTelemetry = _telemetry.value.copy(
                    currentImpact = impact,
                    accelX = currentReading[0],
                    accelY = currentReading[1],
                    accelZ = currentReading[2],
                    gyroX = gx,
                    gyroY = gy,
                    gyroZ = gz,
                    gyroRatio = gyroRms,
                    airPressure = currentPressure,
                    pressureDelta = pDelta,
                    rollSum = Math.abs(rollDeg),
                    gpsSpeed = displaySpeedKmh,
                    speedRawKmh = rawGpsSpeedKmh,
                    crashScore = result.finalScore,
                    vehicleInferenceState = vInferenceState,
                    
                    isWatchGpsActive = isFix,
                    isGpsActive = isFix,
                    activeGpsSource = GpsMode.WATCH_ONLY,

                    imuMotionState = imuMotionState,
                    anomalyType = anomalyType,
                    isStalled = isStalled,
                    spikeSuppressed = spikeSuppressed,
                    stallAgeMs = if (isStalled) now - stallStartTime else 0L,
                    recentAvgSpeedKmh = avgSpeedKmh,
                    satelliteCount = currentSatelliteCount,
                    bearingChangeDeg = bearingChange,
                    
                    gpsConfidence = gpsConfidence,
                    callbackContinuityScore = callbackContinuityScore,
                    realEventConfidence = realEventConfidence,
                    displayDecisionPath = displayDecisionPath,

                    currentGpsWeight = currentGpsWeight,
                    currentImuWeight = currentImuWeight,
                    continuityWeight = continuityWeight,
                    displacementWeight = displacementWeight,
                    bearingWeight = bearingWeight,
                    
                    hbAgeMs = if (lastHeartbeatTime > 0) SystemClock.elapsedRealtime() - lastHeartbeatTime else -1L,
                    offlineReason = offlineR,
                    wearTimestamp = System.currentTimeMillis(),
                    lastUpdateTime = System.currentTimeMillis(),
                    
                    pCrashScore = overallPeak.pCrashScore,
                    maxImpact = overallPeak.maxImpact,
                    pTimestamp = overallPeak.pTimestamp,

                    // Detailed Diagnostics (v28.5)
                    gpsProviderEnabled = gpsProviderEnabled,
                    gnssSignalSeen = gnssSignalSeen,
                    lastLocationReceivedAt = lastWatchGpsTime,
                    watchGpsAgeMs = watchGpsAge,
                    lastLat = lastLat,
                    lastLon = lastLon,
                    lastSpeedMps = rawGpsSpeedMps,
                    lastAccuracyM = currentReading[12],
                    gpsUiReason = gpsUiReason,
                    
                    displacementM = lastDisplacement,
                    stationaryGateApplied = stationaryGateApplied,
                    speedReason = decisionReason,
                    decisionReason = decisionReason,
                    
                    batteryLevel = lastBatteryLevel,
                    batteryChangePerHour = batteryConsumptionPerHour,

                    // Phase 7: Sync & Indicator Diagnostics (v28.6.2)
                    // Keep hardware status even when IDLE to support Pull-to-Refresh visibility
                    accelStatus = getSensorStatus(Sensor.TYPE_ACCELEROMETER),
                    gyroStatus = getSensorStatus(Sensor.TYPE_GYROSCOPE),
                    pressureStatus = getSensorStatus(Sensor.TYPE_PRESSURE),
                    rotationStatus = getSensorStatus(Sensor.TYPE_ROTATION_VECTOR),
                    syncReason = if (shouldSendFullData) "FULL_SYNC" else "STATUS_ONLY",
                    
                    // GPS UI Status Mapping (v28.6.1)
                    gpsStatus = currentGpsStatus,
                    gpsStatusText = currentGpsStatusText
                )
                
                _telemetry.value = updatedTelemetry
                
                if (shouldSyncAny) {
                    sendTelemetryToPhone(updatedTelemetry, fullSync = shouldSendFullData)
                }

                // Batching logic (v27.6)
                if (isMonActive) {
                    val entry = TelemetryLogEntry(
                        timestamp = updatedTelemetry.wearTimestamp,
                        readableTime = logDateFormat.format(Date(updatedTelemetry.wearTimestamp)),
                        ax = updatedTelemetry.accelX,
                        ay = updatedTelemetry.accelY,
                        az = updatedTelemetry.accelZ,
                        gx = updatedTelemetry.gyroX,
                        gy = updatedTelemetry.gyroY,
                        gz = updatedTelemetry.gyroZ,
                        pressure = updatedTelemetry.airPressure,
                        rx = currentReading[7],
                        ry = currentReading[8],
                        rz = currentReading[9],
                        rw = currentReading[10],
                        speed = updatedTelemetry.gpsSpeed,
                        latitude = updatedTelemetry.lastLat,
                        longitude = updatedTelemetry.lastLon,
                        gpsStatus = updatedTelemetry.gpsStatusText,
                        crashScore = updatedTelemetry.crashScore,
                        fsmState = vInferenceState.name
                    )
                    logBuffer.add(entry)

                    val nowTime = System.currentTimeMillis()
                    if (nowTime - lastBatchSaveTime >= 60000 || logBuffer.size >= 300) {
                        val batch = TelemetryLogBatch(entries = logBuffer.toList())
                        logBuffer.clear()
                        lastBatchSaveTime = nowTime
                        serviceScope.launch {
                            logStore.saveBatch(batch)
                        }
                    }
                }

                // Periodic Upload logic (v27.6)
                val nowTime = System.currentTimeMillis()
                if (nowTime - lastLogUploadTime >= 3600000) { // Every 1 hour
                    lastLogUploadTime = nowTime
                    serviceScope.launch {
                        uploadPendingLogs()
                    }
                }

                telemetryCounter++

                // Rule 5: Adaptive Sampling (v28.4 Tiered)
                loopDelay = when {
                    displaySpeedKmh > 80f -> 50L
                    displaySpeedKmh > 60f -> 100L
                    displaySpeedKmh > 20f -> 200L
                    else -> 500L
                }

                val elapsed = SystemClock.elapsedRealtime() - startTime
                val sleepTime = Math.max(0L, loopDelay - elapsed)
                delay(sleepTime)
            }
        }
    }

    private fun getSensorStatus(type: Int): SensorStatus {
        val sensor = sensorManager?.getDefaultSensor(type)
        return if (sensor == null) SensorStatus.MISSING else SensorStatus.AVAILABLE
    }

    private fun sendTelemetryToPhone(telemetry: TelemetryState, fullSync: Boolean) {
        serviceScope.launch {
            if (!dataClientMutex.tryLock()) return@launch
            try {
                val isActive = _currentState.value != IncidentState.IDLE
                val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.STATUS_SYNC).apply {
                    dataMap.putBoolean(ProtocolContract.Keys.IS_ACTIVE, isActive)
                    dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                    dataMap.putString(ProtocolContract.Keys.LOC_STATUS, currentGpsMode.name)

                    // Phase 7: Always include core status to prevent UI "Unknown" flickering (v28.6)
                    dataMap.putString(ProtocolContract.Keys.ACCEL_STATUS, telemetry.accelStatus.name)
                    dataMap.putString(ProtocolContract.Keys.GYRO_STATUS, telemetry.gyroStatus.name)
                    dataMap.putString(ProtocolContract.Keys.PRESS_STATUS, telemetry.pressureStatus.name)
                    dataMap.putString(ProtocolContract.Keys.ROT_STATUS, telemetry.rotationStatus.name)
                    
                    dataMap.putString(ProtocolContract.Keys.WATCH_GPS_STATUS, telemetry.gpsStatus.name)
                    dataMap.putString(ProtocolContract.Keys.WATCH_GPS_TEXT, telemetry.gpsStatusText)

                    if (fullSync || !isActive) { // Always full sync when stopping or if requested
                        val json = Json.encodeToString(telemetry)
                        dataMap.putString(ProtocolContract.Keys.TELEMETRY_JSON, json)
                    }
                    setUrgent()
                }.asPutDataRequest()
                Wearable.getDataClient(this@SentinelService).putDataItem(putDataReq).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send telemetry", e)
            } finally {
                dataClientMutex.unlock()
            }
        }
    }

    private suspend fun uploadPendingLogs() {
        try {
            val batches = logStore.getPendingBatches()
            if (batches.isEmpty()) return
            
            val nodeClient = Wearable.getNodeClient(this)
            val messageClient = Wearable.getMessageClient(this)
            
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w(TAG, "No nodes connected, skipping log upload")
                return
            }

            for ((file, batch) in batches) {
                val json = Json.encodeToString(batch)
                val data = json.toByteArray()
                
                for (node in nodes) {
                    messageClient.sendMessage(
                        node.id,
                        ProtocolContract.Paths.TELEMETRY_LOG,
                        data
                    ).await()
                }

                logStore.deleteFile(file)
                Log.d(TAG, "Uploaded telemetry batch: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload logs", e)
        }
    }

    private fun calculateCurrentImpact(): Float {
        val ax = currentReading[0]; val ay = currentReading[1]; val az = currentReading[2]
        return Math.sqrt((ax*ax + ay*ay + az*az).toDouble()).toFloat() / G_EARTH
    }

    private fun setVInferenceState(newState: VehicleInferenceState) {
        if (vInferenceState != newState) {
            Log.d(TAG, "FSM State Changed: $newState")
            vInferenceState = newState
            lastKnownVState = newState
            SentinelComplicationService.update(this)
            
            if (newState == VehicleInferenceState.IMPACT) {
                startAudioRecording()
            }
        }
    }

    private fun checkFsmTransitions(impact: Float, speedMps: Float, score: Float) {
        val speedKmh = speedMps * MPS_TO_KMH
        when (vInferenceState) {
            VehicleInferenceState.IDLE -> if (speedKmh > 10f) setVInferenceState(VehicleInferenceState.MOVING)
            VehicleInferenceState.MOVING -> {
                if (impact > 3.0f) setVInferenceState(VehicleInferenceState.IMPACT)
                else if (speedKmh < 1f) setVInferenceState(VehicleInferenceState.IDLE)
            }
            VehicleInferenceState.IMPACT -> setVInferenceState(VehicleInferenceState.POST_MOTION)
            VehicleInferenceState.POST_MOTION -> if (speedKmh < 2f) setVInferenceState(VehicleInferenceState.STILLNESS)
            VehicleInferenceState.STILLNESS -> {
                if (speedKmh > 10f && !isSimulatingLocked) {
                    setVInferenceState(VehicleInferenceState.MOVING)
                } else if (overallPeak.pCrashScore > 0.8f) {
                    setVInferenceState(VehicleInferenceState.WAIT_CONFIRM)
                    triggerAlertUI()
                }
            }
            else -> {}
        }
    }

    private fun triggerAlertUI() {
        val intent = Intent(this, IncidentAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startAudioRecording() {
        try {
            val cacheDir = getExternalCacheDir() ?: getCacheDir()
            val file = File(cacheDir, "incident_audio_${System.currentTimeMillis()}.mp4")
            audioFile = file
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            mediaRecorder = recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.getAbsolutePath())
                prepare()
                start()
            }
            serviceScope.launch {
                delay(10000)
                stopAudioRecording(delete = false)
            }
        } catch (e: Exception) { Log.e(TAG, "Audio fail", e) }
    }

    private fun stopAudioRecording(delete: Boolean) {
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            if (delete) { audioFile?.delete(); audioFile = null }
        } catch (e: Exception) { Log.e(TAG, "Audio stop fail", e) }
    }

    fun simulateIncident(path: String) {
        if (isSimulatingLocked) return
        isSimulatingLocked = true
        serviceScope.launch {
            updateSimulationData(65f, 1.0f)
            setVInferenceState(VehicleInferenceState.MOVING)
            delay(1000)
            updateSimulationData(15f, 12.5f)
            setVInferenceState(VehicleInferenceState.IMPACT)
            overallPeak = overallPeak.copy(pCrashScore = 0.99f, maxImpact = 12.5f, pTimestamp = System.currentTimeMillis())
            delay(500)
            updateSimulationData(0f, 1.0f)
            setVInferenceState(VehicleInferenceState.STILLNESS)
            delay(3000) 
            if (vInferenceState != VehicleInferenceState.WAIT_CONFIRM) {
                setVInferenceState(VehicleInferenceState.WAIT_CONFIRM)
                triggerAlertUI()
            }
            isSimulatingLocked = false
        }
    }

    private fun updateSimulationData(speedKmh: Float, impactG: Float) {
        synchronized(currentReading) {
            currentReading.fill(0f)
            currentReading[0] = impactG * G_EARTH
            currentReading[1] = 1.0f * G_EARTH
            currentReading[11] = speedKmh / MPS_TO_KMH
        }
    }

    fun injectCustomSensorData(csv: String) {}

    private fun calculateRoll(rotationVector: FloatArray): Float {
        if (rotationVector.all { it == 0f }) return 0f
        return try {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            Math.toDegrees(orientation[2].toDouble()).toFloat()
        } catch (e: Exception) { 0f }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || isSimulatingLocked) return
        synchronized(currentReading) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    currentReading[0] = event.values[0]; currentReading[1] = event.values[1]; currentReading[2] = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    currentReading[3] = event.values[0]; currentReading[4] = event.values[1]; currentReading[5] = event.values[2]
                }
                Sensor.TYPE_PRESSURE -> {
                    currentReading[6] = event.values[0]
                    if (baselinePressure == 0f) baselinePressure = event.values[0]
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    for (i in 0 until minOf(event.values.size, 4)) currentReading[7 + i] = event.values[i]
                }
            }
        }
    }
    
    override fun onLocationChanged(location: Location) {
        if (isSimulatingLocked) return
        
        val now = SystemClock.elapsedRealtime()
        if (lastLocationTime > 0) {
            lastLocationInterval = now - lastLocationTime
        }
        lastLocationTime = now

        // POLICY: Raw Real-time Direct Reception (v28.1)
        // No filtering, no provider checks (accept all hardware GPS/GNSS)
        synchronized(currentReading) {
            val speed = if (location.hasSpeed()) location.speed else 0f
            currentReading[11] = speed
            currentReading[12] = location.accuracy
            currentReading[13] = location.bearing
            currentReading[14] = location.altitude.toFloat()
            
            if (lastLat != 0.0) {
                val results = FloatArray(1)
                Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, results)
                lastDisplacement = results[0]
            }
            lastLat = location.latitude
            lastLon = location.longitude
        }
        lastWatchGpsTime = SystemClock.elapsedRealtime()
        
        // Update telemetry immediately for GPS status
        _telemetry.value = _telemetry.value.copy(
            watchGpsAccuracy = location.accuracy,
            isWatchGpsActive = true,
            lastLat = location.latitude,
            lastLon = location.longitude,
            lastLocationReceivedAt = lastWatchGpsTime
        )
    }

    private fun startWatchGps() {
        try {
            // Priority: Real-time (0ms interval, 0m distance) - SENSOR_DELAY_GAME equivalent
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            locationManager?.registerGnssStatusCallback(gnssStatusCallback, sensorHandler)
            Log.i(TAG, "Watch GPS Raw Real-time Started with GNSS Monitoring")
        } catch (e: SecurityException) { Log.e(TAG, "GPS Denied", e) }
    }

    private fun stopWatchGps() {
        locationManager?.removeUpdates(this)
        locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
        synchronized(currentReading) {
            currentReading[11] = 0f
        }
        lastWatchGpsTime = 0L
        gnssSignalSeen = false
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return SentinelBinder()
    }
    inner class SentinelBinder : Binder() { fun getService(): SentinelService = this@SentinelService }

    companion object {
        var lastKnownState: IncidentState = IncidentState.IDLE
        var isRunning: Boolean = false
        var lastKnownVState: VehicleInferenceState = VehicleInferenceState.IDLE
            private set
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_MONITORING = "com.jinn.watch2out.wear.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.jinn.watch2out.wear.STOP_MONITORING"
        const val ACTION_RESET_PEAKS = "com.jinn.watch2out.wear.RESET_PEAKS"
        const val ACTION_DASHBOARD_START = "com.jinn.watch2out.wear.DASHBOARD_START"
        const val ACTION_DASHBOARD_STOP = "com.jinn.watch2out.wear.DASHBOARD_STOP"
        const val ACTION_DISMISS_INCIDENT = "com.jinn.watch2out.wear.DISMISS_INCIDENT"
        const val ACTION_FINAL_DISPATCH = "com.jinn.watch2out.wear.FINAL_DISPATCH"
        const val ACTION_UPDATE_HEARTBEAT = "com.jinn.watch2out.wear.UPDATE_HEARTBEAT"
        const val ACTION_SIMULATE = "com.jinn.watch2out.wear.SIMULATE"
        const val ACTION_INJECT_DATA = "com.jinn.watch2out.wear.INJECT_DATA"
        const val ACTION_UPDATE_SYNC_POLICY = "com.jinn.watch2out.wear.UPDATE_SYNC_POLICY"
        const val ACTION_FORCE_SYNC = "com.jinn.watch2out.wear.FORCE_SYNC"
        const val ACTION_SENSOR_STATUS_SYNC = "com.jinn.watch2out.wear.SENSOR_STATUS_SYNC"
    }
}
