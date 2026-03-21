// [Module: :shared]
package com.jinn.watch2out.shared.network

/**
 * Defines the synchronization contract between Mobile and Wear modules.
 * Strictly versioned to ensure safe evolution of the communication layer.
 */
object ProtocolContract {
    
    /**
     * Current Protocol Version. 
     * Incremented whenever breaking changes are made to the message payload structure.
     */
    const val VERSION = 1

    /**
     * Paths for MessageClient communication.
     */
    object Paths {
        private const val BASE = "/watch2out/v$VERSION"
        
        // Remote Control
        const val START_MONITORING = "$BASE/control/start"
        const val STOP_MONITORING = "$BASE/control/stop"
        const val RESET_PEAKS = "$BASE/control/reset_peaks"
        const val UPDATE_SETTINGS = "$BASE/control/update_settings"
        
        // Dashboard Control (v22.0)
        const val DASHBOARD_START = "$BASE/dashboard/start"
        const val DASHBOARD_STOP = "$BASE/dashboard/stop"
        
        // Simulation Commands
        const val SIMULATE_FRONTAL = "$BASE/sim/frontal"
        const val SIMULATE_REAR = "$BASE/sim/rear"
        const val SIMULATE_SIDE = "$BASE/sim/side"
        const val SIMULATE_ROLLOVER = "$BASE/sim/rollover"
        const val SIMULATE_PLUNGE = "$BASE/sim/plunge"
        const val INJECT_CUSTOM_SENSOR = "$BASE/sim/inject_custom"
        
        // Data Synchronization
        const val SETTINGS_SYNC = "$BASE/sync/settings"
        const val STATUS_SYNC = "$BASE/sync/status" // Added for real-time state sync (v22.0)
        const val REQUEST_SETTINGS = "$BASE/sync/request_settings"
        
        // Incident Reporting & Sync
        const val INCIDENT_REPORT = "$BASE/report/incident"
        const val INCIDENT_ALERT_START = "$BASE/incident/alert_start"
        const val INCIDENT_ALERT_DISMISS = "$BASE/incident/alert_dismiss"
        const val INCIDENT_ALERT_DISMISS_ACK = "$BASE/incident/alert_dismiss_ack"
    }
    
    /**
     * Metadata keys for DataMap items.
     */
    object Keys {
        const val PROTOCOL_VERSION = "protocol_version"
        const val TIMESTAMP = "timestamp"
        const val SETTINGS_JSON = "settings_json"
        const val TELEMETRY_JSON = "telemetry_json"
        const val IS_ACTIVE = "is_active"
        const val ACCEL_STATUS = "accel_status"
        const val GYRO_STATUS = "gyro_status"
        const val PRESS_STATUS = "press_status"
        const val ROT_STATUS = "rot_status"
        const val INCIDENT_JSON = "incident_json"
        const val CUSTOM_SENSOR_DATA = "custom_sensor_data"
        const val INCIDENT_REASON = "incident_reason"
    }
}
