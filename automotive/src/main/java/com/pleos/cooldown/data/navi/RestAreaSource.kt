package com.pleos.cooldown.data.navi

import com.pleos.cooldown.domain.fatigue.RestArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 경로 위 다음 휴게소와 다음 회전 안내까지 거리 (F3 휴식 유도, F3-04·05).
 * 경로 안내 중이 아니면 둘 다 null이다.
 */
interface RestAreaSource {
    val nextRestArea: StateFlow<RestArea?>
    val nextTurnDistanceM: StateFlow<Double?>
    fun start()
    fun stop()

    /** 최신 TBT를 다시 요청한다. 주행 중 주기적으로 부른다 */
    fun refresh()
}

/** 내비가 없거나 시연에서 휴게소를 직접 넣을 때 쓴다 */
class FakeRestAreaSource(restArea: RestArea? = null, nextTurnM: Double? = null) : RestAreaSource {
    override val nextRestArea = MutableStateFlow(restArea)
    override val nextTurnDistanceM = MutableStateFlow(nextTurnM)
    override fun start() = Unit
    override fun stop() = Unit
    override fun refresh() = Unit
}
