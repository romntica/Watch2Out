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

/**
 * Persists and provides access to WatchSettings.
 * v27.4: Added telemetry logging toggle.
 */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val ACCEL_THRESHOLD_G = floatPreferencesKey("accel_threshold_g")
        val GYRO_THRESHOLD_DEG = floatPreferencesKey("gyro_threshold_deg")
        val PRESSURE_THRESHOLD_HPA = floatPreferencesKey("pressure_threshold_hpa")
        
        val SPEED_DELTA_KMH = floatPreferencesKey("speed_delta_kmh")
        val STILLNESS_DURATION_MS = longPreferencesKey("stillness_duration_ms")
        val CRASH_SCORE_THRESHOLD = floatPreferencesKey("crash_score_threshold")

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

        // v27.4
        val IS_TELEMETRY_LOGGING_ENABLED = booleanPreferencesKey("is_telemetry_logging_enabled")

        // v34.2
        val USE_PHONE_GPS = booleanPreferencesKey("use_phone_gps")

        // Crash-Recovery (v28.6.5)
        val MONITORING_STATE = stringPreferencesKey("monitoring_state")
    }

    val settingsFlow: Flow<WatchSettings> = context.dataStore.data.map { prefs ->
        WatchSettings(
            accelThresholdG = prefs[Keys.ACCEL_THRESHOLD_G] ?: 10.0f,
            gyroThresholdDeg = prefs[Keys.GYRO_THRESHOLD_DEG] ?: 200.0f,
            pressureThresholdHpa = prefs[Keys.PRESSURE_THRESHOLD_HPA] ?: 2.5f,
            speedDeltaKmh = prefs[Keys.SPEED_DELTA_KMH] ?: 20.0f,
            stillnessDurationMs = prefs[Keys.STILLNESS_DURATION_MS] ?: 8000L,
            crashScoreThreshold = prefs[Keys.CRASH_SCORE_THRESHOLD] ?: 0.7f,
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
            callRecipient = prefs[Keys.CALL_RECIPIENT] ?: "",
            isTelemetryLoggingEnabled = prefs[Keys.IS_TELEMETRY_LOGGING_ENABLED] ?: false,
            usePhoneGps = prefs[Keys.USE_PHONE_GPS] ?: true
        )
    }

    suspend fun updateSettings(settings: WatchSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCEL_THRESHOLD_G] = settings.accelThresholdG
            prefs[Keys.GYRO_THRESHOLD_DEG] = settings.gyroThresholdDeg
            prefs[Keys.PRESSURE_THRESHOLD_HPA] = settings.pressureThresholdHpa
            prefs[Keys.SPEED_DELTA_KMH] = settings.speedDeltaKmh
            prefs[Keys.STILLNESS_DURATION_MS] = settings.stillnessDurationMs
            prefs[Keys.CRASH_SCORE_THRESHOLD] = settings.crashScoreThreshold
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
            prefs[Keys.IS_TELEMETRY_LOGGING_ENABLED] = settings.isTelemetryLoggingEnabled
            prefs[Keys.USE_PHONE_GPS] = settings.usePhoneGps
        }
    }

    val monitoringStateFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.MONITORING_STATE] ?: "IDLE"
    }

    suspend fun updateMonitoringState(state: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MONITORING_STATE] = state
        }
    }
}
