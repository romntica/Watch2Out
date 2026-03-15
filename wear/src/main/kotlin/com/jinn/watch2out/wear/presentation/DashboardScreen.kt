// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.material.*
import com.jinn.watch2out.shared.model.DetectionMode

/**
 * Wear OS Dashboard Screen.
 * Unified terminology: Impact (IMP), Rotation (ROT), Orientation (ORI), Pressure (PRS), Posture (POS).
 * Pedestrian features removed.
 */
@Composable
fun DashboardScreen(
    currentImpact: Float,
    maxImpact: Float,
    peakFallScore: Int, // Deprecated
    peakCrashScore: Int,
    windowImpact: Float,
    windowFallScore: Int, // Deprecated
    windowCrashScore: Int,
    currentMode: DetectionMode,
    accelX: Float,
    accelY: Float,
    accelZ: Float,
    gyroX: Float,
    gyroY: Float,
    gyroZ: Float,
    rotationX: Float,
    rotationY: Float,
    rotationZ: Float,
    airPressure: Float,
    rotationSpeed: Float,
    pressureDelta: Float,
    tiltAngle: Float,
    onResetPeak: () -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { Spacer(modifier = Modifier.height(20.dp)) }

        // Detection Mode Badge
        item {
            val modeColor = Color(0xFF64B5F6)
            Text(
                text = "VEHICLE MODE",
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = modeColor,
                modifier = Modifier
                    .background(modeColor.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // Row 1: Overall Peak Stat
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("OVERALL PEAK", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(modifier = Modifier.width(28.dp))
                    ScoreGroupDisplay(maxImpact, peakCrashScore, Color.Red)
                    Button(
                        onClick = onResetPeak,
                        modifier = Modifier.size(24.dp).padding(start = 4.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("C", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        // Row 2: 5s Window Stat
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("5s WINDOW", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ScoreGroupDisplay(windowImpact, windowCrashScore, Color.Yellow)
                }
            }
        }

        item { 
            Spacer(modifier = Modifier.height(4.dp))
            Spacer(modifier = Modifier.fillMaxWidth(0.7f).height(1.dp).background(Color.DarkGray))
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Row 3+: Live Data
        item { CenteredDetailRow("IMP", formatSensor(accelX, accelY, accelZ, "%.1f")) }
        item { CenteredDetailRow("ROT", formatSensor(gyroX, gyroY, gyroZ, "%d")) }
        item { CenteredDetailRow("ORI", formatSensor(rotationX, rotationY, rotationZ, "%.2f")) }
        item { 
            CenteredDetailRow("PRS", if (airPressure.isNaN()) "--" else String.format("%.1f (Δ%.1f)", airPressure, pressureDelta)) 
        }
        item { 
            CenteredDetailRow("POS", if (rotationSpeed == 0f && tiltAngle == 0f) "--" else String.format("%d° (%d°/s)", tiltAngle.toInt(), rotationSpeed.toInt())) 
        }

        // Close Button
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item {
            Button(
                onClick = onClose,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
            ) {
                Text("X", fontWeight = FontWeight.ExtraBold)
            }
        }
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

private fun formatSensor(x: Float, y: Float, z: Float, pattern: String): String {
    if (x.isNaN()) return "--"
    return try {
        if (pattern == "%d") {
            "${x.toInt()} / ${y.toInt()} / ${z.toInt()}"
        } else {
            String.format("$pattern / $pattern / $pattern", x, y, z)
        }
    } catch (e: Exception) { "--" }
}

@Composable
fun ScoreGroupDisplay(gValue: Float, crash: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (gValue.isNaN()) "--" else String.format("%.1fG", gValue),
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        ScoreItem("C", crash)
    }
}

@Composable
fun ScoreItem(label: String, score: Int) {
    val color = when {
        score >= 90 -> Color.Red
        score >= 70 -> Color.Yellow
        else -> Color.Green
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = Color.Gray)
        Text(
            text = "$score%",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(start = 1.dp)
        )
    }
}

@Composable
fun CenteredDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, 
            fontSize = 8.sp, 
            color = Color.Yellow, 
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Start
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}
