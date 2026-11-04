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

    /** SAFE-04: 지원 범위. 온도·풍량을 지원하지 않으면 null */
    suspend fun limits(): HvacLimits?

    /** 값을 바꾼다. 하나라도 실패하면 false (F2-06) */
    suspend fun apply(target: HvacTarget): Boolean

    /** 내·외기 전환 (F3). 실패하면 false */
    suspend fun setRecirculation(on: Boolean): Boolean

    /** F3-P2: 탑승 인원. 좌석 감지를 지원하지 않으면 null */
    suspend fun occupantCount(): Int?
}
