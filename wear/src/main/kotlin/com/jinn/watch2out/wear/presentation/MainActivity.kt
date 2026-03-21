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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.jinn.watch2out.shared.model.*
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.service.SentinelService
import com.jinn.watch2out.shared.network.ProtocolContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class WearNavScreen {
    object Main : WearNavScreen()
    object Settings : WearNavScreen()
    object Dashboard : WearNavScreen()
}

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private lateinit var settingsRepository: SettingsRepository
    private val sentinelServiceState = mutableStateOf<SentinelService?>(null)
    private var isBound = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        Log.d("MainActivity", "Location Permission Granted: $granted")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SentinelService.SentinelBinder
            sentinelServiceState.value = binder.getService()
            isBound = true
            Log.d("MainActivity", "SentinelService Bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sentinelServiceState.value = null
            isBound = false
            Log.d("MainActivity", "SentinelService Unbound")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        
        requestPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ))

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
        val path = messageEvent.path
        Log.d("MainActivity", "Message received: $path")
        when (path) {
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
            ProtocolContract.Paths.DASHBOARD_START -> {
                startService(Intent(this, SentinelService::class.java).apply { action = SentinelService.ACTION_DASHBOARD_START })
            }
            ProtocolContract.Paths.DASHBOARD_STOP -> {
                startService(Intent(this, SentinelService::class.java).apply { action = SentinelService.ACTION_DASHBOARD_STOP })
            }
            ProtocolContract.Paths.SIMULATE_FRONTAL,
            ProtocolContract.Paths.SIMULATE_REAR,
            ProtocolContract.Paths.SIMULATE_SIDE,
            ProtocolContract.Paths.SIMULATE_ROLLOVER,
            ProtocolContract.Paths.SIMULATE_PLUNGE -> {
                sentinelServiceState.value?.simulateIncident(path)
            }
            ProtocolContract.Paths.INJECT_CUSTOM_SENSOR -> {
                val csv = String(messageEvent.data)
                sentinelServiceState.value?.injectCustomSensorData(csv)
            }
            ProtocolContract.Paths.INCIDENT_ALERT_DISMISS -> {
                startService(Intent(this, SentinelService::class.java).apply { action = SentinelService.ACTION_DISMISS_INCIDENT })
            }
        }
    }

    private suspend fun syncSettingsToMobile(settings: WatchSettings) {
        try {
            val settingsJson = Json.encodeToString(settings)
            val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.SETTINGS_SYNC).apply {
                dataMap.putString(ProtocolContract.Keys.SETTINGS_JSON, settingsJson)
                dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(this).putDataItem(putDataReq).await()
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
        if (isBound) { unbindService(connection); isBound = false }
    }
}

@Composable
fun Watch2OutApp(repository: SettingsRepository, service: SentinelService?) {
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<WearNavScreen>(WearNavScreen.Main) }
    val context = LocalContext.current
    
    val settings by repository.settingsFlow.collectAsState(initial = WatchSettings())
    val telemetryState = service?.telemetry?.collectAsState()
    val telemetry = telemetryState?.value ?: TelemetryState()
    val serviceStateFlow = service?.currentState?.collectAsState()
    val serviceState = serviceStateFlow?.value ?: IncidentState.IDLE

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
        ) {
            when (currentScreen) {
                is WearNavScreen.Main -> {
                    MainScreen(
                        settings = settings,
                        isMonitoring = serviceState == IncidentState.MONITORING,
                        inferenceState = telemetry.vehicleInferenceState.name,
                        onStartMonitoring = {
                            val intent = Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_START_MONITORING }
                            context.startForegroundService(intent)
                        },
                        onStopMonitoring = {
                            val intent = Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_STOP_MONITORING }
                            context.startService(intent)
                        },
                        onNavigateToSettings = { currentScreen = WearNavScreen.Settings },
                        onNavigateToDashboard = { currentScreen = WearNavScreen.Dashboard }
                    )
                }
                is WearNavScreen.Settings -> {
                    SettingsScreen(
                        currentSettings = settings,
                        onApply = { newSettings ->
                            coroutineScope.launch { repository.updateSettings(newSettings) }
                            currentScreen = WearNavScreen.Main
                        },
                        onCancel = { currentScreen = WearNavScreen.Main },
                        onInjectCustomData = { csv -> service?.injectCustomSensorData(csv) }
                    )
                }
                is WearNavScreen.Dashboard -> {
                    DisposableEffect(Unit) {
                        Log.d("WatchApp", "Entering Dashboard")
                        context.startService(Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_DASHBOARD_START })
                        onDispose {
                            Log.d("WatchApp", "Exiting Dashboard")
                            context.startService(Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_DASHBOARD_STOP })
                        }
                    }

                    DashboardScreen(
                        telemetry = telemetry,
                        onClose = { currentScreen = WearNavScreen.Main }
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
    inferenceState: String,
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
                    if (isMonitoring) { Text(text = inferenceState, style = MaterialTheme.typography.caption3, color = Color.Yellow) }
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
