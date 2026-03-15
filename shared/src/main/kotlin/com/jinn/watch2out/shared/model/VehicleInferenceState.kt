// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Detailed state machine for vehicle crash inference.
 */
@Serializable
enum class VehicleInferenceState {
    IDLE,               // Monitoring is active but no driving session detected
    DRIVING,            // Driving session active (includes both moving and temporary stationary states)
    PRE_IMPACT,         // Pre-impact indicators detected (e.g., emergency braking, high RMS)
    PLUNGING,           // Vehicle is in free-fall (low-G)
    IMPACT_DETECTED,    // High-G impact event occurred
    POST_IMPACT_MOTION, // Analyzing post-impact behavior (e.g., rollovers, secondary impacts)
    STILLNESS           // Movement has ceased, verifying user safety
}
