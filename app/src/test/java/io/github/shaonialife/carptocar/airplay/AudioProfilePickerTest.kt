package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「指定 audioType 的档位能不能用来建独立流」的挑选规则。
 *
 * 规则错一步的后果不是报错，而是**静默建错流**：车机没声明 alert 档却照样发 SETUP，
 * 有的固件回 200、给 dataPort、然后一个样本都不消费——现场只表现为「导航没声音」。
 * 所以这里把三条边界钉死：没声明就是 null、位图不含就是 null、PCM 只认 type=100。
 */
class AudioProfilePickerTest {
    private val pcm48k = 1L shl 15
    private val pcm44k = 1L shl 11
    private val aac48k = 1L shl 23
    private val aac44k = 1L shl 22

    /** 与 `AirPlayClient.audioProfileForType` 里同一套优先级：PCM 先，AAC 后。 */
    private val ordered =
        listOf(
            100L to pcm48k,
            100L to pcm44k,
            102L to aac48k,
            102L to aac44k,
            100L to aac48k,
            100L to aac44k,
        )

    private fun channel(
        type: Long,
        audioType: String,
        output: Long,
        input: Long = 0L,
    ) = linkedMapOf<String, Any?>(
        "type" to type,
        "audioType" to audioType,
        "audioOutputFormats" to output,
        "audioInputFormats" to input,
    )

    @Test
    fun undeclaredTypeIsRejected() {
        // 宝马 G38/F22 的真实形态：只有 default/telephony/speechRecognition 与 media，没有 alert
        val declared =
            listOf(
                channel(100L, "default", pcm48k),
                channel(100L, "telephony", pcm48k),
                channel(100L, "speechRecognition", pcm48k),
                channel(102L, "media", aac48k),
            )
        assertNull(pickDeclaredFormat(declared, "alert", ordered))
    }

    @Test
    fun declaredButWithoutBitsIsRejected() {
        val declared = listOf(channel(100L, "alert", 0L))
        assertNull(pickDeclaredFormat(declared, "alert", ordered))
    }

    @Test
    fun declaredButOnlyUnknownBitsIsRejected() {
        // 只声明了 AAC-ELD 之类我们不认识的位（bit6 是 Siri 窄带档常见的位）
        val declared = listOf(channel(100L, "speechRecognition", 1L shl 6))
        assertNull(pickDeclaredFormat(declared, "speechRecognition", ordered))
    }

    @Test
    fun pcmWinsWhenBothDeclared() {
        val declared = listOf(channel(100L, "alert", pcm48k or aac48k or pcm44k))
        assertEquals(100L to pcm48k, pickDeclaredFormat(declared, "alert", ordered))
    }

    @Test
    fun fallsBackToAacOnType102() {
        val declared = listOf(channel(102L, "alert", aac48k))
        assertEquals(102L to aac48k, pickDeclaredFormat(declared, "alert", ordered))
    }

    /** PCM 只出现在 type=100：102 声明了 PCM 位也不能拿去发（那会被车机当怪声/不消费）。 */
    @Test
    fun pcmBitOnType102IsIgnored() {
        val declared = listOf(channel(102L, "alert", pcm48k))
        assertNull(pickDeclaredFormat(declared, "alert", ordered))
    }

    /** 一个 audioType 可能同时挂在 100 和 102 上，各自位图不同，必须取命中的那一条。 */
    @Test
    fun picksTheTypeThatActuallyHasTheBit() {
        val declared =
            listOf(
                channel(100L, "alert", 0L),
                channel(102L, "alert", aac44k),
            )
        assertEquals(102L to aac44k, pickDeclaredFormat(declared, "alert", ordered))
    }

    @Test
    fun audioTypeMatchIsCaseInsensitive() {
        val declared = listOf(channel(100L, "Alert", pcm48k))
        assertEquals(100L to pcm48k, pickDeclaredFormat(declared, "alert", ordered))
    }

    @Test
    fun blankTypeIsRejected() {
        val declared = listOf(channel(100L, "alert", pcm48k))
        assertNull(pickDeclaredFormat(declared, "   ", ordered))
    }

