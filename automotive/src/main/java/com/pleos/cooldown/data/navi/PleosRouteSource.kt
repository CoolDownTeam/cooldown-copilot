package com.pleos.cooldown.data.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteDriving
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.ApplicationVisibleInfo
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.FindRouteResult
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TrafficCongestionInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.pleos.cooldown.domain.briefing.Congestion
import com.pleos.cooldown.domain.briefing.CongestionLevel
import com.pleos.cooldown.domain.briefing.RouteSnapshot
import com.pleos.cooldown.domain.briefing.Turn
import com.pleos.cooldown.domain.briefing.TurnKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NaviHelper 2.0.3으로 지금 안내 중인 경로를 모은다: 회전 안내(TBT), 정체(`RouteStateInfo.trafficStatus`),
 * 총 시간(`RouteStartInfo`·`DestinationInfo`).
 *
 * NaviHelper에는 장소 검색 API가 없고 TBT에는 좌표가 없어서 휴게소를 `addWaypoint`로 넣지 않는다 (명세 F3-04).
 * 거리·시간 단위와 TBT가 경로 전체를 주는지(F1-04)는 에뮬레이터로 확인하고 로그로 남긴다.
 */
class PleosRouteSource(context: Context) : RouteSource {

    private val appContext = context.applicationContext
    private var navi: NaviHelper? = null

    private val _route = MutableStateFlow<RouteSnapshot?>(null)
    override val route: StateFlow<RouteSnapshot?> = _route.asStateFlow()

