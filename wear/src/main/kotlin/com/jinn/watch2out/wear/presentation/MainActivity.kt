// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.TelephonyManager
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
        val locationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
        Log.d("MainActivity", "Permissions - Location: $locationGranted, Audio: $audioGranted")
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
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO
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
            ProtocolContract.Paths.SIMULATE_HARD_BRAKE,
            ProtocolContract.Paths.SIMULATE_FRONTAL,
            ProtocolContract.Paths.SIMULATE_REAR,
            ProtocolContract.Paths.SIMULATE_SIDE,
            ProtocolContract.Paths.SIMULATE_ROLLOVER,
            ProtocolContract.Paths.SIMULATE_PLUNGE -> {
                sentinelServiceState.value?.simulateIncident(path)
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
    
    // v27.6 Optimization: Key performance values are isolated to prevent total recomposition
    val vehicleInferenceState by remember(telemetry) { derivedStateOf { telemetry.vehicleInferenceState } }
    val gpsMode by remember(telemetry) { derivedStateOf { service?.currentGpsMode ?: GpsMode.WATCH_ONLY } }

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
                        inferenceState = vehicleInferenceState.name,
                        onStartMonitoring = {
                            val intent = Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_START_MONITORING }
                            context.startForegroundService(intent)
                        },
                        onStopMonitoring = {
                            val intent = Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_STOP_MONITORING }
                            context.startService(intent)
                        },
                        gpsMode = gpsMode,
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
                        context.startService(Intent(context, SentinelService::class.java).apply { action = SentinelService.ACTION_DASHBOARD_START })
                        onDispose {
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
    gpsMode: GpsMode,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val context = LocalContext.current
    val sensorStatuses = remember(settings) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pm = context.packageManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val hasSms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            @Suppress("DEPRECATION")
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }
        val hasVoice = tm.phoneType != TelephonyManager.PHONE_TYPE_NONE

        mapOf(
            "A" to getSensorStatus(sm, Sensor.TYPE_ACCELEROMETER),
            "G" to getSensorStatus(sm, Sensor.TYPE_GYROSCOPE),
            "P" to getSensorStatus(sm, Sensor.TYPE_PRESSURE),
            "R" to getSensorStatus(sm, Sensor.TYPE_ROTATION_VECTOR),
            "L" to if (pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) SensorStatus.AVAILABLE else SensorStatus.MISSING,
            "M" to if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) SensorStatus.AVAILABLE else SensorStatus.MISSING,
            "T" to if (hasSms || hasVoice) SensorStatus.AVAILABLE else SensorStatus.MISSING
        )
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                sensorStatuses.forEach { (label, status) -> SensorIndicator(label, status) }
            }
            
            if (isMonitoring) {
                Spacer(modifier = Modifier.height(4.dp))
                FusionBadge(gpsMode)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = { if (isMonitoring) onStopMonitoring() else onStartMonitoring() }, modifier = Modifier.fillMaxWidth(0.85f).height(65.dp), colors = if (isMonitoring) ButtonDefaults.secondaryButtonColors() else ButtonDefaults.primaryButtonColors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = if (isMonitoring) "STOP" else "START", style = MaterialTheme.typography.title2)
                    if (isMonitoring) { Text(text = inferenceState, style = MaterialTheme.typography.caption3, color = Color.Yellow) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(0.9f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNavigateToDashboard, modifier = Modifier.weight(1f).height(45.dp)) { Text("DASH", style = MaterialTheme.typography.caption2) }
                Button(onClick = { onNavigateToSettings() }, modifier = Modifier.weight(1f).height(45.dp)) { Text("SET", style = MaterialTheme.typography.caption2) }
            }
        }
    }
}

@Composable
fun FusionBadge(mode: GpsMode) {
    val (color, text) = when (mode) {
        GpsMode.PHONE_PRIMARY -> Color(0xFF42A5F5) to "FUSION (PHONE)"
        GpsMode.WATCH_ONLY -> Color(0xFFFFA726) to "GPS ONLY"
        GpsMode.WATCH_HYBRID -> Color(0xFF66BB6A) to "HYBRID (GPS+NET)"
        GpsMode.WATCH_NETWORK_ONLY -> Color(0xFFEF5350) to "NET ONLY (FALLBACK)"
    }
    
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(text = text, fontSize = 8.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SensorIndicator(label: String, status: SensorStatus) {
    val bgColor = when (status) { 
        SensorStatus.AVAILABLE, SensorStatus.FIX_3D -> Color.Green
        SensorStatus.FIX_2D, SensorStatus.LOW_ACC -> Color.Yellow
        SensorStatus.DISABLED -> Color.DarkGray
        SensorStatus.NO_FIX, SensorStatus.UNAVAILABLE, SensorStatus.MISSING -> Color.Red
        SensorStatus.UNKNOWN -> Color.Gray 
    }
    Box(modifier = Modifier.size(14.dp).background(bgColor, CircleShape), contentAlignment = Alignment.Center) { Text(text = label, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
}

fun getSensorStatus(sm: SensorManager, type: Int): SensorStatus {
    val sensor = sm.getDefaultSensor(type)
    return if (sensor == null) SensorStatus.MISSING else SensorStatus.AVAILABLE
}
