package com.pleos.cooldown.data.hvac

import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.HvacTarget
import com.pleos.cooldown.domain.cooldown.ManualChange
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 운전석 공조 읽기·쓰기 (명세 4.5). 공조는 한 번에 한 정책만 소유한다 (F3-02).
 */
interface HvacController {
    /** 현재 공조 값. 아직 못 읽었거나 지원하지 않으면 null */
    val snapshot: StateFlow<HvacSnapshot?>

    /** 운전자가 직접 바꾼 공조 (앱의 변경은 걸러낸다, F2-04) */
    val manualChanges: SharedFlow<ManualChange>

    /** 여러 번 호출해도 안전하다 */
    fun start()
    fun stop()

    /** 지원 범위를 확인하고 값 구독을 건다. [start] 뒤, 첫 주행 전에 한 번 부른다 */
    suspend fun prepare() = Unit

    /**
     * 풍향을 차량에서 다시 읽는다. 풍향은 변경 리스너가 없어서 주기적으로 불러야 운전자의 조작을 알 수 있다
     * (SAFE-03). 바뀌었으면 [snapshot]을 갱신하고 [manualChanges]로 알린다
     */
    suspend fun refreshFanDirection() = Unit

    /**
     * [prepare] 때 SDK가 답하지 않아 못 얻은 지원 범위·현재 값을 다시 얻는다. 주행 중 주기적으로 부른다.
     * 이게 없으면 시작 직후 한 번 무응답만으로 그 실행 내내 공조 개입이 멈춘다
     */
    suspend fun recoverMissing() = Unit

    /** SAFE-04: 지원 범위. 온도·풍량을 지원하지 않으면 null */
    suspend fun limits(): HvacLimits?

    /** 값을 바꾼다. 하나라도 실패하면 false (F2-06) */
    suspend fun apply(target: HvacTarget): Boolean

    /** 내·외기 전환 (F3). 실패하면 false */
    suspend fun setRecirculation(on: Boolean): Boolean

    /** F3-P2: 탑승 인원. 좌석 감지를 지원하지 않으면 null */
    suspend fun occupantCount(): Int?
}
