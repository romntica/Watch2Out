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
 * Configuration for the Sentinel service and emergency dispatch.
 * Includes SMS, Email and Call settings.
 */
@Serializable
data class WatchSettings(
    val isAccelEnabled: Boolean = true,
    val accelThresholdG: Float = 15.0f,
    val isGyroEnabled: Boolean = true,
    val gyroThresholdDeg: Float = 300.0f,
    val isPressureEnabled: Boolean = true,
    val pressureThresholdHpa: Float = 1.5f,
    val bufferSeconds: Int = 10,
    val samplingRateMs: Int = 100,
    val isSimulationMode: Boolean = false,
    val forcedDetectionMode: SimulationDetectionMode = SimulationDetectionMode.AUTO,
    
    // Auto-Start Policy
    val isAutoStartEnabled: Boolean = true,
    
    // Notification Dispatch Settings
    val useWatchDirectDispatch: Boolean = false,
    
    // Notification Channels & Recipients
    val isSmsEnabled: Boolean = true,
    val smsRecipient: String = "",
    val isEmailEnabled: Boolean = false,
    val emailRecipient: String = "",
    val isCallEnabled: Boolean = false,
    val callRecipient: String = ""
)