    // 콜백은 여러 binder 스레드에서 오므로 값 묶음을 한 번에 바꾼다
    @Volatile private var routing = false
    @Volatile private var routeSeq = 0
    @Volatile private var durationSec: Int? = null
    @Volatile private var distanceM: Int? = null
    @Volatile private var turns: List<Turn> = emptyList()
    @Volatile private var congestions: List<Congestion> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            Log.d(TAG, "route started duration=${info.duration} distance=${info.distance} name=${info.name}")
            routeSeq++
            routing = true
            durationSec = info.duration
            distanceM = info.distance
            publish()
            refresh()
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            Log.d(TAG, "destination ${info.name} totalTime=${info.totalTime} totalDistance=${info.totalDistance}")
            if (info.totalTime > 0) durationSec = info.totalTime
            if (info.totalDistance > 0) distanceM = info.totalDistance
            publish()
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            Log.d(TAG, "TBT ${list.size}: " + list.take(8).joinToString { "${it.type}@${it.distance.toInt()}(${it.description})" })
            turns = list.map { Turn(it.type.toKind(), it.distance, it.description) }
            if (list.isNotEmpty()) routing = true
            publish()
        }

        override fun onRouteStateInfo(info: RouteStateInfo) {
            val items = info.trafficStatus?.congestionItemList.orEmpty()
            Log.d(TAG, "route state routing=${info.isRouting} mode=${info.naviMode} congestion=${items.size}")
            routing = info.isRouting
            congestions = items.map { it.toDomain() }
            publish()
        }

        // F1-02: 재탐색은 같은 경로로 본다(브리핑 다시 안 함). 새 TBT를 받아 구간만 다시 계산한다
        override fun onReRouteEnded() = refresh()
        override fun onRouteEnded() = clear()
        override fun onRouteCancelled() = clear()
        override fun onDestinationArrived(info: DestinationArrivedInfo) = clear()
        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "navi error $code")
        }

        override fun onRouteOptionChanged(option: RouteOption) = Unit
        override fun onWaypointChanged(info: WaypointChangedInfo) = Unit
        override fun onWayPointArrived(info: WaypointArrivedInfo) = Unit
        override fun onBookMarkChanged() = Unit
        override fun onDestinationChanged() = Unit
        override fun onApplicationVisibilityChangeRequest(info: ApplicationVisibleInfo) = Unit
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = Unit
        override fun onWayPointInfo(list: List<WaypointInfo>) = Unit
        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) = Unit
        override fun onBookmarkInfo(list: List<BookmarkInfo>) = Unit
        override fun onUserDefinedRoute(result: FindRouteResult) = Unit
        override fun onCurrentRoadSpeedLimit(limit: Int) = Unit
        override fun onCameraAlert(list: List<CameraAlert>) = Unit
        override fun onDrivingInfo(info: DrivingInfo) = Unit
        override fun onNavigationStatus(status: RouteDriving) = Unit
        override fun onChargerOperatorInfo(list: List<ChargerOperator>) = Unit
    }

    override fun start() {
        if (navi != null) return
        try {
            val n = NaviHelper(appContext)
            n.initialize()
            n.addListener(listener)
            navi = n
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NaviHelper", e)
        }
    }

    override fun stop() {
        val n = navi ?: return
        try {
            n.removeListener(listener)
            n.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop NaviHelper", e)
        }
        navi = null
        clear()
    }

    // TBT가 경로 안내 중 자동으로 계속 오는지 확인하지 못해서, 주기적으로 다시 요청한다
    override fun refresh() {
        val n = navi ?: return
        try {
            n.getRouteStateInfo()
            n.getDestinationInfo()
            n.getTBTInfo()
        } catch (e: Exception) {
            Log.w(TAG, "navi refresh failed", e)
        }
    }

    /** 디버그 시연용: 목적지로 경로를 요청하고 모의 주행을 시작한다 */
    fun requestDemoRoute(latitude: Double, longitude: Double, name: String) {
        val n = navi ?: return
        try {
            // RouteInfo 생성자는 경도가 먼저다 (필드 순서 longitude, latitude — SDK jar 확인)
            n.requestRoute(RouteInfo(longitude, latitude, name, "", "", "", RouteOption.RECOMMENDED))
        } catch (e: Exception) {
            Log.w(TAG, "demo route failed", e)
        }
    }

    fun startDemoSimulation() {
        try {
            navi?.getAssistant()?.requestSimulatedDrive()
        } catch (e: Exception) {
            Log.w(TAG, "simulated drive failed", e)
        }
    }

    private fun publish() {
        _route.value = if (routing) {
            RouteSnapshot("route-$routeSeq", durationSec, distanceM, turns, congestions)
        } else {
            null
        }
    }

    private fun clear() {
        routing = false
        turns = emptyList()
        congestions = emptyList()
        durationSec = null
        distanceM = null
        publish()
    }

    private fun TrafficCongestionInfo.toDomain() = Congestion(
        startM = offset,
        lengthM = distance,
        level = when (trafficCongestionType) {
            TrafficCongestionType.HEAVY -> CongestionLevel.HEAVY
            TrafficCongestionType.SLOW -> CongestionLevel.SLOW
            else -> CongestionLevel.FREE
        },
        roadName = roadName,
    )

    private fun TurnType.toKind(): TurnKind = when (this) {
        TurnType.TURN_STRAIGHT -> TurnKind.STRAIGHT
        TurnType.TURN_LEFT -> TurnKind.LEFT
        TurnType.TURN_RIGHT -> TurnKind.RIGHT
        TurnType.TURN_KEEP_L -> TurnKind.KEEP_LEFT
        TurnType.TURN_KEEP_R -> TurnKind.KEEP_RIGHT
        TurnType.TURN_SHARP_L -> TurnKind.SHARP_LEFT
        TurnType.TURN_SHARP_R -> TurnKind.SHARP_RIGHT
        TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_C -> TurnKind.IC_ENTER
        TurnType.TURN_EXIT_IC_R, TurnType.TURN_EXIT_IC_L, TurnType.TURN_EXIT_IC_C -> TurnKind.IC_EXIT
        TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_ENTER_UNDER_C,
        TurnType.TURN_AVOID_UNDER_R, TurnType.TURN_AVOID_UNDER_L, TurnType.TURN_AVOID_UNDER_C,
        TurnType.TURN_ENTER_OVER_R, TurnType.TURN_ENTER_OVER_L, TurnType.TURN_ENTER_OVER_C,
        TurnType.TURN_AVOID_OVER_R, TurnType.TURN_AVOID_OVER_L, TurnType.TURN_AVOID_OVER_C -> TurnKind.OVERPASS_UNDERPASS
        TurnType.TURN_DEVIDE_MERGE -> TurnKind.DIVIDE_MERGE
        TurnType.TURN_UTURN -> TurnKind.UTURN
        TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
        TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
        TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12, TurnType.TURN_EXIT_ROUNDABOUT -> TurnKind.ROUNDABOUT
        TurnType.TURN_TUNNEL -> TurnKind.TUNNEL
        TurnType.TURN_TOLL -> TurnKind.TOLL
        TurnType.TURN_RESTAREA, TurnType.TURN_ENTER_SERVICEAREA -> TurnKind.REST_AREA
        TurnType.TURN_WAY -> TurnKind.WAYPOINT
        TurnType.TURN_ARRIVE -> TurnKind.ARRIVE
        TurnType.TURN_UNKNOWN -> TurnKind.OTHER
    }

    private companion object {
        const val TAG = "PleosRoute"
    }
}
