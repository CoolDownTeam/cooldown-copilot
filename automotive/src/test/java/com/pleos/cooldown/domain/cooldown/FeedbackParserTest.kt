package com.pleos.cooldown.domain.cooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackParserTest {

    @Test
    fun `F2-11 cold words`() {
        listOf("추워", "아 추워요", "좀 춥다", "추운데", "춥네").forEach {
            assertEquals(it, Feedback.COLD, FeedbackParser.parse(it))
        }
    }

    @Test
    fun `F2-11 stop words`() {
        listOf("그만", "됐어", "꺼 줘", "꺼줘", "하지 마").forEach {
            assertEquals(it, Feedback.STOP, FeedbackParser.parse(it))
        }
    }

    @Test
    fun `anything else keeps the intervention going`() {
        listOf(null, "", "  ", "응", "좋아", "시원하다").forEach {
            assertNull(it, FeedbackParser.parse(it))
        }
    }
}
