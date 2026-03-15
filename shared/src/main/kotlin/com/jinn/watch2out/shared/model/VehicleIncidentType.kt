// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Classification of the detected vehicle incident.
 */
@Serializable
enum class VehicleIncidentType {
    FRONTAL,        // Impact from the front
    REAR_END,       // Impact from the rear (includes stationary rear-end)
    SIDE,           // Side impact (T-bone)
    ROLLOVER,       // Vehicle rollover detected by rotation and gravity vector
    FALL_PLUNGE,    // Vehicle falling or running off-road
    MULTI_IMPACT,   // Multiple impacts or complex crash sequence
    NONE            // No specific incident type identified
}
