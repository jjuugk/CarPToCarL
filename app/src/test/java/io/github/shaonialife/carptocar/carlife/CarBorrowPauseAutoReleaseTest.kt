package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 车机侧暂停门禁的「超时自恢复」验证（2026-11-20）。
 *
 * 车机静音键/原车通话借用主音频（BORROWED_CONSTRAINED）后常在 100ms 内就把主音频还回来，
 * 却漏发 UNBORROWED_TO_PHONE → [CarLifeAudioSink] 的 indefinite 门禁永久拦 PCM → 再播一直无声。
 * 修法：临时借用允许「超时 + 主音频已归还」自动放行；永久接管（切原车 FM）必须继续拦，
 * 否则会与车机原生音源双声。
 */
class CarBorrowPauseAutoReleaseTest {
    private fun sink(
        clock: AtomicLong,
        acquired: AtomicBoolean,
    ): CarLifeAudioSink = CarLifeAudioSink(
        log = {},
        nowMs = { clock.get() },
        carAudioAcquired = { acquired.get() },
    )

    @Test
    fun `temporary borrow auto releases after timeout only when main audio reacquired`() {
        val clock = AtomicLong(1_000L)
        val acquired = AtomicBoolean(false)
        val sink = sink(clock, acquired)

        // 临时借用：indefinite + autoRelease
        sink.setPlaybackPaused(true, indefinite = true, autoRelease = true)
        acquired.set(false)

        // 窗口内、即使已归还也继续拦（残留 PCM 不能漏出去）
        acquired.set(true)
        assertTrue("窗口内必须继续拦", sink.shouldDropPaused(1_000L + 1_999L))

        // 超时但主音频还没归还 → 继续拦（车机还借着，放行会双声）
        acquired.set(false)
        assertTrue("未归还时超时也要继续拦", sink.shouldDropPaused(1_000L + 5_000L))

        // 超时 + 已归还 → 自动解除，放行 PCM
        acquired.set(true)
        assertFalse("超时且归还后必须放行", sink.shouldDropPaused(1_000L + 2_000L))
        // 解除是持久的：后续 PCM 不再被拦，即使 acquired 又翻回 false
        acquired.set(false)
        assertFalse("自恢复后保持放行", sink.shouldDropPaused(1_000L + 9_000L))
    }

    @Test
    fun `permanent take never auto releases even when main audio reacquired`() {
        val clock = AtomicLong(1_000L)
        val acquired = AtomicBoolean(true)
        val sink = sink(clock, acquired)

        // 永久接管（切原车 FM）：indefinite 但 autoRelease=false
        sink.setPlaybackPaused(true, indefinite = true, autoRelease = false)

        // 即便主音频显示已持有、时间远超窗口，也必须继续拦——否则与车机 FM 双声
        assertTrue(
            "永久接管永不自恢复",
            sink.shouldDropPaused(1_000L + 60_000L),
        )
    }

    @Test
    fun `temporary borrow without reacquire stays blocked long after`() {
        val clock = AtomicLong(1_000L)
        val acquired = AtomicBoolean(false)
        val sink = sink(clock, acquired)

        sink.setPlaybackPaused(true, indefinite = true, autoRelease = true)
        // 车机一直没归还（acquired 恒 false）：永远拦，这是「等归还」的正确语义
        assertTrue(sink.shouldDropPaused(1_000L + 300_000L))
    }

    @Test
    fun `engine pause still uses fifteen second window independent of acquisition`() {
        val clock = AtomicLong(1_000L)
        val acquired = AtomicBoolean(false)
        val sink = sink(clock, acquired)

        // 引擎侧暂停：indefinite=false，15 秒窗口后自动放行，与 acquired 无关
        sink.setPlaybackPaused(true, indefinite = false)
        assertTrue(sink.shouldDropPaused(1_000L + 14_999L))
        assertFalse(sink.shouldDropPaused(1_000L + 15_000L))
    }

    @Test
    fun `resume clears all pause state including auto release`() {
        val clock = AtomicLong(1_000L)
        val acquired = AtomicBoolean(false)
        val sink = sink(clock, acquired)

        sink.setPlaybackPaused(true, indefinite = true, autoRelease = true)
        // 显式恢复（车机发了 UNBORROWED_TO_PHONE）
        sink.setPlaybackPaused(false)
        assertFalse(sink.shouldDropPaused(1_000L + 500L))
    }
}
