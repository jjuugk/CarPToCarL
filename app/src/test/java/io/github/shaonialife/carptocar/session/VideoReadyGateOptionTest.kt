package io.github.shaonialife.carptocar.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoReadyGateOption.shouldClearAttempt] 的三种真值组合。
 *
 * 这条判据决定「重连失败计数会不会被清零」。清错了的后果是 hypercar 明写要避免的那个：
 * 假成功（RECORD 200 但车机一帧都没收到）每轮都把计数清零，退避永远停在最短档，
 * 于是变成一波接一波的重连——MGU-81 17:43~17:47 的重连风暴就是这个形状。
 */
class VideoReadyGateOptionTest {
    @Test
    fun `开关关闭时永远清零（保持原行为）`() {
        assertTrue(VideoReadyGateOption.shouldClearAttempt(videoReady = true, gateEnabled = false))
        assertTrue(VideoReadyGateOption.shouldClearAttempt(videoReady = false, gateEnabled = false))
    }

    @Test
    fun `开关开启且画面已送达时清零`() {
        assertTrue(VideoReadyGateOption.shouldClearAttempt(videoReady = true, gateEnabled = true))
    }

    @Test
    fun `开关开启但画面未送达时保留计数`() {
        assertFalse(
            "会话建立但车机没收到画面 = 假成功，不能清零重连计数",
            VideoReadyGateOption.shouldClearAttempt(videoReady = false, gateEnabled = true),
        )
    }
}
