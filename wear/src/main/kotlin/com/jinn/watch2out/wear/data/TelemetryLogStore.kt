// [Module: :wear]
package com.jinn.watch2out.wear.data

import android.content.Context
import android.util.Log
import com.jinn.watch2out.shared.model.TelemetryLogBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists telemetry logs to disk and handles batch retrieval for upload.
 */
class TelemetryLogStore(private val context: Context) {
    private val TAG = "TelemetryLogStore"
    private val logDir = File(context.filesDir, "telemetry_logs").apply { mkdirs() }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun saveBatch(batch: TelemetryLogBatch) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val fileName = "log_${System.currentTimeMillis()}.json"
                val file = File(logDir, fileName)
                file.writeText(json.encodeToString(batch))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save telemetry batch: ${e.message}")
            }
        }
    }

    suspend fun getPendingBatches(): List<Pair<File, TelemetryLogBatch>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val files = logDir.listFiles { _, name -> name.startsWith("log_") && name.endsWith(".json") } ?: emptyArray()
            files.mapNotNull { file ->
                try {
                    val content = file.readText()
                    file to json.decodeFromString<TelemetryLogBatch>(content)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read log file ${file.name}: ${e.message}")
                    file.delete()
                    null
                }
            }
        }
    }

    suspend fun deleteFile(file: File) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (file.exists()) {
                file.delete()
            }
        }
    }
}
