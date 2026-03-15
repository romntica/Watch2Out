// [Module: :app]
package com.jinn.watch2out.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.jinn.watch2out.shared.model.DetectionMode
import java.text.SimpleDateFormat
import java.util.*

/**
 * Advanced Mobile Dashboard.
 * Terminology unified: Impact, Gyroscope, Rotation, Air Pressure.
 * 'Posture' interpreted as 'Tilt & Speed' for user clarity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    currentImpact: Float, maxImpact: Float, peakFallScore: Int, peakCrashScore: Int,
    windowImpact: Float, windowFallScore: Int, windowCrashScore: Int,
    currentMode: DetectionMode,
    accelX: Float, accelY: Float, accelZ: Float,
    gyroX: Float, gyroY: Float, gyroZ: Float,
    rotationX: Float, rotationY: Float, rotationZ: Float,
    airPressure: Float, rotationSpeed: Float, pressureDelta: Float, tiltAngle: Float,
    
    // Overall Peak Snapshots (P)
    pTimestamp: Long,
    pAccelX: Float, pAccelY: Float, pAccelZ: Float,
    pGyroX: Float, pGyroY: Float, pGyroZ: Float,
    pRotationX: Float, pRotationY: Float, pRotationZ: Float,
    pAirPressure: Float, pTiltAngle: Float, pRotationSpeed: Float,
    
    // Window Peak Snapshots (W)
    wTimestamp: Long,
    wAccelX: Float, wAccelY: Float, wAccelZ: Float,
    wGyroX: Float, wGyroY: Float, wGyroZ: Float,
    wRotationX: Float, wRotationY: Float, wRotationZ: Float,
    wAirPressure: Float, wTiltAngle: Float, wRotationSpeed: Float,

    onResetPeak: () -> Unit,
    onClose: () -> Unit
) {
    Surface(color = Color(0xFF050505), modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Sentinel Hub Analytics", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = onResetPeak) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset Peaks", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // --- 1. LIVE FEED SECTION ---
                Text("1. LIVE FEED", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                LiveMetricCard(
                    currentMode, 
                    !currentImpact.isNaN(), 
                    accelX, accelY, accelZ, 
                    gyroX, gyroY, gyroZ, 
                    rotationX, rotationY, rotationZ, 
                    airPressure, tiltAngle, rotationSpeed
                )
                SensorChartCard(currentImpact)

                // --- 2. CRASH ANALYTICS SECTION ---
                AnalyticsGroupSection(
                    title = "2. CRASH ANALYTICS",
                    score = peakCrashScore,
                    accentColor = Color(0xFFFF5252),
                    pTimestamp = pTimestamp,
                    pSnap = listOf(pAccelX, pAccelY, pAccelZ, pGyroX, pGyroY, pGyroZ, pRotationX, pRotationY, pRotationZ, pAirPressure, pTiltAngle, pRotationSpeed),
                    wTimestamp = wTimestamp,
                    wSnap = listOf(wAccelX, wAccelY, wAccelZ, wGyroX, wGyroY, wGyroZ, wRotationX, wRotationY, wRotationZ, wAirPressure, wTiltAngle, wRotationSpeed),
                    live = listOf(accelX, accelY, accelZ, gyroX, gyroY, gyroZ, rotationX, rotationY, rotationZ, airPressure, tiltAngle, rotationSpeed)
                )

                // --- 3. FALL ANALYTICS SECTION ---
                AnalyticsGroupSection(
                    title = "3. FALL ANALYTICS",
                    score = peakFallScore,
                    accentColor = Color(0xFFFFD740),
                    pTimestamp = pTimestamp,
                    pSnap = listOf(pAccelX, pAccelY, pAccelZ, pGyroX, pGyroY, pGyroZ, pRotationX, pRotationY, pRotationZ, pAirPressure, pTiltAngle, pRotationSpeed),
                    wTimestamp = wTimestamp,
                    wSnap = listOf(wAccelX, wAccelY, wAccelZ, wGyroX, wGyroY, wGyroZ, wRotationX, wRotationY, wRotationZ, wAirPressure, wTiltAngle, wRotationSpeed),
                    live = listOf(accelX, accelY, accelZ, gyroX, gyroY, gyroZ, rotationX, rotationY, rotationZ, airPressure, tiltAngle, rotationSpeed)
                )
                
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun LiveMetricCard(mode: DetectionMode, isConnected: Boolean, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float, rx: Float, ry: Float, rz: Float, press: Float, tilt: Float, speed: Float) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(if (isConnected) Color.Green else Color.Red, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(mode.name, color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            HorizontalDivider(color = Color.DarkGray)
            MetricLine("Impact (X/Y/Z)", formatXYZ(ax, ay, az, "%.1f"))
            MetricLine("Gyroscope (X/Y/Z)", formatXYZ(gx, gy, gz, "%d"))
            MetricLine("Rotation (X/Y/Z)", formatXYZ(rx, ry, rz, "%.2f"))
            MetricLine("Air Pressure", if (press.isNaN()) "--" else String.format(Locale.getDefault(), "%.1f hPa", press))
            MetricLine("Posture (Tilt/Speed)", String.format(Locale.getDefault(), "%d° Tilt / %d°/s Speed", tilt.toInt(), speed.toInt()))
        }
    }
}

@Composable
fun AnalyticsGroupSection(score: Int, title: String, accentColor: Color, pTimestamp: Long, pSnap: List<Float>, wTimestamp: Long, wSnap: List<Float>, live: List<Float>) {
    val sdf = SimpleDateFormat("yy/MM/dd-HH:mm:ss", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accentColor)
                Text("$score%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = accentColor)
            }
            
            ComparisonSnapshotBox("Overall Peak Record", pTimestamp, accentColor, pSnap, live, sdf)
            ComparisonSnapshotBox("Window Peak Record (5s)", wTimestamp, Color.White, wSnap, live, sdf)
        }
    }
}

@Composable
fun ComparisonSnapshotBox(title: String, timestamp: Long, color: Color, snap: List<Float>, live: List<Float>, sdf: SimpleDateFormat) {
    val timeStr = if (timestamp > 0) sdf.format(Date(timestamp)) else "No Data"
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
        color = Color.Black, shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
                Text(timeStr, fontSize = 9.sp, color = Color.Gray)
            }
            HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
            
            // Compares Snapshot vs Live for each sensor
            ComparisonItem("Impact", formatXYZ(snap[0], snap[1], snap[2], "%.1f"), formatXYZ(live[0], live[1], live[2], "%.1f"))
            ComparisonItem("Gyroscope", formatXYZ(snap[3], snap[4], snap[5], "%d"), formatXYZ(live[3], live[4], live[5], "%d"))
            ComparisonItem("Rotation", formatXYZ(snap[6], snap[7], snap[8], "%.2f"), formatXYZ(live[6], live[7], live[8], "%.2f"))
            ComparisonItem("Press", formatSingle(snap[9], "%.1f"), formatSingle(live[9], "%.1f"))
            ComparisonItem("Posture (Tilt/Speed)", "${snap[10].toInt()}°/${snap[11].toInt()}°/s", "${live[10].toInt()}°/${live[11].toInt()}°/s")
        }
    }
}

@Composable
fun ComparisonItem(label: String, peak: String, live: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text("$peak (live $live)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun MetricLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun SensorChartCard(currentImpact: Float) {
    val entries = remember { mutableStateListOf<Entry>() }
    var xCounter by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(currentImpact) {
        if (!currentImpact.isNaN()) {
            entries.add(Entry(xCounter, currentImpact))
            xCounter += 1f
            if (entries.size > 40) entries.removeAt(0)
        }
    }
    Card(modifier = Modifier.fillMaxWidth().height(140.dp), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            factory = { context ->
                LineChart(context).apply {
                    description.isEnabled = false; setTouchEnabled(false); legend.isEnabled = false
                    xAxis.apply { position = XAxis.XAxisPosition.BOTTOM; setDrawGridLines(false); setDrawLabels(false) }
                    axisLeft.apply { 
                        setDrawGridLines(true); gridColor = android.graphics.Color.DKGRAY; axisMinimum = 0f; axisMaximum = 10f; textColor = android.graphics.Color.GRAY 
                    }
                    axisRight.isEnabled = false
                }
            },
            update = { chart ->
                val dataSet = LineDataSet(entries.toList(), "Impact").apply {
                    color = Color.White.toArgb()
                    setDrawCircles(false); setDrawValues(false); lineWidth = 2f; mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                chart.data = LineData(dataSet); chart.invalidate()
            }
        )
    }
}

private fun formatSingle(v: Float, pattern: String): String {
    if (v.isNaN()) return "--"
    return try {
        if (pattern.contains("d")) String.format(Locale.getDefault(), pattern, v.toInt())
        else String.format(Locale.getDefault(), pattern, v)
    } catch (e: Exception) { "--" }
}

private fun formatXYZ(x: Float, y: Float, z: Float, pattern: String): String {
    if (x.isNaN()) return "--"
    return try {
        if (pattern == "%d") {
            String.format(Locale.getDefault(), "%d/%d/%d", x.toInt(), y.toInt(), z.toInt())
        } else {
            String.format(Locale.getDefault(), "%.1f/%.1f/%.1f", x, y, z)
        }
    } catch (e: Exception) { "--" }
}
