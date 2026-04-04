// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Detailed status of Phone GPS to manage fallback timing.
 */
@Serializable
enum class PhoneGpsStatus {
    /** GPS is ON and providing high-accuracy data. */
    AVAILABLE,
    
    /** User has manually disabled GPS on Phone. */
    OFF_BY_USER,
    
    /** App does not have location permissions on Phone. */
    PERMISSION_DENIED,
    
    /** GPS is ON but cannot get a fix (e.g., indoors or cold start). */
    NO_FIX
}
