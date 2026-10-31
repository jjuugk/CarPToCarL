package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志限流测试（2026-10-30）。
 *
 * 场景很具体：进程被系统冻结 133 秒后解冻，CarLife 积压的「媒体进度」回调会一次性
 * 涌出上百条，日志被淹掉。限流必须做到「同样的 key 一个窗口内只放行一次」，
 * 同时又不能把不同 key 互相拖累（进度和解析失败是两回事）。
 */
class LogThrottleTest {
    private var now = 0L
    private fun throttle(interval: Long) = LogThrottle(interval) { now }

    @Test
    fun `窗口内重复 key 只放行一次`() {
        val throttle = throttle(10_000L)
        assertTrue(throttle.allow("media-progress"))
        now = 1_000L
        assertFalse(throttle.allow("media-progress"))
        now = 9_999L
        assertFalse(throttle.allow("media-progress"))
    }

    @Test
    fun `过了窗口重新放行`() {
        val throttle = throttle(10_000L)
        assertTrue(throttle.allow("media-progress"))
        now = 10_000L
        assertTrue(throttle.allow("media-progress"))
    }

    @Test
    fun `不同 key 互不影响`() {
        val throttle = throttle(10_000L)
        assertTrue(throttle.allow("media-progress"))
        assertTrue(throttle.allow("media-info-empty"))
        now = 100L
        assertFalse(throttle.allow("media-progress"))
        assertFalse(throttle.allow("media-info-empty"))
    }

    @Test
    fun `reset 后立即放行`() {
        val throttle = throttle(10_000L)
        assertTrue(throttle.allow("k"))
        assertFalse(throttle.allow("k"))
        throttle.reset()
        assertTrue(throttle.allow("k"))
    }
}
