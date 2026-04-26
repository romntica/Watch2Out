// [Module: :shared]
package com.jinn.watch2out.shared.util

import com.jinn.watch2out.shared.model.VehicleInferenceState
import com.jinn.watch2out.shared.model.WatchSettings

/**
 * Centralized Finite State Machine for Incident Detection (v30.0).
 * Implements the "Gold Standard" logic as defined in AGENTS.md.
 */
object IncidentFsm {
    private const val TAG = "IncidentFSM"

    /**
     * Determines the next state based on current sensor inputs and configuration.
     */
    fun nextState(
        currentState: VehicleInferenceState,
        impactG: Float,
        speedKmh: Float,
        currentScore: Float,
        peakScore: Float,
        config: WatchSettings,
        isSimulating: Boolean = false,
        logger: ((String, String) -> Unit)? = null
    ): VehicleInferenceState {
        val nextState = when (currentState) {
            VehicleInferenceState.IDLE -> {
                if (speedKmh > config.movingSpeedThresholdKmh) {
                    VehicleInferenceState.MOVING
                } else if (impactG > config.preEventImpactThresholdG) {
                    VehicleInferenceState.PRE_EVENT
                } else {
                    VehicleInferenceState.IDLE
                }
            }

            VehicleInferenceState.MOVING -> {
                if (impactG > config.preEventImpactThresholdG) {
                    VehicleInferenceState.PRE_EVENT
                } else if (impactG < 0.3f) {
                    VehicleInferenceState.FALLING
                } else if (speedKmh < config.stillnessSpeedThresholdKmh) {
                    VehicleInferenceState.IDLE
                } else {
                    VehicleInferenceState.MOVING
                }
            }

            VehicleInferenceState.PRE_EVENT -> {
                // Gateway to IMPACT or FALLING
                if (impactG > config.accelThresholdG || currentScore > config.crashScoreThreshold) {
                    VehicleInferenceState.IMPACT
                } else if (impactG < 0.3f) {
                    VehicleInferenceState.FALLING
                } else if (impactG < 3.0f) { // FIX: Hysteresis. Only return to movement if G drops significantly.
                    if (speedKmh > config.movingSpeedThresholdKmh) VehicleInferenceState.MOVING else VehicleInferenceState.IDLE
                } else {
                    // Stay in PRE_EVENT if G-force is still elevated (3.0G - 10.0G)
                    VehicleInferenceState.PRE_EVENT
                }
            }

            VehicleInferenceState.FALLING -> {
                if (impactG > config.preEventImpactThresholdG) {
                    VehicleInferenceState.IMPACT
                } else if (impactG > 0.8f && impactG < 1.2f) { // Landed softly
                    if (speedKmh > config.movingSpeedThresholdKmh) VehicleInferenceState.MOVING else VehicleInferenceState.IDLE
                } else {
                    VehicleInferenceState.FALLING
                }
            }

            VehicleInferenceState.IMPACT -> {
                // Impact is a transient state, move to POST_MOTION for analysis
                VehicleInferenceState.POST_MOTION
            }

            VehicleInferenceState.POST_MOTION -> {
                // Analysis of rolling or debris motion
                if (speedKmh < config.stillnessSpeedThresholdKmh) {
                    VehicleInferenceState.STILLNESS
                } else {
                    VehicleInferenceState.POST_MOTION
                }
            }

            VehicleInferenceState.STILLNESS -> {
                // Movement ceased, evaluate if score warrants alert
                // v32.0: Use peakScore (memory) instead of instantaneous currentScore
                if (peakScore >= config.crashScoreThreshold) {
                    VehicleInferenceState.WAIT_CONFIRM
                } else if (speedKmh > config.movingSpeedThresholdKmh && !isSimulating) {
                    // False alarm or minor bump, resumed driving
                    VehicleInferenceState.MOVING
                } else {
                    VehicleInferenceState.STILLNESS
                }
            }

            VehicleInferenceState.WAIT_CONFIRM -> {
                // Logic for timeout to CONFIRMED_CRASH is handled by the service/timer
                VehicleInferenceState.WAIT_CONFIRM
            }

            VehicleInferenceState.CONFIRMED_CRASH -> {
                VehicleInferenceState.CONFIRMED_CRASH
            }
        }

        if (nextState != currentState) {
            logger?.invoke(TAG, "Transition: $currentState -> $nextState (G=${impactG}, Kmh=${speedKmh}, Current=${currentScore}, Peak=${peakScore})")
        }

        return nextState
    }
}
