// [Module: :wear]
package com.jinn.watch2out.wear.data

import android.content.Context
import android.util.Log
import com.jinn.watch2out.shared.model.PendingIncident
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists pending incident metadata to disk for long-term retry.
 * v32.0: Critical for fail-safe data delivery.
 */
class IncidentAssetStore(private val context: Context) {
    private val TAG = "IncidentAssetStore"
    private val storeDir = File(context.filesDir, "pending_incidents").apply { mkdirs() }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun savePendingIncident(incident: PendingIncident) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val fileName = "pending_${incident.id}.json"
                val file = File(storeDir, fileName)
                file.writeText(json.encodeToString(incident))
                Log.d(TAG, "Saved pending incident to fail-safe storage: ${incident.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save pending incident: ${e.message}")
            }
        }
    }

    suspend fun getPendingIncidents(): List<Pair<File, PendingIncident>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val files = storeDir.listFiles { _, name -> name.startsWith("pending_") && name.endsWith(".json") } ?: emptyArray()
            files.mapNotNull { file ->
                try {
                    val content = file.readText()
                    file to json.decodeFromString<PendingIncident>(content)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read pending incident ${file.name}: ${e.message}")
                    file.delete()
                    null
                }
            }
        }
    }

    suspend fun deletePendingIncident(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(storeDir, "pending_$id.json")
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Deleted pending incident after successful transfer: $id")
            }
        }
    }
}
