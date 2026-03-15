// [Module: :shared]
package com.jinn.watch2out.shared.model

import kotlinx.serialization.Serializable

/**
 * Snapshot of processed sensor metrics and system state.
 * Refined terminology and added GPS data for precision.
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

    // Live Data
    val accelX: Float = Float.NaN,
    val accelY: Float = Float.NaN,
    val accelZ: Float = Float.NaN,
    val gyroX: Float = Float.NaN,
    val gyroY: Float = Float.NaN,
    val gyroZ: Float = Float.NaN,
    val rotationX: Float = Float.NaN,
    val rotationY: Float = Float.NaN,
    val rotationZ: Float = Float.NaN,
    val airPressure: Float = Float.NaN,
    val rotationSpeed: Float = 0f,
    val pressureDelta: Float = 0f,
    val tiltAngle: Float = 0f,
    val gpsSpeed: Float = 0f, // Added GPS speed in m/s

    // Snapshot at Overall Peak Magnitude (P)
    val pTimestamp: Long = 0L,
    val pAccelX: Float = Float.NaN,
    val pAccelY: Float = Float.NaN,
    val pAccelZ: Float = Float.NaN,
    val pGyroX: Float = Float.NaN,
    val pGyroY: Float = Float.NaN,
    val pGyroZ: Float = Float.NaN,
    val pRotationX: Float = Float.NaN,
    val pRotationY: Float = Float.NaN,
    val pRotationZ: Float = Float.NaN,
    val pAirPressure: Float = Float.NaN,
    val pTiltAngle: Float = 0f,
    val pRotationSpeed: Float = 0f,
    val pGpsSpeed: Float = 0f,

    // Snapshot at Window Peak Magnitude (W)
    val wTimestamp: Long = 0L,
    val wAccelX: Float = Float.NaN,
    val wAccelY: Float = Float.NaN,
    val wAccelZ: Float = Float.NaN,
    val wGyroX: Float = Float.NaN,
    val wGyroY: Float = Float.NaN,
    val wGyroZ: Float = Float.NaN,
    val wRotationX: Float = Float.NaN,
    val wRotationY: Float = Float.NaN,
    val wRotationZ: Float = Float.NaN,
    val wAirPressure: Float = Float.NaN,
    val wTiltAngle: Float = 0f,
    val wRotationSpeed: Float = 0f,
    val wGpsSpeed: Float = 0f
)
