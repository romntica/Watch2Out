package com.jinn.watch2out.shared.util

import com.jinn.watch2out.shared.model.VehicleInferenceState
import com.jinn.watch2out.shared.model.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentFsmTest {

    private val config = WatchSettings()

    @Test
    fun testIdleToMoving() {
        val state = IncidentFsm.nextState(
            currentState = VehicleInferenceState.IDLE,
            impactG = 1.0f,
            speedKmh = 20.0f,
            crashScore = 0.0f,
            config = config
        )
        assertEquals(VehicleInferenceState.MOVING, state)
    }

    @Test
    fun testMovingToPreEvent() {
        val state = IncidentFsm.nextState(
            currentState = VehicleInferenceState.MOVING,
            impactG = 6.0f,
            speedKmh = 40.0f,
            crashScore = 0.3f,
            config = config
        )
        assertEquals(VehicleInferenceState.PRE_EVENT, state)
    }

    @Test
    fun testPreEventToImpact() {
        val state = IncidentFsm.nextState(
            currentState = VehicleInferenceState.PRE_EVENT,
            impactG = 12.0f,
            speedKmh = 10.0f,
            crashScore = 0.8f,
            config = config
        )
        assertEquals(VehicleInferenceState.IMPACT, state)
    }

    @Test
    fun testFallingToImpact() {
        val state = IncidentFsm.nextState(
            currentState = VehicleInferenceState.FALLING,
            impactG = 6.0f,
            speedKmh = 0.0f,
            crashScore = 0.5f,
            config = config
        )
        assertEquals(VehicleInferenceState.IMPACT, state)
    }

    @Test
    fun testStillnessToWaitConfirm() {
        val state = IncidentFsm.nextState(
            currentState = VehicleInferenceState.STILLNESS,
            impactG = 1.0f,
            speedKmh = 0.0f,
            crashScore = 0.7f,
            config = config
        )
        assertEquals(VehicleInferenceState.WAIT_CONFIRM, state)
    }

    @Test
    fun testStillnessRecovery() {
        val state = IncidentFsm.nextState(
            currentState = VehicleInferenceState.STILLNESS,
            impactG = 1.0f,
            speedKmh = 20.0f,
            crashScore = 0.1f,
            config = config
        )
        assertEquals(VehicleInferenceState.MOVING, state)
    }
}
