// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.DetectionMode
import com.jinn.watch2out.shared.model.IncidentState
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.service.SentinelService
import com.jinn.watch2out.shared.model.TelemetryState
import com.jinn.watch2out.shared.model.SensorStatus
import com.jinn.watch2out.shared.network.ProtocolContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Screen Navigation State
 */
sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
    object Dashboard : Screen()
}

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private lateinit var settingsRepository: SettingsRepository
    private val sentinelServiceState = mutableStateOf<SentinelService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SentinelService.SentinelBinder
            sentinelServiceState.value = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sentinelServiceState.value = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        
        Intent(this, SentinelService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        Wearable.getMessageClient(this).addListener(this)

        setContent {
            val service = sentinelServiceState.value
            Watch2OutApp(settingsRepository, service)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            ProtocolContract.Paths.REQUEST_SETTINGS -> {
                lifecycleScope.launch {
                    val currentSettings = settingsRepository.settingsFlow.first()
                    syncSettingsToMobile(currentSettings)
                }
            }
            ProtocolContract.Paths.START_MONITORING -> {
                startService(Intent(this, SentinelService::class.java).apply { action = SentinelService.ACTION_START_MONITORING })
            }
            ProtocolContract.Paths.STOP_MONITORING -> {
                startService(Intent(this, SentinelService::class.java).apply { action = SentinelService.ACTION_STOP_MONITORING })
            }
            ProtocolContract.Paths.RESET_PEAKS -> {
                startService(Intent(this, SentinelService::class.java).apply { action = SentinelService.ACTION_RESET_PEAKS })
            }
            ProtocolContract.Paths.SIMULATE_FRONTAL,
            ProtocolContract.Paths.SIMULATE_REAR,
            ProtocolContract.Paths.SIMULATE_SIDE,
            ProtocolContract.Paths.SIMULATE_ROLLOVER,
            ProtocolContract.Paths.SIMULATE_PLUNGE -> {
                sentinelServiceState.value?.simulateIncident(messageEvent.path)
            }
            ProtocolContract.Paths.INJECT_CUSTOM_SENSOR -> {
                val csv = String(messageEvent.data)
                sentinelServiceState.value?.injectCustomSensorData(csv)
            }
            ProtocolContract.Paths.INCIDENT_ALERT_DISMISS -> {
                startService(Intent(this, SentinelService::class.java).apply { 
                    action = SentinelService.ACTION_DISMISS_INCIDENT 
                })
            }
        }
    }

    private suspend fun syncSettingsToMobile(settings: WatchSettings) {
        try {
            val settingsJson = Json.encodeToString(settings)
            val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.SETTINGS_SYNC).apply {
                dataMap.putString(ProtocolContract.Keys.SETTINGS_JSON, settingsJson)
                dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                dataMap.putBoolean("is_active", sentinelServiceState.value != null)
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(this).putDataItem(putDataReq).await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun Watch2OutApp(repository: SettingsRepository, service: SentinelService?) {
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
    val context = LocalContext.current
    
    val settings by repository.settingsFlow.collectAsState(initial = WatchSettings())
    val telemetryState = service?.telemetry?.collectAsState(initial = TelemetryState())
    val telemetry = telemetryState?.value ?: TelemetryState()
    val serviceStateFlow = service?.currentState?.collectAsState(initial = IncidentState.IDLE)
    val serviceState = serviceStateFlow?.value ?: IncidentState.IDLE

    val listState = rememberScalingLazyListState()

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { 
                if (currentScreen is Screen.Main || currentScreen is Screen.Settings) {
                    PositionIndicator(scalingLazyListState = listState) 
                }
            }
        ) {
            when (currentScreen) {
                is Screen.Main -> {
                    MainScreen(
                        settings = settings,
                        isMonitoring = serviceState == IncidentState.MONITORING,
                        currentMode = telemetry.currentMode,
                        onStartMonitoring = {
                            val intent = Intent(context, SentinelService::class.java).apply {
                                action = SentinelService.ACTION_START_MONITORING
                            }
                            context.startForegroundService(intent)
                        },
                        onStopMonitoring = {
                            val intent = Intent(context, SentinelService::class.java).apply {
                                action = SentinelService.ACTION_STOP_MONITORING
                            }
                            context.startService(intent)
                        },
                        onNavigateToSettings = { currentScreen = Screen.Settings },
                        onNavigateToDashboard = { currentScreen = Screen.Dashboard }
                    )
                }
                is Screen.Settings -> {
                    SettingsScreen(
                        currentSettings = settings,
                        onApply = { newSettings ->
                            coroutineScope.launch {
                                repository.updateSettings(newSettings)
                            }
                            currentScreen = Screen.Main
                        },
                        onCancel = { currentScreen = Screen.Main },
                        onInjectCustomData = { csv ->
                            service?.injectCustomSensorData(csv)
                        }
                    )
                }
                is Screen.Dashboard -> {
                    DashboardScreen(
                        currentImpact = telemetry.currentImpact,
                        maxImpact = telemetry.maxImpact,
                        peakFallScore = telemetry.peakFallScore,
                        peakCrashScore = telemetry.peakCrashScore,
                        windowImpact = telemetry.windowImpact,
                        windowFallScore = telemetry.windowFallScore,
                        windowCrashScore = telemetry.windowCrashScore,
                        currentMode = telemetry.currentMode,
                        accelX = telemetry.accelX,
                        accelY = telemetry.accelY,
                        accelZ = telemetry.accelZ,
                        gyroX = telemetry.gyroX,
                        gyroY = telemetry.gyroY,
                        gyroZ = telemetry.gyroZ,
                        rotationX = telemetry.rotationX,
                        rotationY = telemetry.rotationY,
                        rotationZ = telemetry.rotationZ,
                        airPressure = telemetry.airPressure,
                        rotationSpeed = telemetry.rotationSpeed,
                        pressureDelta = telemetry.pressureDelta,
                        tiltAngle = telemetry.tiltAngle,
                        onResetPeak = {
                            val intent = Intent(context, SentinelService::class.java).apply {
                                action = SentinelService.ACTION_RESET_PEAKS
                            }
                            context.startService(intent)
                        },
                        onClose = { currentScreen = Screen.Main }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    settings: WatchSettings,
    isMonitoring: Boolean,
    currentMode: DetectionMode,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val context = LocalContext.current
    val sensorStatuses = remember(settings) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mapOf(
            "A" to getSensorStatus(sm, Sensor.TYPE_ACCELEROMETER, settings.isAccelEnabled),
            "G" to getSensorStatus(sm, Sensor.TYPE_GYROSCOPE, settings.isGyroEnabled),
            "P" to getSensorStatus(sm, Sensor.TYPE_PRESSURE, settings.isPressureEnabled),
            "R" to getSensorStatus(sm, Sensor.TYPE_ROTATION_VECTOR, true)
        )
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                sensorStatuses.forEach { (label, status) -> SensorIndicator(label, status) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { if (isMonitoring) onStopMonitoring() else onStartMonitoring() }, modifier = Modifier.fillMaxWidth(0.85f).height(75.dp), colors = if (isMonitoring) ButtonDefaults.secondaryButtonColors() else ButtonDefaults.primaryButtonColors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = if (isMonitoring) "STOP" else "START", style = MaterialTheme.typography.title1)
                    if (isMonitoring) { Text(text = "VEHICLE MODE", style = MaterialTheme.typography.caption3, color = Color.Yellow) }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(0.9f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNavigateToDashboard, modifier = Modifier.weight(1f).height(50.dp)) { Text("DASH", style = MaterialTheme.typography.caption2) }
                Button(onClick = { onNavigateToSettings() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("SET", style = MaterialTheme.typography.caption2) }
            }
        }
    }
}

@Composable
fun SensorIndicator(label: String, status: SensorStatus) {
    val bgColor = when (status) { SensorStatus.AVAILABLE -> Color.Green; SensorStatus.DISABLED -> Color.DarkGray; SensorStatus.UNAVAILABLE -> Color.Red; SensorStatus.MISSING -> Color.Gray; SensorStatus.UNKNOWN -> Color.Gray }
    Box(modifier = Modifier.size(16.dp).background(bgColor, CircleShape), contentAlignment = Alignment.Center) { Text(text = label, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

fun getSensorStatus(sm: SensorManager, type: Int, isEnabledInSettings: Boolean): SensorStatus {
    val sensor = sm.getDefaultSensor(type)
    return when { sensor == null -> SensorStatus.MISSING; !isEnabledInSettings -> SensorStatus.DISABLED; else -> SensorStatus.AVAILABLE }
}
