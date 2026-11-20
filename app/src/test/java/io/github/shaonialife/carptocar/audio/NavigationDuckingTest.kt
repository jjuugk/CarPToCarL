package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 导航播报压低状态机的回归。
 *
 * 实车依据（Q4 分支 2026-11-19 12:44）：9 对 duckAudio/unduckAudio 全 RTSP 200，
 * 方向盘音量类别从 Entertainment 变成 Navigation；不加时音乐虽压低但类别不变。
 */
class NavigationDuckingTest {
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
    }

    @Test
    fun `开始发 duck 结束发 unduck`() {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val ducking =
            NavigationDucking(
                send = { down -> sent.add(if (down) "duck" else "unduck"); true },
                releaseDelayMs = 10L,
            )
        ducking.begin()
        ducking.end()
        waitUntil { sent.size >= 2 }
        assertEquals(listOf("duck", "unduck"), sent)
        ducking.close()
    }

    @Test
    fun `短间隔续播取消恢复只留一次 duck`() {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val ducking =
            NavigationDucking(
                send = { down -> sent.add(if (down) "duck" else "unduck"); true },
                releaseDelayMs = 150L,
            )
        ducking.begin()
        waitUntil { sent.contains("duck") }
        ducking.end() // 排一个恢复
        ducking.begin() // 下一条播报赶在恢复前开始，应取消它
        waitUntil { sent.size >= 1 }
        Thread.sleep(250) // 越过原恢复时刻
        assertEquals(1, sent.count { it == "duck" })
        assertEquals(0, sent.count { it == "unduck" })
        ducking.end()
        waitUntil { sent.contains("unduck") }
        ducking.close()
    }

    @Test
    fun `duck 应答丢失仍在结束时恢复`() {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val ducking =
            NavigationDucking(
                send = { down -> sent.add(if (down) "duck" else "unduck"); down },
                releaseDelayMs = 10L,
            )
        ducking.begin()
        ducking.end()
        waitUntil { sent.size >= 2 }
        assertEquals(listOf("duck", "unduck"), sent)
        ducking.close()
    }

    @Test
    fun `关闭时保证恢复`() {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val ducking =
            NavigationDucking(
                send = { down -> sent.add(if (down) "duck" else "unduck"); true },
                releaseDelayMs = 10_000L, // 正常结束不会恢复
            )
        ducking.begin()
        waitUntil { sent.contains("duck") }
        ducking.close() // 会话退役：必须立刻恢复
        waitUntil { sent.contains("unduck") }
        assertEquals(listOf("duck", "unduck"), sent)
    }
}
