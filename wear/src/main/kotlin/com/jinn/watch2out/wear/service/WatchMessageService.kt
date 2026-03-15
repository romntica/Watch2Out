// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.content.Intent
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.wear.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Listener service for handling remote commands and data synchronization from the mobile companion.
 */
class WatchMessageService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
    }

    /**
     * Handles Data Layer events, such as settings synchronization from Mobile.
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == ProtocolContract.Paths.SETTINGS_SYNC) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val json = dataMap.getString(ProtocolContract.Keys.SETTINGS_JSON)
                
                if (json != null) {
                    serviceScope.launch {
                        try {
                            val newSettings = Json.decodeFromString<WatchSettings>(json)
                            settingsRepository.updateSettings(newSettings)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles Message Layer events, such as remote commands or on-demand settings requests.
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        
        if (!path.startsWith("/watch2out/v${ProtocolContract.VERSION}")) {
            return
        }

        serviceScope.launch {
            when (path) {
                ProtocolContract.Paths.REQUEST_SETTINGS -> {
                    val settings = settingsRepository.settingsFlow.first()
                    replyWithSettings(settings)
                }
                
                ProtocolContract.Paths.START_MONITORING,
                ProtocolContract.Paths.STOP_MONITORING,
                ProtocolContract.Paths.RESET_PEAKS,
                ProtocolContract.Paths.INCIDENT_ALERT_DISMISS -> {
                    val intent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = when (path) {
                            ProtocolContract.Paths.START_MONITORING -> SentinelService.ACTION_START_MONITORING
                            ProtocolContract.Paths.STOP_MONITORING -> SentinelService.ACTION_STOP_MONITORING
                            ProtocolContract.Paths.RESET_PEAKS -> SentinelService.ACTION_RESET_PEAKS
                            ProtocolContract.Paths.INCIDENT_ALERT_DISMISS -> SentinelService.ACTION_DISMISS_INCIDENT
                            else -> null
                        }
                    }
                    intent.action?.let {
                        if (it == SentinelService.ACTION_START_MONITORING) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                }

                ProtocolContract.Paths.SIMULATE_FRONTAL,
                ProtocolContract.Paths.SIMULATE_REAR,
                ProtocolContract.Paths.SIMULATE_SIDE,
                ProtocolContract.Paths.SIMULATE_ROLLOVER,
                ProtocolContract.Paths.SIMULATE_PLUNGE -> {
                    val intent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = SentinelService.ACTION_SIMULATE
                        putExtra("path", path)
                    }
                    startService(intent)
                }

                ProtocolContract.Paths.INJECT_CUSTOM_SENSOR -> {
                    val csv = String(messageEvent.data)
                    val intent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = SentinelService.ACTION_INJECT_DATA
                        putExtra("csv", csv)
                    }
                    startService(intent)
                }
            }
        }
    }

    private suspend fun replyWithSettings(settings: WatchSettings) {
        try {
            val settingsJson = Json.encodeToString(settings)
            val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.SETTINGS_SYNC).apply {
                dataMap.putString(ProtocolContract.Keys.SETTINGS_JSON, settingsJson)
                dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(this@WatchMessageService).putDataItem(putDataReq).await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
