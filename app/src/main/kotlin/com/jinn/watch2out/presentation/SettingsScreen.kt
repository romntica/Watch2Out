// [Module: :app]
package com.jinn.watch2out.presentation

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinn.watch2out.shared.model.SimulationDetectionMode
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.shared.network.ProtocolContract
import java.util.Locale
import kotlin.math.abs

/**
 * Advanced configuration for the Sentinel system.
 * v27.4: Added Telemetry Logging toggle.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    currentSettings: WatchSettings,
    isConnected: Boolean,
    onApply: (WatchSettings) -> Unit,
    onCancel: () -> Unit,
    onInjectCustomData: (Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    
    // 1. Core Sensor Thresholds
    var accelThreshold: Float by remember { mutableFloatStateOf(currentSettings.accelThresholdG) }
    var gyroThreshold: Float by remember { mutableFloatStateOf(currentSettings.gyroThresholdDeg) }
    var pressureThreshold: Float by remember { mutableFloatStateOf(currentSettings.pressureThresholdHpa) }
    var crashScoreThreshold: Float by remember { mutableFloatStateOf(currentSettings.crashScoreThreshold) }

    // 2. CrashScore v27 Tuning
    var accelMinG: Float by remember { mutableFloatStateOf(currentSettings.accelMinG) }
    var accelMaxG: Float by remember { mutableFloatStateOf(currentSettings.accelMaxG) }
    var speedMinKmh: Float by remember { mutableFloatStateOf(currentSettings.speedMinKmh) }
    var speedDeltaMaxKmh: Float by remember { mutableFloatStateOf(currentSettings.speedDeltaMaxKmh) }
    var gyroMaxDegPerSec: Float by remember { mutableFloatStateOf(currentSettings.gyroMaxDegPerSec) }
    var pressureMaxHpa: Float by remember { mutableFloatStateOf(currentSettings.pressureMaxHpa) }
    var stillMaxSec: Float by remember { mutableFloatStateOf(currentSettings.stillMaxSec) }

    // 3. Weights
    var wAccel: Float by remember { mutableFloatStateOf(currentSettings.wAccel) }
    var wSpeed: Float by remember { mutableFloatStateOf(currentSettings.wSpeed) }
    var wGyro: Float by remember { mutableFloatStateOf(currentSettings.wGyro) }
    var wPress: Float by remember { mutableFloatStateOf(currentSettings.wPress) }
    var wStill: Float by remember { mutableFloatStateOf(currentSettings.wStill) }
    var wRoll: Float by remember { mutableFloatStateOf(currentSettings.wRoll) }

    // 4. System & Simulation
    var bufferTime: Int by remember { mutableIntStateOf(currentSettings.bufferSeconds) }
    var samplingRateMs: Int by remember { mutableIntStateOf(currentSettings.samplingRateMs) }
    var simulationMode: Boolean by remember { mutableStateOf(currentSettings.isSimulationMode) }
    var forcedMode: SimulationDetectionMode by remember { mutableStateOf(currentSettings.forcedDetectionMode) }
    var isTelemetryLoggingEnabled: Boolean by remember { mutableStateOf(currentSettings.isTelemetryLoggingEnabled) }

    // 5. Emergency
    var isSmsEnabled: Boolean by remember { mutableStateOf(currentSettings.isSmsEnabled) }
    var smsRecipient: String by remember { mutableStateOf(currentSettings.smsRecipient) }
    var isCallEnabled: Boolean by remember { mutableStateOf(currentSettings.isCallEnabled) }
    var callRecipient: String by remember { mutableStateOf(currentSettings.callRecipient) }
    var useWatchDirectDispatch: Boolean by remember { mutableStateOf(currentSettings.useWatchDirectDispatch) }
    var isAutoStartEnabled: Boolean by remember { mutableStateOf(currentSettings.isAutoStartEnabled) }
    var usePhoneGps: Boolean by remember { mutableStateOf(currentSettings.usePhoneGps) }

    // Custom Injection State
    var rawAccelX: String by remember { mutableStateOf("0.0") }
    var rawAccelY: String by remember { mutableStateOf("0.0") }
    var rawAccelZ: String by remember { mutableStateOf("9.8") }
    var rawGyroX: String by remember { mutableStateOf("0.0") }
    var rawGyroY: String by remember { mutableStateOf("0.0") }
    var rawGyroZ: String by remember { mutableStateOf("0.0") }
    var rawSpeed: String by remember { mutableStateOf("20.0") }
    var rawPressure: String by remember { mutableStateOf("1013.25") }

    fun applySensitivityPreset(preset: String) {
        when (preset) {
            "Conservative" -> {
                accelThreshold = 15f; gyroThreshold = 300f; pressureThreshold = 3.5f; crashScoreThreshold = 0.85f
            }
            "Standard" -> {
                accelThreshold = 10f; gyroThreshold = 200f; pressureThreshold = 2.5f; crashScoreThreshold = 0.70f
            }
            "Aggressive" -> {
                accelThreshold = 7f; gyroThreshold = 150f; pressureThreshold = 1.5f; crashScoreThreshold = 0.55f
            }
        }
    }

    fun applyWeightPreset(preset: String) {
        when (preset) {
            "Balanced" -> {
                wAccel = 0.30f; wSpeed = 0.25f; wGyro = 0.15f; wPress = 0.10f; wStill = 0.15f; wRoll = 0.05f
            }
            "Highway" -> {
                wAccel = 0.40f; wSpeed = 0.35f; wGyro = 0.05f; wPress = 0.05f; wStill = 0.10f; wRoll = 0.05f
            }
            "Technical" -> {
                wAccel = 0.20f; wSpeed = 0.10f; wGyro = 0.30f; wPress = 0.10f; wStill = 0.10f; wRoll = 0.20f
            }
        }
    }

    fun applyScenarioToFields(path: String) {
        when (path) {
            ProtocolContract.Paths.SIMULATE_HARD_BRAKE -> {
                rawAccelX = "0.0"; rawAccelY = "-12.0"; rawAccelZ = "9.8"
                rawGyroX = "0.0"; rawGyroY = "0.0"; rawGyroZ = "0.0"
                rawSpeed = "15.0"; rawPressure = "1013.25"
            }
            ProtocolContract.Paths.SIMULATE_FRONTAL -> {
                rawAccelX = "0.0"; rawAccelY = "-147.0"; rawAccelZ = "19.6"
                rawGyroX = "0.0"; rawGyroY = "0.0"; rawGyroZ = "0.0"
                rawSpeed = "1.5"; rawPressure = "1013.25"
            }
            ProtocolContract.Paths.SIMULATE_REAR -> {
                rawAccelX = "0.0"; rawAccelY = "58.8"; rawAccelZ = "9.8"
                rawGyroX = "0.0"; rawGyroY = "0.0"; rawGyroZ = "0.0"
                rawSpeed = "11.1"; rawPressure = "1013.25"
            }
            ProtocolContract.Paths.SIMULATE_SIDE -> {
                rawAccelX = "14.7"; rawAccelY = "0.0"; rawAccelZ = "9.8"
                rawGyroX = "0.0"; rawGyroY = "34.0"; rawGyroZ = "0.0"
                rawSpeed = "6.9"; rawPressure = "1013.25"
            }
            ProtocolContract.Paths.SIMULATE_ROLLOVER -> {
                rawAccelX = "39.2"; rawAccelY = "29.4"; rawAccelZ = "-19.6"
                rawGyroX = "286.0"; rawGyroY = "150.0"; rawGyroZ = "50.0"
                rawSpeed = "4.2"; rawPressure = "1013.25"
            }
            ProtocolContract.Paths.SIMULATE_PLUNGE -> {
                rawAccelX = "0.0"; rawAccelY = "0.0"; rawAccelZ = "0.0"
                rawGyroX = "0.0"; rawGyroY = "0.0"; rawGyroZ = "0.0"
                rawSpeed = "12.5"; rawPressure = "1010.0"
            }
            ProtocolContract.Paths.SIMULATE_RANDOM -> {
                rawAccelX = "12.5"; rawAccelY = "-8.4"; rawAccelZ = "14.2"
                rawGyroX = "15.0"; rawGyroY = "5.0"; rawGyroZ = "-10.0"
                rawSpeed = "5.0"; rawPressure = "1013.25"
            }
            "CLEAR" -> {
                rawAccelX = "0.0"
                rawAccelY = "0.0"
                rawAccelZ = "9.8"
                rawGyroX = "0.0"
                rawGyroY = "0.0"
                rawGyroZ = "0.0"
                rawSpeed = "0.0"
                rawPressure = "1013.25"
            }
        }
    }

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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- 0. Sensitivity Presets ---
            SettingsSection(title = "Sensitivity Presets", enabled = isConnected) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetButton("Conservative", Modifier.weight(1f)) { applySensitivityPreset("Conservative") }
                    PresetButton("Standard", Modifier.weight(1f)) { applySensitivityPreset("Standard") }
                    PresetButton("Aggressive", Modifier.weight(1f)) { applySensitivityPreset("Aggressive") }
                }
                Text("Changes basic trigger thresholds.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }

            // --- 1. Core Detection ---
            SettingsSection(title = "Core Detection", enabled = isConnected) {
                ThresholdControl("Incident Threshold", crashScoreThreshold, "", { crashScoreThreshold = it }, 0.5f..0.95f, isConnected)
                ThresholdControl("Accel Threshold (G)", accelThreshold, "G", { accelThreshold = it }, 5f..30f, isConnected)
                ThresholdControl("Gyro Threshold", gyroThreshold, "°/s", { gyroThreshold = it }, 100f..1000f, isConnected)
                ThresholdControl("Baro Threshold", pressureThreshold, "hPa", { pressureThreshold = it }, 0.5f..5.0f, isConnected)
            }

            // --- 2. Feature Weights ---
            SettingsSection(title = "Feature Weights (Total ≈ 1.0)", enabled = isConnected) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetButton("Balanced", Modifier.weight(1f)) { applyWeightPreset("Balanced") }
                    PresetButton("Highway", Modifier.weight(1f)) { applyWeightPreset("Highway") }
                    PresetButton("Technical", Modifier.weight(1f)) { applyWeightPreset("Technical") }
                }
                
                ThresholdControl("Weight: Accel", wAccel, "", { wAccel = it }, 0f..1f, isConnected)
                ThresholdControl("Weight: Speed", wSpeed, "", { wSpeed = it }, 0f..1f, isConnected)
                ThresholdControl("Weight: Gyro", wGyro, "", { wGyro = it }, 0f..1f, isConnected)
                ThresholdControl("Weight: Press", wPress, "", { wPress = it }, 0f..1f, isConnected)
                ThresholdControl("Weight: Still", wStill, "", { wStill = it }, 0f..1f, isConnected)
                ThresholdControl("Weight: Roll", wRoll, "", { wRoll = it }, 0f..1f, isConnected)
                
                val currentSum: Float = wAccel + wSpeed + wGyro + wPress + wStill + wRoll
                Text(
                    text = String.format(Locale.US, "Current Sum: %.2f", currentSum),
                    color = if (abs(currentSum - 1.0f) < 0.05f) Color.Green else Color.Red,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // --- 3. CrashScore v27 Tuning ---
            SettingsSection(title = "Advanced Tuning", enabled = isConnected) {
                Text("Range Normalization", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                ThresholdControl("Accel Min G", accelMinG, "G", { accelMinG = it }, 1f..10f, isConnected)
                ThresholdControl("Accel Max G", accelMaxG, "G", { accelMaxG = it }, 10f..30f, isConnected)
                ThresholdControl("Speed Min Kmh", speedMinKmh, "km/h", { speedMinKmh = it }, 0f..40f, isConnected)
                ThresholdControl("Speed Delta Max", speedDeltaMaxKmh, "km/h", { speedDeltaMaxKmh = it }, 10f..100f, isConnected)
                ThresholdControl("Gyro Max", gyroMaxDegPerSec, "°/s", { gyroMaxDegPerSec = it }, 200f..1000f, isConnected)
                ThresholdControl("Still Max", stillMaxSec, "s", { stillMaxSec = it }, 5f..30f, isConnected)
            }

            // --- 4. System & Policy ---
            SettingsSection(title = "System & Policy", enabled = isConnected) {
                SwitchPreference("Auto Start on Boot", "Automatically start monitoring on boot", isAutoStartEnabled, { isAutoStartEnabled = it }, isConnected)
                SwitchPreference("Watch Direct Dispatch", "Watch sends SMS directly", useWatchDirectDispatch, { useWatchDirectDispatch = it }, isConnected)
                SwitchPreference("Use Phone GPS (Hybrid)", "Integrate Phone GPS for better accuracy", usePhoneGps, { usePhoneGps = it }, isConnected)
                SwitchPreference("Telemetry Logging", "Periodically log sensing values for review", isTelemetryLoggingEnabled, { isTelemetryLoggingEnabled = it }, isConnected)
            }

            // --- 5. Developer Simulation ---
            SettingsSection(title = "Developer Simulation", enabled = isConnected) {
                SwitchPreference("Enable Simulation Mode", "Allows manual data injection", simulationMode, { simulationMode = it }, isConnected)
                
                if (simulationMode) {
                    Text("Scenario-Based Replay (v28.6)", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFBC02D))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScenarioButton("Hard Brake", Color(0xFF64B5F6)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_HARD_BRAKE) }
                        ScenarioButton("Frontal", Color(0xFFEF5350)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_FRONTAL) }
                        ScenarioButton("Rear Hit", Color(0xFFFFB74D)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_REAR) }
                        ScenarioButton("Side Slam", Color(0xFFBA68C8)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_SIDE) }
                        ScenarioButton("Rollover", Color(0xFFE57373)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_ROLLOVER) }
                        ScenarioButton("Cliff Drop", Color(0xFF9575CD)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_PLUNGE) }
                        ScenarioButton("Random", Color(0xFF4DB6AC)) { applyScenarioToFields(ProtocolContract.Paths.SIMULATE_RANDOM) }
                        ScenarioButton("CLEAR", Color.Gray) { applyScenarioToFields("CLEAR") }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Manual Raw Injection", style = MaterialTheme.typography.labelMedium, color = Color.Cyan)
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RawInputField("Accel X", rawAccelX, { rawAccelX = it }, Modifier.weight(1f))
                        RawInputField("Accel Y", rawAccelY, { rawAccelY = it }, Modifier.weight(1f))
                        RawInputField("Accel Z", rawAccelZ, { rawAccelZ = it }, Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RawInputField("Gyro X", rawGyroX, { rawGyroX = it }, Modifier.weight(1f))
                        RawInputField("Gyro Y", rawGyroY, { rawGyroY = it }, Modifier.weight(1f))
                        RawInputField("Gyro Z", rawGyroZ, { rawGyroZ = it }, Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RawInputField("Speed Km/h", rawSpeed, { rawSpeed = it }, Modifier.weight(1f))
                        RawInputField("Press hPa", rawPressure, { rawPressure = it }, Modifier.weight(2f))
                    }

                    Button(
                        onClick = {
                            val ax: Float = rawAccelX.toFloatOrNull() ?: 0f
                            val ay: Float = rawAccelY.toFloatOrNull() ?: 0f
                            val az: Float = rawAccelZ.toFloatOrNull() ?: 9.8f
                            val gx: Float = rawGyroX.toFloatOrNull() ?: 0f
                            val gy: Float = rawGyroY.toFloatOrNull() ?: 0f
                            val gz: Float = rawGyroZ.toFloatOrNull() ?: 0f
                            val sp: Float = rawSpeed.toFloatOrNull() ?: 0f
                            val pr: Float = rawPressure.toFloatOrNull() ?: 1013.25f
                            onInjectCustomData(ax, ay, az, gx, gy, gz, sp, pr)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006064))
                    ) {
                        Text("INJECT CUSTOM SENSOR DATA")
                    }
                }
            }

            // --- 6. Emergency Contacts ---
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
                            accelThresholdG = accelThreshold,
                            gyroThresholdDeg = gyroThreshold,
                            pressureThresholdHpa = pressureThreshold,
                            crashScoreThreshold = crashScoreThreshold,
                            
                            accelMinG = accelMinG, accelMaxG = accelMaxG,
                            speedMinKmh = speedMinKmh, speedDeltaMaxKmh = speedDeltaMaxKmh,
                            gyroMaxDegPerSec = gyroMaxDegPerSec, pressureMaxHpa = pressureMaxHpa,
                            stillMaxSec = stillMaxSec,
                            
                            wAccel = wAccel, wSpeed = wSpeed, wGyro = wGyro,
                            wPress = wPress, wStill = wStill, wRoll = wRoll,

                            bufferSeconds = bufferTime, samplingRateMs = samplingRateMs,
                            isSimulationMode = simulationMode, forcedDetectionMode = forcedMode,
                            isTelemetryLoggingEnabled = isTelemetryLoggingEnabled,
                            isAutoStartEnabled = isAutoStartEnabled,
                            useWatchDirectDispatch = useWatchDirectDispatch,
                            usePhoneGps = usePhoneGps,
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
fun ScenarioButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PresetButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(32.dp)) {
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
fun ThresholdControl(label: String, value: Float, unit: String, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("$label: ${String.format(Locale.getDefault(), "%.2f", value)}$unit", style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp))
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
