// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Detection modes for simulation and operational logic.
 */
@Serializable
enum class SimulationDetectionMode {
    AUTO, VEHICLE
}

/**
 * Emergency contact entry with specific dispatch preferences.
 */
@Serializable
data class EmergencyContact(
    val name: String = "",
    val phoneNumber: String = "",
    val enableSms: Boolean = true,
    val enableCall: Boolean = false
)

/**
 * Configuration for the Sentinel service and emergency dispatch.
 * v27.0: Updated with explicit CrashScore v27 weights and ranges.
 * FSM v5.0 thresholds added.
 */
@Serializable
data class WatchSettings(
    // 1. Accel Threshold: 8G ~ 20G (Default 10G)
    val accelThresholdG: Float = 10.0f,
    // 2. Gyro Threshold: 100 ~ 500°/s (Default 200°/s)
    val gyroThresholdDeg: Float = 200.0f,
    // 3. Baro Threshold: 1.0 ~ 5.0hPa (Default 2.5hPa)
    val pressureThresholdHpa: Float = 2.5f,
    // 4. Speed Delta (Δv): 10 ~ 50km/h (Default 20km/h)
    val speedDeltaKmh: Float = 20.0f,
    // 5. Stillness Duration: 3 ~ 15s (Default 8s)
    val stillnessDurationMs: Long = 8000L,
    // 6. Crash Score: 0.5 ~ 0.9 (Default 0.7)
    val crashScoreThreshold: Float = 0.7f,

    // --- CrashScore v27 Specific ---
    val accelMinG: Float = 5.0f,
    val accelMaxG: Float = 15.0f,
    val speedMinKmh: Float = 20.0f,
    val speedDeltaMaxKmh: Float = 40.0f,
    val gyroMaxDegPerSec: Float = 500.0f,
    val pressureMaxHpa: Float = 5.0f,
    val stillMaxSec: Float = 15.0f,
    
    val wAccel: Float = 0.30f,
    val wSpeed: Float = 0.25f,
    val wGyro: Float = 0.15f,
    val wPress: Float = 0.10f,
    val wStill: Float = 0.15f,
    val wRoll: Float = 0.05f,

    val bufferSeconds: Int = 10,
    val samplingRateMs: Int = 100,
    val isSimulationMode: Boolean = false,
    val forcedDetectionMode: SimulationDetectionMode = SimulationDetectionMode.AUTO,
    
    val isAutoStartEnabled: Boolean = true,
    val useWatchDirectDispatch: Boolean = false,
    
    val contacts: List<EmergencyContact> = emptyList(),
    val isSmsEnabled: Boolean = true,
    val smsRecipient: String = "",
    val isCallEnabled: Boolean = false,
    val callRecipient: String = "",
    
    // --- FSM v5.0 Specific Thresholds ---
    val movingSpeedThresholdKmh: Float = 15.0f, // IDLE -> MOVING (GPS Speed > 15 km/h)
    val stillnessSpeedThresholdKmh: Float = 1.0f, // MOVING -> IDLE (Stillness < 1 km/h)
    val stillnessDurationMin: Long = 10L, // MOVING -> IDLE (Stillness > 10 min)
    val preEventImpactThresholdG: Float = 5.0f, // IDLE/MOVING -> PRE_EVENT (External Force >5G)
    val preEventDeltaVThresholdKmh: Float = 10.0f, // MOVING -> PRE_EVENT (Instability / Hard Braking - DeltaV part)
    val impactDeltaVThresholdKmh: Float = 15.0f, // PRE_EVENT -> IMPACT (DeltaV component)
    val impactRotationThresholdDeg: Float = 200.0f, // PRE_EVENT -> IMPACT (Rotation component)
    val alertTimeoutMs: Long = 15000L, // WAIT_CONFIRM -> CONFIRMED_CRASH (Timeout 15s)

    // Dynamic Adaptive Sampling Thresholds (from AGENTS.md)
    val highSpeedThresholdKmh: Float = 80.0f, // 80+ km/h (50-100ms interval)
    val normalSpeedThresholdKmh: Float = 30.0f, // 30-80 km/h (100-200ms interval)
    val lowSpeedThresholdKmh: Float = 10.0f, // 10-30 km/h (200ms interval)

    // --- Telemetry Logging (v27.4) ---
    val isTelemetryLoggingEnabled: Boolean = false
)
