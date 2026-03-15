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
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
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
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Safety-critical service for real-time accident detection.
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
    private var lastLocation: Location? = null
    
    private var pendingIncidentSnapshot: IncidentData? = null
    private var vInferenceState = VehicleInferenceState.IDLE
    private var detectedType = VehicleIncidentType.NONE
    private var stateEntryTime = 0L
    private var currentMode = DetectionMode.VEHICLE
    
    private var isSimulatingLocked = false
    private val TAG = "SentinelFSM"

    private val explicitJson = Json { allowSpecialFloatingPointValues = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private var overallMaxImpact = 0.0f
    private var overallMaxCrashScore = 0
    private var windowMaxImpact = 0.0f
    private var windowMaxCrashScore = 0

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
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        startForeground(NOTIFICATION_ID, createForegroundNotification(), type)
        
        serviceScope.launch { settingsRepository.settingsFlow.collectLatest { handleSettingsChange(it) } }
        serviceScope.launch { 
            _currentState.collectLatest { 
                lastKnownState = it
                SentinelComplicationService.update(this@SentinelService) 
            } 
        }

        serviceScope.launch {
            while (isActive) {
                delay(200)
                val snapshot: FloatArray
                synchronized(currentReading) { 
                    snapshot = currentReading.copyOf() 
                }
                
                // Record to buffer for EDR summary outside synchronized block
                serviceScope.launch { dataLogger.addRecord(snapshot) }
                
                val currentImpact = magnitude(floatArrayOf(snapshot[0], snapshot[1], snapshot[2])) / 9.81f
                if (currentImpact > windowMaxImpact) { windowMaxImpact = currentImpact }
                if (currentImpact > overallMaxImpact) { overallMaxImpact = currentImpact }

                val newState = TelemetryState(
                    currentImpact = currentImpact, maxImpact = overallMaxImpact, peakCrashScore = overallMaxCrashScore,
                    windowImpact = windowMaxImpact, windowCrashScore = windowMaxCrashScore, currentMode = currentMode,
                    vehicleInferenceState = vInferenceState, detectedVehicleIncident = detectedType,
                    accelX = snapshot[0], accelY = snapshot[1], accelZ = snapshot[2],
                    gyroX = snapshot[3] * 57.2958f, gyroY = snapshot[4] * 57.2958f, gyroZ = snapshot[5] * 57.2958f,
                    rotationX = snapshot[7], rotationY = snapshot[8], rotationZ = snapshot[9], airPressure = snapshot[6],
                    gpsSpeed = currentGpsSpeed
                )
                _telemetry.value = newState
                broadcastStatusToMobile(newState)
            }
        }
    }

    private fun setVInferenceState(newState: VehicleInferenceState) {
        if (vInferenceState != newState) {
            Log.d(TAG, "FSM Transition: $vInferenceState -> $newState")
            if (newState == VehicleInferenceState.IMPACT_DETECTED) {
                captureIncidentSnapshot("Vehicle Crash: $detectedType")
            }
            vInferenceState = newState
            stateEntryTime = System.currentTimeMillis()
        }
    }

    private fun updateVehicleInference(accel: FloatArray, gyro: FloatArray, speed: Float, pressure: Float) {
        var loop = true
        var loopLimit = 0
        while (loop && loopLimit < 3) {
            loop = false; loopLimit++
            val now = System.currentTimeMillis()
            val g = magnitude(accel) / 9.81f
            when (vInferenceState) {
                VehicleInferenceState.IDLE -> if (speed > 4.16f || isSimulatingLocked) { setVInferenceState(VehicleInferenceState.DRIVING); loop = true }
                VehicleInferenceState.DRIVING -> if (g > 4.0f) { classifyImpact(accel, gyro, speed); setVInferenceState(VehicleInferenceState.IMPACT_DETECTED); loop = true }
                VehicleInferenceState.IMPACT_DETECTED -> if (now - stateEntryTime > 1000L) { setVInferenceState(VehicleInferenceState.STILLNESS); loop = true }
                VehicleInferenceState.STILLNESS -> {
                    val stillnessRequirement = if (isSimulatingLocked) 500L else 3000L
                    if (magnitude(accel) < 11.0f && now - stateEntryTime > stillnessRequirement) {
                        onIncidentDetected()
                    }
                }
                else -> {}
            }
        }
    }

    private fun captureIncidentSnapshot(reason: String) {
        serviceScope.launch {
            val loc = fetchBestLocation()
            val sensorHistory = dataLogger.getOrderedSnapshot().map { 
                TelemetryPoint(0L, it[0], it[1], it[2], magnitude(floatArrayOf(it[0], it[1], it[2])) / 9.81f)
            }
            pendingIncidentSnapshot = IncidentData(
                type = reason,
                timestamp = System.currentTimeMillis(),
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                maxG = overallMaxImpact,
                speed = currentGpsSpeed,
                sensorData = sensorHistory
            )
            Log.w(TAG, "📸 IMPACT SNAPSHOT CAPTURED: Loc=${loc?.latitude},${loc?.longitude} G=${pendingIncidentSnapshot?.maxG}")
        }
    }

    fun injectCustomSensorData(csv: String) {
        serviceScope.launch {
            isSimulatingLocked = true
            try {
                val parts = csv.split(",").map { it.trim().toFloat() }
                if (parts.size >= 6) {
                    val accel = floatArrayOf(parts[0], parts[1], parts[2])
                    val gyro = floatArrayOf(parts[3], 0f, 0f)
                    synchronized(currentReading) { 
                        currentReading[0] = accel[0]; currentReading[1] = accel[1]; currentReading[2] = accel[2]
                        currentReading[6] = parts[5]; currentGpsSpeed = parts[4]
                    }
                    updateVehicleInference(accel, gyro, parts[4], parts[5])
                    delay(1000)
                    val still = floatArrayOf(0f, 0f, 9.81f)
                    for (i in 1..3) { updateVehicleInference(still, floatArrayOf(0f,0f,0f), 0f, parts[5]); delay(1000) }
                }
            } finally { isSimulatingLocked = false }
        }
    }

    fun simulateIncident(path: String) {
        val reason = when (path) {
            ProtocolContract.Paths.SIMULATE_FRONTAL -> "Simulated Frontal"
            ProtocolContract.Paths.SIMULATE_REAR -> "Simulated Rear-end"
            ProtocolContract.Paths.SIMULATE_SIDE -> "Simulated Side"
            ProtocolContract.Paths.SIMULATE_ROLLOVER -> "Simulated Rollover"
            ProtocolContract.Paths.SIMULATE_PLUNGE -> "Simulated Plunge"
            else -> "Simulated Incident"
        }
        isSimulatingLocked = true
        captureIncidentSnapshot(reason)
        // Wait a bit for snapshot capture since it's async now
        serviceScope.launch {
            delay(500)
            onIncidentDetected()
            isSimulatingLocked = false
        }
    }

    private fun onIncidentDetected() {
        if (_currentState.value == IncidentState.TRIGGERED && !isSimulatingLocked) return
        
        val data = pendingIncidentSnapshot ?: return
        Log.d(TAG, "Triggering Alert UI: ${data.type}")
        _currentState.value = IncidentState.TRIGGERED
        
        // Wake up screen explicitly
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "WWOut:IncidentWake")
        wakeLock.acquire(30000)

        // Notify Mobile with captured JSON
        serviceScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                val json = explicitJson.encodeToString(data)
                for (node in nodes) {
                     Wearable.getMessageClient(this@SentinelService)
                        .sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_START, json.toByteArray())
                }
            } catch (e: Exception) { Log.e(TAG, "Failed to notify mobile: ${e.message}") }
        }

        val intent = Intent(this, IncidentAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("reason", data.type)
            putExtra("timestamp", data.timestamp)
            data.latitude?.let { putExtra("lat", it) }
            data.longitude?.let { putExtra("lon", it) }
            putExtra("has_location", data.latitude != null)
            putExtra("maxG", data.maxG)
            putExtra("speed", data.speed)
        }
        
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())

        val notification = NotificationCompat.Builder(this, INCIDENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("CRASH DETECTED")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true) // Crucial for showing Activity when screen off
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(INCIDENT_NOTIFICATION_ID, notification)
        try { startActivity(intent, options.toBundle()) } catch (e: Exception) { Log.w(TAG, "BAL block.") }
    }

    @SuppressLint("MissingPermission")
    private fun fetchBestLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var bestLoc: Location? = lastLocation
        
        for (provider in providers) {
            try {
                val lastKnown = locationManager.getLastKnownLocation(provider)
                if (lastKnown != null) {
                    if (bestLoc == null || (System.currentTimeMillis() - lastKnown.time < 60000 && lastKnown.accuracy < bestLoc.accuracy)) {
                        bestLoc = lastKnown
                    }
                }
            } catch (e: Exception) {}
        }
        return bestLoc
    }

    fun finalizeEmergencyDispatch(type: String, timestamp: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        Log.i(TAG, "FINAL DISPATCH: $type")
        _currentState.value = IncidentState.DISPATCHING
        alertDispatcher.dispatch(currentSettings, type, timestamp, lat, lon, maxG, speed)
    }

    private fun classifyImpact(accel: FloatArray, gyro: FloatArray, speed: Float) {
        detectedType = if (accel[1] < 0) VehicleIncidentType.FRONTAL else VehicleIncidentType.REAR_END
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sentinel", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(INCIDENT_CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun createForegroundNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Watch2Out Active").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()

    private fun broadcastStatusToMobile(telemetry: TelemetryState) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val request = PutDataMapRequest.create(ProtocolContract.Paths.SETTINGS_SYNC).apply {
            dataMap.putBoolean("is_active", _currentState.value == IncidentState.MONITORING)
            dataMap.putString("mode", currentMode.name)
            dataMap.putString("accel_status", getSensorStatus(sm, Sensor.TYPE_ACCELEROMETER, currentSettings.isAccelEnabled).name)
            dataMap.putString("gyro_status", getSensorStatus(sm, Sensor.TYPE_GYROSCOPE, currentSettings.isGyroEnabled).name)
            dataMap.putString("press_status", getSensorStatus(sm, Sensor.TYPE_PRESSURE, currentSettings.isPressureEnabled).name)
            dataMap.putString("rot_status", getSensorStatus(sm, Sensor.TYPE_ROTATION_VECTOR, true).name)
            try { dataMap.putString("telemetry_json", explicitJson.encodeToString(TelemetryState.serializer(), telemetry)) } catch(e: Exception) {}
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        Wearable.getDataClient(this).putDataItem(request.asPutDataRequest())
    }

    private fun getSensorStatus(sm: SensorManager, type: Int, isEnabledInSettings: Boolean): SensorStatus {
        val sensor = sm.getDefaultSensor(type)
        return when {
            sensor == null -> SensorStatus.MISSING
            !isEnabledInSettings -> SensorStatus.DISABLED
            else -> SensorStatus.AVAILABLE
        }
    }

    private fun magnitude(v: FloatArray) = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
    private suspend fun handleSettingsChange(s: WatchSettings) { 
        currentSettings = s
        dataLogger.reconfigure(s)
        if (_currentState.value == IncidentState.MONITORING) registerSensors() 
    }
    
    fun resetPeaks() { 
        overallMaxImpact = 0.0f; setVInferenceState(VehicleInferenceState.IDLE); detectedType = VehicleIncidentType.NONE; pendingIncidentSnapshot = null
    }

    override fun onStartCommand(intent: Intent?, f: Int, s: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> { _currentState.value = IncidentState.MONITORING; resetPeaks(); registerSensors() }
            ACTION_STOP_MONITORING -> { _currentState.value = IncidentState.IDLE; unregisterSensors() }
            ACTION_RESET_PEAKS -> resetPeaks()
            ACTION_SIMULATE -> simulateIncident(intent.getStringExtra("path") ?: "")
            ACTION_INJECT_DATA -> injectCustomSensorData(intent.getStringExtra("csv") ?: "")
            ACTION_DISMISS_INCIDENT -> dismissIncidentInternal()
            ACTION_FINAL_DISPATCH -> {
                val i = intent ?: return START_STICKY
                finalizeEmergencyDispatch(
                    i.getStringExtra("type") ?: "Incident",
                    i.getLongExtra("timestamp", 0L),
                    if (i.hasExtra("lat")) i.getDoubleExtra("lat", 0.0) else null,
                    if (i.hasExtra("lon")) i.getDoubleExtra("lon", 0.0) else null,
                    i.getFloatExtra("maxG", 0f),
                    i.getFloatExtra("speed", 0f)
                )
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun registerSensors() { 
        unregisterSensors()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val us = currentSettings.samplingRateMs * 1000
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, us) }
        try { 
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this)
        } catch (e: Exception) { Log.e(TAG, "Loc Error: ${e.message}") } 
    }

    private fun unregisterSensors() { sensorManager.unregisterListener(this); locationManager.removeUpdates(this) }
    override fun onSensorChanged(event: SensorEvent?) { if (event == null || isSimulatingLocked) return; synchronized(currentReading) { when (event.sensor.type) { Sensor.TYPE_ACCELEROMETER -> { currentReading[0] = event.values[0]; currentReading[1] = event.values[1]; currentReading[2] = event.values[2]; if (_currentState.value == IncidentState.MONITORING) updateVehicleInference(event.values, floatArrayOf(currentReading[3],currentReading[4],currentReading[5]), currentGpsSpeed, currentReading[6]) }; Sensor.TYPE_GYROSCOPE -> { currentReading[3] = event.values[0]; currentReading[4] = event.values[1]; currentReading[5] = event.values[2] } } } }
    override fun onLocationChanged(l: Location) { 
        lastLocation = l
        if (!isSimulatingLocked && l.hasSpeed()) currentGpsSpeed = l.speed 
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun dismissIncidentInternal() {
        Log.d(TAG, "Dismissing incident alert.")
        _currentState.value = IncidentState.MONITORING; resetPeaks()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(INCIDENT_NOTIFICATION_ID)
        sendBroadcast(Intent("com.jinn.watch2out.DISMISS_ALERT"))
        
        // Send ACK back to confirm synchronization
        serviceScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@SentinelService).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@SentinelService)
                        .sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_DISMISS_ACK, null)
                }
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val INCIDENT_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "sentinel_channel"
        private const val INCIDENT_CHANNEL_ID = "incident_channel"
        const val ACTION_START_MONITORING = "ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING = "ACTION_STOP_MONITORING"
        const val ACTION_RESET_PEAKS = "ACTION_RESET_PEAKS"
        const val ACTION_SIMULATE = "ACTION_SIMULATE"
        const val ACTION_INJECT_DATA = "ACTION_INJECT_DATA"
        const val ACTION_DISMISS_INCIDENT = "ACTION_DISMISS_INCIDENT"
        const val ACTION_FINAL_DISPATCH = "ACTION_FINAL_DISPATCH"
        @Volatile var lastKnownState: IncidentState = IncidentState.IDLE; private set
    }
}
