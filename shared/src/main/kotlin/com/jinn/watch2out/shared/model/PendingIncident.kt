// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Metadata for an incident that is waiting to be transferred to the Mobile companion.
 * Used for fail-safe retries (v32.0).
 */
@Serializable
data class PendingIncident(
    val id: String,           // Unique ID (typically timestamp)
    val edrJson: String,      // The full incident report JSON
    val audioPath: String?,   // Absolute path to the recorded audio file on Wear storage
    val retryCount: Int = 0,
    val lastAttempt: Long = 0L
)
