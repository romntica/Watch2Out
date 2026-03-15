// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class IncidentState {
    IDLE,           // Monitoring stopped
    MONITORING,     // Actively monitoring sensors
    TRIGGERED,      // Threshold crossed, freezing buffer, recording audio
    PRE_ALERT,      // User countdown active
    DISPATCHING,    // Sending emergency signals
    CONFIRMED,      // Help requested, waiting for dismissal
    ERROR           // Sensor or system failure
}
