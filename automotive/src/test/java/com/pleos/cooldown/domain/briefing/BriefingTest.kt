package com.pleos.cooldown.domain.briefing

import com.pleos.cooldown.domain.model.DrivingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingTest {

    private fun route(
        turns: List<Turn> = emptyList(),
        congestions: List<Congestion> = emptyList(),
        durationSec: Int? = 9_000,
        id: String = "r1",
    ) = RouteSnapshot(id, durationSec, 210_000, turns, congestions)

    private val consecutive = listOf(
        Turn(TurnKind.KEEP_RIGHT, 40_000.0, "서해안고속도로 방면"),
        Turn(TurnKind.KEEP_LEFT, 40_300.0, "당진 방면"),
        Turn(TurnKind.KEEP_RIGHT, 40_700.0, "서산 방면"),
    )

    @Test
    fun `6_2 consecutive branches within 500m form one section`() {
        val sections = HardSectionDetector.detect(route(consecutive))

        val branch = sections.single() as HardSection.ConsecutiveBranches
        assertEquals(40_000.0, branch.startM, 1e-9)
        assertEquals(3, branch.count)
        assertEquals("오른쪽", branch.lastDirection)
    }

    @Test
    fun `branches farther than 500m apart are not consecutive`() {
        val sections = HardSectionDetector.detect(route(listOf(
            Turn(TurnKind.RIGHT, 1_000.0, "a"),
            Turn(TurnKind.LEFT, 1_600.0, "b"),
        )))

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `a straight or tunnel breaks the branch chain`() {
        val sections = HardSectionDetector.detect(route(listOf(
            Turn(TurnKind.RIGHT, 1_000.0, "a"),
            Turn(TurnKind.TUNNEL, 1_100.0, "터널"),
            Turn(TurnKind.LEFT, 1_200.0, "b"),
        )))

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `6_2 uturn and roundabout are complex intersections, congestion entries are found`() {
        val sections = HardSectionDetector.detect(route(
            turns = listOf(Turn(TurnKind.UTURN, 2_000.0, "유턴"), Turn(TurnKind.ROUNDABOUT, 9_000.0, "회전")),
            congestions = listOf(
                Congestion(5_000.0, 3_000.0, CongestionLevel.HEAVY, "경부고속도로"),
                Congestion(7_000.0, 1_000.0, CongestionLevel.FREE, "x"),
            ),
        ))

        assertEquals(listOf(2_000.0, 5_000.0, 9_000.0), sections.map { it.startM })
    }

    @Test
    fun `6_3 briefing text`() {
        val r = route(consecutive)

        assertEquals(
            "오늘 길은 2시간 30분이에요. 40km 지점에서 분기가 3번 연속 나와요. 마지막은 오른쪽으로 가세요.",
            BriefingComposer.briefing(r, HardSectionDetector.detect(r))
        )
    }

    @Test
    fun `6_3 no hard sections`() {
        assertEquals("오늘 길은 25분, 어렵지 않은 길이에요.", BriefingComposer.briefing(route(durationSec = 1_500), emptyList()))
    }

    @Test
    fun `briefing keeps at most three sections and stays within 30 seconds`() {
        val congestions = (1..6).map { Congestion(it * 10_000.0, 2_000.0, CongestionLevel.SLOW, "아주아주긴도로이름고속도로$it") }
        val r = route(congestions = congestions)

        val text = BriefingComposer.briefing(r, HardSectionDetector.detect(r))

        assertTrue(text.split("정체가 있어요").size - 1 <= BriefingComposer.MAX_SECTIONS)
        assertTrue(BriefingComposer.estimatedMs(text) <= BriefingComposer.MAX_BRIEFING_MS)
    }

    @Test
    fun `korean subject particle`() {
        assertEquals("유턴이", BriefingComposer.withSubject("유턴"))
        assertEquals("회전교차로가", BriefingComposer.withSubject("회전교차로"))
        assertEquals("곧 회전교차로가 있어요.", BriefingComposer.preAlert(HardSection.ComplexIntersection(500.0, TurnKind.ROUNDABOUT, "")))
    }

    @Test
    fun `distance and duration text`() {
        assertEquals("800m", BriefingComposer.distanceText(760.0))
        assertEquals("1.2km", BriefingComposer.distanceText(1_240.0))
        assertEquals("3km", BriefingComposer.distanceText(3_000.0))
        assertEquals("40km", BriefingComposer.distanceText(40_200.0))
        assertEquals("1시간", BriefingComposer.durationText(3_600))
        assertEquals("1시간 5분", BriefingComposer.durationText(3_900))
    }

    @Test
    fun `F1 step 3 briefing plays once while parked`() {
        val policy = BriefingPolicy()
        val r = route(consecutive)

        val first = policy.onRoute(r, DrivingState.PARKED)
        val again = policy.onRoute(r, DrivingState.PARKED)

        assertTrue(first.single() is BriefingAction.SpeakBriefing)
        assertTrue(again.isEmpty())
    }

    @Test
    fun `route received while driving briefs at the next stop`() {
        val policy = BriefingPolicy()
        val r = route(consecutive)

        assertTrue(policy.onRoute(r, DrivingState.DRIVING).isEmpty())
        assertTrue(policy.onDrivingState(DrivingState.PARKED, r).single() is BriefingAction.SpeakBriefing)
    }

    @Test
    fun `parked state handled before the route still briefs the hard sections`() {
        val policy = BriefingPolicy()
        val r = route(consecutive)

        val action = policy.onDrivingState(DrivingState.PARKED, r).single() as BriefingAction.SpeakBriefing

        assertTrue(action.text, action.text.contains("분기가"))
    }

    @Test
    fun `F1-01 starting to drive stops the briefing`() {
        val policy = BriefingPolicy()
        policy.onRoute(route(consecutive), DrivingState.PARKED)

        assertEquals(listOf(BriefingAction.StopSpeaking), policy.onDrivingState(DrivingState.DRIVING, null))
        assertTrue(policy.onDrivingState(DrivingState.DRIVING, null).isEmpty())
    }

    @Test
    fun `F1-02 rerouting recalculates but does not brief again`() {
        val policy = BriefingPolicy()
        policy.onRoute(route(consecutive), DrivingState.PARKED)
        policy.onBriefingFinished()

        val rerouted = route(listOf(Turn(TurnKind.UTURN, 800.0, "유턴")))
        assertTrue(policy.onRoute(rerouted, DrivingState.PARKED).isEmpty())
        assertEquals(listOf(BriefingAction.SpeakAlert("곧 유턴이 있어요.")), policy.onTick(rerouted, DrivingState.DRIVING))
    }

    @Test
    fun `step 4 pre-alert once within 1km`() {
        val policy = BriefingPolicy()
        val far = route(consecutive)
        policy.onRoute(far, DrivingState.DRIVING)
        assertTrue(policy.onTick(far, DrivingState.DRIVING).isEmpty())

        val near = route(consecutive.map { it.copy(distanceM = it.distanceM - 39_100.0) })
        policy.onRoute(near, DrivingState.DRIVING)

        assertEquals(listOf(BriefingAction.SpeakAlert("곧 연속 분기예요, 오른쪽이에요.")), policy.onTick(near, DrivingState.DRIVING))
        assertTrue(policy.onTick(near, DrivingState.DRIVING).isEmpty())
    }

    @Test
    fun `F1-03 waits while another turn is about to be announced`() {
        val policy = BriefingPolicy()
        val r = route(listOf(Turn(TurnKind.RIGHT, 200.0, "앞 회전"), Turn(TurnKind.UTURN, 900.0, "유턴")))
        policy.onRoute(r, DrivingState.DRIVING)

        assertTrue(policy.onTick(r, DrivingState.DRIVING).isEmpty())
    }
}
