package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频兼容实验：默认关闭、对所有车型生效、两个 A/B 位可独立组合。
 *
 * 红线：OFF 时两个策略位都为 false（与加开关前逐字节一致）。
 */
class AudioCompatExperimentTest {
    @Test
    fun defaultIsOffAndBothStrategiesInactive() {
        assertEquals(AudioCompatExperiment.OFF, AudioCompatExperiment.parse(null))
        assertEquals(AudioCompatExperiment.OFF, AudioCompatExperiment.parse("bogus"))
        assertFalse(AudioCompatExperiment.OFF.skipNav)
        assertFalse(AudioCompatExperiment.OFF.borrowAudio)
        assertFalse(AudioCompatExperiment.OFF.isEnabled)
    }

    @Test
    fun strategyBitsMatchEachMode() {
        assertTrue(AudioCompatExperiment.SKIP_NAV.skipNav)
        assertFalse(AudioCompatExperiment.SKIP_NAV.borrowAudio)
        assertFalse(AudioCompatExperiment.BORROW_AUDIO.skipNav)
        assertTrue(AudioCompatExperiment.BORROW_AUDIO.borrowAudio)
        assertTrue(AudioCompatExperiment.BOTH.skipNav)
        assertTrue(AudioCompatExperiment.BOTH.borrowAudio)
        assertTrue(AudioCompatExperiment.BOTH.isEnabled)
    }

    @Test
    fun cycleVisitsAllFourModesAndReturnsToOff() {
        var mode = AudioCompatExperiment.OFF
        val seen = mutableListOf(mode)
        repeat(4) {
            mode = mode.next()
            seen += mode
        }
        assertEquals(
            listOf(
                AudioCompatExperiment.OFF,
                AudioCompatExperiment.SKIP_NAV,
                AudioCompatExperiment.BORROW_AUDIO,
                AudioCompatExperiment.BOTH,
                AudioCompatExperiment.OFF,
            ),
            seen,
        )
    }
}
