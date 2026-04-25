// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Enumerates the physical or logic-based status of a hardware sensor.
 */
@Serializable
enum class SensorStatus {
    AVAILABLE,
    DISABLED,
    UNAVAILABLE,
    MISSING,
    UNKNOWN,

    // GPS Specific Fix States (v28.5.4)
    NO_FIX,
    FIX_2D,
    FIX_3D,
    LOW_ACC
}
