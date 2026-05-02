// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import com.google.android.gms.wearable.*
import com.jinn.watch2out.shared.model.Heartbeat
import com.jinn.watch2out.shared.model.IncidentState
import com.jinn.watch2out.shared.model.SensorStatus
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.wear.data.SettingsRepository
import com.jinn.watch2out.wear.service.SentinelService
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
                when {
                    path.startsWith(ProtocolContract.Paths.SETTINGS_SYNC) -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val json = dataMap.getString(ProtocolContract.Keys.SETTINGS_JSON) ?: return@forEach
                        
                        serviceScope.launch {
                            try {
                                val currentSettings = settingsRepository.settingsFlow.first()
                                val newSettings = Json.decodeFromString<WatchSettings>(json)
                                
                                // Optimization: Only update if the settings have actually changed
                                // This reduces Disk I/O (Read_top) and unnecessary background work.
                                if (newSettings != currentSettings) {
                                    settingsRepository.updateSettings(newSettings)
                                    Log.d(TAG, "⚙️ Settings updated from remote")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to parse settings: ${e.message}")
                            }
                        }
                    }
                    path.startsWith(ProtocolContract.Paths.HEARTBEAT_SYNC) -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val json = dataMap.getString(ProtocolContract.Keys.HEARTBEAT_JSON) ?: return@forEach

                        // Crash Recovery Guardian (v28.6.5)
                        if (!SentinelService.isRunning) {
                            serviceScope.launch {
                                val persistedState = settingsRepository.monitoringStateFlow.first()
                                if (persistedState == IncidentState.MONITORING.name) {
                                    Log.w(TAG, "Guardian (HB): SentinelService not running but should be. Restarting...")
                                    val startIntent = Intent(applicationContext, SentinelService::class.java).apply {
                                        action = SentinelService.ACTION_START_MONITORING
                                    }
                                    startForegroundService(startIntent)
                                }
                            }
                        }
                        
                        val intent = Intent(applicationContext, SentinelService::class.java).apply {
                            action = SentinelService.ACTION_UPDATE_HEARTBEAT
                            putExtra("heartbeat_json", json)
                        }
                        startService(intent)
                    }
                    path.startsWith(ProtocolContract.Paths.DASHBOARD_CONFIG) -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val windowMs = dataMap.getLong(ProtocolContract.Keys.WINDOW_MS)
                        
                        val intent = Intent(applicationContext, SentinelService::class.java).apply {
                            action = SentinelService.ACTION_DASHBOARD_CONFIG
                            putExtra(ProtocolContract.Keys.WINDOW_MS, windowMs)
                        }
                        startService(intent)
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (!path.startsWith("/watch2out/v${ProtocolContract.VERSION}")) return

        Log.d(TAG, "📩 Remote Message: $path")

        // Crash Recovery Guardian (v28.6.5)
        if (!SentinelService.isRunning) {
            serviceScope.launch {
                val persistedState = settingsRepository.monitoringStateFlow.first()
                if (persistedState == IncidentState.MONITORING.name && path != ProtocolContract.Paths.STOP_MONITORING) {
                    Log.w(TAG, "Guardian: SentinelService not running but should be. Restarting...")
                    val intent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = SentinelService.ACTION_START_MONITORING
                    }
                    startForegroundService(intent)
                }
            }
        }

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
                ProtocolContract.Paths.DASHBOARD_STOP,
                ProtocolContract.Paths.SYNC_POLICY_UPDATE -> {
                    val intent = Intent(applicationContext, SentinelService::class.java).apply {
                        action = when (path) {
                            ProtocolContract.Paths.START_MONITORING -> SentinelService.ACTION_START_MONITORING
                            ProtocolContract.Paths.STOP_MONITORING -> SentinelService.ACTION_STOP_MONITORING
                            ProtocolContract.Paths.RESET_PEAKS -> SentinelService.ACTION_RESET_PEAKS
                            ProtocolContract.Paths.DASHBOARD_START -> SentinelService.ACTION_DASHBOARD_START
                            ProtocolContract.Paths.DASHBOARD_STOP -> SentinelService.ACTION_DASHBOARD_STOP
                            ProtocolContract.Paths.SYNC_POLICY_UPDATE -> SentinelService.ACTION_UPDATE_SYNC_POLICY
                            else -> null
                        }
                        if (path == ProtocolContract.Paths.SYNC_POLICY_UPDATE) {
                            val highSpeed = messageEvent.data.getOrNull(0)?.toInt() == 1
                            putExtra("high_speed", highSpeed)
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

                ProtocolContract.Paths.FULL_SYNC_REQUEST -> {
                    // Phase 8: Handshake Fix (v33.2)
                    // If the main service is running, let it handle the request.
                    // If NOT, send an immediate fallback response so the Mobile UI doesn't hang.
                    if (SentinelService.isRunning) {
                        val intent = Intent(applicationContext, SentinelService::class.java).apply {
                            action = SentinelService.ACTION_FORCE_SYNC
                        }
                        startService(intent)
                    } else {
                        Log.d(TAG, "⚡ Service not running, sending fallback inactive status")
                        sendImmediateSensorStatus()
                    }
                }

                ProtocolContract.Paths.SENSOR_STATUS_REQUEST -> {
                    Log.d(TAG, "⚡ Fast Path: Responding to Sensor Status Request")
                    sendImmediateSensorStatus()
                }

                ProtocolContract.Paths.SIMULATE_HARD_BRAKE,
                ProtocolContract.Paths.SIMULATE_FRONTAL,
                ProtocolContract.Paths.SIMULATE_REAR,
                ProtocolContract.Paths.SIMULATE_SIDE,
                ProtocolContract.Paths.SIMULATE_ROLLOVER,
                ProtocolContract.Paths.SIMULATE_PLUNGE,
                ProtocolContract.Paths.SIMULATE_RANDOM -> {
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
                    startForegroundService(intent)
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

    private fun sendImmediateSensorStatus() {
        serviceScope.launch {
            try {
                val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                
                fun check(type: Int): String = 
                    if (sensorManager.getDefaultSensor(type) != null) SensorStatus.AVAILABLE.name else SensorStatus.MISSING.name

                val putDataReq = PutDataMapRequest.create(ProtocolContract.Paths.STATUS_SYNC).apply {
                    val isActive = SentinelService.isRunning && SentinelService.lastKnownState != IncidentState.IDLE
                    dataMap.putBoolean(ProtocolContract.Keys.IS_ACTIVE, isActive)
                    dataMap.putLong(ProtocolContract.Keys.TIMESTAMP, System.currentTimeMillis())
                    
                    dataMap.putString(ProtocolContract.Keys.ACCEL_STATUS, check(Sensor.TYPE_ACCELEROMETER))
                    dataMap.putString(ProtocolContract.Keys.GYRO_STATUS, check(Sensor.TYPE_GYROSCOPE))
                    dataMap.putString(ProtocolContract.Keys.PRESS_STATUS, check(Sensor.TYPE_PRESSURE))
                    dataMap.putString(ProtocolContract.Keys.ROT_STATUS, check(Sensor.TYPE_ROTATION_VECTOR))
                    dataMap.putString(ProtocolContract.Keys.WATCH_GPS_STATUS, SensorStatus.UNAVAILABLE.name)
                    dataMap.putString(ProtocolContract.Keys.WATCH_GPS_TEXT, "Searching...")
                    
                    setUrgent()
                }.asPutDataRequest()
                Wearable.getDataClient(this@WatchMessageService).putDataItem(putDataReq).await()
                Log.d(TAG, "✅ Immediate Sensor Status Sent (Fast Path)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send immediate sensor status", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
