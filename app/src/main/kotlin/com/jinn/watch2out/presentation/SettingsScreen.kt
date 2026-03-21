// [Module: :app]
package com.jinn.watch2out.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinn.watch2out.shared.model.SimulationDetectionMode
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.shared.network.ProtocolContract
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    currentSettings: WatchSettings,
    isConnected: Boolean,
    onApply: (WatchSettings) -> Unit,
    onCancel: () -> Unit,
    onSimulatePreset: (String) -> Unit,
    onInjectCustomData: (Float, Float, Float, Float, Float, Float) -> Unit
) {
    // 1. Sensor States
    var isAccelEnabled by remember { mutableStateOf<Boolean>(currentSettings.isAccelEnabled) }
    var accelThreshold by remember { mutableFloatStateOf(currentSettings.accelThresholdG) }
    var isGyroEnabled by remember { mutableStateOf<Boolean>(currentSettings.isGyroEnabled) }
    var gyroThreshold by remember { mutableFloatStateOf(currentSettings.gyroThresholdDeg) }
    var isPressureEnabled by remember { mutableStateOf<Boolean>(currentSettings.isPressureEnabled) }
    var pressureThreshold by remember { mutableFloatStateOf(currentSettings.pressureThresholdHpa) }
    
    // 2. Buffer, Interval & Simulation
    var bufferTime by remember { mutableIntStateOf(currentSettings.bufferSeconds) }
    var samplingRateMs by remember { mutableIntStateOf(currentSettings.samplingRateMs) }
    var simulationMode by remember { mutableStateOf<Boolean>(currentSettings.isSimulationMode) }
    var forcedMode by remember { mutableStateOf<SimulationDetectionMode>(currentSettings.forcedDetectionMode) }

    // 3. Custom Injection State
    var rawAccelX by remember { mutableStateOf("0.0") }
    var rawAccelY by remember { mutableStateOf("0.0") }
    var rawAccelZ by remember { mutableStateOf("9.8") }
    var rawGyroX by remember { mutableStateOf("0.0") }
    var rawSpeed by remember { mutableStateOf("20.0") }
    var rawPressure by remember { mutableStateOf("1013.25") }

    // 4. Other States
    var isSmsEnabled by remember { mutableStateOf<Boolean>(currentSettings.isSmsEnabled) }
    var smsRecipient by remember { mutableStateOf(currentSettings.smsRecipient) }
    var isCallEnabled by remember { mutableStateOf<Boolean>(currentSettings.isCallEnabled) }
    var callRecipient by remember { mutableStateOf(currentSettings.callRecipient) }
    var useWatchDirectDispatch by remember { mutableStateOf<Boolean>(currentSettings.useWatchDirectDispatch) }
    var isAutoStartEnabled by remember { mutableStateOf<Boolean>(currentSettings.isAutoStartEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sentinel Settings") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- 1. Hardware Sensors ---
            SettingsSection(title = "Hardware Sensors", enabled = isConnected) {
                ThresholdControl("Accelerometer", accelThreshold, "G", isAccelEnabled, { isAccelEnabled = it }, { accelThreshold = it }, 5f..30f, isConnected)
                ThresholdControl("Gyroscope", gyroThreshold, "°/s", isGyroEnabled, { isGyroEnabled = it }, { gyroThreshold = it }, 100f..1000f, isConnected)
                ThresholdControl("Barometer", pressureThreshold, "hPa", isPressureEnabled, { isPressureEnabled = it }, { pressureThreshold = it }, 0.5f..5.0f, isConnected)
            }

            // --- 2. System & Policy ---
            SettingsSection(title = "System & Policy", enabled = isConnected) {
                SwitchPreference(
                    "Auto Start on Boot", 
                    "Automatically start monitoring when the watch turns on", 
                    isAutoStartEnabled, 
                    { isAutoStartEnabled = it }, 
                    isConnected
                )
                SwitchPreference(
                    "Watch Direct Dispatch", 
                    "Watch will try to send SMS directly (requires LTE/Cellular)", 
                    useWatchDirectDispatch, 
                    { useWatchDirectDispatch = it }, 
                    isConnected
                )
            }

            // --- 3. Developer Simulation ---
            SettingsSection(title = "Developer Simulation", enabled = isConnected) {
                SwitchPreference("Enable Simulation Mode", "Allows manual data injection", simulationMode, { simulationMode = it }, isConnected)
                
                if (simulationMode) {
                    Text("Quick Presets", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFBC02D))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresetButton("Frontal") {
                            rawAccelX = "0.0"; rawAccelY = "-80.0"; rawAccelZ = "0.0"; rawGyroX = "0.0"; rawSpeed = "20.0"; rawPressure = "1000.0"
                        }
                        PresetButton("Rear") {
                            rawAccelX = "0.0"; rawAccelY = "30.0"; rawAccelZ = "0.0"; rawGyroX = "5.0"; rawSpeed = "0.5"; rawPressure = "1000.0"
                        }
                        PresetButton("Side") {
                            rawAccelX = "60.0"; rawAccelY = "0.0"; rawAccelZ = "0.0"; rawGyroX = "0.0"; rawSpeed = "15.0"; rawPressure = "1000.0"
                        }
                        PresetButton("Plunge") {
                            rawAccelX = "0.0"; rawAccelY = "0.0"; rawAccelZ = "1.0"; rawGyroX = "0.0"; rawSpeed = "20.0"; rawPressure = "1000.0"
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Manual Raw Injection", style = MaterialTheme.typography.labelMedium, color = Color.Cyan)
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RawInputField("Accel X", rawAccelX, { rawAccelX = it }, Modifier.weight(1f))
                        RawInputField("Accel Y", rawAccelY, { rawAccelY = it }, Modifier.weight(1f))
                        RawInputField("Accel Z", rawAccelZ, { rawAccelZ = it }, Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RawInputField("Pitch Rate", rawGyroX, { rawGyroX = it }, Modifier.weight(1f))
                        RawInputField("Speed m/s", rawSpeed, { rawSpeed = it }, Modifier.weight(1f))
                        RawInputField("Press hPa", rawPressure, { rawPressure = it }, Modifier.weight(1f))
                    }

                    Button(
                        onClick = {
                            val ax = rawAccelX.toFloatOrNull() ?: 0f
                            val ay = rawAccelY.toFloatOrNull() ?: 0f
                            val az = rawAccelZ.toFloatOrNull() ?: 9.8f
                            val gx = rawGyroX.toFloatOrNull() ?: 0f
                            val sp = rawSpeed.toFloatOrNull() ?: 0f
                            val pr = rawPressure.toFloatOrNull() ?: 1013.25f
                            onInjectCustomData(ax, ay, az, gx, sp, pr)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006064))
                    ) {
                        Text("INJECT CUSTOM SENSOR DATA")
                    }
                }
            }

            // --- 4. Emergency Contacts ---
            SettingsSection(title = "Emergency Contacts", enabled = isConnected) {
                ContactField("SMS Phone", isSmsEnabled, smsRecipient, { isSmsEnabled = it }, { smsRecipient = it }, KeyboardType.Phone, isConnected)
                ContactField("Emergency Call", isCallEnabled, callRecipient, { isCallEnabled = it }, { callRecipient = it }, KeyboardType.Phone, isConnected)
            }

            // --- Save Actions ---
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("CANCEL") }
                Button(
                    onClick = {
                        onApply(WatchSettings(
                            isAccelEnabled = isAccelEnabled, accelThresholdG = accelThreshold,
                            isGyroEnabled = isGyroEnabled, gyroThresholdDeg = gyroThreshold,
                            isPressureEnabled = isPressureEnabled, pressureThresholdHpa = pressureThreshold,
                            bufferSeconds = bufferTime, samplingRateMs = samplingRateMs, 
                            isSimulationMode = simulationMode, forcedDetectionMode = forcedMode,
                            isAutoStartEnabled = isAutoStartEnabled,
                            useWatchDirectDispatch = useWatchDirectDispatch,
                            isSmsEnabled = isSmsEnabled, smsRecipient = smsRecipient,
                            isCallEnabled = isCallEnabled, callRecipient = callRecipient
                        ))
                    },
                    modifier = Modifier.weight(1.5f), enabled = isConnected
                ) { Text("SAVE & APPLY") }
            }
        }
    }
}

@Composable
fun PresetButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.height(32.dp)) {
        Text(label, fontSize = 10.sp)
    }
}

@Composable
fun RawInputField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label, fontSize = 9.sp) },
        modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
    )
}

@Composable
fun SettingsSection(title: String, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        content()
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ThresholdControl(label: String, value: Float, unit: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Text("$label: ${String.format(Locale.getDefault(), "%.1f", value)}$unit", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled && checked, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
fun ContactField(label: String, checked: Boolean, value: String, onCheckedChange: (Boolean) -> Unit, onValueChange: (String) -> Unit, keyboardType: KeyboardType, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (checked) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Recipient") },
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                enabled = enabled,
                singleLine = true
            )
        }
    }
}

@Composable
fun SwitchPreference(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
