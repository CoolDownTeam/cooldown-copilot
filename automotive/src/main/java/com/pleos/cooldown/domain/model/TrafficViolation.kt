package com.pleos.cooldown.domain.model

/**
 * 교통법규 위반 유형 - 대한민국 도로교통법 기준
 * 벌점이 높은 순서로 정렬됨
 */
enum class TrafficViolation(
    val score: Int,
    val description: String,
    val demeritPoints: Int  // 실제 벌점
) {
    // 크리티컬 위반 (즉시 게임오버급)
    SPEEDING_80_PLUS(-100, "80km/h 이상 과속", 80),

    // 중대 위반
    SPEEDING_60_TO_80(-60, "60~80km/h 과속", 60),
    CENTER_LINE_VIOLATION(-30, "중앙선 침범", 30),
    SPEEDING_40_TO_60(-30, "40~60km/h 과속", 30),

    // 일반 위반
    SIGNAL_VIOLATION(-15, "신호위반", 15),
    SPEEDING_20_TO_40(-15, "20~40km/h 과속", 15),
    UNSAFE_DISTANCE(-10, "안전거리 미확보", 10),
    PEDESTRIAN_VIOLATION(-10, "보행자보호의무 위반", 10),
    LANE_CHANGE_VIOLATION(-10, "끼어들기", 10),

    // 경미한 위반
    NO_TURN_SIGNAL(-5, "방향지시등 미사용", 5),
    SPEEDING_UNDER_20(-3, "20km/h 이하 과속", 0),

    // 긍정 행동 (가산점)
    ACCIDENT_ZONE_SLOW_DOWN(20, "사고다발구역 서행", 0),
    SAFE_DRIVING_PER_MINUTE(10, "안전운전 유지 (1분)", 0),
    PROPER_DISTANCE(5, "안전거리 유지", 0),
    PROPER_SIGNAL(5, "방향지시등 사용", 0),
    SPEED_LIMIT_COMPLIANCE(3, "제한속도 준수", 0);

    val isViolation: Boolean
        get() = score < 0

    val isPositive: Boolean
        get() = score > 0
}
