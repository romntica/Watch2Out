// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * A single entry in the telemetry log for post-trip analysis.
 * Matches the 12-channel sensor buffer in SentinelService.
 */
@Serializable
data class TelemetryLogEntry(
    val timestamp: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val pressure: Float,
    val rx: Float,
    val ry: Float,
    val rz: Float,
    val rw: Float,
    val speed: Float,
    val crashScore: Float = 0f,
    val fsmState: String = ""
)

/**
 * A batch of telemetry log entries.
 */
@Serializable
data class TelemetryLogBatch(
    val entries: List<TelemetryLogEntry>,
    val deviceId: String = "watch"
)
