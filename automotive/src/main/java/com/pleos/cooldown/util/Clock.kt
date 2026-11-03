package com.pleos.cooldown.util

/**
 * 시간 소스. 테스트에서는 가짜 시계로 바꿔 끼운다.
 */
fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
