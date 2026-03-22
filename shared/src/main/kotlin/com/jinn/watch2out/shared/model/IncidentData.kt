// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Detailed report of a detected incident.
 * v23.6: Expanded TelemetryPoint with absolute timestamps.
 */
@Serializable
data class IncidentData(
    val type: String,
    val timestamp: Long,
    val utcTime: String = "", 
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maxG: Float = 0f,
    val speed: Float = 0f,
    val isSimulation: Boolean = false,
    val sensorData: List<TelemetryPoint> = emptyList()
)

@Serializable
data class TelemetryPoint(
    val t: Long,      // Absolute timestamp in ms (Wall clock)
    val offset: Long, // Offset in ms relative to impact
    val ax: Float,    // Accel X (g)
    val ay: Float,    // Accel Y (g)
    val az: Float,    // Accel Z (g)
    val gx: Float,    // Gyro X (deg/s)
    val gy: Float,    // Gyro Y (deg/s)
    val gz: Float,    // Gyro Z (deg/s)
    val spd: Float,   // GPS Speed (km/h)
    val mag: Float    // RMS Magnitude (g)
)
