package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 采样率偏好 → 候选档重排。
 *
 * 与 CarLife 引擎下发的 MEDIA_SAMPLE_RATE 两端对齐，避免 44.1↔48 重采样。
 * 关键约束：只重排不删除——车机若只吃另一率的档，仍要能兜底出声。
 */
class AudioRatePreferenceTest {
    private fun profile(rate: Int) = AudioProfile("media", 1L shl 11, rate, 250)

    @Test
    fun autoKeepsOriginalOrder() {
        val list = listOf(profile(44_100), profile(48_000), profile(44_100))
        assertEquals(list, AudioRatePreference.AUTO.reorderCandidates(list))
    }

    @Test
    fun rate48Promotes48First() {
        val a44 = profile(44_100)
        val a48 = profile(48_000)
        val list = listOf(a44, a48, profile(44_100))
        val out = AudioRatePreference.RATE_48K.reorderCandidates(list)
        assertEquals(48_000, out[0].sampleRate)
        assertEquals(3, out.size) // 不删除
        assertEquals(list.toSet(), out.toSet()) // 元素不变
    }

    @Test
    fun rate441Promotes441First() {
        val a48 = profile(48_000)
        val a44 = profile(44_100)
        val list = listOf(a48, a44, profile(48_000))
        val out = AudioRatePreference.RATE_44K1.reorderCandidates(list)
        assertEquals(44_100, out[0].sampleRate)
        assertEquals(3, out.size)
        assertEquals(list.toSet(), out.toSet())
    }

    @Test
    fun featureValuesMatchEngineDecoding() {
        // 引擎：getSampleRateSupported()==1 → 48k，否则 44.1k。
        assertEquals(null, AudioRatePreference.AUTO.mediaSampleRateFeatureValue)
        assertEquals(1, AudioRatePreference.RATE_48K.mediaSampleRateFeatureValue)
        assertEquals(0, AudioRatePreference.RATE_44K1.mediaSampleRateFeatureValue)
        assertEquals(48_000, AudioRatePreference.RATE_48K.targetSampleRate)
        assertEquals(44_100, AudioRatePreference.RATE_44K1.targetSampleRate)
    }

    @Test
    fun parseIsCaseInsensitiveAndFallsBackToAuto() {
        assertEquals(AudioRatePreference.RATE_48K, AudioRatePreference.parse("rate_48k"))
        assertEquals(AudioRatePreference.RATE_44K1, AudioRatePreference.parse(" RATE_44K1 "))
        assertEquals(AudioRatePreference.AUTO, AudioRatePreference.parse("nonsense"))
        assertEquals(AudioRatePreference.AUTO, AudioRatePreference.parse(null))
    }
}
