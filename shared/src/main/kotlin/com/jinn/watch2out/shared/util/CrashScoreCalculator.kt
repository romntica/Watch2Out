// [Module: :shared]
package com.jinn.watch2out.shared.util

import com.jinn.watch2out.shared.model.WatchSettings
import kotlin.math.abs

/**
 * CrashScore v27 Calculator.
 * Implementation of the physics-based scoring formula with minimal intelligence rules.
 */
object CrashScoreCalculator {

    data class Features(
        val peakG: Float,
        val deltaV: Float,
        val vPre: Float,
        val gyroRms: Float,
        val pressureDelta: Float,
        val lowG: Boolean,
        val pressureDrop: Boolean,
        val stillTimeSec: Float,
        val userInput: Boolean,
        val rollSumDeg: Float,
        val hasAccel: Boolean,
        val hasSpeed: Boolean,
        val hasGyro: Boolean,
        val hasPressure: Boolean,
        val hasStill: Boolean,
        val hasRoll: Boolean
    )

    data class SensorConfidence(
        val accel: Float = 1.0f,
        val gps: Float = 1.0f,
        val gyro: Float = 1.0f,
        val pressure: Float = 1.0f,
        val posture: Float = 1.0f
    )

    data class Result(
        val finalScore: Float,
        val baseScore: Float,
        val bonusWeak: Float,
        val bonusFall: Float,
        val bonusImpact: Float,
        val normalized: Map<String, Float>,
        val effectiveWeights: Map<String, Float>
    )

    private fun clamp(x: Float, min: Float = 0.0f, max: Float = 1.0f): Float {
        return if (x < min) min else if (x > max) max else x
    }

    fun computeCrashScore(
        features: Features,
        config: WatchSettings,
        sensorConfidence: SensorConfidence = SensorConfidence()
    ): Result {
        // 0. Safety guards
        val accelRange = (config.accelMaxG - config.accelMinG).coerceAtLeast(0.1f)
        val speedBase = config.speedMinKmh.coerceAtLeast(1.0f)
        val stillRange = (config.stillMaxSec - 3.0f).coerceAtLeast(0.1f)

        // 1. Normalized scores (0 ~ 1)
        val nAccel = if (features.hasAccel) {
            clamp((features.peakG - config.accelMinG) / accelRange)
        } else 0f

        val (nSpeed, nSpeedRaw) = if (features.hasSpeed) {
            val sWeight = clamp(features.vPre / speedBase)
            val raw = clamp(features.deltaV / config.speedDeltaMaxKmh)
            (raw * (0.5f + 0.5f * sWeight)) to raw
        } else 0f to 0f

        val nGyro = if (features.hasGyro) {
            clamp(features.gyroRms / config.gyroMaxDegPerSec)
        } else 0f

        val nPress = if (features.hasPressure && features.lowG) {
            clamp(abs(features.pressureDelta) / config.pressureMaxHpa)
        } else 0f

        val nStill = if (!features.hasStill || features.userInput) {
            0f
        } else {
            val t = (features.stillTimeSec - 3.0f).coerceAtLeast(0.0f)
            clamp(t / stillRange)
        }

        val nRoll = if (features.hasRoll) {
            clamp(features.rollSumDeg / 360.0f)
        } else 0f

        // 2. Weights * sensor confidence + renormalization
        var wAccel = if (features.hasAccel) config.wAccel * sensorConfidence.accel else 0f
        var wSpeed = if (features.hasSpeed) config.wSpeed * sensorConfidence.gps else 0f
        var wGyro = if (features.hasGyro) config.wGyro * sensorConfidence.gyro else 0f
        var wPress = if (features.hasPressure) config.wPress * sensorConfidence.pressure else 0f
        var wStill = if (features.hasStill) config.wStill * sensorConfidence.posture else 0f
        var wRoll = if (features.hasRoll) config.wRoll * sensorConfidence.gyro else 0f

        val totalW = (wAccel + wSpeed + wGyro + wPress + wStill + wRoll).coerceAtLeast(1e-3f)

        wAccel /= totalW
        wSpeed /= totalW
        wGyro /= totalW
        wPress /= totalW
        wStill /= totalW
        wRoll /= totalW

        // 3. Base weighted sum
        var baseScore = (wAccel * nAccel +
                        wSpeed * nSpeed +
                        wGyro * nGyro +
                        wPress * nPress +
                        wStill * nStill +
                        wRoll * nRoll)

        var bonusWeak = 0f
        var bonusFall = 0f
        var bonusImpact = 0f

        // 4. Minimal intelligence (3 rules)
        
        // Rule (1): Weak event suppression
        if (nAccel < 0.3f && nGyro < 0.3f && features.deltaV < 5.0f) {
            val before = baseScore
            baseScore *= 0.6f
            bonusWeak = baseScore - before
        }

        // Rule (2): Falling pattern (Low-G + pressure drop)
        if (features.lowG && features.pressureDrop) {
            val before = baseScore
            baseScore += 0.10f
            bonusFall = 0.10f
        }

        // Rule (3): Strong impact (High G + [Rotation OR large DeltaV])
        if (nAccel > 0.6f && (nGyro > 0.4f || nSpeedRaw > 0.5f)) {
            val before = baseScore
            baseScore += 0.15f
            bonusImpact = 0.15f
        }

        val finalScore = clamp(baseScore)

        return Result(
            finalScore = finalScore,
            baseScore = clamp(baseScore),
            bonusWeak = bonusWeak,
            bonusFall = bonusFall,
            bonusImpact = bonusImpact,
            normalized = mapOf(
                "accel" to nAccel, "speed" to nSpeed, "gyro" to nGyro,
                "press" to nPress, "still" to nStill, "roll" to nRoll
            ),
            effectiveWeights = mapOf(
                "accel" to wAccel, "speed" to wSpeed, "gyro" to wGyro,
                "press" to wPress, "still" to wStill, "roll" to wRoll
            )
        )
    }
}
