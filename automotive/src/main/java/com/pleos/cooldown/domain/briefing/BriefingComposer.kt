package com.pleos.cooldown.domain.briefing

import kotlin.math.roundToInt

/**
 * F1 문장 템플릿 (명세 6.3). 순수 함수.
 */
object BriefingComposer {

    const val MAX_SECTIONS = 3
    const val MAX_BRIEFING_MS = 30_000L

    /** 한국어 TTS 길이 어림 (PleosVoiceOutput과 같은 기준) */
    const val MS_PER_CHAR = 180L

    /** 정차 중 브리핑. 30초를 넘지 않도록 구간 수를 줄인다 (F1 단계 3) */
    fun briefing(route: RouteSnapshot, sections: List<HardSection>): String {
        val time = route.totalDurationSec?.let { durationText(it) }
        if (sections.isEmpty()) {
            return if (time != null) "오늘 길은 ${time}, 어렵지 않은 길이에요." else "오늘 길은 어렵지 않은 길이에요."
        }
        val opening = if (time != null) "오늘 길은 ${time}이에요." else "오늘 길에 조심할 곳이 있어요."
        var picked = sections.take(MAX_SECTIONS)
        while (true) {
            val text = (listOf(opening) + picked.map(::sectionSentence)).joinToString(" ")
            if (estimatedMs(text) <= MAX_BRIEFING_MS || picked.size <= 1) return text
            picked = picked.dropLast(1)
        }
    }

    /** 주행 중 구간 1km 전 한 문장 (SAFE-02) */
    fun preAlert(section: HardSection): String = when (section) {
        is HardSection.ConsecutiveBranches ->
            section.lastDirection?.let { "곧 연속 분기예요, ${it}이에요." } ?: "곧 연속 분기예요."
        is HardSection.ComplexIntersection -> "곧 ${withSubject(kindName(section.kind))} 있어요."
        is HardSection.CongestionEntry -> "곧 정체 구간이에요."
    }

    fun sectionSentence(section: HardSection): String = when (section) {
        is HardSection.ConsecutiveBranches -> {
            val head = "${distanceText(section.startM)} 지점에서 분기가 ${section.count}번 연속 나와요."
            section.lastDirection?.let { "$head 마지막은 ${it}으로 가세요." } ?: head
        }
        is HardSection.ComplexIntersection -> "${distanceText(section.startM)} 지점에 ${withSubject(kindName(section.kind))} 있어요."
        is HardSection.CongestionEntry -> {
            val road = section.roadName.ifBlank { "가는 길" }
            "${road}에서 ${distanceText(section.lengthM)} 정체가 있어요."
        }
    }

    fun distanceText(meters: Double): String = when {
        meters < 1_000 -> "${(meters / 100).roundToInt().coerceAtLeast(1) * 100}m"
        meters < 10_000 -> {
            val km = (meters / 100).roundToInt() / 10.0
            if (km % 1.0 == 0.0) "${km.toInt()}km" else "${km}km"
        }
        else -> "${(meters / 1_000).roundToInt()}km"
    }

    fun durationText(seconds: Int): String {
        val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    fun estimatedMs(text: String): Long = text.count { !it.isWhitespace() } * MS_PER_CHAR

    /** 받침이 있으면 "이", 없으면 "가" (예: 유턴이, 회전교차로가) */
    fun withSubject(word: String): String {
        val last = word.lastOrNull() ?: return word
        val hasFinal = last in '가'..'힣' && (last.code - '가'.code) % 28 != 0
        return word + if (hasFinal) "이" else "가"
    }

    private fun kindName(kind: TurnKind): String = when (kind) {
        TurnKind.UTURN -> "유턴"
        TurnKind.ROUNDABOUT -> "회전교차로"
        else -> "복잡한 교차로"
    }
}
