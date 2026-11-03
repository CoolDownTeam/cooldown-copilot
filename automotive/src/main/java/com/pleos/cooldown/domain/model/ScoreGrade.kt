package com.pleos.cooldown.domain.model

/**
 * 점수 등급. 기록 화면이 F4 리포트로 바뀔 때(11/9) 함께 정리한다.
 */
enum class ScoreGrade(val label: String, val emoji: String) {
    EXCELLENT("우수", "S"),
    GOOD("양호", "A"),
    AVERAGE("보통", "B"),
    POOR("주의", "C"),
    BAD("위험", "D")
}
