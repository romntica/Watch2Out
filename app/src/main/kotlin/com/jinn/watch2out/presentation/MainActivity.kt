// [Module: :app]
package com.jinn.watch2out.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.*
import com.jinn.watch2out.service.MobileAlertDispatcher
import com.jinn.watch2out.service.PhoneGpsManager
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.shared.network.ProtocolContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
    object Dashboard : Screen()
    object TelemetryLogs : Screen()
}

class MainActivity : ComponentActivity(),
    DataClient.OnDataChangedListener,
    CapabilityClient.OnCapabilityChangedListener,
    MessageClient.OnMessageReceivedListener {

    private val isWatchActiveState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private val gpsModeState = mutableStateOf(GpsMode.WATCH_ONLY)
    
    private val sensorStates = mutableStateMapOf<String, SensorStatus>(
        "A" to SensorStatus.UNKNOWN,
        "G" to SensorStatus.UNKNOWN,
        "P" to SensorStatus.UNKNOWN,
        "R" to SensorStatus.UNKNOWN,
        "L" to SensorStatus.UNKNOWN // Location Status
    )
    private val watchSettingsState = mutableStateOf(WatchSettings())
    private val telemetryState = mutableStateOf<TelemetryState>(TelemetryState())
    
    private lateinit var alertDispatcher: MobileAlertDispatcher
    private lateinit var phoneGpsManager: PhoneGpsManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocationAvailability: com.google.android.gms.location.LocationAvailability? = null
    private var currentGpsIntervalMs = 600000L // Default 10 min (Sync #8)
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null

    private var currentScreenState: Screen = Screen.Main

    private val explicitJson = Json {
        allowSpecialFloatingPointValues = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Composable
    fun UpdateIndicatorsEffect() {
        LaunchedEffect(telemetryState.value, isWatchActiveState.value) {
            // This ensures sensorStates SnapshotStateMap is updated whenever telemetryState changes
            val t = telemetryState.value
            val isActive = isWatchActiveState.value
            
            // Phase 7: UI Logic - Show actual status if we have telemetry (even if IDLE/STOPPED)
            // But default to DISABLED/OFF visually if not monitoring to keep it clean.
            // If the user pulled-to-refresh, they will see the real Wear hardware state.
            sensorStates["A"] = t.accelStatus
            sensorStates["G"] = t.gyroStatus
            sensorStates["P"] = t.pressureStatus
            sensorStates["R"] = t.rotationStatus
            sensorStates["L"] = t.gpsStatus
        }
    }

    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertDispatcher = MobileAlertDispatcher(this)
        phoneGpsManager = PhoneGpsManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        startLocationUpdates()
        Wearable.getCapabilityClient(this).addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE)
        Wearable.getMessageClient(this).addListener(this)
        Wearable.getDataClient(this).addListener(this)
        
        checkConnectionStatus()
        fetchLatestTelemetry()
        lifecycleScope.launch { requestFullTelemetrySync() } // Phase 8: Initial Handshake (v32.9)
        handleIntent(intent)

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
            UpdateIndicatorsEffect()

            val refreshing = remember { mutableStateOf(false) }
            val pullRefreshState = rememberPullRefreshState(
                refreshing = refreshing.value,
                onRefresh = {
                    lifecycleScope.launch {
                        refreshing.value = true
                        requestFullTelemetrySync()
                        delay(2000)
                        refreshing.value = false
                    }
                }
            )

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentScreen) {
                        is Screen.Main -> {
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .pullRefresh(pullRefreshState)
                            ) {
                                MainAppContent(
                                    isWatchActive = isWatchActiveState.value,
                                    isConnected = isConnectedState.value,
                                    inferenceState = telemetryState.value.vehicleInferenceState,
                                    sensorStates = sensorStates,
                                    gpsMode = gpsModeState.value,
                                    telemetry = telemetryState.value,
                                    onCommand = { cmd -> sendRemoteCommand(cmd) },
                                    onNavigateToSettings = { requestWatchSettings(); currentScreen = Screen.Settings },
                                    onNavigateToDashboard = { currentScreen = Screen.Dashboard },
                                    onNavigateToLogs = { currentScreen = Screen.TelemetryLogs }
                                )
                                PullRefreshIndicator(
                                    refreshing = refreshing.value,
                                    state = pullRefreshState,
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                            }
                        }
                        is Screen.Settings -> {
                            BackHandler { currentScreen = Screen.Main }
                            key(watchSettingsState.value) {
                                SettingsScreen(
                                    currentSettings = watchSettingsState.value,
                                    isConnected = isConnectedState.value,
                                    onApply = { settings -> sendSettings(settings); currentScreen = Screen.Main },
                                    onCancel = { currentScreen = Screen.Main },
                                    onInjectCustomData = { ax, ay, az, gx, gy, gz, sp, pr -> injectCustomSensor(ax, ay, az, gx, gy, gz, sp, pr) }
                                )
                            }
                        }
                        is Screen.Dashboard -> {
                            BackHandler { currentScreen = Screen.Main }
                            currentScreenState = currentScreen
                            DisposableEffect(Unit) {
                                sendSyncPolicy(highSpeed = true)
                                sendRemoteCommand(ProtocolContract.Paths.DASHBOARD_START)
                                onDispose { 
                                    sendRemoteCommand(ProtocolContract.Paths.DASHBOARD_STOP)
                                    // Rule: Revert to 5s sync (battery save) when exiting dashboard
                                    sendSyncPolicy(highSpeed = false)
                                }
                            }
                            DashboardScreen(
                                telemetry = telemetryState.value,
                                onResetPeak = { sendRemoteCommand(ProtocolContract.Paths.RESET_PEAKS) },
                                onWindowChange = { windowMs -> sendDashboardConfig(windowMs) },
                                onClose = { currentScreen = Screen.Main }
                            )
                        }
                        is Screen.TelemetryLogs -> {
                            BackHandler { currentScreen = Screen.Main }
                            TelemetryLogScreen(onClose = { currentScreen = Screen.Main })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_TRIGGER_DISPATCH") {
            val reason = intent.getStringExtra("reason") ?: "Incident"
            val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
            val lat = if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null
            val lon = if (intent.hasExtra("lon")) intent.getDoubleExtra("lon", 0.0) else null
            val maxG = intent.getFloatExtra("maxG", 0f)
            val speed = intent.getFloatExtra("speed", 0f)
            
            triggerFinalDispatch(reason, timestamp, lat, lon, maxG, speed)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocationUpdates()
        if (permissions[Manifest.permission.SEND_SMS] == false) {
            Log.e("SentinelApp", "SMS Permission Denied. Emergency dispatch will fail.")
        }
    }

    private fun startLocationUpdates(intervalMs: Long? = null) {
        val interval = intervalMs ?: currentGpsIntervalMs
        if (interval == currentGpsIntervalMs && locationCallback != null) return
        
        currentGpsIntervalMs = interval
        Log.d("SentinelApp", "Location updates: ${interval/1000}s interval requested")

        if (PhoneGpsManager.IS_RESERVED_MODE) {
            Log.d("SentinelApp", "Location updates disabled: PhoneGpsManager is in RESERVED_MODE")
            return
        }

        // Cleanup existing callback if any
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                syncHeartbeatToWatch(location)
            }
            override fun onLocationAvailability(availability: LocationAvailability) {
                lastLocationAvailability = availability
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun syncHeartbeatToWatch(location: Location) {
        if (PhoneGpsManager.IS_RESERVED_MODE) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val gpsResult = phoneGpsManager.checkStatus(location, lastLocationAvailability)
                val speedZone = phoneGpsManager.calculateSpeedZone(location, gpsResult.isHintReliable)
                
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                val heartbeat = Heartbeat(
                    fsmState = telemetryState.value.vehicleInferenceState,
                    crashScore = telemetryState.value.crashScore,
                    batteryLevel = 0f, 
                    phoneGpsStatus = gpsResult.status,
                    phoneGpsAccuracy = location.accuracy,
                    phoneSpeedKmh = location.speed * 3.6f,
                    isSpeedHintReliable = gpsResult.isHintReliable,
                    speedZone = speedZone,
                    elapsedMs = elapsedMs
                )

                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val heartbeatJson = explicitJson.encodeToString(heartbeat)
                
                // Use DataClient for heartbeat sync to ensure all nodes receive it
                val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.HEARTBEAT_SYNC).apply {
                    dataMap.putString(ProtocolContract.Keys.HEARTBEAT_JSON, heartbeatJson)
                    dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                
                Wearable.getDataClient(this@MainActivity).putDataItem(putDataReq).await()
            } catch (e: Exception) { }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == ProtocolContract.Paths.INCIDENT_ALERT_START) {
            try {
                val json = String(messageEvent.data)
                val incident = explicitJson.decodeFromString<IncidentData>(json)
                val intent = Intent(this, IncidentAlertActivity::class.java).apply {
                    putExtra("reason", incident.type)
                    putExtra("timestamp", incident.timestamp)
                    incident.latitude?.let { putExtra("lat", it) }
                    incident.longitude?.let { putExtra("lon", it) }
                    incident.lastKnownLat?.let { putExtra("last_lat", it) }
                    incident.lastKnownLon?.let { putExtra("last_lon", it) }
                    putExtra("has_location", incident.latitude != null || incident.lastKnownLat != null)
                    putExtra("maxG", incident.maxG)
                    putExtra("speed", incident.speed)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) { }
        } else if (messageEvent.path == ProtocolContract.Paths.INCIDENT_ALERT_DISMISS) {
            sendBroadcast(Intent("com.jinn.watch2out.DISMISS_ALERT"))
        } else if (messageEvent.path == ProtocolContract.Paths.DASHBOARD_DATA) {
            try {
                val json = String(messageEvent.data)
                val newTelemetry = explicitJson.decodeFromString<TelemetryState>(json)
                val now = System.currentTimeMillis()

                telemetryState.value = newTelemetry.copy(
                    lastUpdateTime = now
                )

                // High-frequency UI indicators update
                sensorStates["A"] = newTelemetry.accelStatus
                sensorStates["G"] = newTelemetry.gyroStatus
                sensorStates["P"] = newTelemetry.pressureStatus
                sensorStates["R"] = newTelemetry.rotationStatus
                sensorStates["L"] = newTelemetry.gpsStatus
            } catch (e: Exception) {
                Log.e("SentinelSync", "Failed to decode dashboard stream", e)
            }
        }
    }

    fun triggerFinalDispatch(reason: String, timestamp: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        alertDispatcher.dispatch(watchSettingsState.value, reason, timestamp, lat, lon, maxG, speed)
    }

    private fun injectCustomSensor(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float, sp: Float, pr: Float) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csvData = "$ax,$ay,$az,$gx,$gy,$gz,$sp,$pr"
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, ProtocolContract.Paths.INJECT_CUSTOM_SENSOR, csvData.toByteArray()).await()
                }
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getCapabilityClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        isConnectedState.value = capabilityInfo.nodes.isNotEmpty()
        if (!isConnectedState.value) sensorStates.keys.forEach { sensorStates[it] = SensorStatus.UNKNOWN }
    }

    private fun checkConnectionStatus() {
        lifecycleScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                isConnectedState.value = nodes.isNotEmpty()
            } catch (e: Exception) { isConnectedState.value = false }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                
                when (path) {
                    ProtocolContract.Paths.STATUS_SYNC -> {
                        val isActive = dataMap.getBoolean(ProtocolContract.Keys.IS_ACTIVE)
                        isWatchActiveState.value = isActive
                        
                        sensorStates["A"] = parseStatus(dataMap.getString(ProtocolContract.Keys.ACCEL_STATUS))
                        sensorStates["G"] = parseStatus(dataMap.getString(ProtocolContract.Keys.GYRO_STATUS))
                        sensorStates["P"] = parseStatus(dataMap.getString(ProtocolContract.Keys.PRESS_STATUS))
                        sensorStates["R"] = parseStatus(dataMap.getString(ProtocolContract.Keys.ROT_STATUS))
                        
                        val locMode = dataMap.getString(ProtocolContract.Keys.LOC_STATUS)
                        gpsModeState.value = try { GpsMode.valueOf(locMode ?: GpsMode.WATCH_ONLY.name) } catch(e: Exception) { GpsMode.WATCH_ONLY }
                        
                        // Phase 7: Parse GPS status from DataMap (v28.6)
                        val gpsStatusStr = dataMap.getString(ProtocolContract.Keys.WATCH_GPS_STATUS)
                        if (gpsStatusStr != null) {
                            sensorStates["L"] = parseStatus(gpsStatusStr)
                        } else {
                            sensorStates["L"] = if (isConnectedState.value) SensorStatus.AVAILABLE else SensorStatus.UNKNOWN
                        }
                        
                        // v28.6.1: Capture direct status text if available
                        dataMap.getString(ProtocolContract.Keys.WATCH_GPS_TEXT)?.let { text ->
                             telemetryState.value = telemetryState.value.copy(gpsStatusText = text)
                        }

                        dataMap.getString(ProtocolContract.Keys.TELEMETRY_JSON)?.let { json -> 
                            try { 
                                val newTelemetry = explicitJson.decodeFromString<TelemetryState>(json)
                                val now = System.currentTimeMillis()
                                
                                telemetryState.value = newTelemetry.copy(
                                    lastUpdateTime = now
                                )
                                
                                // Phase 7: Sync indicator states (v28.6)
                                // We trust top-level IS_ACTIVE for the button to avoid IDLE-movement flicker
                                sensorStates["A"] = newTelemetry.accelStatus
                                sensorStates["G"] = newTelemetry.gyroStatus
                                sensorStates["P"] = newTelemetry.pressureStatus
                                sensorStates["R"] = newTelemetry.rotationStatus
                                
                                // Specific GPS logic: If monitoring is active, use detailed status from telemetry
                                val watchGpsStatus = newTelemetry.gpsStatus
                                sensorStates["L"] = if (!isActive) SensorStatus.DISABLED 
                                                    else watchGpsStatus

                                // Adaptive GPS Sync Strategy (#6, #7, #8)
                                val isDashboardActive = currentScreenState is Screen.Dashboard
                                val isWatchGpsOk = watchGpsStatus == SensorStatus.FIX_3D || watchGpsStatus == SensorStatus.LOW_ACC
                                
                                val nextInterval = when {
                                    isDashboardActive -> 1000L // 1s if Dashboard is open
                                    isWatchGpsOk -> 600000L    // 10m if Watch GPS is OK (#8)
                                    else -> 60000L            // 1m if Watch GPS is lost (#7)
                                }
                                startLocationUpdates(nextInterval)
                            } catch(e: Exception) { 
                                Log.e("SentinelSync", "Failed to decode telemetry", e)
                            }
                        }
                    }
                    ProtocolContract.Paths.SETTINGS_SYNC -> {
                        dataMap.getString(ProtocolContract.Keys.SETTINGS_JSON)?.let { json -> 
                            try { watchSettingsState.value = explicitJson.decodeFromString<WatchSettings>(json) } catch(e: Exception) { } 
                        }
                    }
                }
            }
        }
    }

    private fun parseStatus(name: String?): SensorStatus = try { SensorStatus.valueOf(name ?: SensorStatus.UNKNOWN.name) } catch(e: Exception) { SensorStatus.UNKNOWN }

    private fun fetchLatestTelemetry() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dataItems = Wearable.getDataClient(this@MainActivity)
                    .getDataItems(Uri.parse("wear://*/${ProtocolContract.Paths.STATUS_SYNC}"))
                    .await()
                
                dataItems.forEach { item ->
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val isActive = dataMap.getBoolean(ProtocolContract.Keys.IS_ACTIVE)
                    
                    launch(Dispatchers.Main) {
                        isWatchActiveState.value = isActive
                        sensorStates["A"] = parseStatus(dataMap.getString(ProtocolContract.Keys.ACCEL_STATUS))
                        sensorStates["G"] = parseStatus(dataMap.getString(ProtocolContract.Keys.GYRO_STATUS))
                        sensorStates["P"] = parseStatus(dataMap.getString(ProtocolContract.Keys.PRESS_STATUS))
                        sensorStates["R"] = parseStatus(dataMap.getString(ProtocolContract.Keys.ROT_STATUS))
                        
                        val gpsStatusStr = dataMap.getString(ProtocolContract.Keys.WATCH_GPS_STATUS)
                        if (gpsStatusStr != null) {
                            sensorStates["L"] = parseStatus(gpsStatusStr)
                        }
                    }

                    dataMap.getString(ProtocolContract.Keys.TELEMETRY_JSON)?.let { json ->
                        try {
                            val telemetry = explicitJson.decodeFromString<TelemetryState>(json)
                            val now = System.currentTimeMillis()
                            
                            launch(Dispatchers.Main) {
                                telemetryState.value = telemetry.copy(
                                    lastUpdateTime = now
                                )
                                sensorStates["A"] = telemetry.accelStatus
                                sensorStates["G"] = telemetry.gyroStatus
                                sensorStates["P"] = telemetry.pressureStatus
                                sensorStates["R"] = telemetry.rotationStatus
                                sensorStates["L"] = if (!isActive) SensorStatus.DISABLED 
                                                    else telemetry.gpsStatus
                            }
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.e("SentinelSync", "Initial telemetry fetch failed", e)
            }
        }
    }

    private fun requestWatchSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, ProtocolContract.Paths.REQUEST_SETTINGS, null).await()
            } catch (e: Exception) { }
        }
    }

    private fun sendSettings(settings: WatchSettings) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settingsJson = explicitJson.encodeToString(settings)
                val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.SETTINGS_SYNC).apply {
                    dataMap.putString(ProtocolContract.Keys.SETTINGS_JSON, settingsJson)
                    dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@MainActivity).putDataItem(putDataReq).await()
            } catch (e: Exception) { }
        }
    }

    private fun sendRemoteCommand(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) Wearable.getMessageClient(this@MainActivity).sendMessage(node.id, path, null).await()
            } catch (e: Exception) { }
        }
    }

    private suspend fun requestFullTelemetrySync() {
        try {
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            for (node in nodes) {
                // Request 1: Full Telemetry (for Dashboard data if active)
                Wearable.getMessageClient(this).sendMessage(
                    node.id,
                    ProtocolContract.Paths.FULL_SYNC_REQUEST,
                    "FULL_SYNC_NOW".toByteArray()
                ).await()
                
                // Request 2: Immediate Sensor Status (for Indicators parity)
                Wearable.getMessageClient(this).sendMessage(
                    node.id,
                    ProtocolContract.Paths.SENSOR_STATUS_REQUEST,
                    "CHECK_SENSORS".toByteArray()
                ).await()
            }
            delay(1500)
            fetchLatestTelemetry()
        } catch (e: Exception) {
            Log.e("SentinelSync", "Full sync request failed", e)
        }
    }

    private fun sendSyncPolicy(highSpeed: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val data = byteArrayOf(if (highSpeed) 1 else 0)
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity).sendMessage(
                        node.id, 
                        ProtocolContract.Paths.SYNC_POLICY_UPDATE, 
                        data
                    ).await()
                }
            } catch (e: Exception) { }
        }
    }

    private fun sendDashboardConfig(windowMs: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.DASHBOARD_CONFIG).apply {
                    dataMap.putLong(ProtocolContract.Keys.WINDOW_MS, windowMs)
                    dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                
                Wearable.getDataClient(this@MainActivity).putDataItem(putDataReq).await()
                Log.d("SentinelApp", "Dashboard window updated: ${windowMs/60000} min")
            } catch (e: Exception) { }
        }
    }
}

