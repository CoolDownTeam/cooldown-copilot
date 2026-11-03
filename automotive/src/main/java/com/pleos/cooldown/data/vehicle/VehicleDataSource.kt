package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.model.SignalType
import com.pleos.cooldown.domain.model.VehicleSignal
import kotlinx.coroutines.flow.StateFlow

/**
 * 차량 신호 제공자 (D0). 실제 차량과 가짜 시나리오 재생을 같은 인터페이스로 다룬다.
 */
interface VehicleDataSource {
    val signal: StateFlow<VehicleSignal>
    val drivingState: StateFlow<DrivingState>
    val unavailable: StateFlow<Set<SignalType>>

    /** 여러 번 호출해도 안전하다. 아직 시작하지 못한 신호(권한 대기 등)만 다시 시도한다. */
    fun start()

    fun stop()
}
