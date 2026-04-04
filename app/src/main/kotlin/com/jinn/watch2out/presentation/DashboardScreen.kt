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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.jinn.watch2out.shared.model.TelemetryState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    telemetry: TelemetryState,
    onResetPeak: () -> Unit,
    onClose: () -> Unit
) {
    val timeSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dateSdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    var selectedWindow by remember { mutableStateOf("10m") }
    var showDebug by remember { mutableStateOf(false) }
    
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
                        IconButton(onClick = { showDebug = !showDebug }) {
                            Icon(Icons.Default.BugReport, contentDescription = "Debug", tint = if (showDebug) Color.Magenta else Color.Gray)
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
                // --- 1. LIVE FEED ---
                val lastUpdateStr = if (telemetry.lastUpdateTime > 0) {
                    val diff = (System.currentTimeMillis() - telemetry.lastUpdateTime) / 1000
                    if (diff < 1) "Just now" else "${diff}s ago"
                } else "Never"
                
                SectionHeader("📊 LIVE FEED (Last Update: $lastUpdateStr)")
                LiveFeedCard(telemetry)
                SensorChartCard(telemetry.currentImpact)

                if (showDebug) {
                    DebugTuningCard(telemetry)
                }

                // --- 2. OVERALL PEAK ---
                val sessionStart = if (telemetry.sessionStartTime > 0) dateSdf.format(Date(telemetry.sessionStartTime)) else "N/A"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("📊 OVERALL PEAK (Since: $sessionStart)")
                    IconButton(onClick = onResetPeak, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.Cyan)
                    }
                }
                PeakDataCard(
                    longG = telemetry.maxLongitudinalG,
                    latG = telemetry.maxLateralG,
                    rmsG = telemetry.maxImpact,
                    crashScore = telemetry.pCrashScore,
                    gyroRatio = telemetry.pGyroRatio,
                    speedDelta = telemetry.maxSpeedDrop,
                    pressDelta = telemetry.pPressureDelta,
                    rollSum = telemetry.pRollSum,
                    timestamp = telemetry.pTimestamp
                )

                // --- 3. WINDOWED PEAK ---
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val windowMs = getWindowMs(selectedWindow)
                    val windowStartTime = telemetry.lastUpdateTime - windowMs
                    val rangeStr = "${timeSdf.format(Date(windowStartTime))}~${timeSdf.format(Date(telemetry.lastUpdateTime))}"
                    SectionHeader("📊 $selectedWindow PEAK ($rangeStr)")
                    
                    val tabs = listOf("1m", "5m", "10m", "30m", "1h")
                    ScrollableTabRow(
                        selectedTabIndex = tabs.indexOf(selectedWindow),
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                        divider = {},
                        indicator = { tabPositions ->
                            val index = tabs.indexOf(selectedWindow)
                            if (index != -1) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[index]),
                                    color = Color.White
                                )
                            }
                        }
                    ) {
                        tabs.forEach { window ->
                            Tab(
                                selected = selectedWindow == window,
                                onClick = { selectedWindow = window },
                                text = { Text(window, fontSize = 12.sp, color = if (selectedWindow == window) Color.White else Color.Gray) }
                            )
                        }
                    }
                }
                PeakDataCard(
                    longG = telemetry.wMaxLongitudinalG,
                    latG = telemetry.wMaxLateralG,
                    rmsG = telemetry.windowImpact,
                    crashScore = telemetry.wCrashScore,
                    gyroRatio = telemetry.wGyroRatio,
                    speedDelta = telemetry.wMaxSpeedDrop,
                    pressDelta = telemetry.wPressureDelta,
                    rollSum = telemetry.wRollSum,
                    timestamp = telemetry.wTimestamp
                )
                
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        color = Color.White,
        letterSpacing = 0.5.sp
    )
}

