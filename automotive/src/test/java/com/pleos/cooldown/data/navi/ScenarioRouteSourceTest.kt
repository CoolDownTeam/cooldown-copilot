package com.pleos.cooldown.data.navi

import com.pleos.cooldown.domain.briefing.CongestionLevel
import com.pleos.cooldown.domain.briefing.TurnKind
import com.pleos.cooldown.domain.model.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioRouteSourceTest {

    private val text = """
        # 시연 경로
        route,10000,600
        turn,REST_AREA,8000,행담도휴게소
        turn,KEEP_RIGHT,2000,서평택JC
        congestion,5000,1200,HEAVY,서해안고속도로
    """.trimIndent()

    @Test
    fun `parses route lines and sorts points by position`() {
        val route = ScenarioRouteParser.parse(text)
        assertEquals(10000, route.totalDistanceM)
        assertEquals(600, route.totalDurationSec)
        assertEquals(listOf(TurnKind.KEEP_RIGHT, TurnKind.REST_AREA), route.points.map { it.kind })
        assertEquals(CongestionLevel.HEAVY, route.congestions.single().level)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unknown turn kind`() {
        ScenarioRouteParser.parse("route,100,10\nturn,JUMP,50,x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file without the route line`() {
        ScenarioRouteParser.parse("turn,LEFT,50,x")
    }

    @Test
    fun `snapshot drops passed points and measures from the current position`() {
        val snapshot = ScenarioRouteParser.parse(text).snapshotAt(3000.0)
        assertEquals(listOf(5000.0), snapshot.turns.map { it.distanceM })
        assertEquals(2000.0, snapshot.congestions.single().startM, 0.001)
        assertEquals("행담도휴게소", snapshot.nextRestArea()?.name)
    }

    @Test
    fun `distance shrinks with the replayed speed and the route ends at the destination`() = runTest {
        val signal = MutableStateFlow(VehicleSignal.INITIAL.copy(speedKmh = 72f)) // 20 m/s
        val source = ScenarioRouteSource(ScenarioRouteParser.parse(text), signal, backgroundScope, { testScheduler.currentTime })
        source.start()
        assertEquals(2000.0, source.route.value?.nextTurnDistanceM() ?: -1.0, 0.001)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1800.0, source.route.value?.nextTurnDistanceM() ?: -1.0, 0.001)

        signal.value = signal.value.copy(speedKmh = 0f)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1800.0, source.route.value?.nextTurnDistanceM() ?: -1.0, 0.001)

        signal.value = signal.value.copy(speedKmh = 3600f) // 1 km/s
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(source.route.value)
    }
}
