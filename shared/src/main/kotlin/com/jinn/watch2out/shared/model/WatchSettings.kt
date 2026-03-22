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
 * Updated to v22.0: Removed Email support as per requirement.
 */
@Serializable
data class WatchSettings(
    val isAccelEnabled: Boolean = true,
    val accelThresholdG: Float = 15.0f,
    val longThresholdG: Float = 4.0f,
    val latThresholdG: Float = 3.0f,

    val isGyroEnabled: Boolean = true,
    val gyroThresholdDeg: Float = 300.0f,

    val isPressureEnabled: Boolean = true,
    val pressureThresholdHpa: Float = 1.5f,

    val speedThresholdKmh: Float = 15.0f,
    val stillnessDurationMs: Long = 3000L,

    val bufferSeconds: Int = 10,
    val samplingRateMs: Int = 100,
    val isSimulationMode: Boolean = false,
    val forcedDetectionMode: SimulationDetectionMode = SimulationDetectionMode.AUTO,
    
    val isAutoStartEnabled: Boolean = true,
    val useWatchDirectDispatch: Boolean = false,
    
    // Emergency Contacts List
    val contacts: List<EmergencyContact> = emptyList(),

    // Legacy support (Email removed)
    val isSmsEnabled: Boolean = true,
    val smsRecipient: String = "",
    val isCallEnabled: Boolean = false,
    val callRecipient: String = ""
)
