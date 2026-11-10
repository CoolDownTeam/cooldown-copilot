package com.pleos.cooldown.domain.report

/** 개입 종류 (명세 7.3) */
enum class InterventionKind { COOLDOWN, FRESH_AIR, FATIGUE_HOLD_ON, FATIGUE_SUGGEST }

/** 개입이 어떻게 끝났는지. 리포트 문장을 고르는 데 쓴다 */
enum class InterventionResult {
    /** 평온해져서 되돌렸거나 주행이 끝났다 */
    DONE,
    /** 운전자가 춥다고 했다 (F2-15) */
    COLD,
    /** 운전자가 그만하라고 했다 */
    STOPPED,
    /** 운전자가 직접 조작해서 손을 뗐다 (SAFE-03) */
    YIELDED,
    FAILED,
    /** 휴게소 제안을 받아들였다 */
    ACCEPTED,
    DECLINED,
    /** 휴게소에 들러 정차했다 */
    RESTED,
}

data class InterventionRecord(
    val startMs: Long,
    val endMs: Long?,
    val kind: InterventionKind,
    val maxStage: Int,
    val result: InterventionResult,
    /** 이 개입 뒤 F2 강도가 바뀐 만큼 (−1이면 "다음엔 덜 차갑게 할게요") */
    val levelChange: Int = 0,
    /**
     * 쿨다운을 시작하고 공격성이 차분 기준 아래로 내려와 2분 넘게 유지되기 시작한 시점까지 걸린 시간 (개입 효과).
     * 쿨다운이 아니거나 개입이 끝날 때까지 내려오지 않았으면 null
     */
    val recoveryMs: Long? = null,
) {
    val durationMs: Long? get() = endMs?.let { it - startMs }
}

/** 주행 1회 요약. 원시 센서 로그는 저장하지 않는다 (SAFE-05) */
data class TripRecord(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Float?,
    val avgAggression: Int,
    val maxAggression: Int,
    val avgFatigue: Int,
    val maxFatigue: Int,
    /** 이 주행이 학습 모드였는지 (C0-10) */
    val learning: Boolean,
    /** 이 주행까지 쌓인 주행 횟수. 학습 진행 "3/5회"에 쓴다 */
    val learnedTrips: Int,
    /** F1 직전 안내로 지나간 어려운 구간 수. 경로 안내가 없었으면 null */
    val hardSections: Int?,
    /** 공격성이 차분 기준 아래로 이어진 가장 긴 시간 */
    val longestCalmMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

data class TripWithInterventions(val trip: TripRecord, val interventions: List<InterventionRecord>)
