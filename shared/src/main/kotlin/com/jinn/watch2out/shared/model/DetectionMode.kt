// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Enumeration of detection contexts.
 * VEHICLE: Deceleration, rollover, and plunge focused logic.
 */
@Serializable
enum class DetectionMode {
    VEHICLE
}
