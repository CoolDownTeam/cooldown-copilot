package com.pleos.cooldown.domain.cooldown

/**
 * F2-11: STT로 받은 한마디를 반응으로 바꾼다. 알 수 없으면 null (개입 계속).
 */
object FeedbackParser {

    private val cold = listOf("추워", "춥다", "추운데", "춥네", "추워요")
    private val stop = listOf("그만", "됐어", "꺼 줘", "꺼줘", "하지 마", "하지마")

    fun parse(text: String?): Feedback? {
        val normalized = text?.trim()?.lowercase() ?: return null
        if (normalized.isEmpty()) return null
        return when {
            cold.any { it in normalized } -> Feedback.COLD
            stop.any { it in normalized } -> Feedback.STOP
            else -> null
        }
    }
}
