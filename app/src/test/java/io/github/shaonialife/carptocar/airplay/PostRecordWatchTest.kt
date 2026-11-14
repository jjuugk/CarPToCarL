package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「补发 RECORD 之后被拆」的怀疑窗口（2026-11-13 宝马 MGU-81）。
 *
 * 这个窗口只有一个职责：**别把接收端对重复 RECORD 的过敏，记到音频档位头上**。
 * 判错的代价是不对称的——多记一次档位不兼容，几轮之后唯一能用的档位就被拉黑了；
 * 少记一次，只是下一轮多踩一次同样的坑。
 */
class PostRecordWatchTest {
    private var clock = 1_000_000L

    private fun watch() = PostRecordWatch(now = { clock })

    @Test
    fun `窗口内被拆——命中并给出耗时`() {
        val w = watch()
        w.mark("BMW Group MGU-81")
        clock += 1_790
        val hit = w.take()
        assertEquals("BMW Group MGU-81", hit?.identity)
        assertEquals(1_790L, hit?.elapsedMillis)
    }

    @Test
    fun `没挂表就取——不会凭空命中`() {
        // 纯视频会话（不起音频）本来就不该补发 RECORD，也就永远不该命中。
        assertNull(watch().take())
    }

    @Test
    fun `超出窗口——不命中`() {
        val w = watch()
        w.mark("BMW Group MGU-81")
        clock += PostRecordWatch.SUSPECT_WINDOW_MILLIS + 1
        assertNull(w.take())
    }

    @Test
    fun `窗口边界——正好 3500ms 仍算命中`() {
        val w = watch()
        w.mark("BMW")
        clock += PostRecordWatch.SUSPECT_WINDOW_MILLIS
        assertTrue(w.take() != null)
    }

    @Test
    fun `取走之后不再命中——归因入口会被多线程各调一次`() {
        // 车机掉线时视频通道回调与 BT 关闭回调都会走归因，读两次就会记两遍记忆、
        // 打两遍日志，界面还会连弹两次提示。
        val w = watch()
        w.mark("BMW")
        clock += 2_000
        assertTrue(w.take() != null)
        clock += 10
        assertNull(w.take())
    }

    @Test
    fun `peek 不消费`() {
        val w = watch()
        w.mark("BMW")
        clock += 2_000
        assertEquals(2_000L, w.peek()?.elapsedMillis)
        assertEquals(2_000L, w.take()?.elapsedMillis)
    }

    @Test
    fun `clear 之后不命中——新一轮不该背上一轮的锅`() {
        val w = watch()
        w.mark("BMW")
        clock += 2_000
        w.clear()
        assertNull(w.take())
    }

    @Test
    fun `时间倒退一律当不在窗口内`() {
        // uptimeMillis 理论上不会倒退，但跨进程/测试注入时出现过，不能让负值命中。
        val w = watch()
        w.mark("BMW")
        clock -= 500
        assertNull(w.take())
    }

    @Test
    fun `重新挂表覆盖旧身份——同一轮里换接收端不会张冠李戴`() {
        val w = watch()
        w.mark("Audi MIB3")
        clock += 100
        w.mark("BMW MGU-81")
        clock += 1_900
        assertEquals("BMW MGU-81", w.take()?.identity)
    }
}
