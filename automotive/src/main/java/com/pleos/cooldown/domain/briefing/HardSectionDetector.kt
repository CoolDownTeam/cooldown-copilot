package com.pleos.cooldown.domain.briefing

/**
 * F1 어려운 구간 판정 (명세 6.2). 순수 함수.
 */
object HardSectionDetector {

    const val BRANCH_GAP_M = 500.0
    const val MIN_BRANCHES = 2

    private val complexKinds = setOf(TurnKind.UTURN, TurnKind.ROUNDABOUT)

    /** 가까운 순서로 정렬한 어려운 구간 */
    fun detect(route: RouteSnapshot): List<HardSection> {
        val turns = route.turns.sortedBy { it.distanceM }
        val sections = mutableListOf<HardSection>()

        // 연속 분기: 회전·분기가 앞 분기에서 500m 안에 연달아 나온다
        var group = mutableListOf<Turn>()
        fun flush() {
            if (group.size >= MIN_BRANCHES) {
                val last = group.last()
                sections += HardSection.ConsecutiveBranches(group.first().distanceM, group.size, last.kind.direction, group.first().description)
            }
            group = mutableListOf()
        }
        for (turn in turns) {
            if (!turn.kind.isBranch) {
                flush()
                continue
            }
            val previous = group.lastOrNull()
            if (previous != null && turn.distanceM - previous.distanceM > BRANCH_GAP_M) flush()
            group += turn
        }
        flush()

        // 복잡 교차로: 유턴·로터리
        turns.filter { it.kind in complexKinds }.forEach {
            sections += HardSection.ComplexIntersection(it.distanceM, it.kind, it.description)
        }

        // 정체 진입: 서행·정체 구간의 시작
        route.congestions.filter { it.level != CongestionLevel.FREE && it.lengthM > 0 }.forEach {
            sections += HardSection.CongestionEntry(it.startM, it.lengthM, it.roadName)
        }

        return sections.sortedBy { it.startM }
    }
}
