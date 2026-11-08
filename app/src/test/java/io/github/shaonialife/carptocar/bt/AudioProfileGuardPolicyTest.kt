package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频配置守卫的节奏/上限（纯逻辑）。
 *
 * 这些数字不是随手取的：轮询太长会漏掉车机把 HFP 拉回来那一下，上限太短会在宝马
 * 那个 2~4 分钟的抖动窗口里提前撒手。改动任何一个都得有新的实车证据。
 */
class AudioProfileGuardPolicyTest {
    @Test
    fun keepsGuardingBeforeTheDeadline() {
        assertTrue(AudioProfileGuardPolicy.shouldKeepGuarding(0))
        assertTrue(AudioProfileGuardPolicy.shouldKeepGuarding(120_000))
        assertTrue(AudioProfileGuardPolicy.shouldKeepGuarding(AudioProfileGuardPolicy.MAX_MILLIS - 1))
    }

    @Test
    fun stopsAtAndAfterTheDeadline() {
        assertFalse(AudioProfileGuardPolicy.shouldKeepGuarding(AudioProfileGuardPolicy.MAX_MILLIS))
        assertFalse(AudioProfileGuardPolicy.shouldKeepGuarding(600_000))
    }

    @Test
    fun pollIntervalCatchesReconnects() {
        // 车机把 HFP 拉回来到它真正听到「在线」只有几百毫秒，轮询不能超过 2 秒
        assertTrue(
            "轮询间隔 ${AudioProfileGuardPolicy.POLL_MILLIS}ms 太长，会漏掉车机的重连",
            AudioProfileGuardPolicy.POLL_MILLIS in 500..2_000,
        )
    }

    @Test
    fun deadlineCoversTheBmwFlappingWindow() {
        // 宝马 NBT EVO 实测握手期抖动 2~4 分钟，上限必须盖住它
        assertTrue(
            "上限 ${AudioProfileGuardPolicy.MAX_MILLIS}ms 盖不住 4 分钟抖动窗口",
            AudioProfileGuardPolicy.MAX_MILLIS >= 240_000,
        )
    }
}
