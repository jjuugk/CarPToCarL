package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频档位记忆的排序规则（[AudioProfileMemory.order]）。
 *
 * 背景（2026-10-19 Honda HFT / Alpine 实测）：选「视频+音频」后会话能建立、视频正常，
 * 但音频起流约 6s 后车机拆掉整条 AirPlay 会话；只推视频则稳定。车机不会说明是哪一档
 * 音频不兼容，只能按「起流后的存活时间」反推，并把结论按车机持久化，下次连接换档。
 */
class AudioProfileMemoryTest {
    // 这台车机的真实声明：media 档只有 44.1k，compatibility 档 48k/44.1k 都有
    private val hondaCandidates =
        listOf(
            AudioProfile("media", 1L shl 11, 44_100, 250),
            AudioProfile("compatibility", 1L shl 15, 48_000, 250),
            AudioProfile("compatibility", 1L shl 11, 44_100, 250),
        )

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(hondaCandidates, AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry()))
    }

    @Test
    fun workingProfileGoesFirst() {
        val compat48 = hondaCandidates[1].key
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(working = compat48))
        assertEquals(compat48, ordered.first().key)
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun carPlusTriesNativePcmBeforeRememberedAacButRespectsRejectedPcm() {
        val aac48 = AudioProfile("media", 1L shl 23, 48_000, 250, 102L, AudioCodec.AAC_LC)
        val aac44 = AudioProfile("media", 1L shl 22, 44_100, 250, 102L, AudioCodec.AAC_LC)
        val pcm48 = AudioProfile("media", 1L shl 15, 48_000, 250)
        val pcm44 = hondaCandidates[0]
        val candidates = listOf(aac48, aac44, pcm48, pcm44)
        val entry = AudioProfileMemory.Entry(working = aac44.key)

        assertEquals(
            listOf(pcm48, aac44, aac48, pcm44),
            AudioProfileMemory.order(candidates, entry, preferPcm48Media = true),
        )
        assertEquals(
            listOf(aac44, aac48, pcm44, pcm48),
            AudioProfileMemory.order(candidates, entry.copy(rejected = setOf(pcm48.key)), preferPcm48Media = true),
        )
        assertEquals(
            listOf(aac44, aac48, pcm48, pcm44),
            AudioProfileMemory.order(candidates, entry),
        )
    }

    @Test
    fun rejectedProfileGoesLastButStaysAsFallback() {
        val media44 = hondaCandidates[0].key
        val ordered =
            AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = setOf(media44)))
        assertEquals(media44, ordered.last().key)
        // 仍然保留在候选里：换固件后还有机会用
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun allRejectedKeepsTryingInsteadOfGoingSilent() {
        val all = hondaCandidates.map { it.key }.toSet()
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = all))
        // 全被判死说明与记忆矛盾（换车机/固件升级沿用同一身份键）——照常全试，避免永久无声
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun disabledMeansNoAudioAtAll() {
        assertTrue(
            AudioProfileMemory
                .order(hondaCandidates, AudioProfileMemory.Entry(disabled = true))
                .isEmpty(),
        )
        assertFalse(AudioProfileMemory.order(emptyList(), AudioProfileMemory.Entry()).isNotEmpty())
    }

    @Test
    fun profileKeyIgnoresLatencySoRelearningWorks() {
        val a = AudioProfile("media", 1L shl 11, 44_100, 250)
        val b = AudioProfile("media", 1L shl 11, 44_100, 400)
        assertEquals(a.key, b.key)
        // 大小写差异（/info 里出现过 Media/media）不能造成两套记忆；
        // 键里同时带流类型与编码：同格式在 102/AAC 与 100/PCM 上是完全不同的通路
        assertEquals("100|media|2048|PCM", AudioProfile("Media", 1L shl 11, 44_100, 250).key)
        assertEquals(
            "102|media|4194304|AAC_LC",
            AudioProfile(
                "media",
                1L shl 22,
                44_100,
                250,
                streamType = 102L,
                codec = AudioCodec.AAC_LC,
            ).key,
        )
    }

    @Test
    fun receiverKeyIsStableAcrossCasingAndPunctuation() {
        val a = AudioProfileMemory.receiverKey("Alpine Electronics (China) Co., Ltd. 8A250-30A")
        val b = AudioProfileMemory.receiverKey("alpine electronics china co ltd 8a250 30a")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        assertFalse(a == AudioProfileMemory.receiverKey("AUDI 0000 DELPHI"))
    }

    @Test
    fun setupFailureAloneNeverDisablesAudio() {
        // SETUP 被拒/超时可能只是车机资源仲裁或网络抖动：只降级排序，不能判死整台车机
        val id = "test receive setup-fail"
        AudioProfileMemory.recordRejected(null, id, "media|2048", setOf("media|2048"), markExhausted = false)
        val entry = AudioProfileMemory.load(null, id)
        assertTrue(entry.rejected.contains("media|2048"))
        assertFalse(entry.disabled)
    }

    @Test
    fun teardownOfEveryProfileDisablesAudio() {
        // 只有「起流后整链被拆」这种确定性证据才允许把车机音频判死
        val id = "test receive teardown-all"
        val all = setOf("media|2048", "compatibility|32768")
        AudioProfileMemory.recordRejected(null, id, "media|2048", all)
        AudioProfileMemory.recordRejected(null, id, "compatibility|32768", all)
        assertTrue(AudioProfileMemory.load(null, id).disabled)
        assertTrue(AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.load(null, id)).isEmpty())
    }
}
