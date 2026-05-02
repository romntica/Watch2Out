// [Module: :app]
package com.jinn.watch2out.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationAvailability
import com.jinn.watch2out.shared.model.PhoneGpsStatus

/**
 * Manages Phone GPS data for Sentinel Watch fusion.
 * v27.7.2: Separates UI Availability (Hysteresis) from Control Reliability (Strict).
 */
class PhoneGpsManager(private val context: Context) {

    private var lastZone: Int = 0
    private var wasLastReliable: Boolean = false
    private var lastReliableTimestamp: Long = 0

    companion object {
        // Policy: Watch GPS Only (v27.7.3)
        // Set to true to completely deactivate Phone GPS collection and usage.
        const val IS_RESERVED_MODE = false

        // UI Hysteresis
        private const val UI_ACQUIRE_THRESHOLD = 25f
        private const val UI_RELEASE_THRESHOLD = 60f
        private const val UI_GRACE_PERIOD_MS = 5000L

        // Control Reliability (Strict)
        private const val HINT_MAX_ACCURACY = 35f // Relaxed from 25f for bus stability
        private const val HINT_MAX_AGE_MS = 5000L // Increased from 2s to 5s for vehicle environments
    }

    data class GpsResult(
        val status: PhoneGpsStatus,
        val isHintReliable: Boolean,
        val debugReason: String
    )

    /**
     * Checks Phone GPS status with two-level classification.
     */
    fun checkStatus(location: Location?, availability: LocationAvailability? = null): GpsResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = SystemClock.elapsedRealtime()
        
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val isLocationEnabled = LocationManagerCompat.isLocationEnabled(lm)

        if (!hasPermission) return GpsResult(PhoneGpsStatus.PERMISSION_DENIED, false, "PERM_DENIED")
        if (!isLocationEnabled) return GpsResult(PhoneGpsStatus.OFF_BY_USER, false, "LOC_DISABLED")
        if (location == null) return GpsResult(PhoneGpsStatus.NO_FIX, false, "NO_LOCATION")
        
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

        // 1. UI Availability (with Hysteresis)
        val uiThreshold = if (wasLastReliable) UI_RELEASE_THRESHOLD else UI_ACQUIRE_THRESHOLD
        val isCurrentlyUiValid = location.accuracy < uiThreshold && elapsedMs < 5000 && !isMock
        
        if (isCurrentlyUiValid) {
            lastReliableTimestamp = now
        }
        
        val isInUiGracePeriod = wasLastReliable && (now - lastReliableTimestamp < UI_GRACE_PERIOD_MS)
        val uiStatus = if (isCurrentlyUiValid || isInUiGracePeriod) PhoneGpsStatus.AVAILABLE else PhoneGpsStatus.NO_FIX

        // 2. Control Hint Reliability (Strict, No Hysteresis, No Grace Period)
        // AGENTS.md Rule 5 Compliance: Only trust if fresh and accurate.
        val isHintReliable = location.accuracy <= HINT_MAX_ACCURACY && 
                             elapsedMs <= HINT_MAX_AGE_MS && 
                             !isMock &&
                             !isInUiGracePeriod // Never trust grace period for control

        wasLastReliable = (uiStatus == PhoneGpsStatus.AVAILABLE)

        val debugReason = when {
            isHintReliable -> "HINT_VALID"
            isCurrentlyUiValid -> "UI_ONLY(ACC)"
            isInUiGracePeriod -> "UI_ONLY(GRACE)"
            else -> "STALE/WEAK"
        }

        logDebug(uiStatus, isHintReliable, location, elapsedMs, debugReason)
        
        return GpsResult(uiStatus, isHintReliable, debugReason)
    }

    private fun logDebug(status: PhoneGpsStatus, hint: Boolean, loc: Location, age: Long, reason: String) {
        Log.d("PhoneGpsDebug", "ui=$status, hint=$hint, zone=${if (hint) "LIVE" else "INVALID"}, res=$reason | acc=${loc.accuracy}m, age=${age}ms")
    }

    /**
     * Calculates Speed Zone with Hysteresis.
     * v27.7.2: returns null if not hint-reliable.
     */
    fun calculateSpeedZone(location: Location?, isReliable: Boolean): Int? {
        if (!isReliable || location == null || !location.hasSpeed()) return null
        
        val speedKmh = location.speed * 3.6f
        val currentZone = when {
            speedKmh >= 82f -> 3
            speedKmh >= 32f -> 2
            speedKmh >= 12f -> 1
            else -> 0
        }

        return if (currentZone < lastZone) {
            if (speedKmh > (getZoneThreshold(lastZone) - 5f)) lastZone else currentZone
        } else {
            currentZone
        }.also {
            lastZone = it
        }
    }

    private fun getZoneThreshold(zone: Int): Float = when(zone) {
        3 -> 82f
        2 -> 32f
        1 -> 12f
        else -> 0f
    }
}