@Composable
fun MainAppContent(
    isWatchActive: Boolean,
    isConnected: Boolean,
    inferenceState: VehicleInferenceState,
    sensorStates: SnapshotStateMap<String, SensorStatus>,
    gpsMode: GpsMode,
    telemetry: TelemetryState,
    onCommand: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    Column(modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp), 
        horizontalAlignment = Alignment.CenterHorizontally, 
        verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WATCH² OUT", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            StatusBadge(isConnected)
            
            if (isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                FusionStatusCard(gpsMode, telemetry.pGpsSpeed)
            }
        }

        // System Confidence Section
        if (isConnected && isWatchActive) {
            ConfidenceIndicator(crashScore = telemetry.crashScore)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("A", "G", "P", "R", "L").forEach { key ->
                val status = sensorStates[key] ?: SensorStatus.UNKNOWN
                val fullName = when(key) { 
                    "A" -> "ACCELEROMETER"
                    "G" -> "GYROSCOPE"
                    "P" -> "PRESSURE"
                    "R" -> "ROTATION"
                    "L" -> "GPS (WATCH)"
                    else -> "UNKNOWN" 
                }
                val text = if (key == "L") telemetry.gpsStatusText else null
                FullSensorIndicator(fullName, status, text)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val startButtonColor = if (!isConnected) Color.DarkGray 
                                    else if (isWatchActive) Color(0xFFD32F2F) 
                                    else if (PhoneGpsManager.IS_RESERVED_MODE) Color(0xFF388E3C)
                                    else Color(0xFF388E3C)
            
            Button(onClick = { onCommand(if (isWatchActive) ProtocolContract.Paths.STOP_MONITORING else ProtocolContract.Paths.START_MONITORING) }, 
                   enabled = isConnected, 
                   modifier = Modifier.size(200.dp), 
                   shape = CircleShape, 
                   colors = ButtonDefaults.buttonColors(containerColor = startButtonColor), 
                   elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = if (isWatchActive) "STOP" else "START", fontSize = 34.sp, fontWeight = FontWeight.Black)
                    if (isConnected) Text(text = if (isWatchActive) inferenceState.name else "READY TO ARM", style = MaterialTheme.typography.labelLarge, color = Color.Yellow.copy(alpha = 0.9f))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LargeNavButton(label = "DASHBOARD", icon = Icons.Default.Analytics, modifier = Modifier.weight(1f), enabled = isConnected, onClick = onNavigateToDashboard)
            LargeNavButton(label = "HISTORY", icon = Icons.Default.History, modifier = Modifier.weight(1f), onClick = onNavigateToLogs)
            LargeNavButton(label = "SETTINGS", icon = Icons.Default.Settings, modifier = Modifier.weight(1f), enabled = isConnected, onClick = onNavigateToSettings)
        }
    }
}

