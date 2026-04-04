// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.shared.util.CrashScoreCalculator
import com.jinn.watch2out.wear.data.DataLogger
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.data.TelemetryLogStore
import com.jinn.watch2out.wear.presentation.IncidentAlertActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.* 
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*
import kotlin.math.sqrt

/**
 * Safety-critical service for real-time accident detection.
 * v27.4: Added Telemetry Logging feature for "day time" review.
 */
class SentinelService : Service(), SensorEventListener, LocationListener {

    private val binder: SentinelBinder = SentinelBinder() 

    inner class SentinelBinder : Binder() {
        fun getService(): SentinelService = this@SentinelService
    }

    override fun onBind(intent: Intent?): IBinder = binder 

    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _currentState: MutableStateFlow<IncidentState> = MutableStateFlow(IncidentState.IDLE)
    val currentState: StateFlow<IncidentState> = _currentState.asStateFlow()
    
    private val _telemetry: MutableStateFlow<TelemetryState> = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var settingsRepository: SettingsRepository? = null
    private var alertDispatcher: WearAlertDispatcher? = null
    private val dataLogger: DataLogger = DataLogger()
    private lateinit var telemetryLogStore: TelemetryLogStore
    
    private var currentSettings: WatchSettings = WatchSettings()
    private val currentReading: FloatArray = FloatArray(SENSOR_READING_SIZE) 
    
    @Volatile private var currentGpsSpeed: Float = 0f 
    @Volatile private var prevGpsSpeed: Float = 0f
    private var lastLocation: Location? = null
    private var lastGpsUpdateTime: Long = 0L

    private var pendingIncidentSnapshot: IncidentData? = null
    private var vInferenceState: VehicleInferenceState = VehicleInferenceState.IDLE
    private var detectedType: VehicleIncidentType = VehicleIncidentType.NONE
    private var stateEntryTime: Long = 0L
    private var lastSignificantMotionTime: Long = 0L
    private var lastStreamTime: Long = 0L
    private var lastLogTime: Long = 0L
    private var sessionStartTime: Long = 0L
    private var currentMode: DetectionMode = DetectionMode.VEHICLE
    
    private var isSimulatingLocked: Boolean = false
    private var isDashboardActive: Boolean = false
    private val TAG: String = "SentinelFSM"

    private var mediaRecorder: MediaRecorder? = null
    private val recordedAudioFiles: MutableList<File> = mutableListOf()
    private var audioRecordingJob: Job? = null

    private val explicitJson: Json = Json { 
        allowSpecialFloatingPointValues = true 
        ignoreUnknownKeys = true 
        encodeDefaults = true 
    }

    private var dynamicRateMs: Int = 450
    private var lastRateChangeTime: Long = 0L
    
    // Phase 3: GPS Fusion
    private var lastHeartbeat: Heartbeat? = null
    private var gpsGracePeriodStart: Long = 0L
    var currentGpsMode = GpsMode.WATCH_ONLY
        private set

    private var baselinePressure: Float = 0f
    private var totalRotation: Float = 0f
    private var lastGyroTimeNs: Long = 0L
    
    private var preImpactSpeedKmh: Float = 0f
    private var maxDeltaV: Float = 0f
    private var maxGyroRatio: Float = 1.0f

    private var hasAccel: Boolean = false
    private var hasGyro: Boolean = false
    private var hasBaro: Boolean = false
    private var hasGps: Boolean = false
    private var hasMic: Boolean = false
    private var hasSms: Boolean = false
    private var hasVoice: Boolean = false

    private var overallPeak: TelemetryState = TelemetryState()
    private var windowPeak: TelemetryState = TelemetryState()
    private var lastWindowResetTime: Long = 0L

    private val telemetryLogBuffer = mutableListOf<TelemetryLogEntry>()

