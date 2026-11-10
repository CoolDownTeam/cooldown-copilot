package com.pleos.cooldown.domain.report

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * F4 리포트 문장 (화면 명세 4절). 점수는 내보내지 않고 문장으로 바꾼다 (UI-04).
 * 운전자를 탓하는 표현은 쓰지 않는다.
 */
object TripSummarizer {

    const val HEADLINE_CALM = "평소보다 차분한 주행이었어요"
    const val HEADLINE_SIMILAR = "평소와 비슷했어요"
    const val HEADLINE_BUSY = "오늘은 조금 바빴네요"

    /** 리포트를 자동으로 띄우는 최소 주행 시간 (명세 7.1) */
    const val AUTO_SHOW_MIN_MS = 5 * 60_000L
    const val TREND_TRIPS = 7
    const val MAX_TIMELINE = 4

    /** 평소와 이만큼 차이 나면 "차분"·"바쁨"으로 본다 (점수 단위) */
    const val HEADLINE_MARGIN = 6

    /** 평소 회복 시간과 비교하려면 지난 쿨다운 기록이 이만큼 있어야 한다 */
    const val RECOVERY_COMPARE_MIN = 3
    const val RECOVERY_MARGIN_MS = 60_000L

    /** 비교할 지난 주행이 없을 때 기준. 기준선과 같은 운전이 20점 안팎이다 */
    const val FIRST_TRIP_REFERENCE = 20

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

    /** [previous]는 이번 주행 이전 기록 (최근 것부터) */
    fun headline(trip: TripRecord, previous: List<TripRecord>): String {
        val recent = previous.filter { it.startMs < trip.startMs }.take(TREND_TRIPS)
        val reference = if (recent.isEmpty()) FIRST_TRIP_REFERENCE.toDouble() else recent.map { it.avgAggression }.average()
        val diff = trip.avgAggression - reference
        return when {
            diff <= -HEADLINE_MARGIN -> HEADLINE_CALM
            diff >= HEADLINE_MARGIN -> HEADLINE_BUSY
            else -> HEADLINE_SIMILAR
        }
    }

    fun shouldAutoShow(trip: TripRecord): Boolean = trip.durationMs >= AUTO_SHOW_MIN_MS

    /** 리포트 타임라인. 시간순, 최대 4개 */
    fun timeline(interventions: List<InterventionRecord>, zone: ZoneId): List<String> =
        interventions.sortedBy { it.startMs }.take(MAX_TIMELINE).map { timelineLine(it, zone) }

    fun timelineLine(record: InterventionRecord, zone: ZoneId): String {
        val clock = clockFormat.format(Instant.ofEpochMilli(record.startMs).atZone(zone))
        val minutes = record.durationMs?.let { minutesText(it) }
        val head = if (minutes != null && record.result in timedResults) "$clock · $minutes" else clock
        return "$head — ${sentence(record)}"
    }

    /**
     * 개입 효과 한 줄: 쿨다운 뒤 차분해지기까지 걸린 시간의 평균.
     * 지난 기록이 있으면 평소와 비교한다. 쿨다운이 없었거나 측정하지 못했으면 null
     */
    fun recoveryText(interventions: List<InterventionRecord>, previous: List<InterventionRecord> = emptyList()): String? {
        val now = recoveries(interventions).takeIf { it.isNotEmpty() }?.average() ?: return null
        val text = "시원하게 해 드린 뒤 평균 ${minutesText(now.toLong())} 만에 차분해졌어요"
        val usual = recoveries(previous).takeIf { it.size >= RECOVERY_COMPARE_MIN }?.average() ?: return text
        return when {
            now <= usual - RECOVERY_MARGIN_MS -> "$text (평소보다 빨라요)"
            now >= usual + RECOVERY_MARGIN_MS -> "$text (평소보다 오래 걸렸어요)"
            else -> text
        }
    }

    private fun recoveries(list: List<InterventionRecord>) =
        list.filter { it.kind == InterventionKind.COOLDOWN }.mapNotNull { it.recoveryMs }

