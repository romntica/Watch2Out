// [Module: :app]
package com.jinn.watch2out.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.*
import com.jinn.watch2out.service.MobileAlertDispatcher
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.shared.network.ProtocolContract
import kotlinx.coroutines.Dispatchers
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
    
    private val sensorStates = mutableStateMapOf<String, SensorStatus>(
        "A" to SensorStatus.UNKNOWN,
        "G" to SensorStatus.UNKNOWN,
        "P" to SensorStatus.UNKNOWN,
        "R" to SensorStatus.UNKNOWN
    )
    private val watchSettingsState = mutableStateOf(WatchSettings())
    private val telemetryState = mutableStateOf<TelemetryState>(TelemetryState())
    
    private lateinit var alertDispatcher: MobileAlertDispatcher

    private val explicitJson = Json {
        allowSpecialFloatingPointValues = true
        ignoreUnknownKeys = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertDispatcher = MobileAlertDispatcher(this)
        
        Wearable.getDataClient(this).addListener(this)
        Wearable.getCapabilityClient(this).addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE)
        Wearable.getMessageClient(this).addListener(this)
        
        checkConnectionStatus()
        handleIntent(intent)

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentScreen) {
                        is Screen.Main -> MainAppContent(
                            isWatchActive = isWatchActiveState.value,
                            isConnected = isConnectedState.value,
                            inferenceState = telemetryState.value.vehicleInferenceState,
                            sensorStates = sensorStates,
                            onCommand = { cmd -> sendRemoteCommand(cmd) },
                            onNavigateToSettings = { requestWatchSettings(); currentScreen = Screen.Settings },
                            onNavigateToDashboard = { currentScreen = Screen.Dashboard },
                            onNavigateToLogs = { currentScreen = Screen.TelemetryLogs }
                        )
                        is Screen.Settings -> key(watchSettingsState.value) {
                            SettingsScreen(
                                currentSettings = watchSettingsState.value,
                                isConnected = isConnectedState.value,
                                onApply = { settings -> sendSettings(settings); currentScreen = Screen.Main },
                                onCancel = { currentScreen = Screen.Main },
                                onSimulatePreset = { path -> sendRemoteCommand(path) },
                                onInjectCustomData = { ax, ay, az, gx, sp, pr -> injectCustomSensor(ax, ay, az, gx, sp, pr) }
                            )
                        }
                        is Screen.Dashboard -> {
                            DisposableEffect(Unit) {
                                sendRemoteCommand(ProtocolContract.Paths.DASHBOARD_START)
                                onDispose { sendRemoteCommand(ProtocolContract.Paths.DASHBOARD_STOP) }
                            }
                            DashboardScreen(
                                telemetry = telemetryState.value,
                                onResetPeak = { sendRemoteCommand(ProtocolContract.Paths.RESET_PEAKS) },
                                onClose = { currentScreen = Screen.Main }
                            )
                        }
                        is Screen.TelemetryLogs -> {
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
                    putExtra("has_location", incident.latitude != null)
                    putExtra("maxG", incident.maxG)
                    putExtra("speed", incident.speed)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) { }
        } else if (messageEvent.path == ProtocolContract.Paths.INCIDENT_ALERT_DISMISS) {
            sendBroadcast(Intent("com.jinn.watch2out.DISMISS_ALERT"))
        }
    }

    fun triggerFinalDispatch(reason: String, timestamp: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        alertDispatcher.dispatch(watchSettingsState.value, reason, timestamp, lat, lon, maxG, speed)
    }

    private fun injectCustomSensor(ax: Float, ay: Float, az: Float, gx: Float, sp: Float, pr: Float) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csvData = "$ax,$ay,$az,$gx,$sp,$pr"
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
                        isWatchActiveState.value = dataMap.getBoolean(ProtocolContract.Keys.IS_ACTIVE)
                        sensorStates["A"] = parseStatus(dataMap.getString(ProtocolContract.Keys.ACCEL_STATUS))
                        sensorStates["G"] = parseStatus(dataMap.getString(ProtocolContract.Keys.GYRO_STATUS))
                        sensorStates["P"] = parseStatus(dataMap.getString(ProtocolContract.Keys.PRESS_STATUS))
                        sensorStates["R"] = parseStatus(dataMap.getString(ProtocolContract.Keys.ROT_STATUS))

                        dataMap.getString(ProtocolContract.Keys.TELEMETRY_JSON)?.let { json -> 
                            try { telemetryState.value = explicitJson.decodeFromString<TelemetryState>(json) } catch(e: Exception) { } 
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
}

@Composable
fun MainAppContent(isWatchActive: Boolean, isConnected: Boolean, inferenceState: VehicleInferenceState, sensorStates: SnapshotStateMap<String, SensorStatus>, onCommand: (String) -> Unit, onNavigateToSettings: () -> Unit, onNavigateToDashboard: () -> Unit, onNavigateToLogs: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WATCH² OUT", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            StatusBadge(isConnected)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("A", "G", "P", "R").forEach { key ->
                val status = sensorStates[key] ?: SensorStatus.UNKNOWN
                val fullName = when(key) { "A" -> "ACCELEROMETER"; "G" -> "GYROSCOPE"; "P" -> "PRESSURE"; "R" -> "ROTATION"; else -> "UNKNOWN" }
                FullSensorIndicator(fullName, status)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { onCommand(if (isWatchActive) ProtocolContract.Paths.STOP_MONITORING else ProtocolContract.Paths.START_MONITORING) }, enabled = isConnected, modifier = Modifier.size(200.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = if (!isConnected) Color.DarkGray else if (isWatchActive) Color(0xFFD32F2F) else Color(0xFF388E3C)), elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)) {
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
fun StatusBadge(isConnected: Boolean) {
    val color = if (isConnected) Color(0xFF4CAF50) else Color.Gray
    Surface(color = color.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.5f)), modifier = Modifier.padding(top = 8.dp)) {
        Text(text = if (isConnected) "SENTINEL ONLINE" else "SENTINEL OFFLINE", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FullSensorIndicator(name: String, status: SensorStatus) {
    val color = when (status) { SensorStatus.AVAILABLE -> Color(0xFF4CAF50); SensorStatus.DISABLED -> Color(0xFFFBC02D); SensorStatus.UNAVAILABLE, SensorStatus.MISSING -> Color(0xFFD32F2F); SensorStatus.UNKNOWN -> Color(0xFFB0BEC5) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
        Text(text = status.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = color)
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
