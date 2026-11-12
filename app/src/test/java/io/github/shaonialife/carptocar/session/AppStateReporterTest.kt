package io.github.shaonialife.carptocar.session

import io.github.shaonialife.carptocar.airplay.AirPlaySpeechMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机应用态的推导：**什么时候报「在收音」、什么时候报「在说话」、什么时候闭嘴**。
 *
 * 为什么锁这些（AIS `Table 23-56` 原文）：`Speech` 的语义是「某实体正在做语音操作，
 * **收音期间所有音频输出必须关闭**，以免干扰识别算法」。也就是说这条状态报错的代价是
 * 双向的——该报不报，车机不会闭嘴（车内音源灌进麦克风）；不该报乱报，车机在放音乐时
 * 被莫名静音。所以判据与边沿都必须钉住。
 */
class AppStateReporterTest {
    private val sent = mutableListOf<Pair<AirPlaySpeechMode, Boolean>>()
    private val logs = mutableListOf<String>()
    private var now = 1_000L

    private fun reporter(): AppStateReporter =
        AppStateReporter(
            publish = { speech, call -> sent += speech to call },
            log = { line -> logs += line },
            nowMillis = { now },
        )

    @Test
    fun `引擎进入语音会话时先报收音，不报说话`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        assertEquals(listOf(AirPlaySpeechMode.RECOGNIZING to false), sent)
        // 还没说过话：反复 tick 不该重复发（车机收到同一条只会重算一次静音）
        reporter.tick()
        assertEquals(1, sent.size)
    }

    @Test
    fun `语音会话里有 TTS 就报说话，静默超过保持窗口再退回收音`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        sent.clear()

        reporter.onSpeechAudio()
        reporter.tick()
        assertEquals(AirPlaySpeechMode.SPEAKING, sent.last().first)

        // 句间停顿：保持窗口内仍算「在说」，不能一停顿就把车机的音源放回来
        now += 500
        reporter.tick()
        assertEquals("还没超过 700ms，不该退回收音", AirPlaySpeechMode.SPEAKING, sent.last().first)

        // 说完超过窗口 → 回到收音（用户在回答）
        now += 400
        reporter.tick()
        assertEquals(AirPlaySpeechMode.RECOGNIZING, sent.last().first)
    }

    @Test
    fun `语音会话结束一定回到 None，哪怕刚才还在说话`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        reporter.onSpeechAudio()
        reporter.tick()
        reporter.onEngineVoice(false)
        assertEquals(AirPlaySpeechMode.NONE to false, sent.last())
    }

    @Test
    fun `导航播报不算说话——不在语音会话里的 TTS 一律忽略`() {
        val reporter = reporter()
        reporter.onSpeechAudio()
        reporter.onEngineVoice(false)
        reporter.tick()
        // 一条都不该发：既没语音会话，也没通话
        assertTrue("导航播报不该被算成 Speech：$sent", sent.isEmpty())
    }

    @Test
    fun `通话是独立的一条状态，与语音互不影响`() {
        val reporter = reporter()
        reporter.onCallActive(true)
        assertEquals(AirPlaySpeechMode.NONE to true, sent.last())
        reporter.onEngineVoice(true)
        assertEquals(AirPlaySpeechMode.RECOGNIZING to true, sent.last())
        reporter.onCallActive(false)
        assertEquals(AirPlaySpeechMode.RECOGNIZING to false, sent.last())
    }

    @Test
    fun `重复的同值事件不重复发报文`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        reporter.onEngineVoice(true)
        reporter.onCallActive(false)
        reporter.tick()
        assertEquals(1, sent.size)
    }

    @Test
    fun `拆会话时静默复位，不再补发一条没人收的报文`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        sent.clear()
        reporter.stop(publishFinal = false)
        assertTrue("拆会话时不该再发报文", sent.isEmpty())
        // 复位之后重新开始，从 None 起步
        reporter.onEngineVoice(true)
        assertEquals(AirPlaySpeechMode.RECOGNIZING, sent.single().first)
    }

    @Test
    fun `正常收尾会补发 None，让车机把音源放回来`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        sent.clear()
        reporter.stop()
        assertEquals(AirPlaySpeechMode.NONE to false, sent.single())
    }

    @Test
    fun `describe 说的是当前真实状态`() {
        val reporter = reporter()
        assertTrue(reporter.describe().contains("无语音会话"))
        reporter.onEngineVoice(true)
        assertTrue(reporter.describe(), reporter.describe().contains("收音中"))
        reporter.onSpeechAudio()
        reporter.tick()
        assertTrue(reporter.describe(), reporter.describe().contains("正在说话"))
    }

    @Test
    fun `收敛线程能自己把说话态退回收音`() {
        val reporter = reporter()
        reporter.onEngineVoice(true)
        sent.clear()
        reporter.onSpeechAudio()
        reporter.start(intervalMillis = 20)
        try {
            awaitUntil { sent.lastOrNull()?.first == AirPlaySpeechMode.SPEAKING }
            // 说话结束、也没再收到新 PCM：由收敛线程（而不是某个事件）把它退回收音
            now += 2_000
            awaitUntil { sent.lastOrNull()?.first == AirPlaySpeechMode.RECOGNIZING }
        } finally {
            reporter.stop(publishFinal = false)
        }
        assertEquals(AirPlaySpeechMode.RECOGNIZING, sent.last().first)
        assertTrue("必须先经过说话态", sent.any { it.first == AirPlaySpeechMode.SPEAKING })
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("等待超时", condition())
    }
}