@Composable
fun FusionStatusCard(mode: GpsMode, speed: Float) {
    val (color, text) = when (mode) {
        GpsMode.PHONE_PRIMARY -> Color(0xFF42A5F5) to "GPS FUSION: ACTIVE (PHONE)"
        GpsMode.WATCH_ONLY -> Color(0xFFFFA726) to "GPS FUSION: STANDALONE (WATCH)"
        GpsMode.WATCH_HYBRID -> Color(0xFF66BB6A) to "GPS FUSION: HYBRID (GPS+NET)"
        GpsMode.WATCH_NETWORK_ONLY -> Color(0xFFEF5350) to "GPS FUSION: NETWORK (FALLBACK)"
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            if (speed > 0) {
                Text(
                    text = "${speed.toInt()} km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun ConfidenceIndicator(crashScore: Float) {
    val confidence = ((1f - crashScore.coerceIn(0f, 1f)) * 100).toInt()
    val color = when {
        confidence > 80 -> Color(0xFF4CAF50)
        confidence > 50 -> Color(0xFFFBC02D)
        else -> Color(0xFFD32F2F)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text("SYSTEM CONFIDENCE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("$confidence%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Black, color = color)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { confidence / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
    }
}

@Composable
fun StatusBadge(isConnected: Boolean) {
    val color = if (isConnected) Color(0xFF4CAF50) else Color.Gray
    Surface(color = color.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.5f)), modifier = Modifier.padding(top = 8.dp)) {
        Text(text = if (isConnected) "SENTINEL ONLINE" else "SENTINEL OFFLINE", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FullSensorIndicator(name: String, status: SensorStatus, displayText: String? = null) {
    val color = when (status) { 
        SensorStatus.AVAILABLE, SensorStatus.FIX_3D -> Color(0xFF4CAF50)
        SensorStatus.FIX_2D, SensorStatus.LOW_ACC -> Color(0xFFFBC02D)
        SensorStatus.DISABLED -> Color.Gray
        SensorStatus.NO_FIX, SensorStatus.UNAVAILABLE, SensorStatus.MISSING -> Color(0xFFD32F2F)
        SensorStatus.UNKNOWN -> Color(0xFFB0BEC5) 
    }
    
    val text = when {
        status == SensorStatus.DISABLED -> "OFF"
        displayText != null -> displayText
        else -> status.name
    }
    
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
fun LargeNavButton(label: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant), contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
