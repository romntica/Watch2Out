// [Module: :app]
package com.jinn.watch2out.service

import android.content.Intent
import android.os.Environment
import android.util.Log
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.network.ProtocolContract
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Receives EDR logs, Audio assets, and Telemetry logs from the Watch.
 * v27.4: Added Telemetry Logging support for daily review.
 */
class MobileMessageService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "MobileMessageService"

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            ProtocolContract.Paths.INCIDENT_REPORT -> {
                val json = String(messageEvent.data)
                saveIncidentJson(json)
            }
            ProtocolContract.Paths.TELEMETRY_LOG -> {
                saveTelemetryLog(messageEvent.data)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == ProtocolContract.Paths.INCIDENT_AUDIO_ASSET) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val asset = dataMap.getAsset(ProtocolContract.Keys.AUDIO_FILE)
                val fileName = dataMap.getString("file_name") ?: "unknown_audio"
                
                if (asset != null) {
                    serviceScope.launch {
                        saveAssetToFile(asset, fileName)
                    }
                }
            }
        }
    }

    private fun saveIncidentJson(json: String) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watch2out")
            if (!dir.exists()) dir.mkdirs()
            
            // Format: YYYYMMDD-HHMMSS-IncidentType.json
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "$timestamp-EDR.json")
            
            FileOutputStream(file).use { it.write(json.toByteArray()) }
            Log.i(TAG, "📁 Saved EDR Log: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save JSON: ${e.message}")
        }
    }

    private fun saveTelemetryLog(data: ByteArray) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watch2out/telemetry")
            if (!dir.exists()) dir.mkdirs()
            
            // Format: YYYYMMDD-HHMMSS-telemetry.json
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "$timestamp-telemetry.json")
            
            FileOutputStream(file).use { it.write(data) }
            Log.d(TAG, "📊 Saved Telemetry Batch: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save telemetry log: ${e.message}")
        }
    }

    private suspend fun saveAssetToFile(asset: Asset, fileName: String) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watch2out")
            if (!dir.exists()) dir.mkdirs()
            
            // fileName now expected to contain extension (e.g., 20260426-135558-REC.aac)
            val file = if (fileName.contains(".")) {
                File(dir, fileName)
            } else {
                File(dir, "$fileName.aac")
            }

            val inputStream = Wearable.getDataClient(this).getFdForAsset(asset).await().inputStream
            
            FileOutputStream(file).use { out ->
                inputStream.copyTo(out)
            }
            Log.i(TAG, "🎵 Saved Audio Evidence: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Audio: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
