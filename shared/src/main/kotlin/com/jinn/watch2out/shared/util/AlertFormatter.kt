// [Module: :shared]
package com.jinn.watch2out.shared.util

import com.jinn.watch2out.shared.model.IncidentData
import com.jinn.watch2out.shared.model.TelemetryPoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility to format emergency messages for SMS and Email.
 * Includes sensor data summaries for technical analysis.
 */
object AlertFormatter {
    
    fun formatEmailSubject(type: String, timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        return "🚨 WWOUT EMERGENCY: $type @ ${sdf.format(Date(timestamp))}"
    }

    /**
     * Formats the detailed body for emergency emails.
     */
    fun formatEmailBody(data: IncidentData): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val time = sdf.format(Date(data.timestamp))
        val locStr = if (data.latitude != null && data.longitude != null) {
            "Coordinates: ${String.format(Locale.US, "%.6f", data.latitude)}, ${String.format(Locale.US, "%.6f", data.longitude)}\n" +
            "Google Maps: https://www.google.com/maps/search/?api=1&query=${data.latitude},${data.longitude}"
        } else "Location: Unavailable"

        val sensorSummary = formatSensorSummary(data.sensorData)

        return """
            [URGENT] Watch2Out Safety Alert
            
            An accident has been detected for the user.
            
            Incident Details:
            - Type: ${data.type}
            - Time: $time
            - Peak Force: ${String.format(Locale.US, "%.1f", data.maxG)} G
            - Speed at Impact: ${String.format(Locale.US, "%.1f", data.speed * 3.6f)} km/h
            
            Location:
            - $locStr
            
            $sensorSummary
            
            Please contact the user immediately. If no response, notify local emergency services.
        """.trimIndent()
    }

    private fun formatSensorSummary(points: List<TelemetryPoint>): String {
        if (points.isEmpty()) return "Sensor Snapshot: N/A"
        
        // Take representative points
        val step = maxOf(1, points.size / 10)
        val summary = points.filterIndexed { i, _ -> i % step == 0 }.takeLast(10)
        
        val sb = StringBuilder("Technical Impact Timeline (Last 5-10s):\n")
        summary.forEach { p ->
            val indicator = if (p.mag >= 3.0f) " [CRITICAL]" else ""
            sb.append("- Force: ${String.format(Locale.US, "%.1f", p.mag)} G$indicator\n")
        }
        return sb.toString()
    }

    /**
     * Requirement: 40 chars max.
     * Logic: Extracts core incident type (e.g., FRONTAL) from full description.
     */
    fun formatSms(type: String, timestamp: Long, lat: Double?, lon: Double?): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        
        // Extract core type
        val coreType = type.split(":").lastOrNull()?.trim() 
            ?: type.replace("Simulated ", "").replace(" Collision", "").split(" ").lastOrNull() 
            ?: type
            
        val latStr = if (lat != null) String.format(Locale.US, "%.4f", lat) else "?"
        val lonStr = if (lon != null) String.format(Locale.US, "%.4f", lon) else "?"
        
        // Structure: WWOUT! [Type] [Time] [Lat],[Lon]
        val msg = "WWOUT! $coreType ${sdf.format(Date(timestamp))} $latStr,$lonStr"
        
        return if (msg.length <= 40) msg else "WWOUT! $latStr,$lonStr".take(40)
    }
}
