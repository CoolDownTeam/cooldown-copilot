package com.pleos.cooldown.domain.report

import com.pleos.cooldown.domain.cooldown.InterventionOutcome
import com.pleos.cooldown.domain.fatigue.FatiguePhase
import com.pleos.cooldown.domain.model.DriverState
import com.pleos.cooldown.domain.model.SpeedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TripReportTest {

    private val utc = ZoneOffset.UTC
    /** 1970-01-01 18:52 UTC */
    private val t0 = (18 * 60 + 52) * 60_000L

    private fun state(ms: Long, aggression: Int, fatigue: Int = 10) =
        DriverState(ms, aggression, fatigue, SpeedBand.URBAN, learning = false)

    private fun trip(avg: Int, startMs: Long = t0, id: Long = 0, durationMs: Long = 20 * 60_000L) = TripRecord(
        id = id, startMs = startMs, endMs = startMs + durationMs, distanceKm = null,
        avgAggression = avg, maxAggression = avg, avgFatigue = 10, maxFatigue = 10,
        learning = false, learnedTrips = 6, hardSections = null, longestCalmMs = 0,
    )

    @Test
    fun `recorder averages scores, tracks longest calm and distance`() {
        val r = TripRecorder()
        r.start(t0, odometerKm = 100f)
        listOf(10, 20, 30, 70, 10, 10, 10, 10).forEachIndexed { i, a -> r.onState(state(t0 + i * 60_000L, a, fatigue = i * 10)) }

        val trip = r.finish(t0 + 8 * 60_000L, odometerKm = 112.5f, learning = true, learnedTrips = 3)?.trip ?: error("no trip")

        assertEquals(21, trip.avgAggression)
        assertEquals(70, trip.maxAggression)
        assertEquals(70, trip.maxFatigue)
        assertEquals(3 * 60_000L, trip.longestCalmMs)
        assertEquals(12.5f, trip.distanceKm ?: 0f, 1e-3f)
        assertNull(trip.hardSections)
        assertTrue(trip.learning)
    }

    @Test
    fun `recorder keeps intervention outcomes and learning change`() {
        val r = TripRecorder()
        r.start(t0, null)
        r.onCooldownStarted(t0 + 60_000)
        r.onCooldownEnded(t0 + 90_000, InterventionOutcome.COLD_FEEDBACK, maxStage = 1)
        r.onComfortLevelChanged(-1)
        r.onFatiguePhase(t0 + 100_000, FatiguePhase.IDLE)
        r.onFatiguePhase(t0 + 200_000, FatiguePhase.AWAITING_REPLY)
        r.onFatiguePhase(t0 + 208_000, FatiguePhase.HEADING_TO_REST)
        r.onRested()
        r.onFreshAirStarted(t0 + 300_000)

        val list = r.finish(t0 + 400_000, null, false, 7)?.interventions ?: error("no trip")

        assertEquals(listOf(InterventionKind.COOLDOWN, InterventionKind.FATIGUE_SUGGEST, InterventionKind.FRESH_AIR), list.map { it.kind })
        assertEquals(InterventionResult.COLD, list[0].result)
        assertEquals(-1, list[0].levelChange)
        assertEquals(InterventionResult.RESTED, list[1].result)
        assertEquals(t0 + 400_000, list[2].endMs)
    }

    @Test
    fun `recorder ignores events outside a trip`() {
        val r = TripRecorder()
        r.onCooldownStarted(t0)
        assertNull(r.finish(t0 + 1, null, false, 0))
    }

    @Test
    fun `hard sections are counted only when a route was seen`() {
        val r = TripRecorder()
        r.start(t0, null)
        r.onRouteSeen()
        r.onHardSectionAlert()
        r.onHardSectionAlert()
        val trip = r.finish(t0 + 1, null, false, 0)?.trip ?: error("no trip")
        assertEquals(2, trip.hardSections)
        assertEquals("어려운 구간" to "2곳 통과", TripSummarizer.thirdStat(trip))
    }

    @Test
    fun `headline compares with recent trips`() {
        val previous = listOf(trip(30, startMs = t0 - 100_000), trip(30, startMs = t0 - 200_000))
        assertEquals(TripSummarizer.HEADLINE_CALM, TripSummarizer.headline(trip(20), previous))
        assertEquals(TripSummarizer.HEADLINE_SIMILAR, TripSummarizer.headline(trip(33), previous))
        assertEquals(TripSummarizer.HEADLINE_BUSY, TripSummarizer.headline(trip(40), previous))
        assertEquals(TripSummarizer.HEADLINE_SIMILAR, TripSummarizer.headline(trip(20), emptyList()))
    }

    @Test
    fun `timeline sentences show learning results`() {
        val lines = TripSummarizer.timeline(
            listOf(
                InterventionRecord(t0, t0 + 4 * 60_000L, InterventionKind.COOLDOWN, 1, InterventionResult.DONE),
                InterventionRecord(t0 + 18 * 60_000L, t0 + 18 * 60_000L + 5_000, InterventionKind.COOLDOWN, 1, InterventionResult.COLD, levelChange = -1),
                InterventionRecord(t0 + 48 * 60_000L, t0 + 49 * 60_000L, InterventionKind.FATIGUE_SUGGEST, 1, InterventionResult.RESTED),
            ),
            utc,
        )
        assertEquals(
            listOf(
                "18:52 · 4분 — 시원하게 해 드렸어요",
                "19:10 — 추워하셔서 바로 되돌렸어요 · 다음엔 덜 차갑게 할게요",
                "19:40 — 휴게소를 제안했어요 · 들르셨어요",
            ),
            lines,
        )
    }

    @Test
    fun `timeline keeps at most four`() {
        val many = (0 until 6).map { InterventionRecord(t0 + it * 60_000L, null, InterventionKind.FRESH_AIR, 1, InterventionResult.DONE) }
        assertEquals(TripSummarizer.MAX_TIMELINE, TripSummarizer.timeline(many, utc).size)
    }

    @Test
    fun `home summary line`() {
        assertEquals("도와드릴 일 없이 평온했어요", TripSummarizer.interventionSummary(emptyList()))
        assertEquals(
            "쿨다운 1회 · 추워하셔서 바로 되돌렸어요",
            TripSummarizer.interventionSummary(listOf(InterventionRecord(t0, t0, InterventionKind.COOLDOWN, 1, InterventionResult.COLD))),
        )
    }

    @Test
    fun `report auto shows only after five minutes`() {
        assertTrue(TripSummarizer.shouldAutoShow(trip(20, durationMs = 5 * 60_000L)))
        assertEquals(false, TripSummarizer.shouldAutoShow(trip(20, durationMs = 4 * 60_000L)))
        assertEquals("1시간 5분", TripSummarizer.durationText(65 * 60_000L))
    }
}
