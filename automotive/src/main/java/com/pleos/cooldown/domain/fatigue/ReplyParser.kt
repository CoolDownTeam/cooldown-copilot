package com.pleos.cooldown.domain.fatigue

/**
 * F3-03: 휴게소 제안에 대한 한마디를 수락·거절로 바꾼다. 알 수 없으면 null (거절로 처리).
 */
object ReplyParser {

    private val decline = listOf("아니", "괜찮아", "됐어", "싫어", "그냥 갈", "안 들러", "안들러")
    private val accept = listOf("응", "네", "예", "그래", "좋아", "가자", "들르자", "들를게", "오케이")

    fun parse(text: String?): Boolean? {
        val normalized = text?.trim()?.lowercase() ?: return null
        if (normalized.isEmpty()) return null
        // "아니 괜찮아"처럼 거절 말이 섞이면 거절을 먼저 본다
        return when {
            decline.any { it in normalized } -> false
            accept.any { it in normalized } -> true
            else -> null
        }
    }
}
