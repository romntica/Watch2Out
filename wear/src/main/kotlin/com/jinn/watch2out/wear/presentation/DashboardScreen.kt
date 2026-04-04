// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.material.*
import com.jinn.watch2out.shared.model.GpsMode
import com.jinn.watch2out.shared.model.TelemetryState
import com.jinn.watch2out.shared.model.VehicleInferenceState
import java.util.Locale

/**
 * Condensed Wear OS Dashboard Screen (v22.0).
 * Matches mobile items in a compact live-only view.
 */
@Composable
fun DashboardScreen(
    telemetry: TelemetryState,
    onClose: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 1. STATE & SPEED
        item {
            val isGpsStale = (System.currentTimeMillis() - telemetry.lastUpdateTime) > 15000L
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = telemetry.vehicleInferenceState.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Yellow,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f km/h", telemetry.gpsSpeed),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isGpsStale) Color.Gray else Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    if (isGpsStale) {
                        Spacer(Modifier.width(4.dp))
                        Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape))
                    }
                }
            }
        }

        item { HorizontalDivider() }

        // 2. ACCEL DATA (Condensed)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric("LON", String.format(Locale.getDefault(), "%+.1fg", telemetry.accelY / 9.81f))
                SmallMetric("LAT", String.format(Locale.getDefault(), "%+.1fg", telemetry.accelX / 9.81f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric("VER", String.format(Locale.getDefault(), "%+.1fg", telemetry.accelZ / 9.81f))
                SmallMetric("MAG", String.format(Locale.getDefault(), "%.1fg", telemetry.currentImpact))
            }
        }

        item { HorizontalDivider() }

        // 3. ANALYTICS (Score & Gyro)
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("SCORE:", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                Text(
                    text = String.format(Locale.getDefault(), "%.2f", telemetry.crashScore),
                    fontSize = 11.sp,
                    color = getScoreColor(telemetry.crashScore),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(4.dp))
                Box(modifier = Modifier.size(8.dp).background(getScoreColor(telemetry.crashScore), CircleShape))
                Spacer(Modifier.width(12.dp))
                SmallMetric("GYRO", String.format(Locale.getDefault(), "%.1f", telemetry.gyroRatio))
            }
        }

        // 4. ENVIRONMENT (Pressure & Roll)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric("PRES", String.format(Locale.getDefault(), "%+.1f", telemetry.pressureDelta))
                SmallMetric("ROLL", String.format(Locale.getDefault(), "%.0f°", telemetry.rollSum))
            }
        }

        // 5. GPS FUSION (v27.7)
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GpsStatusMiniBox(
                    label = "WATCH",
                    isActive = telemetry.isWatchGpsActive,
                    isPrimary = telemetry.activeGpsSource == GpsMode.WATCH_ONLY,
                    accuracy = telemetry.watchGpsAccuracy,
                    modifier = Modifier.weight(1f)
                )
                GpsStatusMiniBox(
                    label = "PHONE",
                    isActive = telemetry.isPhoneGpsActive,
                    isPrimary = telemetry.activeGpsSource == GpsMode.PHONE_PRIMARY,
                    accuracy = telemetry.phoneGpsAccuracy,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 6. SYSTEM STATUS
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric("CONF", String.format(Locale.getDefault(), "%.0f%%", telemetry.sensorConfidence * 100f))
            }
        }

        // Close Button
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Button(
                onClick = onClose,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("X", fontWeight = FontWeight.ExtraBold)
            }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
private fun GpsStatusMiniBox(label: String, isActive: Boolean, isPrimary: Boolean, accuracy: Float, modifier: Modifier = Modifier) {
    val borderColor = if (isPrimary) Color(0xFF42A5F5) else if (isActive) Color(0xFF4CAF50) else Color.DarkGray
    val bgColor = if (isPrimary) Color(0xFF42A5F5).copy(alpha = 0.15f) else Color.Transparent
    
    Column(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 7.sp, color = if (isPrimary) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(4.dp).background(if (isActive) Color.Green else Color.Red, CircleShape))
            Spacer(Modifier.width(2.dp))
            Text(
                text = if (isActive) "${accuracy.toInt()}m" else "OFF",
                fontSize = 9.sp,
                color = if (isActive) Color.White else Color.Gray,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String, valueColor: Color = Color.White) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", fontSize = 8.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(2.dp))
        Text(value, fontSize = 10.sp, color = valueColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HorizontalDivider() {
    Spacer(modifier = Modifier.fillMaxWidth(0.6f).height(1.dp).background(Color.DarkGray))
}

private fun getScoreColor(score: Float): Color {
    return when {
        score < 0.3f -> Color.Green
        score < 0.7f -> Color.Yellow
        else -> Color.Red
    }
}
