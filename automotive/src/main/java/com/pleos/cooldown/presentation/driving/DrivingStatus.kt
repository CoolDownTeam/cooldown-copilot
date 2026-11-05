package com.pleos.cooldown.presentation.driving

import com.pleos.cooldown.domain.cooldown.CooldownPhase
import com.pleos.cooldown.domain.fatigue.FatiguePhase

/** SC-03 주행 중 화면 상태 한 줄 (화면 명세 5절) */
enum class DrivingStatus(val text: String) {
    CALM("평온하게 주행 중"),
    LEARNING("운전 습관 배우는 중"),
    COOLING("시원하게 해 드리는 중"),
    FRESH_AIR("환기하는 중"),
    REST("쉬어 갈 곳을 찾는 중");

    companion object {
        /** 운전자가 지금 알아야 할 것부터: 휴게소 > 쿨다운 > 환기 > 학습 > 평온 */
        fun of(cooldown: CooldownPhase, fatigue: FatiguePhase, freshAir: Boolean, learning: Boolean): DrivingStatus = when {
            fatigue == FatiguePhase.AWAITING_REPLY || fatigue == FatiguePhase.HEADING_TO_REST -> REST
            cooldown == CooldownPhase.STAGE1 || cooldown == CooldownPhase.STAGE2 || cooldown == CooldownPhase.RESTORING -> COOLING
            // 피로 케어의 공조 유지(HOLD_ON)도 바깥 공기를 들이므로 환기로 보여 준다
            freshAir || fatigue == FatiguePhase.HOLD_ON -> FRESH_AIR
            learning -> LEARNING
            else -> CALM
        }
    }
}
