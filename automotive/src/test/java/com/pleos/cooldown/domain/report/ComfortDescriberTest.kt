package com.pleos.cooldown.domain.report

import com.pleos.cooldown.domain.cooldown.ComfortLearner
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.ComfortSignal
import com.pleos.cooldown.domain.cooldown.SeasonBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComfortDescriberTest {

    @Test
    fun `default intensities match screen spec 7`() {
        val p = ComfortProfile.DEFAULT
        assertEquals("최대 −2℃ · 바람 · 통풍시트", ComfortDescriber.intensity(p, SeasonBand.SUMMER))
        assertEquals("최대 −1℃ · 바람", ComfortDescriber.intensity(p, SeasonBand.MILD))
        assertEquals("바람만 · 얼굴 쪽", ComfortDescriber.intensity(p, SeasonBand.WINTER))
        assertEquals("지금은 여름 모드 · 최대 −2℃", ComfortDescriber.currentMode(p, SeasonBand.SUMMER))
    }

    @Test
    fun `learned level and baseline temperature`() {
        val lowered = ComfortLearner.apply(ComfortProfile.DEFAULT, SeasonBand.WINTER, ComfortSignal.STRONG_COLD)
        assertEquals("이 계절엔 손대지 않아요", ComfortDescriber.intensity(lowered, SeasonBand.WINTER))
        assertNull(ComfortDescriber.baselineTemp(ComfortProfile.DEFAULT, SeasonBand.SUMMER))
        val withTemp = ComfortLearner.observeTripStartTemp(ComfortProfile.DEFAULT, SeasonBand.SUMMER, 24.2f)
        assertEquals("여름엔 보통 24℃에 두세요", ComfortDescriber.baselineTemp(withTemp, SeasonBand.SUMMER))
    }
}
