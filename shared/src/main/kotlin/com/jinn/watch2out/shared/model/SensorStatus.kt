// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Enumerates the physical or logic-based status of a hardware sensor.
 */
@Serializable
enum class SensorStatus {
    /** Sensor is available and functioning. */
    AVAILABLE,
    /** Sensor exists but is disabled via user settings. */
    DISABLED,
    /** Sensor is temporarily unavailable or busy. */
    UNAVAILABLE,
    /** Physical hardware sensor is missing on this device. */
    MISSING,
    /** Connection to the remote node is lost. */
    UNKNOWN
}
