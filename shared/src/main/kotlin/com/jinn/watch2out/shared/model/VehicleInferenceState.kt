// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Enhanced FSM for incident detection (v24.5).
 */
@Serializable
enum class VehicleInferenceState {
    IDLE,               // Monitoring active, no significant motion
    MOVING,             // Sustained movement detected (>15km/h)
    PRE_EVENT,          // Universal gateway for potential incidents (External force >5G)
    FALLING,            // Low-G state detected (<0.3G)
    IMPACT,             // Confirmed high-G impact with sensor fusion
    POST_MOTION,        // Post-impact analysis (Rolling/Rotation)
    STILLNESS,          // Movement ceased, evaluating crash severity
    WAIT_CONFIRM,       // Severity verified, awaiting user response
    CONFIRMED_CRASH     // Crash confirmed (Timeout or Manual)
}
