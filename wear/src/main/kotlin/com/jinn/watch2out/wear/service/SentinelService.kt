// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.shared.util.CrashScoreCalculator
import com.jinn.watch2out.shared.util.IncidentFsm
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.wear.R
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.data.TelemetryLogStore
import com.jinn.watch2out.wear.data.IncidentAssetStore
import com.jinn.watch2out.wear.presentation.IncidentAlertActivity
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
 * Sentinel Service (v33.3): Performance Optimized & Streamlined.
 */
class SentinelService : LifecycleService(), SensorEventListener, LocationListener {

    private val TAG = "SentinelFSM"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataClientMutex = Mutex()
    private val dashboardStreamMutex = Mutex()
    private val audioRecordingMutex = Mutex()
    private val transferMutex = Mutex()
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var serviceVibrator: Vibrator? = null

    private lateinit var logStore: TelemetryLogStore
    private lateinit var assetStore: IncidentAssetStore
    private lateinit var settingsRepository: SettingsRepository
    private val logBuffer = mutableListOf<TelemetryLogEntry>()
    private val edrBuffer = mutableListOf<TelemetryPoint>()
    private val edrActiveBuffer = mutableListOf<TelemetryPoint>()
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val audioFileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val readablePointFormat = SimpleDateFormat("yyyyMMdd-HH:mm:ss:SSS", Locale.US)
    
