package com.pleos.cooldown.domain.report

import com.pleos.cooldown.domain.cooldown.ComfortLearner
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.SeasonBand
import kotlin.math.roundToInt

/** 설정 "배운 내 취향"과 홈 쿨다운 카드 문장 (화면 명세 3·7절, F2-07) */
object ComfortDescriber {

    fun seasonName(band: SeasonBand) = when (band) {
        SeasonBand.SUMMER -> "여름"
        SeasonBand.MILD -> "봄가을"
        SeasonBand.WINTER -> "겨울"
    }

    /** "최대 −2℃ · 바람 · 통풍시트" / "최대 −1℃ · 바람" / "바람만 · 얼굴 쪽" */
    fun intensity(profile: ComfortProfile, band: SeasonBand): String {
        val plan = ComfortLearner.plan(profile, band) ?: return "이 계절엔 손대지 않아요"
        val parts = mutableListOf<String>()
        if (plan.maxDropC > 0f) {
            parts += "최대 −${plan.maxDropC.roundToInt()}℃"
            parts += "바람"
        } else {
            parts += "바람만"
        }
        if (plan.faceDirection) parts += "얼굴 쪽"
        if (plan.seatVentilation) parts += "통풍시트"
        return parts.joinToString(" · ")
    }

    /** 홈 카드: "지금은 여름 모드 · 최대 −2℃" */
    fun currentMode(profile: ComfortProfile, band: SeasonBand): String =
        "지금은 ${seasonName(band)} 모드 · ${intensity(profile, band).substringBefore(" · ")}"

    /** "여름엔 보통 24℃에 두세요". 아직 모르면 null */
    fun baselineTemp(profile: ComfortProfile, band: SeasonBand): String? =
        profile.band(band).baselineTempC?.let { "${seasonName(band)}엔 보통 ${it.roundToInt()}℃에 두세요" }
}
