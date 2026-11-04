package com.pleos.cooldown.data.hvac

import com.pleos.cooldown.domain.cooldown.FanDirection
import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.HvacTarget
import com.pleos.cooldown.domain.cooldown.ManualChange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 메모리 안의 가짜 공조. `CONTROL_CAR_CLIMATE` 승인 전 시연과 통합 확인에 쓴다 (명세 1.3).
 */
class FakeHvacController(
    initial: HvacSnapshot = HvacSnapshot(temperatureC = 24f, fanSpeed = 3, powerOn = true, fanDirection = FanDirection.FLOOR, seatVentilation = 0),
    private val limits: HvacLimits = HvacLimits(
        minTempC = 16f, maxTempC = 32f, maxFanSpeed = 8, supportsFaceDirection = true, maxSeatVentilation = 3,
    ),
) : HvacController {

    private val _snapshot = MutableStateFlow<HvacSnapshot?>(initial)
    override val snapshot: StateFlow<HvacSnapshot?> = _snapshot.asStateFlow()

    private val _manualChanges = MutableSharedFlow<ManualChange>(extraBufferCapacity = 8)
    override val manualChanges: SharedFlow<ManualChange> = _manualChanges.asSharedFlow()

    override fun start() = Unit
    override fun stop() = Unit

    override suspend fun limits(): HvacLimits = limits

    override suspend fun apply(target: HvacTarget): Boolean {
        _snapshot.value = _snapshot.value?.copy(
            temperatureC = target.temperatureC,
            fanSpeed = target.fanSpeed,
            fanDirection = target.fanDirection,
            seatVentilation = target.seatVentilation,
        )
        return true
    }

    /** 시연용: 운전자가 직접 조작한 것처럼 만든다 */
    fun simulateManualChange(change: ManualChange, newSnapshot: HvacSnapshot) {
        _snapshot.value = newSnapshot
        _manualChanges.tryEmit(change)
    }
}
