// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import android.app.RemoteInput
import android.os.Bundle
import android.text.InputType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import com.jinn.watch2out.shared.model.SimulationDetectionMode
import com.jinn.watch2out.shared.model.WatchSettings

@Composable
fun SettingsScreen(
    currentSettings: WatchSettings,
    onApply: (WatchSettings) -> Unit,
    onCancel: () -> Unit,
    onInjectCustomData: (String) -> Unit
) {
    // 1. Sensor States
    var isAccelEnabled by remember { mutableStateOf(currentSettings.isAccelEnabled) }
    var accelThreshold by remember { mutableFloatStateOf(currentSettings.accelThresholdG) }
    var isGyroEnabled by remember { mutableStateOf(currentSettings.isGyroEnabled) }
    var gyroThreshold by remember { mutableFloatStateOf(currentSettings.gyroThresholdDeg) }
    var isPressureEnabled by remember { mutableStateOf(currentSettings.isPressureEnabled) }
    var pressureThreshold by remember { mutableFloatStateOf(currentSettings.pressureThresholdHpa) }
    
    // 2. Buffer, Interval & Simulation
    var bufferTime by remember { mutableIntStateOf(currentSettings.bufferSeconds) }
    var samplingRateMs by remember { mutableIntStateOf(currentSettings.samplingRateMs) }
    var simulationMode by remember { mutableStateOf(currentSettings.isSimulationMode) }
    var forcedMode by remember { mutableStateOf(currentSettings.forcedDetectionMode) }

    // 3. Custom Injection UI States (Same as Mobile)
    var rawAccelX by remember { mutableStateOf("0.0") }
    var rawAccelY by remember { mutableStateOf("0.0") }
    var rawAccelZ by remember { mutableStateOf("9.8") }
    var rawGyroX by remember { mutableStateOf("0.0") }
    var rawSpeed by remember { mutableStateOf("20.0") }
    var rawPressure by remember { mutableStateOf("1013.25") }

    // 4. Notification & Policy States
    var isSmsEnabled by remember { mutableStateOf(currentSettings.isSmsEnabled) }
    var smsRecipient by remember { mutableStateOf(currentSettings.smsRecipient) }
    var isCallEnabled by remember { mutableStateOf(currentSettings.isCallEnabled) }
    var callRecipient by remember { mutableStateOf(currentSettings.callRecipient) }
    var useWatchDirectDispatch by remember { mutableStateOf(currentSettings.useWatchDirectDispatch) }
    var isAutoStartEnabled by remember { mutableStateOf(currentSettings.isAutoStartEnabled) }

    val bufferOptions = listOf(0, 1, 5, 10, 15)
    val intervalOptions = listOf(100, 200, 300, 500)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { ListHeader { Text("Settings") } }

        // --- Sensors Section ---
        item { Text("Sensors", style = MaterialTheme.typography.caption1) }
        item { ThresholdSliderWithToggle("Accel", accelThreshold, "G", isAccelEnabled, { isAccelEnabled = it }, { accelThreshold = it }, 5f..30f, 25) }
        item { ThresholdSliderWithToggle("Gyro", gyroThreshold, "°/s", isGyroEnabled, { isGyroEnabled = it }, { gyroThreshold = it }, 100f..1000f, 18) }
        item { ThresholdSliderWithToggle("Pressure", pressureThreshold, "hPa", isPressureEnabled, { isPressureEnabled = it }, { pressureThreshold = it }, 0.5f..5.0f, 45) }

        // --- Simulation Mode ---
        item { ListHeader { Text("Simulation") } }
        item {
            ToggleChip(
                checked = simulationMode, onCheckedChange = { simulationMode = it },
                label = { Text("Simulation Mode") }, toggleControl = { Switch(checked = simulationMode, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (simulationMode) {
            item { Text("Quick Presets", style = MaterialTheme.typography.caption2, color = Color.Yellow) }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CompactChip(onClick = { rawAccelX = "0.0"; rawAccelY = "-80.0"; rawAccelZ = "0.0"; rawGyroX = "0.0"; rawSpeed = "20.0"; rawPressure = "1000.0" }, label = { Text("Front", fontSize = 9.sp) })
                    CompactChip(onClick = { rawAccelX = "0.0"; rawAccelY = "30.0"; rawAccelZ = "0.0"; rawGyroX = "5.0"; rawSpeed = "0.5"; rawPressure = "1000.0" }, label = { Text("Rear", fontSize = 9.sp) })
                    CompactChip(onClick = { rawAccelX = "60.0"; rawAccelY = "0.0"; rawAccelZ = "0.0"; rawGyroX = "0.0"; rawSpeed = "15.0"; rawPressure = "1000.0" }, label = { Text("Side", fontSize = 9.sp) })
                    CompactChip(onClick = { rawAccelX = "0.0"; rawAccelY = "0.0"; rawAccelZ = "1.0"; rawGyroX = "0.0"; rawSpeed = "20.0"; rawPressure = "1000.0" }, label = { Text("Fall", fontSize = 9.sp) })
                }
            }

            item { Text("Raw Values (A_XYZ / G / S / P)", style = MaterialTheme.typography.caption2, color = Color.Cyan) }
            item {
                Text(
                    text = "${rawAccelX}/${rawAccelY}/${rawAccelZ} | ${rawGyroX} | ${rawSpeed} | ${rawPressure}",
                    style = MaterialTheme.typography.caption3, fontSize = 8.sp, color = Color.White
                )
            }
            item {
                Button(
                    onClick = {
                        val csv = "${rawAccelX},${rawAccelY},${rawAccelZ},${rawGyroX},${rawSpeed},${rawPressure}"
                        onInjectCustomData(csv)
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text("INJECT EVENT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- Emergency Contacts Section ---
        item { ListHeader { Text("Emergency Contacts") } }
        item { NotificationContactItem("SMS", isSmsEnabled, smsRecipient, { isSmsEnabled = it }, { smsRecipient = it }, InputType.TYPE_CLASS_PHONE) }
        item { NotificationContactItem("Call", isCallEnabled, callRecipient, { isCallEnabled = it }, { callRecipient = it }, InputType.TYPE_CLASS_PHONE) }

        // --- System & Dispatch Section ---
        item { ListHeader { Text("System & Dispatch") } }
        item {
            ToggleChip(
                checked = isAutoStartEnabled, onCheckedChange = { isAutoStartEnabled = it },
                label = { Text("Auto Start on Boot") }, toggleControl = { Switch(checked = isAutoStartEnabled, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = useWatchDirectDispatch, onCheckedChange = { useWatchDirectDispatch = it },
                label = { Text("Watch Direct Dispatch") }, toggleControl = { Switch(checked = useWatchDirectDispatch, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- Action Buttons ---
        item {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        onApply(WatchSettings(
                            isAccelEnabled = isAccelEnabled, accelThresholdG = accelThreshold,
                            isGyroEnabled = isGyroEnabled, gyroThresholdDeg = gyroThreshold,
                            isPressureEnabled = isPressureEnabled, pressureThresholdHpa = pressureThreshold,
                            bufferSeconds = bufferTime, samplingRateMs = samplingRateMs, 
                            isSimulationMode = simulationMode, forcedDetectionMode = forcedMode,
                            isAutoStartEnabled = isAutoStartEnabled, useWatchDirectDispatch = useWatchDirectDispatch,
                            isSmsEnabled = isSmsEnabled, smsRecipient = smsRecipient,
                            isCallEnabled = isCallEnabled, callRecipient = callRecipient
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) { Text("SAVE & APPLY") }
                
                Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.fillMaxWidth(0.9f)) { Text("CANCEL") }
            }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun ThresholdSliderWithToggle(
    label: String, value: Float, unit: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, steps: Int
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(text = "$label: ${String.format("%.1f", value)}$unit", style = MaterialTheme.typography.caption2, modifier = Modifier.padding(start = 4.dp).alpha(if (checked) 1f else 0.5f))
        }
        InlineSlider(
            value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, enabled = checked,
            modifier = Modifier.fillMaxWidth().alpha(if (checked) 1f else 0.5f),
            decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "Dec") }, increaseIcon = { Icon(InlineSliderDefaults.Increase, "Inc") }
        )
    }
}

@Composable
fun NotificationContactItem(
    label: String, enabled: Boolean, recipient: String,
    onToggle: (Boolean) -> Unit, onUpdateRecipient: (String) -> Unit,
    inputType: Int = InputType.TYPE_CLASS_TEXT
) {
    val remoteInputKey = "extra_recipient"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val results: Bundle? = RemoteInput.getResultsFromIntent(result.data)
            val text = results?.getCharSequence(remoteInputKey)
            if (!text.isNullOrEmpty()) onUpdateRecipient(text.toString())
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleChip(checked = enabled, onCheckedChange = onToggle, label = { Text(label) }, toggleControl = { Checkbox(checked = enabled, onCheckedChange = null) }, modifier = Modifier.fillMaxWidth())
        if (enabled) {
            Chip(
                onClick = { 
                    val remoteInputs = listOf(RemoteInput.Builder(remoteInputKey).setLabel("Enter $label").build())
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
                    intent.putExtra("androidx.wear.input.extra.INPUT_TYPE", inputType)
                    launcher.launch(intent)
                },
                label = { Text(text = recipient.ifEmpty { "Tap to set" }, style = MaterialTheme.typography.caption2, maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
        }
    }
}
