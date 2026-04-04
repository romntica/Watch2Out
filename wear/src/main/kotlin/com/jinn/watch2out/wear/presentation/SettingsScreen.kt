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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import com.jinn.watch2out.shared.model.WatchSettings
import java.util.Locale

/**
 * Advanced configuration for the Sentinel system on Wear OS.
 * v27.4: Added Telemetry Logging toggle.
 */
@Composable
fun SettingsScreen(
    currentSettings: WatchSettings,
    onApply: (WatchSettings) -> Unit,
    onCancel: () -> Unit,
    onInjectCustomData: (String) -> Unit
) {
    // 1. Core Thresholds
    var accelThreshold by remember { mutableFloatStateOf(currentSettings.accelThresholdG) }
    var gyroThreshold by remember { mutableFloatStateOf(currentSettings.gyroThresholdDeg) }
    var pressureThreshold by remember { mutableFloatStateOf(currentSettings.pressureThresholdHpa) }
    var speedDelta by remember { mutableFloatStateOf(currentSettings.speedDeltaKmh) }
    var stillnessDurationSec by remember { mutableFloatStateOf(currentSettings.stillnessDurationMs / 1000f) }
    var crashScoreThreshold by remember { mutableFloatStateOf(currentSettings.crashScoreThreshold) }
    
    // 2. System & Simulation States
    var simulationMode by remember { mutableStateOf(currentSettings.isSimulationMode) }
    var isTelemetryLoggingEnabled by remember { mutableStateOf(currentSettings.isTelemetryLoggingEnabled) }
    var isSmsEnabled by remember { mutableStateOf(currentSettings.isSmsEnabled) }
    var smsRecipient by remember { mutableStateOf(currentSettings.smsRecipient) }
    var isCallEnabled by remember { mutableStateOf(currentSettings.isCallEnabled) }
    var callRecipient by remember { mutableStateOf(currentSettings.callRecipient) }
    var useWatchDirectDispatch by remember { mutableStateOf(currentSettings.useWatchDirectDispatch) }
    var isAutoStartEnabled by remember { mutableStateOf(currentSettings.isAutoStartEnabled) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { ListHeader { Text("Threshold Config", color = Color.Cyan) } }

        // --- Core Thresholds ---
        item { ThresholdAdjuster("Accel", accelThreshold, "G", 8f..20f, 0.5f) { accelThreshold = it } }
        item { ThresholdAdjuster("Gyro", gyroThreshold, "°/s", 100f..500f, 10f) { gyroThreshold = it } }
        item { ThresholdAdjuster("Baro", pressureThreshold, "hPa", 1.0f..5.0f, 0.1f) { pressureThreshold = it } }
        item { ThresholdAdjuster("Δv", speedDelta, "km/h", 10f..50f, 1f) { speedDelta = it } }
        item { ThresholdAdjuster("Still", stillnessDurationSec, "s", 3f..15f, 1f) { stillnessDurationSec = it } }
        item { ThresholdAdjuster("Score", crashScoreThreshold, "", 0.5f..0.9f, 0.05f) { crashScoreThreshold = it } }

        // --- Simulation & Logging ---
        item { ListHeader { Text("Simulation & Logs") } }
        item {
            ToggleChip(
                checked = simulationMode, onCheckedChange = { simulationMode = it },
                label = { Text("Simulation Mode") }, toggleControl = { Switch(checked = simulationMode, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = isTelemetryLoggingEnabled, onCheckedChange = { isTelemetryLoggingEnabled = it },
                label = { Text("Telemetry Log") }, toggleControl = { Switch(checked = isTelemetryLoggingEnabled, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- Emergency Contacts ---
        item { ListHeader { Text("Emergency Contacts") } }
        item { NotificationContactItem("SMS", isSmsEnabled, smsRecipient, { isSmsEnabled = it }, { smsRecipient = it }, InputType.TYPE_CLASS_PHONE) }
        item { NotificationContactItem("Call", isCallEnabled, callRecipient, { isCallEnabled = it }, { callRecipient = it }, InputType.TYPE_CLASS_PHONE) }

        // --- System & Dispatch ---
        item { ListHeader { Text("System") } }
        item {
            ToggleChip(
                checked = isAutoStartEnabled, onCheckedChange = { isAutoStartEnabled = it },
                label = { Text("Auto Start") }, toggleControl = { Switch(checked = isAutoStartEnabled, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = useWatchDirectDispatch, onCheckedChange = { useWatchDirectDispatch = it },
                label = { Text("Direct Dispatch") }, toggleControl = { Switch(checked = useWatchDirectDispatch, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- Action Buttons ---
        item {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        onApply(WatchSettings(
                            accelThresholdG = accelThreshold,
                            gyroThresholdDeg = gyroThreshold,
                            pressureThresholdHpa = pressureThreshold,
                            speedDeltaKmh = speedDelta,
                            stillnessDurationMs = (stillnessDurationSec * 1000).toLong(),
                            crashScoreThreshold = crashScoreThreshold,
                            isSimulationMode = simulationMode,
                            isTelemetryLoggingEnabled = isTelemetryLoggingEnabled,
                            isAutoStartEnabled = isAutoStartEnabled,
                            useWatchDirectDispatch = useWatchDirectDispatch,
                            isSmsEnabled = isSmsEnabled,
                            smsRecipient = smsRecipient,
                            isCallEnabled = isCallEnabled,
                            callRecipient = callRecipient
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
fun ThresholdAdjuster(label: String, value: Float, unit: String, range: ClosedFloatingPointRange<Float>, step: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$label: ${if (unit == "hPa" || label == "Score") String.format(Locale.US, "%.2f", value) else value.toInt().toString()}$unit", style = MaterialTheme.typography.caption2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Button(onClick = { onValueChange((value - step).coerceAtLeast(range.start)) }, modifier = Modifier.size(32.dp), colors = ButtonDefaults.secondaryButtonColors()) {
                Text("-", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = { onValueChange((value + step).coerceAtMost(range.endInclusive)) }, modifier = Modifier.size(32.dp), colors = ButtonDefaults.secondaryButtonColors()) {
                Text("+", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun NotificationContactItem(
    label: String, enabled: Boolean, recipient: String,
    onToggle: (Boolean) -> Unit, onUpdateRecipient: (String) -> Unit,
    inputType: Int = InputType.TYPE_CLASS_PHONE
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
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
    }
}