    private val prettyJson = Json { 
        prettyPrint = true 
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    
    private var lastBatchSaveTime = 0L
    private var lastLogUploadTime = 0L
    private var lastFullTelemetrySyncTime = 0L
    private var lastReportedState = IncidentState.IDLE

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    
    private val _currentState = MutableStateFlow(IncidentState.IDLE)
    val currentState: StateFlow<IncidentState> = _currentState.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private var vInferenceState = VehicleInferenceState.IDLE
    private var incidentPeakScore = 0f
    private var isSimulatingLocked = false
    
    private val currentReading = FloatArray(15) 
    private var lastWatchGpsTime = 0L
    private var lastNetworkTime = 0L
    private var lastLocationTime = 0L
    private var lastLocationInterval = 0L
    private var gnssSignalSeen = false
    private var gpsProviderEnabled = false
    
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastDisplacement = 0f

    private val speedHistory = mutableListOf<Float>()
    private var lastBearing = 0f
    private var bearingChange = 0f
    private var currentSatelliteCount = 0
    private var isStalled = false
    private var spikeSuppressed = false
    private var stallDecaySpeedMps = 0f
    private var stallStartTime = 0L
    private var stallInitialSpeedMps = 0f
    private var lastBatteryCheckTime = 0L
    private var lastBatteryLevel = -1
    private var batteryConsumptionPerHour = 0f
    private var lastHeartbeatTime = 0L
    private var serviceStartTime = 0L

    private val gnssStatusCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            currentSatelliteCount = status.satelliteCount
            gnssSignalSeen = currentSatelliteCount > 0
        }
    }

    private fun warmupSerialization() {
        serviceScope.launch(Dispatchers.Default) {
            try {
                val dummy = TelemetryState()
                val json = ProtocolContract.protocolJson.encodeToString(dummy)
                ProtocolContract.protocolJson.decodeFromString<TelemetryState>(json)
                Log.d(TAG, "Serialization warmed up")
            } catch (e: Exception) {
                Log.e(TAG, "Warmup failed: ${e.message}")
            }
        }
    }

    private fun updateBatteryDiagnostics() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SystemClock.elapsedRealtime()
        
        if (lastBatteryLevel != -1 && now - lastBatteryCheckTime > 60000) {
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
    private var lastReportedFsmState = VehicleInferenceState.IDLE
    private var lastDashboardStreamTime = 0L
    
    private var windowPeak = TelemetryState()
    private val peakHistoryWindowMs = 3600000L
    private var requestedWindowMs = 600000L
    private val peakHistory = mutableListOf<Pair<Long, FloatArray>>()

    private val usePhoneGpsReserved = false 

    var currentGpsMode = GpsMode.WATCH_ONLY
        private set

    private val gEarth = 9.80665f
    private val mpsToKmh = 3.6f

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        serviceStartTime = SystemClock.elapsedRealtime()
        logStore = TelemetryLogStore(this)
        assetStore = IncidentAssetStore(this)
        settingsRepository = SettingsRepository(this)
        lastLogUploadTime = System.currentTimeMillis()
        
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Watch\u00b2 Out Sentinel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        warmupSerialization()

        serviceScope.launch {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            
            initHardwareIPC()
            
            delay(3000) 
            sendTelemetryToPhone(_telemetry.value, fullSync = true)
            startProcessingLoop()

            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                Log.d(TAG, "Settings Updated: ScoreThreshold=${settings.crashScoreThreshold}")
                // v34.5: Immediately push state to phone when settings change (e.g. Phone GPS toggle)
                sendTelemetryToPhone(_telemetry.value, fullSync = true)
            }
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
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Watch\u00b2 Out Sentinel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        super.onStartCommand(intent, flags, startId)
        
        if (intent == null) {
            handleCrashRecovery()
            return Service.START_STICKY
        }

        val action = intent.action ?: return Service.START_STICKY
        
        try {
            when (action) {
                ACTION_START_MONITORING -> startMonitoringLogic()
                ACTION_STOP_MONITORING -> handleStopMonitoring()
                ACTION_RESET_PEAKS -> handleResetPeaks()
                ACTION_DISMISS_INCIDENT -> handleDismissIncident()
                ACTION_DASHBOARD_START -> handleDashboardStart()
                ACTION_DASHBOARD_STOP -> handleDashboardStop()
                ACTION_DASHBOARD_CONFIG -> handleDashboardConfig(intent)
                ACTION_FINAL_DISPATCH -> handleFinalDispatch()
                ACTION_UPDATE_HEARTBEAT -> handleUpdateHeartbeat(intent)
                ACTION_SIMULATE -> simulateIncident(intent.getStringExtra("path") ?: "")
                ACTION_INJECT_DATA -> injectCustomSensorData(intent.getStringExtra("csv") ?: "")
                ACTION_UPDATE_SYNC_POLICY -> handleUpdateSyncPolicy(intent)
                ACTION_FORCE_SYNC -> handleForceSync()
                ACTION_SENSOR_STATUS_SYNC -> handleSensorStatusSync()
                ACTION_STOP_VIBRATION -> stopServiceVibration()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand for action: $action", e)
        }
        return Service.START_STICKY
    }

    private fun handleCrashRecovery() {
        serviceScope.launch {
            val persistedState = settingsRepository.monitoringStateFlow.first()
            if (persistedState == IncidentState.MONITORING.name) {
                withContext(Dispatchers.Main) {
                    startMonitoringLogic()
                }
            }
        }
    }

    private fun handleStopMonitoring() {
        _currentState.value = IncidentState.IDLE
        lastKnownState = IncidentState.IDLE
        serviceScope.launch { settingsRepository.updateMonitoringState(IncidentState.IDLE.name) }
        setVInferenceState(VehicleInferenceState.IDLE)
        stopWatchGps()
        stopAudioRecording(delete = true)
        stopServiceVibration()
        
        // v33.8: Clear any active incident notifications
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INCIDENT_NOTIFICATION_ID)

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
            gpsStatusText = "Available",
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
                    
                    val json = prettyJson.encodeToString(finalTelemetry)
                    dataMap.putString(ProtocolContract.Keys.TELEMETRY_JSON, json)
                    setUrgent()
                }.asPutDataRequest()
                
                Wearable.getDataClient(this@SentinelService).putDataItem(putDataReq).await()
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

    private fun handleResetPeaks() {
        overallPeak = TelemetryState()
        windowPeak = TelemetryState()
        peakHistory.clear()
        incidentPeakScore = 0f // v33.7: Also reset internal FSM score memory
        
        // v33.7: Update telemetry state immediately and trigger sync so Mobile UI reflects changes
        val resetTelemetry = _telemetry.value.copy(
            pCrashScore = 0f,
            maxImpact = 1.0f,
            pTimestamp = 0L,
            pGpsSpeed = 0f,
            pAccelX = 0f,
            pAccelY = 0f,
            pAccelZ = 0f,
            maxLongitudinalG = 0f,
            maxLateralG = 0f,
            maxSpeedDrop = 0f,
            pRollSum = 0f,
            pPressureDelta = 0f,
            
            wCrashScore = 0f,
            windowImpact = 1.0f,
            wMaxSpeedDrop = 0f,
            wGpsSpeed = 0f,
            wRollSum = 0f,
            wPressureDelta = 0f,
            wMaxLongitudinalG = 0f,
            wMaxLateralG = 0f,
            wTimestamp = 0L
        )
        _telemetry.value = resetTelemetry
        sendTelemetryToPhone(resetTelemetry, fullSync = true)
        
        Log.i(TAG, "Peaks reset and synced to mobile")
    }

    private fun handleDismissIncident() {
        setVInferenceState(VehicleInferenceState.IDLE)
        stopAudioRecording(delete = true)
        stopServiceVibration()
        
        // v33.8: Clear any active incident notifications
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INCIDENT_NOTIFICATION_ID)

        if (audioRecordingMutex.isLocked) audioRecordingMutex.unlock()
        SentinelComplicationService.update(this)
    }

    private fun handleDashboardStart() {
        isHighSpeedSyncRequested = true
        telemetryCounter = 0
    }

    private fun handleDashboardStop() {
        isHighSpeedSyncRequested = false
    }

    private fun handleDashboardConfig(intent: Intent) {
        val windowMs = intent.getLongExtra(ProtocolContract.Keys.WINDOW_MS, 600000L)
        requestedWindowMs = windowMs
        sendTelemetryToPhone(_telemetry.value, fullSync = true)
    }

    private fun handleFinalDispatch() {
        setVInferenceState(VehicleInferenceState.CONFIRMED_CRASH)
        if (mediaRecorder == null) {
            startAudioRecording(durationMs = 15000L)
        }
    }

    private fun handleUpdateHeartbeat(intent: Intent) {
        val json = intent.getStringExtra("heartbeat_json") ?: ""
        if (json.isNotEmpty()) {
            val hb = ProtocolContract.protocolJson.decodeFromString<Heartbeat>(json)
            lastHeartbeatTime = SystemClock.elapsedRealtime()

            // v34.2: Option to ignore Phone GPS data
            if (currentSettings.usePhoneGps) {
                _telemetry.value = _telemetry.value.copy(
                    isPhoneGpsActive = hb.phoneGpsStatus == PhoneGpsStatus.AVAILABLE,
                    phoneGpsAccuracy = hb.phoneGpsAccuracy,
                    isHintReliable = hb.isSpeedHintReliable,
                    gpsAgeMs = hb.elapsedMs,
                    hbAgeMs = 0L
                )
            } else {
                _telemetry.value = _telemetry.value.copy(
                    isPhoneGpsActive = false,
                    hbAgeMs = 0L
                )
            }
        }
    }

    private fun handleUpdateSyncPolicy(intent: Intent) {
        val wasHighSpeed = isHighSpeedSyncRequested
        isHighSpeedSyncRequested = intent.getBooleanExtra("high_speed", false)
        if (isHighSpeedSyncRequested && !wasHighSpeed) {
            telemetryCounter = 0 
        }
    }

    private fun handleForceSync() {
        sendTelemetryToPhone(_telemetry.value, fullSync = true)
    }

    private fun handleSensorStatusSync() {
        val telemetry = _telemetry.value.copy(
            accelStatus = getSensorStatus(Sensor.TYPE_ACCELEROMETER),
            gyroStatus = getSensorStatus(Sensor.TYPE_GYROSCOPE),
            pressureStatus = getSensorStatus(Sensor.TYPE_PRESSURE),
            rotationStatus = getSensorStatus(Sensor.TYPE_ROTATION_VECTOR),
            wearTimestamp = System.currentTimeMillis()
        )
        sendTelemetryToPhone(telemetry, fullSync = false)
    }

    private fun stopServiceVibration() {
        serviceVibrator?.cancel()
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
        
        serviceScope.launch {
            sensors.forEach { (type, name) ->
                val sensor = sm.getDefaultSensor(type)
                if (sensor != null) {
                    val success = sm.registerListener(this@SentinelService, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                    Log.i(TAG, "Sensor registration: $name -> $success")
                } else {
                    Log.w(TAG, "Sensor missing: $name")
                }
                delay(150)
            }
        }
    }

    private var lastAccelMag = 1.0f
    private var imuMotionState = "UNKNOWN"
    private var anomalyType = "NONE"
    private var gpsConfidence = 1.0f
    private var callbackContinuityScore = 1.0f
    private var realEventConfidence = 0f
    private var decisionReason = "INITIALIZING"

    private var loopDelay = 500L

    private fun updateImuMotionState(impactG: Float, gyroRms: Float) {
        imuMotionState = when {
            impactG > 1.2f || gyroRms > 15f -> "ACTIVE_HIGH"
            impactG > 1.05f || gyroRms > 5f -> "ACTIVE_LOW"
            else -> "STATIONARY"
        }
    }

    private fun startProcessingLoop() {
        serviceScope.launch {
            val readingSnapshot = FloatArray(15)
            while (isActive) {
                val startTime = SystemClock.elapsedRealtime()
                try {
                    processTelemetryCycle(readingSnapshot, startTime)
                } catch (e: Exception) {
                    Log.e(TAG, "Processing loop error: ${e.message}", e)
                }

                val elapsed = SystemClock.elapsedRealtime() - startTime
                val sleepTime = (loopDelay - elapsed).coerceAtLeast(0L)
                delay(sleepTime)
            }
        }
    }

    private fun processTelemetryCycle(readingSnapshot: FloatArray, startTime: Long) {
        synchronized(currentReading) {
            currentReading.copyInto(readingSnapshot)
        }
        
        val nowTime = System.currentTimeMillis()
        val ax = readingSnapshot[0]; val ay = readingSnapshot[1]; val az = readingSnapshot[2]
        val impact = Math.sqrt((ax*ax + ay*ay + az*az).toDouble()).toFloat() / gEarth
        
        val gx = readingSnapshot[3]; val gy = readingSnapshot[4]; val gz = readingSnapshot[5]
        val gyroRms = Math.sqrt((gx*gx + gy*gy + gz*gz).toDouble()).toFloat()
        
        updateImuMotionState(impact, gyroRms)

        val currentPressure = readingSnapshot[6]
        val pDelta = if (baselinePressure > 0f) currentPressure - baselinePressure else 0f
        
        val rotVec = readingSnapshot.sliceArray(7..10)
        val rollDeg = calculateRoll(rotVec)

        val rawGpsSpeedMps = readingSnapshot[11]
        val rawGpsSpeedKmh = rawGpsSpeedMps * mpsToKmh
        val gpsAccuracy = readingSnapshot[12]
        val currentBearing = readingSnapshot[13]
        
        val now = SystemClock.elapsedRealtime()
        val watchGpsAge = if (lastWatchGpsTime > 0) now - lastWatchGpsTime else -1L
        
        var displacementScore = 1.0f

        if (!isSimulatingLocked) {
            callbackContinuityScore = if (lastLocationInterval > 0) {
                Math.max(0f, 1f - (Math.abs(lastLocationInterval - 1000f) / 3000f))
            } else 0.5f

            val expectedDisplacement = rawGpsSpeedMps * (lastLocationInterval / 1000f)
            displacementScore = if (expectedDisplacement > 0.5f) {
                Math.min(1.2f, lastDisplacement / expectedDisplacement).coerceIn(0f, 1f)
            } else 1.0f

            val accScore = Math.max(0f, 1f - (gpsAccuracy / 80f))
            val ageScore = if (watchGpsAge >= 0) Math.max(0f, 1f - (watchGpsAge / 10000f)) else 0f
            val satScore = Math.min(1f, currentSatelliteCount / 6f)
            
            bearingChange = Math.abs(currentBearing - lastBearing)
            if (bearingChange > 180) bearingChange = 360 - bearingChange
            lastBearing = currentBearing
            val bearingScore = Math.max(0f, 1f - (bearingChange / 90f))

            val (cW, dW, bW, iW, gW) = if (rawGpsSpeedKmh > 20f) {
                listOf(0.15f, 0.20f, 0.25f, 0.20f, 0.20f)
            } else {
                listOf(0.30f, 0.40f, 0.05f, 0.15f, 0.10f)
            }
            
            val gpsInternal = (accScore * 0.4f + ageScore * 0.3f + satScore * 0.3f)
            gpsConfidence = (gpsInternal * gW + 
                            displacementScore * dW + 
                            bearingScore * bW + 
                            (1f - realEventConfidence) * iW + 
                            callbackContinuityScore * cW)

            realEventConfidence = Math.min(1f, Math.max(0f, (impact - 1.2f) / 2.0f) + (gyroRms / 150f))

            if (rawGpsSpeedKmh > 0.1f) {
                speedHistory.add(rawGpsSpeedKmh)
                if (speedHistory.size > 25) speedHistory.removeAt(0)
            }
        }

        val avgSpeedKmh = if (speedHistory.isNotEmpty()) speedHistory.average().toFloat() else 0f
        val maxSpeedRecent = if (speedHistory.isNotEmpty()) speedHistory.maxOrNull() ?: 0f else 0f
        val deltaV = (maxSpeedRecent - rawGpsSpeedKmh).coerceAtLeast(0f)

        anomalyType = "NONE"
        val isIndoorPossible = (imuMotionState == "STATIONARY") && (gpsAccuracy > 25f || currentSatelliteCount < 4 || watchGpsAge > 3000)
        val isIndoorSpike = isIndoorPossible && (rawGpsSpeedKmh > avgSpeedKmh + 25f) && (callbackContinuityScore < 0.7f)
        val isDriftSpike = (rawGpsSpeedKmh > 8f) && (displacementScore < 0.15f) && (imuMotionState == "STATIONARY")
        val isBearingAnomaly = (bearingChange > 100f) && (rawGpsSpeedKmh > 25f) && (imuMotionState != "ACTIVE_HIGH")
        
        if (isIndoorSpike) anomalyType = "INDOOR_SPIKE"
        else if (isDriftSpike) anomalyType = "DRIFT_SPIKE"
        else if (isBearingAnomaly) anomalyType = "BEARING_ANOMALY"

        val isRealEventConfirmed = (rawGpsSpeedKmh > 3f) && (imuMotionState == "ACTIVE_HIGH")
        if (isRealEventConfirmed) anomalyType = "REAL_EVENT_CONFIRMED"

        spikeSuppressed = (anomalyType != "NONE" && anomalyType != "REAL_EVENT_CONFIRMED")

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

        val (displaySpeedMps, path) = when {
            anomalyType == "REAL_EVENT_CONFIRMED" -> {
                val blended = (rawGpsSpeedMps * gpsConfidence) + ((avgSpeedKmh/mpsToKmh) * (1f - gpsConfidence))
                blended to "REAL_EVENT_AUXILIARY"
            }
            isStalled -> stallDecaySpeedMps to "STALL_DECAY"
            spikeSuppressed -> (avgSpeedKmh / mpsToKmh) to "SPIKE_SUPPRESSED"
            rawGpsSpeedMps < 0.5f || (imuMotionState == "STATIONARY" && rawGpsSpeedMps < 1.5f) -> 0f to "STATIONARY_GATE"
            gpsAccuracy > 100f -> 0f to "ZERO_BY_ACCURACY"
            else -> rawGpsSpeedMps to "PASS_THROUGH"
        }
        
        decisionReason = anomalyType.ifEmpty { path }
        val stationaryGateApplied = (displaySpeedMps == 0f && rawGpsSpeedMps > 0.1f)
        val displaySpeedKmh = displaySpeedMps * mpsToKmh
        
        loopDelay = when {
            displaySpeedKmh > currentSettings.highSpeedThresholdKmh -> 50L
            displaySpeedKmh > currentSettings.normalSpeedThresholdKmh -> 100L
            displaySpeedKmh > currentSettings.lowSpeedThresholdKmh -> 200L
            else -> 500L
        }

        val features = CrashScoreCalculator.Features(
            peakG = impact, deltaV = deltaV, vPre = displaySpeedKmh,
            gyroRms = gyroRms * (180f / Math.PI.toFloat()),
            pressureDelta = pDelta, lowG = impact < 0.3f, pressureDrop = pDelta < -1.0f,
            stillTimeSec = 0f, userInput = false, rollSumDeg = Math.abs(rollDeg),
            hasAccel = true, hasSpeed = true, hasGyro = gyroRms > 0.01f,
            hasPressure = currentPressure > 0, hasStill = true, hasRoll = Math.abs(rollDeg) > 1.0f
        )
        val result = CrashScoreCalculator.computeCrashScore(features, currentSettings, CrashScoreCalculator.SensorConfidence(gpsConfidence))
        
        val nowMs = System.currentTimeMillis()
        var op = overallPeak
        if (impact > op.maxImpact) op = op.copy(maxImpact = impact, pTimestamp = nowMs)
        if (result.finalScore > op.pCrashScore) op = op.copy(pCrashScore = result.finalScore, pTimestamp = nowMs)
        if (deltaV > op.maxSpeedDrop) op = op.copy(maxSpeedDrop = deltaV, pTimestamp = nowMs)
        if (Math.abs(rollDeg) > op.pRollSum) op = op.copy(pRollSum = Math.abs(rollDeg), pTimestamp = nowMs)
        if (Math.abs(pDelta) > op.pPressureDelta) op = op.copy(pPressureDelta = Math.abs(pDelta), pTimestamp = nowMs)
        if (displaySpeedKmh > op.pGpsSpeed) op = op.copy(pGpsSpeed = displaySpeedKmh, pTimestamp = nowMs)
        if (Math.abs(readingSnapshot[1]) > op.maxLongitudinalG) op = op.copy(maxLongitudinalG = Math.abs(readingSnapshot[1]), pTimestamp = nowMs)
        if (Math.abs(readingSnapshot[0]) > op.maxLateralG) op = op.copy(maxLateralG = Math.abs(readingSnapshot[0]), pTimestamp = nowMs)
        if (Math.abs(readingSnapshot[0]) > Math.abs(op.pAccelX)) op = op.copy(pAccelX = readingSnapshot[0])
        if (Math.abs(readingSnapshot[1]) > Math.abs(op.pAccelY)) op = op.copy(pAccelY = readingSnapshot[1])
        if (Math.abs(readingSnapshot[2]) > Math.abs(op.pAccelZ)) op = op.copy(pAccelZ = readingSnapshot[2])
        overallPeak = op

        peakHistory.add(nowMs to floatArrayOf(
            impact, result.finalScore, deltaV, Math.abs(rollDeg), Math.abs(pDelta),
            Math.abs(readingSnapshot[1]), Math.abs(readingSnapshot[0]), displaySpeedKmh
        ))
        peakHistory.removeIf { it.first < nowMs - peakHistoryWindowMs }
        
        val relevantHistory = peakHistory.filter { it.first >= nowMs - requestedWindowMs }
        windowPeak = windowPeak.copy(
            windowImpact = relevantHistory.maxOfOrNull { it.second[0] } ?: 0f,
            wCrashScore = relevantHistory.maxOfOrNull { it.second[1] } ?: 0f,
            wMaxSpeedDrop = relevantHistory.maxOfOrNull { it.second[2] } ?: 0f,
            wRollSum = relevantHistory.maxOfOrNull { it.second[3] } ?: 0f,
            wPressureDelta = relevantHistory.maxOfOrNull { it.second[4] } ?: 0f,
            wMaxLongitudinalG = relevantHistory.maxOfOrNull { it.second[5] } ?: 0f,
            wMaxLateralG = relevantHistory.maxOfOrNull { it.second[6] } ?: 0f,
            wGpsSpeed = relevantHistory.maxOfOrNull { it.second[7] } ?: 0f,
            wTimestamp = nowMs
        )

        if (vInferenceState == VehicleInferenceState.IDLE || vInferenceState == VehicleInferenceState.MOVING) incidentPeakScore = 0f
        if (result.finalScore > incidentPeakScore) incidentPeakScore = result.finalScore

        val nextFsmState = IncidentFsm.nextState(vInferenceState, impact, displaySpeedKmh, result.finalScore, incidentPeakScore, currentSettings, isSimulatingLocked) { t, m -> Log.d(t, m) }
        if (nextFsmState != vInferenceState) {
            setVInferenceState(nextFsmState)
            if (nextFsmState == VehicleInferenceState.WAIT_CONFIRM) triggerAlertUI()
        }
        
        gpsProviderEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val currentUpdateNow = SystemClock.elapsedRealtime()
        val cWAge = if (lastWatchGpsTime > 0) currentUpdateNow - lastWatchGpsTime else -1L
        val cNAge = if (lastNetworkTime > 0) currentUpdateNow - lastNetworkTime else -1L
        val cPAge = if (lastHeartbeatTime > 0) currentUpdateNow - lastHeartbeatTime else -1L
        
        if (!isSimulatingLocked) {
            // v34.4: Refined GpsMode Priority Logic
            currentGpsMode = when {
                // 1. Phone Primary (If enabled & heartbeat is fresh < 15s)
                currentSettings.usePhoneGps && cPAge in 0..15000L -> GpsMode.PHONE_PRIMARY
                
                // 2. Watch Hybrid (GPS + Net fresh < 10s)
                cWAge in 0..10000L && cNAge in 0..10000L -> GpsMode.WATCH_HYBRID
                
                // 3. Watch GPS Only
                cWAge in 0..10000L -> GpsMode.WATCH_ONLY
                
                // 4. Watch Network Only (Fallback)
                cNAge in 0..20000L -> GpsMode.WATCH_NETWORK_ONLY
                
                // 5. Default/Searching
                else -> GpsMode.WATCH_ONLY
            }
        }

        val gpsUiR = when {
            !gpsProviderEnabled -> "GPS_OFF"
            cWAge in 0..4999 && currentSatelliteCount >= 4 -> if (readingSnapshot[12] <= 25f) "FIX_3D" else "LOW_ACC"
            cWAge > 15000L -> "STALE"
            currentSatelliteCount > 0 -> "NO_FIX"
            else -> "SEARCHING"
        }

        val cGpsStatus = when (gpsUiR) {
            "FIX_3D" -> SensorStatus.FIX_3D
            "LOW_ACC" -> SensorStatus.LOW_ACC
            "NO_FIX", "SEARCHING" -> SensorStatus.NO_FIX
            "GPS_OFF", "STALE" -> SensorStatus.UNAVAILABLE
            else -> SensorStatus.UNKNOWN
        }

        val isFix = cGpsStatus == SensorStatus.FIX_3D || cGpsStatus == SensorStatus.LOW_ACC
        val cGpsStatusText = when {
            currentGpsMode == GpsMode.PHONE_PRIMARY -> "Phone GPS"
            !gpsProviderEnabled -> "Unavailable"
            isFix -> String.format(Locale.US, "%.1fm", readingSnapshot[12])
            _currentState.value == IncidentState.MONITORING -> "Searching"
            else -> "Unavailable"
        }

        updateBatteryDiagnostics()

        // v34.9: Strictly Critical-Only Sync Strategy
        // 1. Monitor State Change: Only explicit Start/Stop commands
        val overallStateChanged = _currentState.value != lastReportedState
        
        // 2. Critical Alert Transitions Only (IMPACT, WAIT_CONFIRM, CONFIRMED_CRASH)
        // All other states (IDLE, MOVING, PRE_EVENT, FALLING, POST_MOTION, STILLNESS) rely on periodic sync.
        val isCriticalState = vInferenceState == VehicleInferenceState.IMPACT || 
                             vInferenceState == VehicleInferenceState.WAIT_CONFIRM || 
                             vInferenceState == VehicleInferenceState.CONFIRMED_CRASH
        val isIncidentTransition = isCriticalState && (vInferenceState != lastReportedFsmState)

        // 3. Tiered Periodic Sync
        val periodicInterval = if (currentSettings.usePhoneGps) 60000L else 600000L // 1 min (Fusion) vs 10 min (Standalone)
        val isTimeForPeriodicSync = (nowTime - lastFullTelemetrySyncTime >= periodicInterval)
        
        val shouldDataSyncNow = overallStateChanged || isIncidentTransition || isTimeForPeriodicSync
        
        val updatedTelemetry = _telemetry.value.copy(
            currentImpact = impact, accelX = readingSnapshot[0], accelY = readingSnapshot[1], accelZ = readingSnapshot[2],
            gyroX = gx, gyroY = gy, gyroZ = gz, airPressure = readingSnapshot[6], pressureDelta = pDelta, rollSum = Math.abs(rollDeg),
            gpsSpeed = displaySpeedKmh, speedRawKmh = rawGpsSpeedKmh, crashScore = result.finalScore, vehicleInferenceState = vInferenceState,
            isWatchGpsActive = isFix, isGpsActive = isFix, activeGpsSource = currentGpsMode, imuMotionState = imuMotionState,
            anomalyType = anomalyType, isStalled = isStalled, spikeSuppressed = spikeSuppressed, satelliteCount = currentSatelliteCount,
            bearingChangeDeg = bearingChange, hbAgeMs = if (lastHeartbeatTime > 0) SystemClock.elapsedRealtime() - lastHeartbeatTime else -1L,
            offlineReason = if (gpsUiR != "FIX_3D" && gpsUiR != "LOW_ACC") gpsUiR else "", wearTimestamp = nowMs, lastUpdateTime = nowMs,
            pCrashScore = op.pCrashScore, maxImpact = op.maxImpact, pTimestamp = op.pTimestamp, pGpsSpeed = op.pGpsSpeed,
            pAccelX = op.pAccelX, pAccelY = op.pAccelY, pAccelZ = op.pAccelZ, maxLongitudinalG = op.maxLongitudinalG, maxLateralG = op.maxLateralG,
            maxSpeedDrop = op.maxSpeedDrop, pRollSum = op.pRollSum, pPressureDelta = op.pPressureDelta,
            wCrashScore = windowPeak.wCrashScore, windowImpact = windowPeak.windowImpact, wMaxSpeedDrop = windowPeak.wMaxSpeedDrop,
            wGpsSpeed = windowPeak.wGpsSpeed, wRollSum = windowPeak.wRollSum, wPressureDelta = windowPeak.wPressureDelta,
            wMaxLongitudinalG = windowPeak.wMaxLongitudinalG, wMaxLateralG = windowPeak.wMaxLateralG, wTimestamp = windowPeak.wTimestamp,
            gpsProviderEnabled = gpsProviderEnabled, gnssSignalSeen = gnssSignalSeen, lastLocationReceivedAt = lastWatchGpsTime,
            watchGpsAgeMs = cWAge, lastLat = lastLat, lastLon = lastLon, lastAccuracyM = readingSnapshot[12],
            stationaryGateApplied = stationaryGateApplied, speedReason = decisionReason, batteryLevel = lastBatteryLevel,
            batteryChangePerHour = batteryConsumptionPerHour, accelStatus = getSensorStatus(Sensor.TYPE_ACCELEROMETER),
            gyroStatus = getSensorStatus(Sensor.TYPE_GYROSCOPE), pressureStatus = getSensorStatus(Sensor.TYPE_PRESSURE),
            rotationStatus = getSensorStatus(Sensor.TYPE_ROTATION_VECTOR), gpsStatus = cGpsStatus, gpsStatusText = cGpsStatusText
        )
        
        _telemetry.value = updatedTelemetry
        if (shouldDataSyncNow) {
            sendTelemetryToPhone(updatedTelemetry, fullSync = true)
            lastFullTelemetrySyncTime = nowTime
            lastReportedState = _currentState.value
            lastReportedFsmState = vInferenceState
        }

        // v34.9: High-Speed Dash Streaming (Triggered by Phone Request or Critical State)
        if (isHighSpeedSyncRequested || isCriticalState) {
            if (startTime - lastDashboardStreamTime >= 200L) {
                streamTelemetryToDashboard(updatedTelemetry)
                lastDashboardStreamTime = startTime
            }
        }

        val currentPoint = TelemetryPoint(
            time = readablePointFormat.format(Date(nowMs)), t = nowMs, offset = 0,
            ax = updatedTelemetry.accelX, ay = updatedTelemetry.accelY, az = updatedTelemetry.accelZ,
            gx = updatedTelemetry.gyroX, gy = updatedTelemetry.gyroY, gz = updatedTelemetry.gyroZ,
            pres = updatedTelemetry.airPressure, spd = updatedTelemetry.gpsSpeed, mag = impact,
            rx = readingSnapshot[7], ry = readingSnapshot[8], rz = readingSnapshot[9], rw = readingSnapshot[10],
            lat = if (updatedTelemetry.lastLat != 0.0) updatedTelemetry.lastLat else null,
            lon = if (updatedTelemetry.lastLon != 0.0) updatedTelemetry.lastLon else null
        )

        edrBuffer.add(currentPoint)
        while (edrBuffer.size > (5000 / loopDelay).toInt().coerceAtLeast(10)) edrBuffer.removeAt(0)
        if (mediaRecorder != null) edrActiveBuffer.add(currentPoint)

        if (_currentState.value != IncidentState.IDLE) {
            logBuffer.add(TelemetryLogEntry(
                timestamp = nowMs, readableTime = logDateFormat.format(Date(nowMs)),
                ax = updatedTelemetry.accelX, ay = updatedTelemetry.accelY, az = updatedTelemetry.accelZ,
                gx = updatedTelemetry.gyroX, gy = updatedTelemetry.gyroY, gz = updatedTelemetry.gyroZ,
                pressure = updatedTelemetry.airPressure, rx = readingSnapshot[7], ry = readingSnapshot[8],
                rz = readingSnapshot[9], rw = readingSnapshot[10], speed = updatedTelemetry.gpsSpeed,
                latitude = updatedTelemetry.lastLat, longitude = updatedTelemetry.lastLon,
                gpsStatus = updatedTelemetry.gpsStatusText, crashScore = updatedTelemetry.crashScore,
                fsmState = vInferenceState.name
            ))
            if (nowMs - lastBatchSaveTime >= 60000 || logBuffer.size >= 300) {
                val batch = TelemetryLogBatch(entries = logBuffer.toList())
                logBuffer.clear()
                lastBatchSaveTime = nowMs
                serviceScope.launch { logStore.saveBatch(batch) }
            }
        }

        if (nowTime - lastLogUploadTime >= 3600000) {
            lastLogUploadTime = nowTime
            serviceScope.launch { uploadPendingLogs() }
        }
        telemetryCounter++
    }

    private fun getSensorStatus(type: Int): SensorStatus {
        val sensor = sensorManager?.getDefaultSensor(type)
        return if (sensor == null) SensorStatus.MISSING else SensorStatus.AVAILABLE
    }

    private fun sendTelemetryToPhone(telemetry: TelemetryState, fullSync: Boolean) {
        serviceScope.launch {
            if (!dataClientMutex.tryLock()) return@launch
            try {
                withTimeoutOrNull(5000L) {
                    val isActive = _currentState.value != IncidentState.IDLE
                    val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.STATUS_SYNC).apply {
                        dataMap.putBoolean(ProtocolContract.Keys.IS_ACTIVE, isActive)
                        dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                        dataMap.putString(ProtocolContract.Keys.LOC_STATUS, currentGpsMode.name)
                        dataMap.putString(ProtocolContract.Keys.ACCEL_STATUS, telemetry.accelStatus.name)
                        dataMap.putString(ProtocolContract.Keys.GYRO_STATUS, telemetry.gyroStatus.name)
                        dataMap.putString(ProtocolContract.Keys.PRESS_STATUS, telemetry.pressureStatus.name)
                        dataMap.putString(ProtocolContract.Keys.ROT_STATUS, telemetry.rotationStatus.name)
                        dataMap.putString(ProtocolContract.Keys.WATCH_GPS_STATUS, telemetry.gpsStatus.name)
                        dataMap.putString(ProtocolContract.Keys.WATCH_GPS_TEXT, telemetry.gpsStatusText)
                        if (fullSync || !isActive) {
                            val json = withContext(Dispatchers.IO) { ProtocolContract.protocolJson.encodeToString(telemetry) }
                            dataMap.putString(ProtocolContract.Keys.TELEMETRY_JSON, json)
                        }
                        setUrgent()
                    }.asPutDataRequest()
                    Wearable.getDataClient(this@SentinelService).putDataItem(putDataReq).await()
                }
            } catch (e: Exception) { Log.e(TAG, "Sync fail", e) }
            finally { dataClientMutex.unlock() }
        }
    }

    private fun streamTelemetryToDashboard(telemetry: TelemetryState) {
        serviceScope.launch {
            if (!dashboardStreamMutex.tryLock()) return@launch
            try {
                val json = withContext(Dispatchers.IO) { ProtocolContract.protocolJson.encodeToString(telemetry) }
                val data = json.toByteArray()
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.DASHBOARD_DATA, data)
                }
            } catch (e: Exception) { Log.e(TAG, "Stream fail", e) }
            finally { dashboardStreamMutex.unlock() }
        }
    }

    private suspend fun uploadPendingLogs() {
        try {
            val oldest = logStore.getOldestBatch() ?: return
            val (file, batch) = oldest
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            if (nodes.isEmpty()) return
            val json = prettyJson.encodeToString(batch)
            val data = json.toByteArray()
            for (node in nodes) {
                Wearable.getMessageClient(this).sendMessage(node.id, ProtocolContract.Paths.TELEMETRY_LOG, data).await()
            }
            logStore.deleteFile(file)
        } catch (e: Exception) { Log.e(TAG, "Log upload fail", e) }
    }

    private fun setVInferenceState(newState: VehicleInferenceState) {
        if (vInferenceState != newState) {
            vInferenceState = newState
            SentinelComplicationService.update(this)
            if (newState == VehicleInferenceState.IMPACT) startAudioRecording()
        }
    }

    private var failSafeRetryJob: Job? = null
    private fun startFailSafeRetryLoop() {
        if (failSafeRetryJob?.isActive == true) return
        failSafeRetryJob = serviceScope.launch {
            while (isActive) {
                retryPendingIncidents()
                delay(300000L)
            }
        }
    }

    private suspend fun retryPendingIncidents() {
        try {
            val pending = assetStore.getPendingIncidents()
            if (pending.isEmpty()) return
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            if (nodes.isEmpty()) return
            for ((_, record) in pending) {
                var edrSent = false
                for (node in nodes) {
                    try {
                        Wearable.getMessageClient(this).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_REPORT, record.edrJson.toByteArray()).await()
                        edrSent = true
                    } catch (e: Exception) {}
                }
                if (edrSent) assetStore.deletePendingIncident(record.id)
            }
        } catch (e: Exception) { Log.e(TAG, "Retry fail", e) }
    }

    private fun startMonitoringLogic() {
        if (_currentState.value == IncidentState.MONITORING) return
        _currentState.value = IncidentState.MONITORING
        serviceScope.launch { settingsRepository.updateMonitoringState(IncidentState.MONITORING.name) }
        baselinePressure = 0f
        startWatchGps()
        startFailSafeRetryLoop()
        sendTelemetryToPhone(_telemetry.value, fullSync = true)
        SentinelComplicationService.update(this)
    }

    private fun triggerAlertUI() {
        serviceScope.launch {
            try {
                val edrSnapshot = edrBuffer.toList()
                edrActiveBuffer.clear()
                edrActiveBuffer.addAll(edrSnapshot)
                
                // v33.9: Continuous High-Intensity Service Vibration as Fail-safe
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                serviceVibrator = vib
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 1000, 200), 0) // Repeating
                vib.vibrate(effect)

                // v34.1: Use BroadcastReceiver to bridge Activity launch (Most reliable BAL bypass)
                val alertIntent = Intent(this@SentinelService, IncidentAlertActivity.AlertReceiver::class.java).apply {
                    putExtra("reason", if (isSimulatingLocked) "SIMULATION" else "CRASH DETECTED")
                    putExtra("timestamp", System.currentTimeMillis())
                    putExtra("maxG", overallPeak.pCrashScore)
                    putExtra("speed", overallPeak.pGpsSpeed)
                    if (_telemetry.value.lastLat != 0.0) {
                        putExtra("lat", _telemetry.value.lastLat); putExtra("lon", _telemetry.value.lastLon)
                        putExtra("last_lat", _telemetry.value.lastLat); putExtra("last_lon", _telemetry.value.lastLon)
                        putExtra("has_location", true)
                    } else putExtra("has_location", false)
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    this@SentinelService, 0, alertIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                try {
                    // Send broadcast immediately to trigger Activity via Receiver
                    pendingIntent.send()
                    Log.i(TAG, "🚀 Incident Broadcast sent to AlertReceiver")
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast send failed", e)
                }

                // v33.8: Full Screen Intent via Notification (System-level redundant backup)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val alertChannelId = "incident_alerts"
                val channel = NotificationChannel(alertChannelId, "Critical Incident Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)

                val notificationBuilder = NotificationCompat.Builder(this@SentinelService, alertChannelId)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("CRASH DETECTED")
                    .setContentText("Emergency alert active!")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                notificationManager.notify(INCIDENT_NOTIFICATION_ID, notificationBuilder.build())
                Log.i(TAG, "📢 Full-screen incident notification posted")

                val incidentData = IncidentData(
                    type = if (isSimulatingLocked) "SIMULATION" else "IMPACT",
                    timestamp = System.currentTimeMillis(), utcTime = readablePointFormat.format(Date()),
                    maxG = overallPeak.pCrashScore, speed = overallPeak.pGpsSpeed, isSimulation = isSimulatingLocked,
                    latitude = if (_telemetry.value.gpsStatus == SensorStatus.FIX_3D) _telemetry.value.lastLat else null,
                    longitude = if (_telemetry.value.gpsStatus == SensorStatus.FIX_3D) _telemetry.value.lastLon else null,
                    lastKnownLat = if (_telemetry.value.lastLat != 0.0) _telemetry.value.lastLat else null,
                    lastKnownLon = if (_telemetry.value.lastLon != 0.0) _telemetry.value.lastLon else null,
                    sensorData = edrSnapshot
                )

                val edrJson = prettyJson.encodeToString(incidentData)
                sendEdrReportImmediately(edrJson, incidentData.timestamp.toString())
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_START, edrJson.toByteArray()).await()
                }
            } catch (e: Exception) { Log.e(TAG, "Alert fail", e) }
        }
        startAudioRecording(durationMs = 25000L)
    }

    private fun sendEdrReportImmediately(edrJson: String, incidentId: String) {
        serviceScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_REPORT, edrJson.toByteArray()).await()
            } catch (e: Exception) {
                assetStore.savePendingIncident(PendingIncident(incidentId, edrJson, null, 0, System.currentTimeMillis()))
            }
        }
    }

    private var recordingJob: Job? = null
    private fun startAudioRecording(durationMs: Long = 10000L) {
        serviceScope.launch {
            if (!audioRecordingMutex.tryLock()) return@launch
            try {
                if (mediaRecorder != null) return@launch
                val file = File(getExternalCacheDir() ?: cacheDir, "${audioFileNameFormat.format(Date())}-REC.aac")
                audioFile = file
                mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@SentinelService) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setOutputFile(file.absolutePath)
                    prepare(); start()
                }
                recordingJob?.cancel()
                recordingJob = serviceScope.launch { delay(durationMs); stopAudioRecording(delete = false) }
            } catch (e: Exception) { 
                Log.e(TAG, "Audio fail", e)
                if (audioRecordingMutex.isLocked) audioRecordingMutex.unlock()
            }
        }
    }

    private fun stopAudioRecording(delete: Boolean) {
        serviceScope.launch {
            try {
                val recorder = mediaRecorder
                mediaRecorder = null
                recorder?.apply { stop(); release() }
                val file = audioFile
                audioFile = null
                if (delete) { file?.delete(); edrActiveBuffer.clear() }
                else if (file != null && file.exists()) transferAudioEvidenceOnly(file)
            } catch (e: Exception) { Log.e(TAG, "Audio stop fail", e) }
            finally { if (audioRecordingMutex.isLocked) audioRecordingMutex.unlock() }
        }
    }

    private suspend fun transferAudioEvidenceOnly(audioFile: File) {
        if (!transferMutex.tryLock()) return
        try {
            var success = false
            for (attempt in 0..2) {
                try {
                    val nodes = Wearable.getNodeClient(this).connectedNodes.await()
                    if (nodes.isEmpty()) { delay(5000); continue }
                    val asset = if (attempt < 2) Asset.createFromUri(Uri.fromFile(audioFile)) else Asset.createFromBytes(audioFile.readBytes())
                    val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.INCIDENT_AUDIO_ASSET).apply {
                        dataMap.putAsset(ProtocolContract.Keys.AUDIO_FILE, asset)
                        dataMap.putString("file_name", audioFile.nameWithoutExtension)
                        dataMap.putLong("timestamp", System.currentTimeMillis())
                        setUrgent()
                    }.asPutDataRequest()
                    Wearable.getDataClient(this).putDataItem(putDataReq).await()
                    success = true; break
                } catch (e: Exception) { delay(5000) }
            }
            if (success) audioFile.delete()
        } finally { transferMutex.unlock() }
    }

    fun simulateIncident(path: String) {
        if (isSimulatingLocked) return
        isSimulatingLocked = true
        serviceScope.launch {
            try {
                when (path) {
                    ProtocolContract.Paths.SIMULATE_HARD_BRAKE -> simulateHardBrake()
                    ProtocolContract.Paths.SIMULATE_FRONTAL -> simulateFrontalImpact()
                    ProtocolContract.Paths.SIMULATE_SIDE -> simulateSideImpact()
                    ProtocolContract.Paths.SIMULATE_ROLLOVER -> simulateRollover()
                    ProtocolContract.Paths.SIMULATE_REAR -> simulateRearImpact()
                    ProtocolContract.Paths.SIMULATE_PLUNGE -> simulateCliffPlunge()
                    ProtocolContract.Paths.SIMULATE_RANDOM -> simulateRandom()
                    else -> simulateDefault()
                }
            } finally { isSimulatingLocked = false }
        }
    }

    private suspend fun simulateHardBrake() {
        updateSim(speedKmh = 100f); delay(500)
        updateSim(ay = -1.2f * gEarth, speedKmh = 60f); delay(1000)
        updateSim(ay = -0.8f * gEarth, speedKmh = 20f); delay(1000)
    }

    private suspend fun simulateFrontalImpact() {
        updateSim(speedKmh = 80f); delay(500)
        updateSim(ay = -6.0f * gEarth, speedKmh = 70f); delay(500)
        updateSim(ay = -15.0f * gEarth, az = 2.0f * gEarth, speedKmh = 5f); delay(200)
        updateSim(speedKmh = 0f); delay(2000)
    }

    private suspend fun simulateSideImpact() {
        updateSim(speedKmh = 40f); delay(1000)
        updateSim(ax = 6.5f * gEarth, gy = 4.0f, speedKmh = 35f); delay(1000)
        updateSim(ax = 10.5f * gEarth, gz = 12.0f, speedKmh = 15f); delay(1000)
        updateSim(speedKmh = 0f); delay(3000)
    }

    private suspend fun simulateRollover() {
        updateSim(speedKmh = 70f); delay(1000)
        updateSim(az = 6.5f * gEarth, gx = 8.0f, gy = 10.0f, gz = 5.0f, speedKmh = 50f); delay(1000)
        updateSim(ax = 9.0f * gEarth, ay = 8.0f * gEarth, az = -5.0f * gEarth, gx = 15.0f, speedKmh = 20f); delay(1000)
        updateSim(speedKmh = 0f); delay(3000)
    }

    private suspend fun simulateRearImpact() {
        updateSim(speedKmh = 30f); delay(500)
        updateSim(ay = 6.0f * gEarth, speedKmh = 40f); delay(200)
        updateSim(ay = -12.0f * gEarth, speedKmh = 5f); delay(500)
        updateSim(speedKmh = 0f); delay(2000)
    }

    private suspend fun simulateCliffPlunge() {
        updateSim(speedKmh = 50f); delay(1000)
        updateSim(ax = 0f, ay = 0f, az = 0f, speedKmh = 45f); delay(1500)
        updateSim(az = 0.2f * gEarth, speedKmh = 30f); delay(1500)
        updateSim(az = 7.0f * gEarth, gx = 5.0f, speedKmh = 0f); delay(1000)
        updateSim(speedKmh = 0f); delay(3000)
    }

    private suspend fun simulateRandom() {
        val rand = java.util.Random()
        updateSim(speedKmh = 80f); delay(500)
        repeat(5) { updateSim(ax = (rand.nextFloat() - 0.5f) * 15f, gy = rand.nextFloat() * 10f, speedKmh = 80f - it * 15f); delay(200) }
        updateSim(speedKmh = 0f); delay(2000)
    }

    private suspend fun simulateDefault() {
        updateSim(speedKmh = 65f); delay(1000)
        updateSim(az = 12.5f * gEarth, speedKmh = 15f); delay(500)
        updateSim(speedKmh = 0f); delay(3000) 
    }

    private fun updateSim(ax: Float = 0f, ay: Float = 0f, az: Float = gEarth, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f, speedKmh: Float = 0f, accuracy: Float = 5f, confidence: Float = 1.0f, lat: Double = 37.5665, lon: Double = 126.9780) {
        synchronized(currentReading) {
            currentReading[0] = ax; currentReading[1] = ay; currentReading[2] = az
            currentReading[3] = gx; currentReading[4] = gy; currentReading[5] = gz
            currentReading[11] = speedKmh / mpsToKmh; currentReading[12] = accuracy
            lastLat = lat; lastLon = lon
        }
        gpsConfidence = confidence
        _telemetry.value = _telemetry.value.copy(lastLat = lat, lastLon = lon)
    }

    fun injectCustomSensorData(csv: String) {
        try {
            val parts = csv.split(",")
            if (parts.size >= 8) {
                val ax = parts[0].toFloat(); val ay = parts[1].toFloat(); val az = parts[2].toFloat()
                val gx = parts[3].toFloat(); val gy = parts[4].toFloat(); val gz = parts[5].toFloat()
                val sp = parts[6].toFloat(); val pr = parts[7].toFloat()
                serviceScope.launch {
                    synchronized(currentReading) {
                        currentReading[0] = ax; currentReading[1] = ay; currentReading[2] = az
                        val toRad = (Math.PI / 180.0).toFloat()
                        currentReading[3] = gx * toRad; currentReading[4] = gy * toRad; currentReading[5] = gz * toRad
                        currentReading[11] = sp / mpsToKmh; currentReading[6] = pr
                    }
                    baselinePressure = pr
                    if (Math.sqrt((ax*ax + ay*ay + az*az).toDouble()).toFloat() / gEarth > 10.0f) triggerAlertUI()
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Parse fail", e) }
    }

    private fun calculateRoll(rotationVector: FloatArray): Float {
        if (rotationVector.all { it == 0f }) return 0f
        return try {
            val rM = FloatArray(9); SensorManager.getRotationMatrixFromVector(rM, rotationVector)
            val orient = FloatArray(3); SensorManager.getOrientation(rM, orient)
            Math.toDegrees(orient[2].toDouble()).toFloat()
        } catch (e: Exception) { 0f }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        synchronized(currentReading) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { currentReading[0] = event.values[0]; currentReading[1] = event.values[1]; currentReading[2] = event.values[2]; lastAccelMag = Math.sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble()).toFloat() / gEarth }
                Sensor.TYPE_GYROSCOPE -> { currentReading[3] = event.values[0]; currentReading[4] = event.values[1]; currentReading[5] = event.values[2] }
                Sensor.TYPE_PRESSURE -> { currentReading[6] = event.values[0]; if (baselinePressure == 0f) baselinePressure = event.values[0] }
                Sensor.TYPE_ROTATION_VECTOR -> { for (i in 0 until minOf(event.values.size, 4)) currentReading[7 + i] = event.values[i] }
            }
        }
    }
    
    override fun onLocationChanged(location: Location) {
        if (isSimulatingLocked) return
        val now = SystemClock.elapsedRealtime()
        if (lastLocationTime > 0) lastLocationInterval = now - lastLocationTime
        lastLocationTime = now
        if (location.provider != LocationManager.GPS_PROVIDER) lastNetworkTime = now
        synchronized(currentReading) {
            if (location.provider == LocationManager.GPS_PROVIDER) { currentReading[11] = if (location.hasSpeed()) location.speed else 0f; lastWatchGpsTime = now }
            currentReading[12] = location.accuracy; currentReading[13] = location.bearing; currentReading[14] = location.altitude.toFloat()
            if (lastLat != 0.0) { val res = FloatArray(1); Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, res); lastDisplacement = res[0] }
            lastLat = location.latitude; lastLon = location.longitude
        }
        _telemetry.value = _telemetry.value.copy(watchGpsAccuracy = location.accuracy, lastLat = location.latitude, lastLon = location.longitude, lastLocationReceivedAt = now)
    }

    private fun startWatchGps() {
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            try { locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this) } catch (e: Exception) {}
            locationManager?.registerGnssStatusCallback(gnssStatusCallback, sensorHandler)
        } catch (e: SecurityException) {}
    }

    private fun stopWatchGps() {
        locationManager?.removeUpdates(this); locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
        synchronized(currentReading) { currentReading[11] = 0f }
        lastWatchGpsTime = 0L; lastNetworkTime = 0L; lastLocationTime = 0L; gnssSignalSeen = false; currentGpsMode = GpsMode.WATCH_ONLY
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return SentinelBinder() }
    inner class SentinelBinder : Binder() { fun getService(): SentinelService = this@SentinelService }

    companion object {
        var lastKnownState: IncidentState = IncidentState.IDLE
        var isRunning: Boolean = false
        var lastKnownVState: VehicleInferenceState = VehicleInferenceState.IDLE
            private set
        private const val NOTIFICATION_ID = 1001
        private const val INCIDENT_NOTIFICATION_ID = 1002
        const val ACTION_START_MONITORING = "com.jinn.watch2out.wear.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.jinn.watch2out.wear.STOP_MONITORING"
        const val ACTION_RESET_PEAKS = "com.jinn.watch2out.wear.RESET_PEAKS"
        const val ACTION_DASHBOARD_START = "com.jinn.watch2out.wear.DASHBOARD_START"
        const val ACTION_DASHBOARD_STOP = "com.jinn.watch2out.wear.DASHBOARD_STOP"
        const val ACTION_DASHBOARD_CONFIG = "com.jinn.watch2out.wear.DASHBOARD_CONFIG"
        const val ACTION_DISMISS_INCIDENT = "com.jinn.watch2out.wear.DISMISS_INCIDENT"
        const val ACTION_FINAL_DISPATCH = "com.jinn.watch2out.wear.FINAL_DISPATCH"
        const val ACTION_UPDATE_HEARTBEAT = "com.jinn.watch2out.wear.UPDATE_HEARTBEAT"
        const val ACTION_SIMULATE = "com.jinn.watch2out.wear.SIMULATE"
        const val ACTION_INJECT_DATA = "com.jinn.watch2out.wear.INJECT_DATA"
        const val ACTION_UPDATE_SYNC_POLICY = "com.jinn.watch2out.wear.UPDATE_SYNC_POLICY"
        const val ACTION_FORCE_SYNC = "com.jinn.watch2out.wear.FORCE_SYNC"
        const val ACTION_SENSOR_STATUS_SYNC = "com.jinn.watch2out.wear.SENSOR_STATUS_SYNC"
        const val ACTION_STOP_VIBRATION = "com.jinn.watch2out.wear.STOP_VIBRATION"
    }
}
