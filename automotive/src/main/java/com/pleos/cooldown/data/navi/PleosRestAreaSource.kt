package com.pleos.cooldown.data.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteDriving
import ai.pleos.playground.navi.constants.RouteOption
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
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.pleos.cooldown.domain.fatigue.RestArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NaviHelper 2.0.3의 회전 안내(TBT)에서 다음 휴게소를 찾는다.
 *
 * NaviHelper에는 장소 검색 API가 없고 TBT에는 좌표가 없어서 `addWaypoint`로 휴게소를 넣을 수 없다.
 * 대신 경로 위에 이미 있는 휴게소(`TURN_RESTAREA`, `TURN_ENTER_SERVICEAREA`)를 쓴다 (명세 5.4).
 * TBT 거리 단위(m로 가정)와 description에 휴게소 이름이 오는지는 에뮬레이터로 확인이 필요하다.
 */
class PleosRestAreaSource(context: Context) : RestAreaSource {

    private val appContext = context.applicationContext
    private var navi: NaviHelper? = null

    private val _nextRestArea = MutableStateFlow<RestArea?>(null)
    override val nextRestArea: StateFlow<RestArea?> = _nextRestArea.asStateFlow()

    private val _nextTurnDistanceM = MutableStateFlow<Double?>(null)
    override val nextTurnDistanceM: StateFlow<Double?> = _nextTurnDistanceM.asStateFlow()

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(list: List<TBTInfo>) {
            _nextTurnDistanceM.value = list.firstOrNull()?.distance
            _nextRestArea.value = list.firstOrNull { it.type in REST_TYPES }?.let { RestArea(it.description.trim(), it.distance) }
        }

        override fun onRouteEnded() = clear()
        override fun onRouteCancelled() = clear()
        override fun onDestinationArrived(info: DestinationArrivedInfo) = clear()
        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "navi error $code")
        }

        override fun onRouteStarted(info: RouteStartInfo) = Unit
        override fun onReRouteEnded() = Unit
        override fun onRouteOptionChanged(option: RouteOption) = Unit
        override fun onWaypointChanged(info: WaypointChangedInfo) = Unit
        override fun onWayPointArrived(info: WaypointArrivedInfo) = Unit
        override fun onBookMarkChanged() = Unit
        override fun onDestinationChanged() = Unit
        override fun onApplicationVisibilityChangeRequest(info: ApplicationVisibleInfo) = Unit
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = Unit
        override fun onRouteStateInfo(info: RouteStateInfo) = Unit
        override fun onDestinationInfo(info: DestinationInfo) = Unit
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
            n.getTBTInfo()
            navi = n
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
        try {
            navi?.getTBTInfo()
        } catch (e: Exception) {
            Log.w(TAG, "TBT request failed", e)
        }
    }

    private fun clear() {
        _nextRestArea.value = null
        _nextTurnDistanceM.value = null
    }

    private companion object {
        const val TAG = "PleosRestArea"
        val REST_TYPES = setOf(TurnType.TURN_RESTAREA, TurnType.TURN_ENTER_SERVICEAREA)
    }
}
