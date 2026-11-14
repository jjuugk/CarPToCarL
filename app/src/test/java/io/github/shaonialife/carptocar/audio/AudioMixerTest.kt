package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混音器的行为约定。
 *
 * 这些断言对应 2026-11-14 那次「导航和音乐一起播放、过一会儿声音卡顿」的排查结论：
 * 两路必须**相加在同一帧里**、速率必须按墙钟定拍、缓冲满了只能丢最旧。
 */
class AudioMixerTest {
    private val rate = 48_000
    private val channels = 2
    private val frameSamples = 1024
    private val frameBytes = frameSamples * channels * 2

    private fun mixer(capacityMillis: Int = 500) =
        AudioMixer(
            sampleRate = rate,
            channels = channels,
            frameSamples = frameSamples,
            capacityMillis = capacityMillis,
        )

    /** 构造一段交织 PCM，所有样本取同一个值。 */
    private fun pcm(
        frames: Int,
        value: Int,
    ): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var i = 0
        while (i < out.size) {
            out[i] = (value and 0xFF).toByte()
            out[i + 1] = ((value shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun sampleAt(
        buffer: ByteArray,
        frame: Int,
        channel: Int = 0,
    ): Int {
        val offset = (frame * channels + channel) * 2
        val lo = buffer[offset].toInt() and 0xFF
        val hi = buffer[offset + 1].toInt() and 0xFF
        return (((hi shl 8) or lo).toShort()).toInt()
    }

    @Test
    fun `只有媒体时原样输出`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        mixer.feed(AudioSource.MEDIA, pcm(frameSamples, 1000), 0, frameSamples * 4)
        mixer.mixNextFrame(buffer, System.nanoTime())
        assertEquals(1000, sampleAt(buffer, 0))
        assertEquals(1000, sampleAt(buffer, frameSamples - 1, 1))
    }

    @Test
    fun `只有播报时也不能被丢掉`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        mixer.feed(AudioSource.NAV, pcm(frameSamples, 800), 0, frameSamples * 4)
        mixer.mixNextFrame(buffer, System.nanoTime())
        // 播报是「加」进来的，音乐为空时输出就是播报本身
        assertEquals(800, sampleAt(buffer, 0))
        assertEquals(800, sampleAt(buffer, 512))
    }

    @Test
    fun `两路在同一帧里相加而不是首尾拼接`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        mixer.feed(AudioSource.MEDIA, pcm(frameSamples * 2, 1000), 0, frameSamples * 8)
        mixer.feed(AudioSource.NAV, pcm(frameSamples * 2, 500), 0, frameSamples * 8)
        // 旧实现这里会得到「前 512 帧 1000、后 512 帧 500」（首尾相接、互不相加）；
        // 正确结果是每一帧都是「被压过的音乐 + 播报」，而不是两段拼接。
        mixer.mixNextFrame(buffer, System.nanoTime())
        // 起手这一帧仍在斜坡起点：音乐原音量 + 播报
        assertEquals(1500, sampleAt(buffer, 0))
        // 一帧之内压到 −12dB：末样本的增益是 259/1024（还没完全落到 258）
        assertEquals(((1000 * 259) shr 10) + 500, sampleAt(buffer, frameSamples - 1))
        // 第二帧全程稳定在 duck 增益（258/1024 ≈ −12dB）
        mixer.mixNextFrame(buffer, System.nanoTime())
        assertEquals(((1000 * 258) shr 10) + 500, sampleAt(buffer, 0))
        assertEquals(((1000 * 258) shr 10) + 500, sampleAt(buffer, frameSamples - 1))
    }

    @Test
    fun `相加溢出时饱和限幅`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        mixer.feed(AudioSource.MEDIA, pcm(frameSamples, 30_000), 0, frameSamples * 4)
        mixer.feed(AudioSource.NAV, pcm(frameSamples, 30_000), 0, frameSamples * 4)
        mixer.mixNextFrame(buffer, System.nanoTime())
        assertEquals(Short.MAX_VALUE.toInt(), sampleAt(buffer, 0))

