package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送端队列的喂料决策（[AudioSender.feedFramesFor]）。
 *
 * 背景：2026-11-17 WinPlay 日志里，音频「一顿一顿」——每 30 秒窗口产 1407 帧、发 1407 包
 * （总量完全正确），但「最长停拍」稳定落在 62/64/82/84/85/100/101/102/104/107ms，
 * 全是 21.33ms（1024 样本 @48k）的整数倍。根因是 `MediaCodec` 一次吐 3~5 帧，
 * 旧实现就地全发出去 → 0ms 内 5 个包 → 后续 107ms 一个包都没有。
 *
 * 修法是把编码和发送解耦：帧进队列，泵线程一拍一包。这里验的是「喂多少」——
 * 喂多了队列无限加深（延迟堆到车机抖动缓冲之上），喂少了队列空（每拍一个空洞）。
 */
class AudioSendQueueTest {
    private val target = 1
    private val deepTarget = AudioSender.MAX_QUEUE_TARGET

    @Test
    fun `队列为空时多喂一帧把它垫起来`() {
        // 目标是 1：这一拍要发掉 1 个，所以必须喂 2 个才能让队列维持在 1。
        assertEquals(2, AudioSender.feedFramesFor(depth = 0, target = target))
    }

    @Test
    fun `队列正好等于目标时只喂一帧维持流水线`() {
        // 喂 1 → 队列 2 → 发 1 → 队列 1。深度不变，这就是稳态。
        assertEquals(1, AudioSender.feedFramesFor(depth = 1, target = target))
    }

    @Test
    fun `队列超过目标就停止喂料让它消化`() {
        // 编码器返吐 3 帧后队列积压，此时继续喂只会把延迟越堆越高。
        assertEquals(0, AudioSender.feedFramesFor(depth = 2, target = target))
        assertEquals(0, AudioSender.feedFramesFor(depth = 6, target = target))
    }

    @Test
    fun `队列顶到硬上限时一定停喂`() {
        assertEquals(0, AudioSender.feedFramesFor(depth = AudioSender.MAX_QUEUE_DEPTH, target = deepTarget))
        assertEquals(0, AudioSender.feedFramesFor(depth = AudioSender.MAX_QUEUE_DEPTH + 3, target = deepTarget))
    }

    @Test
    fun `垫深时单拍最多喂 MAX_FEED_PER_TICK 帧`() {
        // 从 0 垫到 4 需要喂 5，但一拍最多 4——否则 5 帧 PCM（107ms）在 0ms 内灌进去，
        // 环形缓冲被瞬间抽干、编码器输入队列顶满，从「慢」变「丢」。
        assertEquals(AudioSender.MAX_FEED_PER_TICK, AudioSender.feedFramesFor(depth = 0, target = deepTarget))
        assertEquals(AudioSender.MAX_FEED_PER_TICK, AudioSender.feedFramesFor(depth = 1, target = deepTarget))
    }

    @Test
    fun `垫深到目标后回到每拍一帧`() {
        assertEquals(2, AudioSender.feedFramesFor(depth = 3, target = deepTarget))
        assertEquals(1, AudioSender.feedFramesFor(depth = 4, target = deepTarget))
    }

    /**
     * 端到端推演：编码器**每 3 拍才吐一次 3 帧**（日志里观测到的形态），
     * 验「一拍一包」下队列不会空、也不会无限加深。
     */
    @Test
    fun `编码器成批吐帧时队列不空也不无限加深`() {
        var depth = 1 // 起播已垫到稳态
        var targetDepth = 1
        var codecBacklog = 0 // 编码器里还没吐出来的帧
        var emptyTicks = 0
        val depthTrace = mutableListOf<Int>()

        repeat(300) { tick ->
            val feed = AudioSender.feedFramesFor(depth, targetDepth)
            codecBacklog += feed
            // 每 3 拍吐一次，把积压全吐出来（就是日志里 3~5 帧一口气到账的形态）
            if (tick % 3 == 2) {
                depth += codecBacklog
                codecBacklog = 0
                if (depth > AudioSender.MAX_QUEUE_DEPTH) {
                    depth = AudioSender.MAX_QUEUE_DEPTH
                    codecBacklog = 0
                }
            }
            // 一拍一包
            if (depth > 0) {
                depth--
            } else {
                emptyTicks++
                if (targetDepth < AudioSender.MAX_QUEUE_TARGET) targetDepth++
            }
            depthTrace += depth
        }

        assertTrue(
            "队列必须始终远低于硬上限（延迟不能一路堆上去），实测峰值 ${depthTrace.max()}",
            depthTrace.max() <= AudioSender.MAX_QUEUE_DEPTH,
        )
        // 自适应把目标垫到 2 帧即可吸收「3 拍吐 3 帧」的节奏；允许极少数空拍，
        // 但绝不能是「每 3 拍空 1 次」那种（旧实现 300 拍会空 100 次）。
        assertTrue("空拍应被自适应压到很低，实测 $emptyTicks/300", emptyTicks < 10)
    }
}
