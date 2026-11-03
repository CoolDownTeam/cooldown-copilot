package com.pleos.cooldown.testutil

import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.VehicleSignal

/**
 * 10Hz 차량 신호를 만들어 내는 녹화 시퀀스 빌더. 표본마다 [onSample]을 부르고,
 * 1초가 지날 때마다 [onSecond]를 부른다.
 */
class DriveSequence(
    private val onSample: (timeMs: Long, signal: VehicleSignal) -> Unit,
    private val onSecond: (timeMs: Long) -> Unit = {},
) {
    var timeMs = 0L
        private set
    var speedKmh = 0.0
        private set
    private var steerDeg = 0.0
    private var brake = false

    fun cruise(sec: Double, kmh: Double) {
        speedKmh = kmh
        brake = false
        repeat(steps(sec)) { step() }
    }

    fun accelerate(sec: Double, ms2: Double, brake: Boolean = false) {
        this.brake = brake
        repeat(steps(sec)) {
            speedKmh = (speedKmh + ms2 * STEP_SEC * 3.6).coerceAtLeast(0.0)
            step()
        }
        this.brake = false
    }

    fun spike(deltaKmh: Double) {
        speedKmh += deltaKmh
        step()
        speedKmh -= deltaKmh
    }

    fun steerBy(deg: Double, sec: Double) {
        val n = steps(sec)
        val perStep = deg / n
        repeat(n) {
            steerDeg += perStep
            step()
        }
    }

    fun gap(ms: Long) {
        timeMs += ms
    }

    private fun step() {
        timeMs += STEP_MS
        onSample(
            timeMs,
            VehicleSignal(
                timestampMs = timeMs,
                speedKmh = speedKmh.toFloat(),
                gear = Gear.D,
                brakePressed = brake,
                steeringAngleDeg = steerDeg.toFloat(),
                odometerKm = null
            )
        )
        if (timeMs % 1_000L == 0L) onSecond(timeMs)
    }

    private fun steps(sec: Double) = Math.round(sec / STEP_SEC).toInt()

    companion object {
        const val STEP_MS = 100L
        const val STEP_SEC = 0.1
    }
}
