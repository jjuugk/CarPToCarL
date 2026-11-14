package io.github.shaonialife.carptocar.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CriticalEvents] 的取舍规则。
 *
 * 这个环存在的唯一意义就是「日志滚掉之后还能看到会话开头的结论行」，
 * 所以两条断言最关键：**关键行必须留下、趋势行必须不留**。
 */
class CriticalEventsTest {
    @Before
    fun setUp() {
        CriticalEvents.clear()
    }

    @After
    fun tearDown() {
        CriticalEvents.clear()
    }

    @Test
    fun `协商结果与自检留下`() {
        CriticalEvents.note("[10:00:00.000] AIRPLAY: ✓ 音频流协商成功（type=102 media format=0x800000 48000Hz）")
        CriticalEvents.note("[10:00:00.100] AUDIO: 自检 = 首帧 548B 本机解码 1024 个样本，峰值 9123")
        assertEquals(2, CriticalEvents.snapshot().size)
    }

    @Test
    fun `每 30 秒的趋势行不进环`() {
        CriticalEvents.note("[10:00:30.000] AUDIO: 链路体检 t102/AAC_LC —— 累计 1406 包；锚点 30/30 被拒")
        CriticalEvents.note("[10:00:30.100] CARLIFE: 音频直通 216 包 / 864KB / 1413kbps · 电平 100%")
        CriticalEvents.note("[10:00:30.200] VIDEO: 帧 #41641 61916B (key) 已发送")
        assertTrue(CriticalEvents.snapshot().isEmpty())
    }

    @Test
    fun `超过上限时丢最旧的`() {
        repeat(250) { CriticalEvents.note("[10:00:00.000] AUDIO: 自检 #$it") }
        val lines = CriticalEvents.snapshot()
        assertEquals(200, lines.size)
        assertTrue(lines.first().endsWith("#50"))
        assertTrue(lines.last().endsWith("#249"))
    }

    @Test
    fun `顺序与时间戳原样保留`() {
        CriticalEvents.note("[10:00:00.000] SESSION 开始")
        CriticalEvents.note("[10:00:05.000] STREAM: 顺序 = 音频优先")
        assertEquals(
            listOf("[10:00:00.000] SESSION 开始", "[10:00:05.000] STREAM: 顺序 = 音频优先"),
            CriticalEvents.snapshot(),
        )
    }
}
