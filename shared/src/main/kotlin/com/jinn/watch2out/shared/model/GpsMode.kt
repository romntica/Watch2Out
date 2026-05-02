// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * GPS operation strategy.
 */
@Serializable
enum class GpsMode {
    /** Watch GPS is OFF, using speed hints from Phone via Bluetooth. */
    PHONE_PRIMARY,
    
    /** Watch GPS operates independently. */
    WATCH_ONLY,

    /** Watch using both GPS and Network (Supplemental). */
    WATCH_HYBRID,

    /** Watch using only Network (Fallback). */
    WATCH_NETWORK_ONLY
}