        val mixer2 = mixer()
        val buffer2 = ByteArray(frameBytes)
        mixer2.feed(AudioSource.MEDIA, pcm(frameSamples, -30_000), 0, frameSamples * 4)
        mixer2.feed(AudioSource.NAV, pcm(frameSamples, -30_000), 0, frameSamples * 4)
        mixer2.mixNextFrame(buffer2, System.nanoTime())
        assertEquals(Short.MIN_VALUE.toInt(), sampleAt(buffer2, 0))
    }

    @Test
    fun `播报在场时音乐被压到约负12dB`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        // 各喂两帧：第一帧走完增益斜坡，第二帧稳定在 duck 增益
        mixer.feed(AudioSource.MEDIA, pcm(frameSamples * 2, 1000), 0, frameSamples * 8)
        mixer.feed(AudioSource.NAV, pcm(frameSamples * 2, 500), 0, frameSamples * 8)
        mixer.mixNextFrame(buffer, System.nanoTime())
        mixer.mixNextFrame(buffer, System.nanoTime())
        // 258/1024 ≈ 0.252 → 1000 → 252，加播报 500 = 752
        val expected = ((1000 * 258) shr 10) + 500
        assertEquals(expected, sampleAt(buffer, 0))
        assertEquals(expected, sampleAt(buffer, frameSamples - 1))
    }

    @Test
    fun `播报停了 hold 之后音乐放回来`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        val now = System.nanoTime()
        mixer.feed(AudioSource.NAV, pcm(frameSamples, 500), 0, frameSamples * 4)
        mixer.feed(AudioSource.MEDIA, pcm(frameSamples * 4, 1000), 0, frameSamples * 16)
        mixer.mixNextFrame(buffer, now)
        // 播报数据已读完，但 250ms 以内仍按 duck 处理
        mixer.mixNextFrame(buffer, now + 100_000_000L)
        assertEquals((1000 * 258) shr 10, sampleAt(buffer, 0))
        // 超过 hold：这一帧开始回升（斜坡起点仍在 258），帧末已回到原音量
        mixer.mixNextFrame(buffer, now + 400_000_000L)
        assertEquals((1000 * 258) shr 10, sampleAt(buffer, 0))
        assertTrue("帧末应已回到接近原音量", sampleAt(buffer, frameSamples - 1) > 990)
        // 再下一帧全程原音量
        mixer.mixNextFrame(buffer, now + 420_000_000L)
        assertEquals(1000, sampleAt(buffer, 0))
    }

    @Test
    fun `两路都没有数据时输出静音`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes) { 0x7F }
        repeat(3) { mixer.mixNextFrame(buffer, System.nanoTime()) }
        assertEquals(0, sampleAt(buffer, 0))
        assertEquals(0, sampleAt(buffer, frameSamples - 1, 1))
    }

    @Test
    fun `环形缓冲写满时丢最旧`() {
        // 10ms 容量 = 480 帧
        val mixer = mixer(capacityMillis = 10)
        val buffer = ByteArray(frameBytes)
        // 写入 600 帧，每帧的值等于它的序号（<30000，避免限幅干扰）
        val source = ByteArray(600 * channels * 2)
        for (frame in 0 until 600) {
            for (channel in 0 until channels) {
                val offset = (frame * channels + channel) * 2
                val value = frame
                source[offset] = (value and 0xFF).toByte()
                source[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }
        mixer.feed(AudioSource.MEDIA, source, 0, source.size)
        mixer.mixNextFrame(buffer, System.nanoTime())
        // 容量 480 → 最旧的 120 帧被丢掉，第 0 帧读到的应是序号 120
        assertEquals(120, sampleAt(buffer, 0))
        assertTrue("溢出必须记进统计（持续增长说明某一路推得比实时快）", mixer.droppedBytes > 0)
    }

    @Test
    fun `定拍_一秒产出等于每秒帧数`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        val t0 = 1_000_000_000L
        assertEquals(1, mixer.dueFrames(t0))
        mixer.mixNextFrame(buffer, t0)
        // 10ms 还没到下一帧（21.33ms）
        assertEquals(0, mixer.dueFrames(t0 + 10_000_000L))
        // 22ms 到了第一帧的边界，但已经产出 1 帧
        assertEquals(0, mixer.dueFrames(t0 + 22_000_000L))

        // 模拟泵线程：每 5ms 醒一次，走满一秒。产帧数应严格等于 48000/1024 ≈ 46.9
        var emitted = 1
        var now = t0
        repeat(200) {
            now += 5_000_000L
            val due = mixer.dueFrames(now)
            if (due > 0) {
                repeat(due) { mixer.mixNextFrame(buffer, now) }
                emitted += due
            }
        }
        assertTrue("一秒产出应≈46 帧，实际 $emitted", emitted in 45..48)
    }

    @Test
    fun `落后太多不追赶`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        val t0 = 2_000_000_000L
        mixer.dueFrames(t0)
        mixer.mixNextFrame(buffer, t0)
        // 一整秒没被调度：不能一口气补 46 帧（RTP 时间戳会瞬间前跳），只补 1 帧并重新对齐
        assertEquals(1, mixer.dueFrames(t0 + 1_000_000_000L))
    }

    @Test
    fun `重置后统计与缓冲都归零`() {
        val mixer = mixer()
        val buffer = ByteArray(frameBytes)
        mixer.feed(AudioSource.MEDIA, pcm(frameSamples, 1000), 0, frameSamples * 4)
        mixer.mixNextFrame(buffer, System.nanoTime())
        assertTrue(mixer.windowFrames > 0)
        mixer.reset()
        assertEquals(0, mixer.windowFrames)
        assertEquals(0, mixer.framesEmitted)
        mixer.mixNextFrame(buffer, System.nanoTime())
        assertEquals(0, sampleAt(buffer, 0))
    }
}
