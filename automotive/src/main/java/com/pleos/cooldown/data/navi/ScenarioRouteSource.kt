package com.pleos.cooldown.data.navi

import com.pleos.cooldown.domain.briefing.Congestion
import com.pleos.cooldown.domain.briefing.CongestionLevel
import com.pleos.cooldown.domain.briefing.RouteSnapshot
import com.pleos.cooldown.domain.briefing.Turn
import com.pleos.cooldown.domain.briefing.TurnKind
import com.pleos.cooldown.domain.model.VehicleSignal
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 시연 경로의 한 지점. [positionM]은 출발점에서 그 지점까지 거리 */
data class ScenarioRoutePoint(val kind: TurnKind, val positionM: Double, val description: String)

data class ScenarioCongestion(val startPositionM: Double, val lengthM: Double, val level: CongestionLevel, val roadName: String)

/** 시나리오와 같이 재생하는 경로 (명세 10절). 내비 경로 서버 없이 F1·F3를 시연한다 */
data class ScenarioRoute(
    val totalDistanceM: Int,
    val totalDurationSec: Int,
    val points: List<ScenarioRoutePoint>,
    val congestions: List<ScenarioCongestion>,
) {
    /** 출발점에서 [traveledM]만큼 왔을 때 내비가 줄 경로. 지나간 지점은 뺀다 */
    fun snapshotAt(traveledM: Double): RouteSnapshot = RouteSnapshot(
        routeId = ROUTE_ID,
        totalDurationSec = totalDurationSec,
        totalDistanceM = totalDistanceM,
        turns = points.filter { it.positionM >= traveledM }
            .map { Turn(it.kind, it.positionM - traveledM, it.description) },
        congestions = congestions.filter { it.startPositionM >= traveledM }
            .map { Congestion(it.startPositionM - traveledM, it.lengthM, it.level, it.roadName) },
    )

    companion object {
        const val ROUTE_ID = "scenario"
    }
}

/**
 * 경로 파일 한 줄씩:
 * - `route,<총 거리 m>,<총 시간 s>` (한 번)
 * - `turn,<TurnKind>,<출발점부터 거리 m>,<설명>`
 * - `congestion,<출발점부터 시작 거리 m>,<길이 m>,<CongestionLevel>,<도로 이름>`
 */
object ScenarioRouteParser {

    fun parse(text: String): ScenarioRoute {
        var total: Pair<Int, Int>? = null
        val points = mutableListOf<ScenarioRoutePoint>()
        val congestions = mutableListOf<ScenarioCongestion>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cols = line.split(",").map { it.trim() }
            val no = index + 1
            when (cols[0]) {
                "route" -> {
                    require(cols.size == 3) { "line $no: route needs 3 columns" }
                    total = number(cols[1], no).toInt() to number(cols[2], no).toInt()
                }
                "turn" -> {
                    require(cols.size == 4) { "line $no: turn needs 4 columns" }
                    val kind = TurnKind.entries.firstOrNull { it.name == cols[1] }
                        ?: throw IllegalArgumentException("line $no: bad turn kind '${cols[1]}'")
                    points += ScenarioRoutePoint(kind, number(cols[2], no), cols[3])
                }
                "congestion" -> {
                    require(cols.size == 5) { "line $no: congestion needs 5 columns" }
                    val level = CongestionLevel.entries.firstOrNull { it.name == cols[3] }
                        ?: throw IllegalArgumentException("line $no: bad congestion level '${cols[3]}'")
                    congestions += ScenarioCongestion(number(cols[1], no), number(cols[2], no), level, cols[4])
                }
                else -> throw IllegalArgumentException("line $no: unknown record '${cols[0]}'")
            }
        }
        val (distance, duration) = total ?: throw IllegalArgumentException("route line missing")
        return ScenarioRoute(distance, duration, points.sortedBy { it.positionM }, congestions.sortedBy { it.startPositionM })
    }

    private fun number(value: String, line: Int): Double =
        value.toDoubleOrNull() ?: throw IllegalArgumentException("line $line: bad number '$value'")
}

/**
 * 시연용 경로 소스. 재생 중인 차속으로 달린 거리를 쌓아서 남은 거리를 줄여 간다.
 */
class ScenarioRouteSource(
    private val plan: ScenarioRoute,
    private val signal: StateFlow<VehicleSignal>,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val updateIntervalMs: Long = 1_000,
) : RouteSource {

    private val _route = MutableStateFlow<RouteSnapshot?>(null)
    override val route: StateFlow<RouteSnapshot?> = _route.asStateFlow()

    private var job: Job? = null
    private var traveledM = 0.0

    override fun start() {
        if (job?.isActive == true) return
        _route.value = plan.snapshotAt(traveledM)
        job = scope.launch {
            var lastMs = clock.nowMs()
            while (isActive) {
                delay(updateIntervalMs)
                val now = clock.nowMs()
                traveledM += signal.value.speedKmh / 3.6 * (now - lastMs) / 1_000.0
                lastMs = now
                // 도착 지점을 지나면 경로 안내가 끝난 것으로 본다
                _route.value = if (traveledM > plan.totalDistanceM) null else plan.snapshotAt(traveledM)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun refresh() = Unit
}