@Composable
fun LiveFeedCard(t: TelemetryState) {
    val isGpsStale = t.lastUpdateTime > 0 && (System.currentTimeMillis() - t.lastUpdateTime) > 15000L
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "STATE: ${t.vehicleInferenceState.name}",
                    color = Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isGpsStale) {
                        Box(modifier = Modifier.size(8.dp).background(Color.Red, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f km/h", t.gpsSpeed),
                        color = if (isGpsStale) Color.Gray else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
            HorizontalDivider(color = Color.DarkGray)
            
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem("LON", String.format(Locale.getDefault(), "%+.1fg", t.accelY / 9.81f), Modifier.weight(1f))
                MetricItem("LAT", String.format(Locale.getDefault(), "%+.1fg", t.accelX / 9.81f), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem("VER", String.format(Locale.getDefault(), "%+.1fg", t.accelZ / 9.81f), Modifier.weight(1f))
                MetricItem("MAG", String.format(Locale.getDefault(), "%.1fg", t.currentImpact), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("SCORE: ", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Text(
                        text = String.format(Locale.getDefault(), "%.2f", t.crashScore),
                        fontSize = 12.sp,
                        color = getScoreColor(t.crashScore),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(modifier = Modifier.size(10.dp).background(getScoreColor(t.crashScore), CircleShape))
                }
                MetricItem("GYRO", String.format(Locale.getDefault(), "%.1f", t.gyroRatio), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem("PRES", String.format(Locale.getDefault(), "%+.1fhPa", t.pressureDelta), Modifier.weight(1f))
                MetricItem("ROLL", String.format(Locale.getDefault(), "%.0f\u00b0", t.rollSum), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem("CONF", String.format(Locale.getDefault(), "%.0f%%", t.sensorConfidence * 100f), Modifier.weight(1f))
                MetricItem("GPS", if (t.isGpsActive) "ACTIVE" else "SEARCH", Modifier.weight(1f), valueColor = if (t.isGpsActive) Color.Green else Color.Yellow)
            }
        }
    }
}

@Composable
fun DebugTuningCard(t: TelemetryState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Magenta.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CrashScore v27 Diagnostics", color = Color.Magenta, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = Color.DarkGray)
            
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    DebugMetric("Normalized", "")
                    DebugMetric(" Acc: ", String.format("%.2f", t.nAccel))
                    DebugMetric(" Spd: ", String.format("%.2f", t.nSpeed))
                    DebugMetric(" Gyr: ", String.format("%.2f", t.nGyro))
                    DebugMetric(" Prs: ", String.format("%.2f", t.nPress))
                    DebugMetric(" Stl: ", String.format("%.2f", t.nStill))
                    DebugMetric(" Rol: ", String.format("%.2f", t.nRoll))
                }
                Column(Modifier.weight(1f)) {
                    DebugMetric("Eff. Weights", "")
                    DebugMetric(" wAcc: ", String.format("%.2f", t.wAccel))
                    DebugMetric(" wSpd: ", String.format("%.2f", t.wSpeed))
                    DebugMetric(" wGyr: ", String.format("%.2f", t.wGyro))
                    DebugMetric(" wPrs: ", String.format("%.2f", t.wPress))
                    DebugMetric(" wStl: ", String.format("%.2f", t.wStill))
                    DebugMetric(" wRol: ", String.format("%.2f", t.wRoll))
                }
            }
            HorizontalDivider(color = Color.DarkGray)
            Text("Rule Bonues", color = Color.Gray, fontSize = 10.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DebugMetric("Weak: ", String.format("%+.2f", t.bonusWeak), if (t.bonusWeak < 0) Color.Red else Color.Gray)
                DebugMetric("Fall: ", String.format("%+.2f", t.bonusFall), if (t.bonusFall > 0) Color.Green else Color.Gray)
                DebugMetric("Impact: ", String.format("%+.2f", t.bonusImpact), if (t.bonusImpact > 0) Color.Green else Color.Gray)
            }
        }
    }
}

@Composable
fun DebugMetric(label: String, value: String, valueColor: Color = Color.Cyan) {
    Row {
        Text(label, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PeakDataCard(
    longG: Float,
    latG: Float,
    rmsG: Float,
    crashScore: Float,
    gyroRatio: Float,
    speedDelta: Float,
    pressDelta: Float,
    rollSum: Float,
    timestamp: Long
) {
    val timeSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = if (timestamp > 0) " (${timeSdf.format(Date(timestamp))})" else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PeakLine("MAX LON", String.format(Locale.getDefault(), "%+.1fg", longG), timeStr)
            PeakLine("MAX LAT", String.format(Locale.getDefault(), "%+.1fg", latG), timeStr)
            PeakLine("MAX MAG", String.format(Locale.getDefault(), "%.1fg", rmsG), timeStr)
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MAX SCORE: ", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace, modifier = Modifier.width(135.dp))
                Text(String.format(Locale.getDefault(), "%.2f", crashScore), fontSize = 12.sp, color = getScoreColor(crashScore), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(4.dp))
                Box(modifier = Modifier.size(8.dp).background(getScoreColor(crashScore), CircleShape))
                Text(timeStr, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
            }

            PeakLine("MAX GYRO", String.format(Locale.getDefault(), "%.1fx", gyroRatio), timeStr)
            PeakLine("MAX ΔVEL", String.format(Locale.getDefault(), "%.0f km/h", speedDelta), timeStr)
            PeakLine("MAX PRES", String.format(Locale.getDefault(), "%+.1fhPa", pressDelta), timeStr)
            PeakLine("MAX ROLL", String.format(Locale.getDefault(), "%.0f\u00b0", rollSum), timeStr)
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.White) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun PeakLine(label: String, value: String, time: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace, modifier = Modifier.width(135.dp))
        Text(value, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(time, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

private fun getScoreColor(score: Float): Color {
    return when {
        score < 0.3f -> Color.Green
        score < 0.7f -> Color.Yellow
        else -> Color.Red
    }
}

private fun getWindowMs(window: String): Long {
    return when(window) {
        "1m" -> 60_000L
        "5m" -> 300_000L
        "10m" -> 600_000L
        "30m" -> 1_800_000L
        "1h" -> 3_600_000L
        else -> 600_000L
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
    Card(modifier = Modifier.fillMaxWidth().height(100.dp), colors = CardDefaults.cardColors(containerColor = Color.Black), shape = RoundedCornerShape(4.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            factory = { context ->
                LineChart(context).apply {
                    description.isEnabled = false; setTouchEnabled(false); legend.isEnabled = false
                    xAxis.apply { position = XAxis.XAxisPosition.BOTTOM; setDrawGridLines(false); setDrawLabels(false); axisLineColor = android.graphics.Color.DKGRAY }
                    axisLeft.apply { 
                        setDrawGridLines(true); gridColor = android.graphics.Color.rgb(30, 30, 30); axisMinimum = 0f; axisMaximum = 10f; textColor = android.graphics.Color.GRAY; textSize = 8f
                    }
                    axisRight.isEnabled = false
                }
            },
            update = { chart ->
                val dataSet = LineDataSet(entries.toList(), "Impact").apply {
                    color = Color.Cyan.toArgb()
                    setDrawCircles(false); setDrawValues(false); lineWidth = 1.5f; mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                chart.data = LineData(dataSet); chart.invalidate()
            }
        )
    }
}
