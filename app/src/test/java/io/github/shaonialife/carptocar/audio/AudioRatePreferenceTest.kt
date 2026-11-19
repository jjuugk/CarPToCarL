package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 采样率策略 → 候选档排序。
 *
 * 默认档是「跟随车机」：用引擎（车机协商后）实际宣告的采样率排序候选，
 * 44.1k 就协商 44.1k、48k 就协商 48k，不再写死 48k 优先。
 * 关键约束：只重排不删除——车机若只吃另一率的档，仍要能兜底出声。
 */
class AudioRatePreferenceTest {
    private fun profile(rate: Int) = AudioProfile("media", 1L shl 11, rate, 250)

    @Before
    fun resetAnnouncedRate() {
        AudioRatePreference.resetAnnouncedMediaRate()
    }

    @Test
    fun autoFollowsAnnouncedRate() {
        AudioRatePreference.noteAnnouncedMediaRate(44_100)
        assertEquals(44_100, AudioRatePreference.AUTO.preferredRate())

        AudioRatePreference.noteAnnouncedMediaRate(48_000)
        assertEquals(48_000, AudioRatePreference.AUTO.preferredRate())
    }

    @Test
    fun explicitRatesIgnoreAnnouncement() {
        AudioRatePreference.noteAnnouncedMediaRate(44_100)
        assertEquals(48_000, AudioRatePreference.RATE_48K.preferredRate())
        assertEquals(44_100, AudioRatePreference.RATE_44K1.preferredRate())
    }

    @Test
    fun autoFallsBackTo48WhenNeverAnnounced() {
        assertEquals(48_000, AudioRatePreference.AUTO.preferredRate())
        assertNull(AudioRatePreference.announcedMediaRate())
        // 非 44.1k/48k 的怪值不采信，仍按旧顺序
        AudioRatePreference.noteAnnouncedMediaRate(22_050)
        assertNull(AudioRatePreference.announcedMediaRate())
        assertEquals(48_000, AudioRatePreference.AUTO.preferredRate())
    }

    @Test
    fun followReceiverPromotes441WhenEngineAnnounces441() {
        val a48 = profile(48_000)
        val a44 = profile(44_100)
        val list = listOf(a48, a44)
        val out = AudioRatePreference.reorderCandidates(list, 44_100)
        assertEquals(44_100, out[0].sampleRate)
        assertEquals(2, out.size) // 不删除
        assertEquals(list.toSet(), out.toSet()) // 元素不变
    }

    @Test
    fun followReceiverKeeps48FirstWhenEngineAnnounces48() {
        val a44 = profile(44_100)
        val a48 = profile(48_000)
        val list = listOf(a44, a48)
        val out = AudioRatePreference.reorderCandidates(list, 48_000)
        assertEquals(48_000, out[0].sampleRate)
        assertEquals(list.toSet(), out.toSet())
    }

    @Test
    fun rate48Promotes48First() {
        val a44 = profile(44_100)
        val a48 = profile(48_000)
        val list = listOf(a44, a48, profile(44_100))
        val out = AudioRatePreference.reorderCandidates(list, AudioRatePreference.RATE_48K.preferredRate())
        assertEquals(48_000, out[0].sampleRate)
        assertEquals(3, out.size) // 不删除
        assertEquals(list.toSet(), out.toSet()) // 元素不变
    }

    @Test
    fun rate441Promotes441First() {
        val a48 = profile(48_000)
        val a44 = profile(44_100)
        val list = listOf(a48, a44, profile(48_000))
        val out = AudioRatePreference.reorderCandidates(list, AudioRatePreference.RATE_44K1.preferredRate())
        assertEquals(44_100, out[0].sampleRate)
        assertEquals(3, out.size)
        assertEquals(list.toSet(), out.toSet())
    }

    @Test
    fun reorderKeepsListWhenPreferredRateAbsent() {
        // 车机只声明 48k，即便偏好 44.1k 也不能把列表弄空或改顺序
        val list = listOf(profile(48_000), profile(48_000))
        assertEquals(list, AudioRatePreference.reorderCandidates(list, 44_100))
    }

    @Test
    fun featureValuesMatchEngineDecoding() {
        // 引擎：getSampleRateSupported()==1 → 48k，否则 44.1k。
        assertEquals(null, AudioRatePreference.AUTO.mediaSampleRateFeatureValue)
        assertEquals(1, AudioRatePreference.RATE_48K.mediaSampleRateFeatureValue)
        assertEquals(0, AudioRatePreference.RATE_44K1.mediaSampleRateFeatureValue)
    }

    @Test
    fun parseIsCaseInsensitiveAndFallsBackToAuto() {
        assertEquals(AudioRatePreference.RATE_48K, AudioRatePreference.parse("rate_48k"))
        assertEquals(AudioRatePreference.RATE_44K1, AudioRatePreference.parse(" RATE_44K1 "))
        assertEquals(AudioRatePreference.AUTO, AudioRatePreference.parse("nonsense"))
        assertEquals(AudioRatePreference.AUTO, AudioRatePreference.parse(null))
    }
}
