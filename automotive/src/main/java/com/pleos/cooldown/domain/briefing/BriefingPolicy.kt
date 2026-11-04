package com.pleos.cooldown.domain.briefing

import com.pleos.cooldown.domain.model.DrivingState

sealed interface BriefingAction {
    /** 정차 중 브리핑. 주행이 시작되면 [StopSpeaking]으로 끊는다 (F1-01) */
    data class SpeakBriefing(val text: String) : BriefingAction
    data class SpeakAlert(val text: String) : BriefingAction
    data object StopSpeaking : BriefingAction
}

/**
 * F1 코스 브리핑 흐름 (명세 6.1, F1-01~04). 순수 Kotlin, 한 코루틴에서만 호출한다.
 */
class BriefingPolicy(private val config: Config = Config()) {

    data class Config(
        val alertWithinM: Double = 1_000.0,
        /** 직전 안내가 내비 회전 안내와 겹치지 않게, 다른 회전이 이보다 가까우면 미룬다 (F1-03) */
        val navVoiceGuardM: Double = 300.0,
    )

    private var routeId: String? = null
    private var briefedRouteId: String? = null
    private var briefing = false
    private var sections: List<HardSection> = emptyList()
    private val alerted = mutableSetOf<String>()

    /** 경로가 바뀌거나 갱신될 때. 재탐색이어도 같은 routeId면 브리핑을 다시 하지 않는다 (F1-02) */
    fun onRoute(route: RouteSnapshot?, state: DrivingState): List<BriefingAction> {
        if (route == null) {
            routeId = null
            sections = emptyList()
            return emptyList()
        }
        if (route.routeId != routeId) {
            routeId = route.routeId
            alerted.clear()
        }
        sections = HardSectionDetector.detect(route)
        return if (state == DrivingState.PARKED) maybeBrief(route) else emptyList()
    }

    fun onDrivingState(state: DrivingState, route: RouteSnapshot?): List<BriefingAction> = when (state) {
        DrivingState.DRIVING -> if (briefing) {
            // F1-01: 출발하면 즉시 멈추고, 남은 구간은 직전 안내로만 전한다
            briefing = false
            listOf(BriefingAction.StopSpeaking)
        } else emptyList()
        DrivingState.PARKED -> route?.let(::maybeBrief).orEmpty()
    }

    fun onBriefingFinished() {
        briefing = false
    }

    /** 주행 중 매 갱신마다: 1km 안에 들어온 어려운 구간을 한 번씩 알린다 */
    fun onTick(route: RouteSnapshot?, state: DrivingState): List<BriefingAction> {
        if (route == null || state != DrivingState.DRIVING) return emptyList()
        val section = sections.firstOrNull { it.startM <= config.alertWithinM && it.key !in alerted } ?: return emptyList()
        val otherTurnClose = route.turns.any { it.distanceM < config.navVoiceGuardM && it.distanceM < section.startM }
        if (otherTurnClose) return emptyList()
        alerted += section.key
        return listOf(BriefingAction.SpeakAlert(BriefingComposer.preAlert(section)))
    }

    private fun maybeBrief(route: RouteSnapshot): List<BriefingAction> {
        if (briefedRouteId == route.routeId || briefing) return emptyList()
        briefedRouteId = route.routeId
        briefing = true
        return listOf(BriefingAction.SpeakBriefing(BriefingComposer.briefing(route, sections)))
    }
}
