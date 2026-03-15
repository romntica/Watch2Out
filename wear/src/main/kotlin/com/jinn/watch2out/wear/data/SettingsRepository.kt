// [Module: :wear]
package com.jinn.watch2out.wear.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jinn.watch2out.shared.model.SimulationDetectionMode
import com.jinn.watch2out.shared.model.WatchSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val IS_ACCEL_ENABLED = booleanPreferencesKey("is_accel_enabled")
        val ACCEL_THRESHOLD_G = floatPreferencesKey("accel_threshold_g")
        val IS_GYRO_ENABLED = booleanPreferencesKey("is_gyro_enabled")
        val GYRO_THRESHOLD_DEG = floatPreferencesKey("gyro_threshold_deg")
        val IS_PRESSURE_ENABLED = booleanPreferencesKey("is_pressure_enabled")
        val PRESSURE_THRESHOLD_HPA = floatPreferencesKey("pressure_threshold_hpa")
        
        val BUFFER_SECONDS = intPreferencesKey("buffer_seconds")
        val SAMPLING_RATE_MS = intPreferencesKey("sampling_rate_ms")
        val SIMULATION_MODE = booleanPreferencesKey("simulation_mode")
        val FORCED_DETECTION_MODE = stringPreferencesKey("forced_detection_mode")
        
        val IS_AUTO_START_ENABLED = booleanPreferencesKey("is_auto_start_enabled")
        val USE_WATCH_DIRECT_DISPATCH = booleanPreferencesKey("use_watch_direct_dispatch")
        
        val IS_SMS_ENABLED = booleanPreferencesKey("is_sms_enabled")
        val SMS_RECIPIENT = stringPreferencesKey("sms_recipient")
        val IS_CALL_ENABLED = booleanPreferencesKey("is_call_enabled")
        val CALL_RECIPIENT = stringPreferencesKey("call_recipient")
    }

    val settingsFlow: Flow<WatchSettings> = context.dataStore.data.map { prefs ->
        WatchSettings(
            isAccelEnabled = prefs[Keys.IS_ACCEL_ENABLED] ?: true,
            accelThresholdG = prefs[Keys.ACCEL_THRESHOLD_G] ?: 15.0f,
            isGyroEnabled = prefs[Keys.IS_GYRO_ENABLED] ?: true,
            gyroThresholdDeg = prefs[Keys.GYRO_THRESHOLD_DEG] ?: 300.0f,
            isPressureEnabled = prefs[Keys.IS_PRESSURE_ENABLED] ?: true,
            pressureThresholdHpa = prefs[Keys.PRESSURE_THRESHOLD_HPA] ?: 1.5f,
            bufferSeconds = prefs[Keys.BUFFER_SECONDS] ?: 10,
            samplingRateMs = prefs[Keys.SAMPLING_RATE_MS] ?: 100,
            isSimulationMode = prefs[Keys.SIMULATION_MODE] ?: false,
            forcedDetectionMode = SimulationDetectionMode.valueOf(
                prefs[Keys.FORCED_DETECTION_MODE] ?: SimulationDetectionMode.AUTO.name
            ),
            isAutoStartEnabled = prefs[Keys.IS_AUTO_START_ENABLED] ?: true,
            useWatchDirectDispatch = prefs[Keys.USE_WATCH_DIRECT_DISPATCH] ?: false,
            isSmsEnabled = prefs[Keys.IS_SMS_ENABLED] ?: true,
            smsRecipient = prefs[Keys.SMS_RECIPIENT] ?: "",
            isCallEnabled = prefs[Keys.IS_CALL_ENABLED] ?: false,
            callRecipient = prefs[Keys.CALL_RECIPIENT] ?: ""
        )
    }

    suspend fun updateSettings(settings: WatchSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_ACCEL_ENABLED] = settings.isAccelEnabled
            prefs[Keys.ACCEL_THRESHOLD_G] = settings.accelThresholdG
            prefs[Keys.IS_GYRO_ENABLED] = settings.isGyroEnabled
            prefs[Keys.GYRO_THRESHOLD_DEG] = settings.gyroThresholdDeg
            prefs[Keys.IS_PRESSURE_ENABLED] = settings.isPressureEnabled
            prefs[Keys.PRESSURE_THRESHOLD_HPA] = settings.pressureThresholdHpa
            prefs[Keys.BUFFER_SECONDS] = settings.bufferSeconds
            prefs[Keys.SAMPLING_RATE_MS] = settings.samplingRateMs
            prefs[Keys.SIMULATION_MODE] = settings.isSimulationMode
            prefs[Keys.FORCED_DETECTION_MODE] = settings.forcedDetectionMode.name
            prefs[Keys.IS_AUTO_START_ENABLED] = settings.isAutoStartEnabled
            prefs[Keys.USE_WATCH_DIRECT_DISPATCH] = settings.useWatchDirectDispatch
            prefs[Keys.IS_SMS_ENABLED] = settings.isSmsEnabled
            prefs[Keys.SMS_RECIPIENT] = settings.smsRecipient
            prefs[Keys.IS_CALL_ENABLED] = settings.isCallEnabled
            prefs[Keys.CALL_RECIPIENT] = settings.callRecipient
        }
    }
}
