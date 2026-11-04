package com.pleos.cooldown.data.navi

import com.pleos.cooldown.domain.briefing.RouteSnapshot
import com.pleos.cooldown.domain.briefing.TurnKind
import com.pleos.cooldown.domain.fatigue.RestArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 지금 안내 중인 경로 (F1 코스 브리핑, F3 휴게소). 경로 안내 중이 아니면 null.
 */
interface RouteSource {
    val route: StateFlow<RouteSnapshot?>
    fun start()
    fun stop()

    /** 최신 회전 안내·교통 정보를 다시 요청한다. 주행 중 주기적으로 부른다 */
    fun refresh()
}

/** F3: 경로 위 다음 휴게소 (명세 F3-04) */
fun RouteSnapshot.nextRestArea(): RestArea? =
    turns.filter { it.kind == TurnKind.REST_AREA }.minByOrNull { it.distanceM }?.let { RestArea(it.description.trim(), it.distanceM) }

/** F3-05: 다음 회전 안내까지 거리 */
fun RouteSnapshot.nextTurnDistanceM(): Double? = turns.minOfOrNull { it.distanceM }

/** 내비가 없거나 시연에서 경로를 직접 넣을 때 쓴다 */
class FakeRouteSource(route: RouteSnapshot? = null) : RouteSource {
    override val route = MutableStateFlow(route)
    override fun start() = Unit
    override fun stop() = Unit
    override fun refresh() = Unit
}
