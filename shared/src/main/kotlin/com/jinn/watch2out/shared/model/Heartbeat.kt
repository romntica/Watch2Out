// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Phase 1: GPS Fusion Heartbeat contract.
 * Used for status synchronization between Phone and Watch.
 * v27.7.2: Added isSpeedHintReliable to separate UI status from Control signaling.
 */
@Serializable
data class Heartbeat(
    val fsmState: VehicleInferenceState,
    val crashScore: Float,
    val batteryLevel: Float,
    
    // Phase 1: Phone GPS Status
    val phoneGpsStatus: PhoneGpsStatus = PhoneGpsStatus.NO_FIX,
    val phoneGpsAccuracy: Float = 0f,
    val phoneSpeedKmh: Float = 0f,
    
    /** 
     * If true, speedZone and phoneSpeedKmh are high-accuracy and fresh (<25m, <2s).
     * If false, phoneGpsStatus might still be AVAILABLE (UI only).
     */
    val isSpeedHintReliable: Boolean = false,
    
    val speedZone: Int? = null  // 0: 0-12, 1: 12-32, 2: 32-82, 3: 82+ km/h
) {
    /**
     * Determines the adaptive sampling interval based on the current speed zone.
     * Complies with AGENTS.md v27.6: Only use hint if reliable.
     */
    fun getAdaptiveIntervalMs(): Long {
        if (!isSpeedHintReliable || speedZone == null) return 450L // Default Power Save if unreliable
        
        return when (speedZone) {
            0 -> 450L  // 0-12 km/h
            1 -> 200L  // 12-32 km/h
            2 -> 150L  // 32-82 km/h
            3 -> 75L   // 82+ km/h: High-Res
            else -> 450L
        }
    }
}
