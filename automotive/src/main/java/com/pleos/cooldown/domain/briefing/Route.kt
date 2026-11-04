package com.pleos.cooldown.domain.briefing

/** 내비 회전 안내(TBT) 한 항목. [distanceM]은 현재 위치에서 그 지점까지 거리 */
data class Turn(val kind: TurnKind, val distanceM: Double, val description: String)

enum class TurnKind {
    STRAIGHT, LEFT, RIGHT, KEEP_LEFT, KEEP_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    IC_ENTER, IC_EXIT, OVERPASS_UNDERPASS, DIVIDE_MERGE,
    UTURN, ROUNDABOUT, TUNNEL, TOLL, REST_AREA, WAYPOINT, ARRIVE, OTHER;

    /** 연속 분기 판정에 세는 회전·분기 (명세 6.2) */
    val isBranch: Boolean
        get() = this in setOf(LEFT, RIGHT, KEEP_LEFT, KEEP_RIGHT, SHARP_LEFT, SHARP_RIGHT, IC_ENTER, IC_EXIT, DIVIDE_MERGE)

    /** 안내 문장에 쓰는 방향. 방향이 없으면 null */
    val direction: String?
        get() = when (this) {
            LEFT, KEEP_LEFT, SHARP_LEFT -> "왼쪽"
            RIGHT, KEEP_RIGHT, SHARP_RIGHT -> "오른쪽"
            else -> null
        }
}

enum class CongestionLevel { FREE, SLOW, HEAVY }

/** 경로 위 정체 구간. [startM]은 현재 위치에서 시작 지점까지 거리 */
data class Congestion(val startM: Double, val lengthM: Double, val level: CongestionLevel, val roadName: String)

/** 지금 안내 중인 경로. 경로 안내 중이 아니면 이 값 자체가 없다 */
data class RouteSnapshot(
    val routeId: String,
    val totalDurationSec: Int?,
    val totalDistanceM: Int?,
    val turns: List<Turn>,
    val congestions: List<Congestion>,
)

/** 어려운 구간 (명세 6.2). [key]는 같은 구간을 다시 안내하지 않으려고 쓴다 */
sealed interface HardSection {
    val startM: Double
    val key: String

    data class ConsecutiveBranches(override val startM: Double, val count: Int, val lastDirection: String?, val description: String) : HardSection {
        override val key get() = "branch:$description:$count"
    }

    data class ComplexIntersection(override val startM: Double, val kind: TurnKind, val description: String) : HardSection {
        override val key get() = "complex:$kind:$description"
    }

    data class CongestionEntry(override val startM: Double, val lengthM: Double, val roadName: String) : HardSection {
        override val key get() = "congestion:$roadName"
    }
}
