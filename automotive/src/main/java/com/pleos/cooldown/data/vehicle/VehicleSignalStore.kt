package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.SignalType
import com.pleos.cooldown.domain.model.VehicleSignal
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * 개별 리스너 값을 하나의 [VehicleSignal]로 합친다 (D0-10).
 * null 값은 직전 값을 유지하고 (D0-11), 실패한 신호는 사용 불가로 표시한다 (D0-12).
 */
class VehicleSignalStore(private val clock: Clock) {

    private val _signal = MutableStateFlow(VehicleSignal.INITIAL)
    val signal: StateFlow<VehicleSignal> = _signal.asStateFlow()

    private val _drivingState = MutableStateFlow(DrivingState.PARKED)
    val drivingState: StateFlow<DrivingState> = _drivingState.asStateFlow()

    private val _unavailable = MutableStateFlow<Set<SignalType>>(emptySet())
    val unavailable: StateFlow<Set<SignalType>> = _unavailable.asStateFlow()

    fun onSpeed(speedKmh: Float?) {
        speedKmh ?: return
        markAvailable(SignalType.SPEED)
        emit { it.copy(speedKmh = speedKmh.coerceAtLeast(0f)) }
    }

    fun onGear(gear: Gear?) {
        gear ?: return
        markAvailable(SignalType.GEAR)
        emit { it.copy(gear = gear) }
    }

    fun onBrake(pressed: Boolean?) {
        pressed ?: return
        markAvailable(SignalType.BRAKE)
        emit { it.copy(brakePressed = pressed) }
    }

    fun onSteering(angleDeg: Float?) {
        angleDeg ?: return
        markAvailable(SignalType.STEERING)
        emit { it.copy(steeringAngleDeg = angleDeg) }
    }

    fun onOdometer(km: Float?) {
        km ?: return
        markAvailable(SignalType.ODOMETER)
        emit { it.copy(odometerKm = km) }
    }

    fun markUnavailable(type: SignalType) {
        _unavailable.update { it + type }
    }

    private fun markAvailable(type: SignalType) {
        if (type in _unavailable.value) _unavailable.update { it - type }
    }

    private fun emit(change: (VehicleSignal) -> VehicleSignal) {
        val next = _signal.updateAndGet { change(it).copy(timestampMs = clock.nowMs()) }
        _drivingState.value = resolveDrivingState(next)
    }

    companion object {
        /**
         * 주행 중 판정 (D0-13). 기어를 아직 모르면(UNKNOWN) 차속만으로 판단한다.
         */
        fun resolveDrivingState(signal: VehicleSignal): DrivingState {
            val gearMoving = signal.gear == Gear.R || signal.gear == Gear.N || signal.gear == Gear.D
            return if (gearMoving || signal.speedKmh > 0f) DrivingState.DRIVING else DrivingState.PARKED
        }
    }
}
