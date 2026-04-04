// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Snapshot of processed sensor metrics and system state.
 * Updated to v27.0 requirements: includes advanced scoring metrics for debugging and tuning.
 */
@Serializable
data class TelemetryState(
    val currentImpact: Float = 1.0f,
    val maxImpact: Float = 1.0f,
    val peakFallScore: Int = 0,
    val peakCrashScore: Int = 0,
    val windowImpact: Float = 1.0f,
    val windowFallScore: Int = 0,
    val windowCrashScore: Int = 0,
    val currentMode: DetectionMode = DetectionMode.VEHICLE,
    
    // Inference State
    val vehicleInferenceState: VehicleInferenceState = VehicleInferenceState.IDLE,
    val detectedVehicleIncident: VehicleIncidentType = VehicleIncidentType.NONE,

    // Sync Metadata
    val lastUpdateTime: Long = 0L,
    val sessionStartTime: Long = 0L,

    // Live Data
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val airPressure: Float = 0f,
    val rotationSpeed: Float = 0f,
    val pressureDelta: Float = 0f,
    val tiltAngle: Float = 0f,
    val gpsSpeed: Float = 0f,
    val crashScore: Float = 0f,
    val gyroRatio: Float = 0f,
    val rollSum: Float = 0f,
    val sensorConfidence: Float = 1.0f,
    
    // Phase 4: Fusion GPS Details (v27.6)
    val isGpsActive: Boolean = false,      // System-wide GPS status (Any source)
    val isWatchGpsActive: Boolean = false, // Watch hardware status
    val isPhoneGpsActive: Boolean = false, // Phone link status
    val watchGpsAccuracy: Float = 0f,
    val phoneGpsAccuracy: Float = 0f,
    val activeGpsSource: GpsMode = GpsMode.WATCH_ONLY,

    // CrashScore v27 Debug & Tuning
    val bonusWeak: Float = 0f,
    val bonusFall: Float = 0f,
    val bonusImpact: Float = 0f,
    val nAccel: Float = 0f,
    val nSpeed: Float = 0f,
    val nGyro: Float = 0f,
    val nPress: Float = 0f,
    val nStill: Float = 0f,
    val nRoll: Float = 0f,
    val wAccel: Float = 0f,
    val wSpeed: Float = 0f,
    val wGyro: Float = 0f,
    val wPress: Float = 0f,
    val wStill: Float = 0f,
    val wRoll: Float = 0f,

    // Overall Peak Data (All-time or since reset)
    val pTimestamp: Long = 0L,
    val pAccelX: Float = 0f,
    val pAccelY: Float = 0f,
    val pAccelZ: Float = 0f,
    val pGyroX: Float = 0f,
    val pGyroY: Float = 0f,
    val pGyroZ: Float = 0f,
    val pAirPressure: Float = 0f,
    val pTiltAngle: Float = 0f,
    val pRotationSpeed: Float = 0f,
    val pGpsSpeed: Float = 0f,
    val pCrashScore: Float = 0f,
    val pGyroRatio: Float = 0f,
    val pRollSum: Float = 0f,
    val pPressureDelta: Float = 0f,
    val maxLongitudinalG: Float = 0f,
    val maxLateralG: Float = 0f,
    val maxSpeedDrop: Float = 0f,

    // Window Peak Data
    val wTimestamp: Long = 0L,
    val wAccelX: Float = 0f,
    val wAccelY: Float = 0f,
    val wAccelZ: Float = 0f,
    val wGyroX: Float = 0f,
    val wGyroY: Float = 0f,
    val wGyroZ: Float = 0f,
    val wAirPressure: Float = 0f,
    val wTiltAngle: Float = 0f,
    val wRotationSpeed: Float = 0f,
    val wGpsSpeed: Float = 0f,
    val wCrashScore: Float = 0f,
    val wGyroRatio: Float = 0f,
    val wRollSum: Float = 0f,
    val wPressureDelta: Float = 0f,
    val wMaxLongitudinalG: Float = 0f,
    val wMaxLateralG: Float = 0f,
    val wMaxSpeedDrop: Float = 0f
)