    @Test
    fun opusOnlyNavDefaultIsRejected() {
        // BMW iD8 实车 /info：101/default 仅声明 bit 30（Opus 48k 单声道，0x40000000）
        val navOrder =
            listOf(
                101L to pcm48k,
                101L to (1L shl 14),
                101L to pcm44k,
                101L to (1L shl 10),
                101L to aac48k,
                101L to aac44k,
                100L to pcm48k,
                100L to (1L shl 14),
                100L to pcm44k,
                100L to (1L shl 10),
                100L to aac48k,
                100L to aac44k,
            )
        val declared = listOf(channel(101L, "default", 1L shl 30))
        assertNull(pickDeclaredFormat(declared, "default", navOrder))
    }

    @Test
    fun navBorrowIsSkippedWhenNavStreamUnavailable() {
        val plan =
            CarPlayResourceStreams.planAudioStep(
                media = false,
                navigation = true,
                canPlayNavigationAudio = false,
                phoneOwnsAudio = false,
                phoneBorrowsAudio = false,
            )
        assertEquals(false, plan.effectiveNavigation)
        assertNull(plan.acquirePriority)
        assertEquals(false, plan.acquireTemporary)
        assertEquals(false, plan.releaseBorrow)
    }

    @Test
    fun navBorrowStillAcquiresWhenNavStreamSupported() {
        val plan =
            CarPlayResourceStreams.planAudioStep(
                media = false,
                navigation = true,
                canPlayNavigationAudio = true,
                phoneOwnsAudio = false,
                phoneBorrowsAudio = false,
            )
        assertEquals(true, plan.effectiveNavigation)
        assertEquals(AirPlayResourceState.PRIORITY_BACKGROUND, plan.acquirePriority)
        assertEquals(true, plan.acquireTemporary)
        assertEquals(false, plan.releaseBorrow)
    }

    @Test
    fun mediaStillAcquiresEvenWhenNavStreamUnavailable() {
        val plan =
            CarPlayResourceStreams.planAudioStep(
                media = true,
                navigation = true,
                canPlayNavigationAudio = false,
                phoneOwnsAudio = false,
                phoneBorrowsAudio = false,
            )
        assertEquals(false, plan.effectiveNavigation)
        assertEquals(AirPlayResourceState.PRIORITY_USER, plan.acquirePriority)
        assertEquals(false, plan.acquireTemporary)
        assertEquals(false, plan.releaseBorrow)
    }

    @Test
    fun mbuxExperimentBorrowsMediaWithoutChangingPriority() {
        // B 策略：媒体从「永久 claim」改「临时借用」，优先级仍是 USER（借得到），
        // 永久归属留车机——减少 MBUX 对永久夺走主音频的翻脸概率。
        val plan =
            CarPlayResourceStreams.planAudioStep(
                media = true,
                navigation = false,
                canPlayNavigationAudio = false,
                phoneOwnsAudio = false,
                phoneBorrowsAudio = false,
                borrowMediaAudio = true,
            )
        assertEquals(AirPlayResourceState.PRIORITY_USER, plan.acquirePriority)
        assertEquals(true, plan.acquireTemporary)
        assertEquals(true, plan.borrowMediaAudio)
        assertEquals(false, plan.releaseBorrow) // 媒体一直在播就不归还
    }

    @Test
    fun defaultMediaStillClaimsPermanently() {
        // 默认（OFF）行为必须与加开关前一致：媒体 USER 优先级 + 永久 claim。
        val plan =
            CarPlayResourceStreams.planAudioStep(
                media = true,
                navigation = false,
                canPlayNavigationAudio = false,
                phoneOwnsAudio = false,
                phoneBorrowsAudio = false,
            )
        assertEquals(AirPlayResourceState.PRIORITY_USER, plan.acquirePriority)
        assertEquals(false, plan.acquireTemporary)
        assertEquals(false, plan.borrowMediaAudio)
    }

    @Test
    fun existingNavBorrowIsReleasedIfNavStreamBecomesUnavailable() {
        val plan =
            CarPlayResourceStreams.planAudioStep(
                media = false,
                navigation = true,
                canPlayNavigationAudio = false,
                phoneOwnsAudio = true,
                phoneBorrowsAudio = true,
            )
        assertEquals(false, plan.effectiveNavigation)
        assertNull(plan.acquirePriority)
        assertEquals(true, plan.releaseBorrow)
    }
}