    private val dismissAlertReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS_ALERT) {
                Log.d(TAG, "Received DISMISS_ALERT broadcast. User is OK.")
                dismissIncidentInternal()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val sm: SensorManager? = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lm: LocationManager? = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = sm
        locationManager = lm
        settingsRepository = SettingsRepository(this)
        telemetryLogStore = TelemetryLogStore(this)
        alertDispatcher = WearAlertDispatcher(this)
        
        hasAccel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        hasGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        hasBaro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        hasGps = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        hasMic = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        
        val tm: TelephonyManager? = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        hasVoice = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CDMA) || 
                   packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_GSM)
        
        hasSms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            @Suppress("DEPRECATION")
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }

        val now: Long = System.currentTimeMillis()
        sessionStartTime = now
        overallPeak = TelemetryState(sessionStartTime = now)
        _telemetry.update { it.copy(sessionStartTime = now) }

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        
        val intentFilter: IntentFilter = IntentFilter(ACTION_DISMISS_ALERT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissAlertReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dismissAlertReceiver, intentFilter)
        }

        settingsRepository?.let { repo ->
            serviceScope.launch { repo.settingsFlow.collectLatest { handleSettingsChange(it) } }
        }

        serviceScope.launch { 
            _currentState.collectLatest { state ->
                lastKnownState = state
                if (state == IncidentState.TRIGGERED) {
                    _telemetry.update { it.copy(vehicleInferenceState = VehicleInferenceState.WAIT_CONFIRM) }
                    broadcastStatusToMobile(_telemetry.value)
                } else if (state == IncidentState.IDLE) {
                    _telemetry.update { it.copy(vehicleInferenceState = VehicleInferenceState.IDLE) }
                    broadcastStatusToMobile(_telemetry.value)
                }
                SentinelComplicationService.update(this@SentinelService) 
            }
        }
        
        serviceScope.launch {
            _telemetry.map { it.vehicleInferenceState }.distinctUntilChanged().collect { state ->
                Log.d(TAG, "FSM State Changed: $state")
                broadcastStatusToMobile(_telemetry.value)
                
                when(state) {
                    VehicleInferenceState.IMPACT -> {
                        Log.w(TAG, "🚨 IMPACT DETECTED! Starting evidence capture.")
                        captureIncidentSnapshot("Incident: $detectedType")
                        startEvidenceRecording()
                    }
                    VehicleInferenceState.WAIT_CONFIRM -> launchIncidentAlertActivity()
                    VehicleInferenceState.CONFIRMED_CRASH -> {
                        pendingIncidentSnapshot?.let { data ->
                            alertDispatcher?.dispatch(currentSettings, data.type, data.timestamp, data.latitude, data.longitude, data.maxG, data.speed)
                        }
                        resetFsmAfterDispatch()
                    }
                    else -> {}
                }
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(3600000L) // Upload every 1 hour
                if (currentSettings.isTelemetryLoggingEnabled) {
                    uploadPendingTelemetryLogs()
                }
            }
        }
        
        serviceScope.launch {
            while (isActive) {
                val nowMs: Long = System.currentTimeMillis()
                
                // GPS Timeout: Reset speed if data is stale
                if (lastGpsUpdateTime != 0L && (nowMs - lastGpsUpdateTime > GPS_TIMEOUT_MS)) {
                    currentGpsSpeed = 0f
                    synchronized(currentReading) { currentReading[11] = 0f }
                }

                updateMonitoringRate(nowMs)
                delay(dynamicRateMs.toLong())
                
                val snapshot: FloatArray = synchronized(currentReading) {
                    val copy: FloatArray = currentReading.copyOf()
                    if (currentGpsSpeed < prevGpsSpeed) {
                        val drop: Float = (prevGpsSpeed - currentGpsSpeed) * MPS_TO_KMH
                        maxDeltaV = maxOf(maxDeltaV, drop)
                    }
                    prevGpsSpeed = currentGpsSpeed
                    copy
                }
                
                if (currentGpsSpeed < STILLNESS_THRESHOLD_MPS && vInferenceState != VehicleInferenceState.IMPACT && vInferenceState != VehicleInferenceState.STILLNESS) {
                    if (nowMs - lastSignificantMotionTime > AUTO_IDLE_TIMEOUT_MS) {
                        totalRotation = 0f
                        lastSignificantMotionTime = nowMs
                        if (vInferenceState == VehicleInferenceState.MOVING) {
                             setVInferenceState(VehicleInferenceState.IDLE)
                        }
                    }
                } else {
                    lastSignificantMotionTime = nowMs
                }

                if (_currentState.value == IncidentState.MONITORING || isDashboardActive) {
                    serviceScope.launch { dataLogger.addRecord(nowMs, snapshot) }
                }
                
                val currentImpact: Float = magnitude(floatArrayOf(snapshot[0], snapshot[1], snapshot[2])) / G_EARTH
                val scoringResult: CrashScoreCalculator.Result = calculateCrashScoreResult(currentImpact, maxDeltaV, totalRotation, snapshot)
                val currentCrashScore: Float = scoringResult.finalScore
                
                // 3s Grace Period (Phase 3 GPS Fusion)
                val inGrace = System.currentTimeMillis() - gpsGracePeriodStart < 3000L
                val activeMode = if (inGrace) GpsMode.PHONE_PRIMARY else currentGpsMode
                
                val currentGyroMagnitude: Float = magnitude(floatArrayOf(snapshot[3], snapshot[4], snapshot[5]))
                val currentGyroRatio: Float = (currentGyroMagnitude / GYRO_NORMALIZER).coerceIn(0f, 10f)
                maxGyroRatio = maxOf(maxGyroRatio, currentGyroRatio)
                val currentPressDelta: Float = if (baselinePressure > 0) snapshot[IDX_PRESSURE] - baselinePressure else 0f

                if (nowMs - lastWindowResetTime > currentSettings.stillnessDurationMs * 10) { 
                    windowPeak = TelemetryState()
                    lastWindowResetTime = nowMs
                }

                if (currentCrashScore >= overallPeak.pCrashScore) {
                    overallPeak = updatePeakSnapshot(overallPeak, currentImpact, scoringResult, currentGyroRatio, currentPressDelta, totalRotation, snapshot, maxDeltaV, currentGpsSpeed * MPS_TO_KMH)
                }

                if (currentCrashScore >= windowPeak.wCrashScore) {
                    windowPeak = updatePeakSnapshot(windowPeak, currentImpact, scoringResult, currentGyroRatio, currentPressDelta, totalRotation, snapshot, maxDeltaV, currentGpsSpeed * MPS_TO_KMH, isWindow = true)
                }

                val newState: TelemetryState = TelemetryState(
                    currentImpact = currentImpact, maxImpact = overallPeak.maxImpact,
                    peakCrashScore = (overallPeak.pCrashScore * 100).toInt(),
                    windowImpact = windowPeak.windowImpact,
                    windowCrashScore = (windowPeak.wCrashScore * 100).toInt(),
                    currentMode = currentMode, vehicleInferenceState = vInferenceState, 
                    detectedVehicleIncident = detectedType, lastUpdateTime = nowMs,
                    sessionStartTime = sessionStartTime, accelX = snapshot[0], accelY = snapshot[1], accelZ = snapshot[2],
                    gyroX = snapshot[3], gyroY = snapshot[4], gyroZ = snapshot[5],
                    airPressure = snapshot[IDX_PRESSURE], rotationSpeed = snapshot[3], gpsSpeed = currentGpsSpeed * MPS_TO_KMH,
                    crashScore = currentCrashScore, gyroRatio = currentGyroRatio,
                    pressureDelta = currentPressDelta, rollSum = totalRotation,
                    
                    bonusWeak = scoringResult.bonusWeak, bonusFall = scoringResult.bonusFall, bonusImpact = scoringResult.bonusImpact,
                    nAccel = scoringResult.normalized["accel"] ?: 0f, nSpeed = scoringResult.normalized["speed"] ?: 0f, nGyro = scoringResult.normalized["gyro"] ?: 0f,
                    nPress = scoringResult.normalized["press"] ?: 0f, nStill = scoringResult.normalized["still"] ?: 0f, nRoll = scoringResult.normalized["roll"] ?: 0f,
                    wAccel = scoringResult.effectiveWeights["accel"] ?: 0f, wSpeed = scoringResult.effectiveWeights["speed"] ?: 0f, wGyro = scoringResult.effectiveWeights["gyro"] ?: 0f,
                    wPress = scoringResult.effectiveWeights["press"] ?: 0f, wStill = scoringResult.effectiveWeights["still"] ?: 0f, wRoll = scoringResult.effectiveWeights["roll"] ?: 0f,

                    pTimestamp = overallPeak.pTimestamp, pCrashScore = overallPeak.pCrashScore, pGpsSpeed = overallPeak.pGpsSpeed,
                    pGyroRatio = overallPeak.pGyroRatio, pRollSum = overallPeak.pRollSum, pPressureDelta = overallPeak.pPressureDelta,
                    maxLongitudinalG = overallPeak.maxLongitudinalG, maxLateralG = overallPeak.maxLateralG, maxSpeedDrop = overallPeak.maxSpeedDrop,
                    wTimestamp = windowPeak.wTimestamp, wCrashScore = windowPeak.wCrashScore, wGpsSpeed = windowPeak.wGpsSpeed,
                    wGyroRatio = windowPeak.wGyroRatio, wRollSum = windowPeak.wRollSum, wPressureDelta = windowPeak.wPressureDelta,
                    wMaxLongitudinalG = windowPeak.wMaxLongitudinalG, wMaxLateralG = windowPeak.wMaxLateralG, wMaxSpeedDrop = windowPeak.wMaxSpeedDrop
                )
                _telemetry.value = newState
                
                if (isDashboardActive || (nowMs - lastStreamTime >= TELEMETRY_STREAM_INTERVAL_MS)) {
                    if (_telemetry.value.vehicleInferenceState == vInferenceState) {
                         broadcastStatusToMobile(newState)
                    }
                    lastStreamTime = nowMs
                }

                // Telemetry Logging (v27.4)
                if (currentSettings.isTelemetryLoggingEnabled && (nowMs - lastLogTime >= 1000L)) {
                    lastLogTime = nowMs
                    telemetryLogBuffer.add(TelemetryLogEntry(
                        timestamp = nowMs,
                        ax = snapshot[0], ay = snapshot[1], az = snapshot[2],
                        gx = snapshot[3], gy = snapshot[4], gz = snapshot[5],
                        pressure = snapshot[IDX_PRESSURE],
                        rx = snapshot[7], ry = snapshot[8], rz = snapshot[9], rw = snapshot[10],
                        speed = currentGpsSpeed * MPS_TO_KMH,
                        crashScore = currentCrashScore,
                        fsmState = vInferenceState.name
                    ))
                    if (telemetryLogBuffer.size >= 60) { // Batch size: 60 seconds
                        val batch = TelemetryLogBatch(ArrayList(telemetryLogBuffer))
                        telemetryLogBuffer.clear()
                        serviceScope.launch { telemetryLogStore.saveBatch(batch) }
                    }
                }
                
                if (_currentState.value == IncidentState.MONITORING) {
                    checkFsmTransitions(currentImpact, currentGpsSpeed, currentGyroMagnitude, currentPressDelta, scoringResult.finalScore)
                }
            }
        }
    }

    private suspend fun uploadPendingTelemetryLogs() {
        val pending = telemetryLogStore.getPendingBatches()
        if (pending.isEmpty()) return
        
        Log.d(TAG, "Attempting to upload ${pending.size} telemetry log batches.")
        for ((file, batch) in pending) {
            if (sendTelemetryLogBatch(batch)) {
                telemetryLogStore.deleteFile(file)
            } else {
                break // Stop if connection is lost
            }
        }
    }

    private suspend fun sendTelemetryLogBatch(batch: TelemetryLogBatch): Boolean {
        return try {
            val json = explicitJson.encodeToString(batch)
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            if (nodes.isEmpty()) return false
            
            var success = true
            for (node in nodes) {
                try {
                    Wearable.getMessageClient(this).sendMessage(node.id, ProtocolContract.Paths.TELEMETRY_LOG, json.toByteArray()).await()
                } catch (e: Exception) {
                    success = false
                    Log.e(TAG, "Failed to send batch to node ${node.id}: ${e.message}")
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send telemetry log batch: ${e.message}")
            false
        }
    }

    private fun setVInferenceState(newState: VehicleInferenceState) {
        if (vInferenceState != newState) {
            // Capture pre-impact speed when moving out of steady states
            if ((vInferenceState == VehicleInferenceState.IDLE || vInferenceState == VehicleInferenceState.MOVING) && 
                (newState == VehicleInferenceState.PRE_EVENT || newState == VehicleInferenceState.IMPACT)) {
                preImpactSpeedKmh = currentGpsSpeed * MPS_TO_KMH
            }

            vInferenceState = newState
            stateEntryTime = System.currentTimeMillis()
            _telemetry.update { it.copy(vehicleInferenceState = newState) }
        }
    }

    private fun checkFsmTransitions(impact: Float, speed: Float, gyro: Float, pDelta: Float, crashScore: Float) {
        val now = System.currentTimeMillis()
        val timeInState = now - stateEntryTime

        when (vInferenceState) {
            VehicleInferenceState.IDLE -> {
                if (isMovingSustained(speed)) setVInferenceState(VehicleInferenceState.MOVING)
                else if (isExternalForce(impact)) setVInferenceState(VehicleInferenceState.PRE_EVENT)
            }
            VehicleInferenceState.MOVING -> {
                if (isStill(speed, timeInState)) setVInferenceState(VehicleInferenceState.IDLE)
                else if (isInstabilityOrHardBraking(impact, maxDeltaV)) setVInferenceState(VehicleInferenceState.PRE_EVENT)
            }
            VehicleInferenceState.PRE_EVENT -> {
                if (isHighG(impact) || hasSpeedDelta(maxDeltaV) || hasRotation(totalRotation)) setVInferenceState(VehicleInferenceState.IMPACT)
                else if (isLowG(impact, timeInState)) setVInferenceState(VehicleInferenceState.FALLING)
                else if (timeInState > 2000L) setVInferenceState(if (speed * MPS_TO_KMH > 5f) VehicleInferenceState.MOVING else VehicleInferenceState.IDLE)
            }
            VehicleInferenceState.FALLING -> {
                if (isTerminalImpact(impact)) setVInferenceState(VehicleInferenceState.IMPACT)
                else if (isSoftLanding(impact, pDelta)) setVInferenceState(VehicleInferenceState.IDLE)
            }
            VehicleInferenceState.IMPACT -> {
                if (timeInState > 500L) setVInferenceState(VehicleInferenceState.POST_MOTION)
            }
            VehicleInferenceState.POST_MOTION -> {
                if (isImmediateStop(impact, gyro, speed) || timeInState > 3000L) setVInferenceState(VehicleInferenceState.STILLNESS)
                else if (isSecondaryDrop(impact, pDelta, timeInState)) setVInferenceState(VehicleInferenceState.IMPACT)
            }
            VehicleInferenceState.STILLNESS -> {
                if (crashScore >= currentSettings.crashScoreThreshold && timeInState >= currentSettings.stillnessDurationMs) setVInferenceState(VehicleInferenceState.WAIT_CONFIRM)
                else if (isMovingSustained(speed)) setVInferenceState(VehicleInferenceState.MOVING)
                else if (isNoSignificantImpact(impact, timeInState) && crashScore < 0.3f) setVInferenceState(VehicleInferenceState.IDLE)
            }
            else -> {}
        }
    }

    private fun updatePeakSnapshot(currentPeak: TelemetryState, impact: Float, result: CrashScoreCalculator.Result, gyroRatio: Float, pDelta: Float, roll: Float, snapshot: FloatArray, dV: Float, spd: Float, isWindow: Boolean = false): TelemetryState {
        val now = System.currentTimeMillis()
        return if (isWindow) {
            currentPeak.copy(
                wTimestamp = now, wCrashScore = result.finalScore, windowImpact = impact,
                wAccelX = snapshot[0], wAccelY = snapshot[1], wAccelZ = snapshot[2],
                wGyroX = snapshot[3], wGyroY = snapshot[4], wGyroZ = snapshot[5],
                wAirPressure = snapshot[IDX_PRESSURE], wGpsSpeed = spd,
                wGyroRatio = gyroRatio, wPressureDelta = pDelta, wRollSum = roll,
                wMaxLongitudinalG = maxOf(currentPeak.wMaxLongitudinalG, snapshot[1] / G_EARTH),
                wMaxLateralG = maxOf(currentPeak.wMaxLateralG, snapshot[0] / G_EARTH),
                wMaxSpeedDrop = maxOf(currentPeak.wMaxSpeedDrop, dV)
            )
        } else {
            currentPeak.copy(
                pTimestamp = now, pCrashScore = result.finalScore, maxImpact = impact,
                pAccelX = snapshot[0], pAccelY = snapshot[1], pAccelZ = snapshot[2],
                pGyroX = snapshot[3], pGyroY = snapshot[4], pGyroZ = snapshot[5],
                pAirPressure = snapshot[IDX_PRESSURE], pGpsSpeed = spd,
                pGyroRatio = gyroRatio, pPressureDelta = pDelta, pRollSum = roll,
                maxLongitudinalG = maxOf(currentPeak.maxLongitudinalG, snapshot[1] / G_EARTH),
                maxLateralG = maxOf(currentPeak.maxLateralG, snapshot[0] / G_EARTH),
                maxSpeedDrop = maxOf(currentPeak.maxSpeedDrop, dV)
            )
        }
    }

    private fun calculateCrashScoreResult(impact: Float, deltaV: Float, rollSum: Float, snapshot: FloatArray): CrashScoreCalculator.Result {
        val gyroRms: Float = magnitude(floatArrayOf(snapshot[3], snapshot[4], snapshot[5])) * RAD_TO_DEG
        val pressureDelta: Float = if (baselinePressure > 0) snapshot[IDX_PRESSURE] - baselinePressure else 0f
        val stillTimeSec: Float = if (vInferenceState == VehicleInferenceState.STILLNESS) (System.currentTimeMillis() - stateEntryTime) / 1000f else 0f

        val features: CrashScoreCalculator.Features = CrashScoreCalculator.Features(
            peakG = impact, deltaV = deltaV, vPre = preImpactSpeedKmh, gyroRms = gyroRms, pressureDelta = pressureDelta,
            lowG = impact < 0.3f, pressureDrop = pressureDelta < -0.5f, stillTimeSec = stillTimeSec, userInput = false,
            rollSumDeg = rollSum, hasAccel = hasAccel, hasSpeed = hasGps && lastLocation != null, hasGyro = hasGyro,
            hasPressure = hasBaro, hasStill = true, hasRoll = hasGyro
        )
        
        val loc: Location? = lastLocation
        val confidence: CrashScoreCalculator.SensorConfidence = CrashScoreCalculator.SensorConfidence(
            accel = 1.0f, gyro = 1.0f, pressure = 1.0f, posture = 1.0f,
            gps = if (loc != null && (System.currentTimeMillis() - loc.time) < 5000) 1.0f else 0.5f
        )

        return CrashScoreCalculator.computeCrashScore(features, currentSettings, confidence)
    }

    private fun updateMonitoringRate(nowMs: Long) {
        val speedKmh: Float = currentGpsSpeed * MPS_TO_KMH
        
        // 1. Determine Target Rate (Phase 3 GPS Fusion)
        val targetRate: Int = if (vInferenceState != VehicleInferenceState.IDLE && vInferenceState != VehicleInferenceState.MOVING) {
            100 // High-Res during incident
        } else {
            val hb = lastHeartbeat
            // v27.7.2: ONLY use phone speed zone if explicitly marked as reliable (Strict <25m, <2s)
            if (hb != null && hb.isSpeedHintReliable && hb.speedZone != null) {
                hb.getAdaptiveIntervalMs().toInt()
            } else {
                // Fallback to Watch Speed Zone (Independent Mode)
                when {
                    speedKmh >= 82f -> 75
                    speedKmh >= 32f -> 150
                    speedKmh >= 12f -> 200
                    else -> 450
                }
            }
        }

        if (targetRate != dynamicRateMs && (nowMs - lastRateChangeTime > RATE_CHANGE_COOLDOWN_MS)) {
            Log.d(TAG, "Changing sampling rate: $dynamicRateMs -> $targetRate ms (Speed: $speedKmh km/h, Mode: $currentGpsMode)")
            dynamicRateMs = targetRate; lastRateChangeTime = nowMs
            if (_currentState.value == IncidentState.MONITORING || isDashboardActive) registerSensors()
        }
    }

    private fun handleHeartbeat(hb: Heartbeat) {
        val prevMode = currentGpsMode
        val now = System.currentTimeMillis()
        lastHeartbeat = hb
        
        // 1. UI Status Update (with 5s Hysteresis)
        if (hb.phoneGpsStatus == PhoneGpsStatus.AVAILABLE) {
            lastGpsUpdateTime = now
        }
        val isPhoneGpsAvailableForUi = hb.phoneGpsStatus == PhoneGpsStatus.AVAILABLE || (now - lastGpsUpdateTime < 5000)

        // 2. Control Logic Update (Strict Reliability - No Hysteresis)
        // AGENTS.md Rule 5: Only switch to PHONE_PRIMARY if the hint is strictly reliable (<25m, <2s)
        currentGpsMode = if (hb.isSpeedHintReliable && hb.speedZone != null) {
            GpsMode.PHONE_PRIMARY
        } else {
            GpsMode.WATCH_ONLY
        }

        Log.d(TAG, "FUSION_DEBUG: hb_status=${hb.phoneGpsStatus}, hint_reliable=${hb.isSpeedHintReliable}, mode=$currentGpsMode")

        // Update the local telemetry state
        _telemetry.update { current ->
            val watchActive = lastLocation != null && (now - (lastLocation?.time ?: 0) < 15000)
            val phoneActive = isPhoneGpsAvailableForUi
            
            current.copy(
                isGpsActive = watchActive || phoneActive,
                isWatchGpsActive = watchActive,
                isPhoneGpsActive = phoneActive,
                phoneGpsAccuracy = if (hb.phoneGpsStatus == PhoneGpsStatus.AVAILABLE) hb.phoneGpsAccuracy else (if (phoneActive) current.phoneGpsAccuracy else 0f),
                watchGpsAccuracy = if (watchActive) lastLocation?.accuracy ?: 0f else 0f,
                activeGpsSource = currentGpsMode,
                lastUpdateTime = now
            )
        }

        if (prevMode == GpsMode.PHONE_PRIMARY && currentGpsMode == GpsMode.WATCH_ONLY) {
            Log.w(TAG, "FUSION_DEBUG: Phone GPS officially lost after grace period.")
        }

        if (prevMode != currentGpsMode) {
            Log.i(TAG, "FUSION_DEBUG: GPS Mode Change: $prevMode -> $currentGpsMode")
            manageWatchGpsHardware() // Toggle Watch GPS hardware for battery saving
            broadcastStatusToMobile(_telemetry.value)
        }
    }

    private fun isMovingSustained(speed: Float): Boolean = speed * MPS_TO_KMH > currentSettings.movingSpeedThresholdKmh 
    private fun isStill(speed: Float, timeInState: Long): Boolean = speed * MPS_TO_KMH < currentSettings.stillnessSpeedThresholdKmh && timeInState > currentSettings.stillnessDurationMin * 60000L

    private fun isExternalForce(impact: Float): Boolean = impact > currentSettings.preEventImpactThresholdG 
    private fun isInstabilityOrHardBraking(impact: Float, deltaV: Float): Boolean = impact > currentSettings.preEventImpactThresholdG || deltaV > currentSettings.preEventDeltaVThresholdKmh
    private fun isHighG(impact: Float): Boolean = impact > currentSettings.accelThresholdG 
    private fun hasSpeedDelta(deltaV: Float): Boolean = deltaV > currentSettings.impactDeltaVThresholdKmh 
    private fun hasRotation(rollSum: Float): Boolean = rollSum > currentSettings.impactRotationThresholdDeg 
    private fun isLowG(impact: Float, timeInState: Long): Boolean = impact < 0.3f && timeInState >= 200L 
    private fun isPressureDrop(pDelta: Float): Boolean = pDelta < currentSettings.pressureThresholdHpa * -1
    private fun isStabilized(impact: Float, gyro: Float): Boolean = impact < 1.5f && gyro < 0.5f 
    private fun isNoMovement(impact: Float, spd: Float, gyro: Float): Boolean = impact < 0.2f && spd < 0.5f && gyro < 0.1f 
    private fun isTerminalImpact(impact: Float): Boolean = impact > currentSettings.accelThresholdG / 2
    private fun isSoftLanding(impact: Float, pDelta: Float): Boolean = impact < 2.0f && pDelta > (currentSettings.pressureThresholdHpa * -0.2f)
    private fun isNoSignificantImpact(impact: Float, time: Long): Boolean = impact < 0.5f && time > 2000L 
    private fun isRolling(roll: Float, time: Long): Boolean = roll > currentSettings.gyroThresholdDeg && time > 500L 
    private fun isImmediateStop(impact: Float, gyro: Float, spd: Float): Boolean = impact < 0.5f && gyro < 0.2f && spd < 0.5f 
    private fun isSecondaryDrop(impact: Float, pDelta: Float, time: Long): Boolean = impact < 0.5f && pDelta < currentSettings.pressureThresholdHpa * -0.5f && time > 500L 
    private fun isMotionCeases(impact: Float, gyro: Float, spd: Float): Boolean = impact < 0.5f && gyro < 0.2f && spd < 0.5f 

    private fun captureIncidentSnapshot(reason: String) {
        val loc: Location? = fetchBestLocation()
        pendingIncidentSnapshot = IncidentData(
            type = reason, timestamp = System.currentTimeMillis(), latitude = loc?.latitude, longitude = loc?.longitude,
            maxG = overallPeak.maxImpact, speed = overallPeak.pGpsSpeed, isSimulation = currentSettings.isSimulationMode
        )
    }

    private fun startEvidenceRecording() {
        if (currentSettings.isSimulationMode || !hasMic) return
        audioRecordingJob?.cancel()
        audioRecordingJob = serviceScope.launch {
            try {
                val file: File = File(getExternalFilesDir(null), "evidence_${System.currentTimeMillis()}.amr")
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@SentinelService) else @Suppress("DEPRECATION") MediaRecorder()
                mediaRecorder?.let { mr ->
                    mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                    mr.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    mr.setOutputFile(file.absolutePath)
                    mr.prepare()
                    mr.start()
                }
                recordedAudioFiles.add(file)
                delay(10000)
                mediaRecorder?.let { 
                    it.stop()
                    it.release()
                }
                mediaRecorder = null
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed: ${e.message}")
            }
        }
    }

    private fun launchIncidentAlertActivity() {
        val data: IncidentData = pendingIncidentSnapshot ?: run { setVInferenceState(VehicleInferenceState.IDLE); return }
        if (_currentState.value != IncidentState.TRIGGERED) _currentState.value = IncidentState.TRIGGERED

        val wakeLock: PowerManager.WakeLock? = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "WWOut:IncidentWake")
        wakeLock?.acquire(30000)

        serviceScope.launch {
            try {
                val json: String = explicitJson.encodeToString(data)
                val nodes: List<Node> = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) Wearable.getMessageClient(this@SentinelService).sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_START, json.toByteArray())
            } catch (e: Exception) { Log.e(TAG, "Alert sync failed: ${e.message}") }
        }
        val intent: Intent = Intent(this, IncidentAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("reason", data.type); putExtra("timestamp", data.timestamp); putExtra("lat", data.latitude ?: 0.0); putExtra("lon", data.longitude ?: 0.0); putExtra("has_location", data.latitude != null); putExtra("maxG", data.maxG); putExtra("speed", data.speed)
        }
        val options: ActivityOptions = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
        val notification: Notification = NotificationCompat.Builder(this, INCIDENT_CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("CRASH DETECTED").setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(pendingIntent, true).setAutoCancel(true).setOngoing(true).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(INCIDENT_NOTIFICATION_ID, notification)
        try { startActivity(intent, options.toBundle()) } catch (e: Exception) { Log.e(TAG, "Activity launch failed: ${e.message}") }
    }

    private fun resetFsmAfterDispatch() {
        vInferenceState = VehicleInferenceState.IDLE
        _currentState.value = IncidentState.IDLE
        _telemetry.update { it.copy(vehicleInferenceState = VehicleInferenceState.IDLE) }
        pendingIncidentSnapshot = null; isSimulatingLocked = false; currentGpsSpeed = 0f; prevGpsSpeed = 0f; maxDeltaV = 0f; preImpactSpeedKmh = 0f; totalRotation = 0f; lastGyroTimeNs = 0L; broadcastStatusToMobile(_telemetry.value)
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(INCIDENT_NOTIFICATION_ID)
    }

    private fun dismissIncidentInternal() {
        audioRecordingJob?.cancel(); try { mediaRecorder?.stop() } catch (e: Exception) {}
        mediaRecorder?.release(); mediaRecorder = null; recordedAudioFiles.forEach { it.delete() }; recordedAudioFiles.clear()
        setVInferenceState(VehicleInferenceState.IDLE)
    }

    private fun resetPeaks() { 
        val now: Long = System.currentTimeMillis()
        sessionStartTime = now
        overallPeak = TelemetryState().copy(sessionStartTime = now)
        windowPeak = TelemetryState()
        setVInferenceState(VehicleInferenceState.IDLE)
        pendingIncidentSnapshot = null
        isSimulatingLocked = false
        currentGpsSpeed = 0f
        prevGpsSpeed = 0f
        maxDeltaV = 0f
        preImpactSpeedKmh = 0f
        totalRotation = 0f
        lastGyroTimeNs = 0L
        lastSignificantMotionTime = now
        detectedType = VehicleIncidentType.NONE
        broadcastStatusToMobile(_telemetry.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> { 
                _currentState.value = IncidentState.MONITORING
                registerSensors()
                setVInferenceState(VehicleInferenceState.IDLE)
                broadcastStatusToMobile(_telemetry.value)
            }
            ACTION_STOP_MONITORING -> { 
                _currentState.value = IncidentState.IDLE
                unregisterSensors()
                setVInferenceState(VehicleInferenceState.IDLE)
                broadcastStatusToMobile(_telemetry.value)
            }
            ACTION_RESET_PEAKS -> {
                resetPeaks()
                broadcastStatusToMobile(_telemetry.value)
            }
            ACTION_DASHBOARD_START -> { 
                isDashboardActive = true
                registerSensors()
                broadcastStatusToMobile(_telemetry.value)
            }
            ACTION_DASHBOARD_STOP -> { 
                isDashboardActive = false
                if (_currentState.value != IncidentState.MONITORING) unregisterSensors()
                broadcastStatusToMobile(_telemetry.value)
            }
            ACTION_SIMULATE -> simulateIncident(intent.getStringExtra("path") ?: "")
            ACTION_INJECT_DATA -> injectCustomSensorData(intent.getStringExtra("csv") ?: "")
            ACTION_DISMISS_INCIDENT -> {
                dismissIncidentInternal()
                broadcastStatusToMobile(_telemetry.value)
            }
            ACTION_UPDATE_HEARTBEAT -> {
                val json = intent.getStringExtra("heartbeat_json")
                if (json != null) {
                    try {
                        val hb = explicitJson.decodeFromString<Heartbeat>(json)
                        handleHeartbeat(hb)
                    } catch (e: Exception) {
                        Log.e(TAG, "Heartbeat parse failed: ${e.message}")
                    }
                }
            }
            ACTION_FINAL_DISPATCH -> setVInferenceState(VehicleInferenceState.CONFIRMED_CRASH)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun manageWatchGpsHardware() {
        val shouldBeRunning = _currentState.value == IncidentState.MONITORING || isDashboardActive
        val useWatchGps = currentGpsMode == GpsMode.WATCH_ONLY
        
        Handler(Looper.getMainLooper()).post {
            try {
                if (shouldBeRunning && useWatchGps) {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "FUSION: Starting Watch GPS Hardware")
                        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                        locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this)
                    }
                } else {
                    Log.d(TAG, "FUSION: Suspending Watch GPS Hardware (Mode=$currentGpsMode, Running=$shouldBeRunning)")
                    locationManager?.removeUpdates(this)
                }
            } catch (e: Exception) { Log.e(TAG, "FUSION: GPS Hardware Toggle Failed", e) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSensors() { 
        unregisterSensors()
        manageWatchGpsHardware()
        Handler(Looper.getMainLooper()).post {
            try { 
                val rateUs: Int = (dynamicRateMs * 1000).coerceAtLeast(10000)
                sensorManager?.let { sm ->
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, rateUs) }
                    sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, rateUs) }
                    sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                    sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                }
            } catch (e: Exception) { Log.e(TAG, "Sensor reg failed: ${e.message}") } 
        }
    }
    
    private fun unregisterSensors() { 
        sensorManager?.unregisterListener(this)
        locationManager?.removeUpdates(this) 
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        val ev: SensorEvent = event ?: return
        if (isSimulatingLocked) return
        
        val now = System.currentTimeMillis()
        if (isDashboardActive && now - lastStreamTime > 100) {
            lastStreamTime = now
            broadcastStatusToMobile(_telemetry.value)
        }

        synchronized(currentReading) {
            when (ev.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { currentReading[0] = ev.values[0]; currentReading[1] = ev.values[1]; currentReading[2] = ev.values[2] }
                Sensor.TYPE_GYROSCOPE -> {
                    val dx: Float = ev.values[0]; val dy: Float = ev.values[1]; val dz: Float = ev.values[2]
                    currentReading[3] = dx; currentReading[4] = dy; currentReading[5] = dz
                    val nowNs: Long = System.nanoTime()
                    if (lastGyroTimeNs != 0L) {
                        val dt: Float = (nowNs - lastGyroTimeNs) / 1_000_000_000f
                        val mag: Float = sqrt(dx*dx + dy*dy + dz*dz)
                        if (mag > 1.05f) totalRotation += mag * dt * RAD_TO_DEG
                        else { totalRotation *= 0.95f; if (totalRotation < 1.0f) totalRotation = 0f }
                    }
                    lastGyroTimeNs = nowNs
                    if (totalRotation >= 360f) totalRotation = 0f
                }
                Sensor.TYPE_PRESSURE -> {
                    currentReading[IDX_PRESSURE] = ev.values[0]
                    if (baselinePressure == 0f) baselinePressure = ev.values[0]
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    currentReading[7] = ev.values[0]; currentReading[8] = ev.values[1]; currentReading[9] = ev.values[2]; currentReading[10] = ev.values[3]
                }
            }
        }
    }
    
    override fun onLocationChanged(l: Location) { 
        if (!isSimulatingLocked) {
            val speed: Float = if (l.hasSpeed()) l.speed else {
                lastLocation?.let { last ->
                    val dist: Float = l.distanceTo(last); val time: Float = (l.time - last.time) / 1000f
                    if (time > 0.1f && time < 10f) dist / time else null
                } ?: 0f
            }
            currentGpsSpeed = speed
            lastGpsUpdateTime = System.currentTimeMillis()
            synchronized(currentReading) { currentReading[11] = currentGpsSpeed }
        }
        lastLocation = l
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() { 
        super.onDestroy() 
        serviceScope.cancel() 
        try { unregisterReceiver(dismissAlertReceiver) } catch (e: Exception) {}
        unregisterSensors()
    }

    private fun magnitude(v: FloatArray): Float = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])

    private fun fetchBestLocation(): Location? {
        val providers: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                val loc: Location? = locationManager?.getLastKnownLocation(provider)
                if (loc != null && System.currentTimeMillis() - loc.time < 60000) return loc
            } catch (e: Exception) {}
        }
        return lastLocation
    }

    private fun createNotificationChannels() {
        val nm: NotificationManager? = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sentinel", NotificationManager.IMPORTANCE_LOW))
        nm?.createNotificationChannel(NotificationChannel(INCIDENT_CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); setBypassDnd(true) })
    }

    private fun createForegroundNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Watch2Out Active").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()

    private fun broadcastStatusToMobile(telemetry: TelemetryState) {
        val now = System.currentTimeMillis()
        
        // USE PERSISTED STATE from the latest _telemetry update to ensure consistency
        val currentSnapshot = _telemetry.value.copy(lastUpdateTime = now)

        // 1. Update LOCAL StateFlow to ensure consistent UI across all components
        _telemetry.value = currentSnapshot

        // 2. Broadcast to Mobile
        val request: PutDataRequest = PutDataMapRequest.create(ProtocolContract.Paths.STATUS_SYNC).apply {
            dataMap.putBoolean(ProtocolContract.Keys.IS_ACTIVE, _currentState.value == IncidentState.MONITORING)
            dataMap.putString(ProtocolContract.Keys.ACCEL_STATUS, if (hasAccel) SensorStatus.AVAILABLE.name else SensorStatus.MISSING.name)
            dataMap.putString(ProtocolContract.Keys.GYRO_STATUS, if (hasGyro) SensorStatus.AVAILABLE.name else SensorStatus.MISSING.name)
            dataMap.putString(ProtocolContract.Keys.PRESS_STATUS, if (hasBaro) SensorStatus.AVAILABLE.name else SensorStatus.MISSING.name)
            dataMap.putString(ProtocolContract.Keys.ROT_STATUS, if (hasGyro) SensorStatus.AVAILABLE.name else SensorStatus.MISSING.name)
            dataMap.putString(ProtocolContract.Keys.LOC_STATUS, currentGpsMode.name)
            
            try { 
                dataMap.putString(ProtocolContract.Keys.TELEMETRY_JSON, explicitJson.encodeToString(TelemetryState.serializer(), currentSnapshot))
            } catch(e: Exception) {
                Log.e(TAG, "FUSION_DEBUG: Telemetry JSON encoding failed", e)
            }
            dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, now)
        }.asPutDataRequest().setUrgent()
        
        Wearable.getDataClient(this).putDataItem(request)
    }

    private suspend fun handleSettingsChange(s: WatchSettings) { 
        currentSettings = s; dataLogger.reconfigure(s)
        if (_currentState.value == IncidentState.MONITORING || isDashboardActive) registerSensors() 
    }

    fun simulateIncident(path: String) {
        isSimulatingLocked = true; captureIncidentSnapshot("Simulated Incident"); startEvidenceRecording()
        serviceScope.launch { delay(500); setVInferenceState(VehicleInferenceState.PRE_EVENT); delay(500); setVInferenceState(VehicleInferenceState.IMPACT); delay(500); setVInferenceState(VehicleInferenceState.STILLNESS); delay(currentSettings.stillnessDurationMs + 100); setVInferenceState(VehicleInferenceState.WAIT_CONFIRM); isSimulatingLocked = false }
    }

    fun injectCustomSensorData(csv: String) {
        serviceScope.launch {
            isSimulatingLocked = true
            try {
                val p: List<Float> = csv.split(",").map { it.trim().toFloat() }
                if (p.size >= 6) {
                    synchronized(currentReading) { currentReading[0] = p[0]; currentReading[1] = p[1]; currentReading[2] = p[2]; currentReading[IDX_PRESSURE] = p[5]; currentGpsSpeed = p[4] / MPS_TO_KMH; currentReading[11] = currentGpsSpeed }
                    delay(1000)
                }
            } finally { isSimulatingLocked = false }
        }
    }

    companion object {
        const val NOTIFICATION_ID: Int = 1001
        const val INCIDENT_NOTIFICATION_ID: Int = 1002
        const val CHANNEL_ID: String = "sentinel_channel"
        const val INCIDENT_CHANNEL_ID: String = "incident_channel"
        const val ACTION_START_MONITORING: String = "ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING: String = "ACTION_STOP_MONITORING"
        const val ACTION_RESET_PEAKS: String = "ACTION_RESET_PEAKS"
        const val ACTION_DASHBOARD_START: String = "ACTION_DASHBOARD_START"
        const val ACTION_DASHBOARD_STOP: String = "ACTION_DASHBOARD_STOP"
        const val ACTION_SIMULATE: String = "ACTION_SIMULATE"
        const val ACTION_INJECT_DATA: String = "ACTION_INJECT_DATA"
        const val ACTION_DISMISS_INCIDENT: String = "ACTION_DISMISS_INCIDENT"
        const val ACTION_UPDATE_HEARTBEAT: String = "ACTION_UPDATE_HEARTBEAT"
        const val ACTION_FINAL_DISPATCH: String = "ACTION_FINAL_DISPATCH"
        const val ACTION_DISMISS_ALERT: String = "com.jinn.watch2out.DISMISS_ALERT"
        
        private const val SENSOR_READING_SIZE: Int = 12
        private const val IDX_PRESSURE: Int = 6
        private const val G_EARTH: Float = 9.81f
        private const val MPS_TO_KMH: Float = 3.6f
        private const val RAD_TO_DEG: Float = 57.2958f
        private const val STILLNESS_THRESHOLD_MPS: Float = 0.55f
        private const val AUTO_IDLE_TIMEOUT_MS: Long = 600000L
        private const val TELEMETRY_STREAM_INTERVAL_MS: Long = 100L
        private const val RATE_CHANGE_COOLDOWN_MS: Long = 2000L
        private const val GYRO_NORMALIZER: Float = 5.0f
        private const val GPS_TIMEOUT_MS: Long = 15000L

        @Volatile var lastKnownState: IncidentState = IncidentState.IDLE; private set
    }
}
