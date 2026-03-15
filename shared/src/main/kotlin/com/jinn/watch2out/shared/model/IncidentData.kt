// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Detailed report of a detected incident.
 */
@Serializable
data class IncidentData(
    val type: String,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maxG: Float = 0f,
    val speed: Float = 0f,
    val isSimulation: Boolean = false,
    val sensorData: List<TelemetryPoint> = emptyList()
)

@Serializable
data class TelemetryPoint(
    val t: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val mag: Float
)
