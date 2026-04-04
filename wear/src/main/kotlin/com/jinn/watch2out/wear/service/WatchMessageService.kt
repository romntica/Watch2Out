// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.Heartbeat
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
 * v22.0: Immediate UI dismissal broadcast and robust service state sync.
 */
class WatchMessageService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private val TAG = "WatchMessageService"

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path ?: return@forEach
            if (event.type == DataEvent.TYPE_CHANGED) {
                when (path) {
                    ProtocolContract.Paths.SETTINGS_SYNC -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val json = dataMap.getString(ProtocolContract.Keys.SETTINGS_JSON)
                        if (json != null) {
                            serviceScope.launch {
                                try {
                                    val newSettings = Json.decodeFromString<WatchSettings>(json)
                                    settingsRepository.updateSettings(newSettings)
                                } catch (e: Exception) { }
                            }
                        }
                    }
                    ProtocolContract.Paths.HEARTBEAT_SYNC -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val json = dataMap.getString(ProtocolContract.Keys.HEARTBEAT_JSON)
                        if (json != null) {
                            val intent = Intent(applicationContext, SentinelService::class.java).apply {
                                action = SentinelService.ACTION_UPDATE_HEARTBEAT
                                putExtra("heartbeat_json", json)
                            }
                            startService(intent)
                        }
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (!path.startsWith("/watch2out/v${ProtocolContract.VERSION}")) return

        Log.d(TAG, "📩 Remote Message: $path")

        serviceScope.launch {
            when (path) {
                ProtocolContract.Paths.REQUEST_SETTINGS -> {
                    val settings = settingsRepository.settingsFlow.first()
                    replyWithSettings(settings)
                }
                
                ProtocolContract.Paths.INCIDENT_ALERT_DISMISS -> {
                    // 1. Immediate UI close via broadcast
                    val dismissIntent = Intent("com.jinn.watch2out.DISMISS_ALERT").apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(dismissIntent)
                    Log.d(TAG, "📢 Sent local dismiss broadcast to UI")

                    // 2. Notify Service to reset FSM state
                    val serviceIntent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = SentinelService.ACTION_DISMISS_INCIDENT
                    }
                    startService(serviceIntent)
                }

                ProtocolContract.Paths.START_MONITORING,
                ProtocolContract.Paths.STOP_MONITORING,
                ProtocolContract.Paths.RESET_PEAKS,
                ProtocolContract.Paths.DASHBOARD_START,
                ProtocolContract.Paths.DASHBOARD_STOP -> {
                    val intent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = when (path) {
                            ProtocolContract.Paths.START_MONITORING -> SentinelService.ACTION_START_MONITORING
                            ProtocolContract.Paths.STOP_MONITORING -> SentinelService.ACTION_STOP_MONITORING
                            ProtocolContract.Paths.RESET_PEAKS -> SentinelService.ACTION_RESET_PEAKS
                            ProtocolContract.Paths.DASHBOARD_START -> SentinelService.ACTION_DASHBOARD_START
                            ProtocolContract.Paths.DASHBOARD_STOP -> SentinelService.ACTION_DASHBOARD_STOP
                            else -> null
                        }
                    }
                    intent.action?.let {
                        if (it == SentinelService.ACTION_START_MONITORING || it == SentinelService.ACTION_DASHBOARD_START) {
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
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
