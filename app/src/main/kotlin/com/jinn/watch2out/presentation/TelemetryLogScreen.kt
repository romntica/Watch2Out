// [Module: :app]
package com.jinn.watch2out.presentation

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinn.watch2out.shared.model.TelemetryLogBatch
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryLogScreen(
    onClose: () -> Unit
) {
    var logFiles by remember { mutableStateOf(emptyList<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var batchData by remember { mutableStateOf<TelemetryLogBatch?>(null) }

    val explicitJson = remember { Json { ignoreUnknownKeys = true } }

    fun refreshFiles() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watch2out/telemetry")
        if (dir.exists()) {
            logFiles = dir.listFiles { file -> file.extension == "json" }?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }

    LaunchedEffect(Unit) {
        refreshFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedFile?.name ?: "Trip History") },
                navigationIcon = {
                    IconButton(onClick = { if (selectedFile != null) selectedFile = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedFile == null) {
                        IconButton(onClick = { refreshFiles() }) {
                            Text("Refresh", fontSize = 12.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selectedFile == null) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (logFiles.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No telemetry logs found.\nEnable 'Telemetry Logging' in Settings.", color = Color.Gray)
                        }
                    }
                }
                items(logFiles) { file ->
                    LogFileItem(file, onClick = {
                        selectedFile = file
                        try {
                            batchData = explicitJson.decodeFromString<TelemetryLogBatch>(file.readText())
                        } catch (e: Exception) {
                            batchData = null
                        }
                    }, onDelete = {
                        file.delete()
                        refreshFiles()
                    })
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                if (batchData == null) {
                    Text("Failed to parse log file.", color = Color.Red)
                } else {
                    Text("Batch size: ${batchData!!.entries.size} samples", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(batchData!!.entries) { entry ->
                            LogEntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogFileItem(file: File, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("${file.length() / 1024} KB", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: com.jinn.watch2out.shared.model.TelemetryLogEntry) {
    val timeStr = entry.readableTime.ifEmpty { 
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeStr, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.fsmState, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("G: ${String.format(Locale.US, "%.2f", entry.ax/9.81f)}, ${String.format(Locale.US, "%.2f", entry.ay/9.81f)}, ${String.format(Locale.US, "%.2f", entry.az/9.81f)}", fontSize = 10.sp)
            Text("SPD: ${String.format(Locale.US, "%.1f", entry.speed)} km/h", fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val gpsLoc = if (entry.latitude != 0.0) {
                "${String.format(Locale.US, "%.4f", entry.latitude)}, ${String.format(Locale.US, "%.4f", entry.longitude)}"
            } else entry.gpsStatus
            Text("GPS: $gpsLoc", fontSize = 10.sp, color = Color.Gray)
            Text("P: ${String.format(Locale.US, "%.2f", entry.pressure)} hPa", fontSize = 10.sp)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CRS: ${String.format(Locale.US, "%.2f", entry.crashScore)}", fontSize = 10.sp, color = if(entry.crashScore > 0.7) Color.Red else Color.Unspecified)
        }
    }
}