    /** 홈 "지난 주행" 카드의 개입 요약 한 줄 */
    fun interventionSummary(interventions: List<InterventionRecord>): String {
        if (interventions.isEmpty()) return "도와드릴 일 없이 평온했어요"
        val counts = InterventionKind.entries.mapNotNull { kind ->
            val n = interventions.count { it.kind == kind }
            if (n == 0) null else "${kindLabel(kind)} ${n}회"
        }
        val note = interventions.lastOrNull { it.result == InterventionResult.COLD }?.let { "추워하셔서 바로 되돌렸어요" }
            ?: interventions.lastOrNull { it.result == InterventionResult.RESTED }?.let { "휴게소에 들르셨어요" }
        return (counts + listOfNotNull(note)).joinToString(" · ")
    }

    /** 숫자 3개 중 세 번째: F1 브리핑이 있었으면 어려운 구간, 없으면 최고 연속 차분 시간 */
    fun thirdStat(trip: TripRecord): Pair<String, String> = trip.hardSections?.let { "어려운 구간" to "${it}곳 통과" }
        ?: ("최고 연속 차분" to minutesText(trip.longestCalmMs))

    fun learningText(trip: TripRecord, learningTrips: Int): String? =
        if (trip.learning) "운전 습관을 배우는 중이에요 (${trip.learnedTrips.coerceAtMost(learningTrips)}/${learningTrips}회)" else null

    fun dateText(ms: Long, zone: ZoneId): String = dateFormat.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun durationText(ms: Long): String {
        val totalMinutes = (ms / 60_000.0).roundToInt()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    fun minutesText(ms: Long): String = "${(ms / 60_000.0).roundToInt().coerceAtLeast(1)}분"

    private val timedResults = setOf(InterventionResult.DONE, InterventionResult.FAILED)

    private fun kindLabel(kind: InterventionKind) = when (kind) {
        InterventionKind.COOLDOWN -> "쿨다운"
        InterventionKind.FRESH_AIR -> "환기"
        InterventionKind.FATIGUE_HOLD_ON -> "졸음 케어"
        InterventionKind.FATIGUE_SUGGEST -> "휴게소 제안"
    }

    private fun sentence(r: InterventionRecord): String {
        val base = when (r.kind) {
            InterventionKind.COOLDOWN -> when (r.result) {
                InterventionResult.COLD -> "추워하셔서 바로 되돌렸어요"
                InterventionResult.STOPPED -> "그만하라고 하셔서 멈췄어요"
                InterventionResult.YIELDED -> "직접 조절하셔서 손을 뗐어요"
                InterventionResult.FAILED -> "공조가 응답하지 않아 멈췄어요"
                else -> "시원하게 해 드렸어요"
            }
            InterventionKind.FRESH_AIR -> if (r.result == InterventionResult.YIELDED) {
                "환기하다가 직접 조절하셔서 멈췄어요"
            } else "바깥 공기로 환기했어요"
            InterventionKind.FATIGUE_HOLD_ON -> when (r.result) {
                InterventionResult.YIELDED -> "졸음 케어 중 직접 조절하셔서 손을 뗐어요"
                InterventionResult.FAILED -> "공조가 응답하지 않아 멈췄어요"
                else -> "졸음이 오지 않게 시원하게 해 드렸어요"
            }
            InterventionKind.FATIGUE_SUGGEST -> when (r.result) {
                InterventionResult.RESTED -> "휴게소를 제안했어요 · 들르셨어요"
                InterventionResult.ACCEPTED -> "휴게소를 제안했어요 · 경로에 넣었어요"
                else -> "휴게소를 제안했어요 · 다음에 쉬기로 했어요"
            }
        }
        val learned = when {
            r.levelChange < 0 -> " · 다음엔 덜 차갑게 할게요"
            r.levelChange > 0 -> " · 다음엔 조금 더 시원하게 할게요"
            else -> ""
        }
        return base + learned
    }
}
