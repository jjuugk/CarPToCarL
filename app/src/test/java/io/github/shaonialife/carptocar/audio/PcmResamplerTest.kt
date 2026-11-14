package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [PcmResampler] 的行为约定。
 *
 * 这些断言都是从 2026-11-13 的真机日志倒推出来的：
 * 「音频直通 216 包 / 5s（44.1k）× AAC 46.86 帧/秒（48k）」——比值对，
 * 但累计发出去的样本比声明的采样率少 0.05%，长会话会把车机的抖动缓冲抽干。
 */
class PcmResamplerTest {
    private val chunkFrames = 1024

    /** 造一块 16bit 小端交织 PCM：每帧左右声道各填 [value]。 */
    private fun pcm(
        frames: Int,
        value: Short = 0,
    ): ByteArray {
        val out = ByteArray(frames * 4)
        var i = 0
        while (i < frames) {
            for (c in 0 until 2) {
                out[i * 4 + c * 2] = (value.toInt() and 0xFF).toByte()
                out[i * 4 + c * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            }
            i++
        }
        return out
    }

    private fun framesOf(bytes: ByteArray): Int = bytes.size / 4

    @Test
    fun `采样率相同时原样返回并清掉相位`() {
        val resampler = PcmResampler()
        val src = pcm(chunkFrames, 123)
        val once = resampler.convert(src, src.size, 48_000, 48_000)
        assertEquals(src.size, once.size)
        // 换过采样率之后不能用上一段的尾巴做插值：这里再走一次 44.1k，长度必须按新比值算
        val resampled = resampler.convert(src, src.size, 44_100, 48_000)
        assertEquals(1114, framesOf(resampled))
    }

    @Test
    fun `44点1k 转 48k 的长期比值精确（不按块截断）`() {
        val resampler = PcmResampler()
        val src = pcm(chunkFrames)
        val chunks = 100
        var total = 0
        repeat(chunks) { total += framesOf(resampler.convert(src, src.size, 44_100, 48_000)) }
        val ideal = chunks.toDouble() * chunkFrames * 48_000 / 44_100
        // 旧实现每块 floor(1024×48000/44100)=1114，100 块固定 111400（少 56 帧）
        assertTrue(
            "累计输出 $total 帧，应接近 $ideal（旧实现会给 111400）",
            abs(total - ideal) <= 2.0,
        )
    }

    @Test
    fun `分块与整块喂入的输出帧数一致（相位跨块延续）`() {
        val chunked = PcmResampler()
        var chunkedFrames = 0
        val src = pcm(chunkFrames)
        repeat(10) { chunkedFrames += framesOf(chunked.convert(src, src.size, 44_100, 48_000)) }

        val whole = PcmResampler()
        val big = pcm(chunkFrames * 10)
        val wholeFrames = framesOf(whole.convert(big, big.size, 44_100, 48_000))
        assertTrue(
            "分块 $chunkedFrames 帧 vs 整块 $wholeFrames 帧",
            abs(chunkedFrames - wholeFrames) <= 2,
        )
    }

    @Test
    fun `直流信号重采样后仍然直流（插值不引入台阶）`() {
        val resampler = PcmResampler()
        val src = pcm(chunkFrames, 16384)
        val out = resampler.convert(src, src.size, 44_100, 48_000)
        var i = 0
        var min = Short.MAX_VALUE.toInt()
        var max = Short.MIN_VALUE.toInt()
        while (i < out.size) {
            val sample = (((out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)).toShort()).toInt()
            if (sample < min) min = sample
            if (sample > max) max = sample
            i += 2
        }
        // 允许 ±1（线性插值在整数相减时的取值误差）
        assertTrue("min=$min max=$max", min >= 16383 && max <= 16385)
    }

    @Test
    fun `左右声道各自独立插值，不会串台`() {
        val resampler = PcmResampler()
        // 左 +4096、右 -4096：插值若把两路混在一起，对称性会立刻被破坏
        val frames = chunkFrames
        val src = ByteArray(frames * 4)
        for (i in 0 until frames) {
            src[i * 4] = 0x00
            src[i * 4 + 1] = 0x10 // 4096
            src[i * 4 + 2] = 0x00
            src[i * 4 + 3] = 0xF0.toByte() // -4096
        }
        val out = resampler.convert(src, src.size, 48_000, 44_100)
        assertTrue(out.isNotEmpty())
        var i = 0
        while (i < out.size) {
            val left = (((out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)).toShort()).toInt()
            val right = (((out[i + 2].toInt() and 0xFF) or (out[i + 3].toInt() shl 8)).toShort()).toInt()
            assertEquals(4096, left)
            assertEquals(-4096, right)
            i += 4
        }
    }

    @Test
    fun `空输入不崩`() {
        val resampler = PcmResampler()
        assertEquals(0, resampler.convert(ByteArray(0), 0, 44_100, 48_000).size)
        assertEquals(0, resampler.convert(ByteArray(2), 2, 44_100, 48_000).size)
    }

    @Test
    fun `输出样本数换算成每秒与声明采样率对齐`() {
        val resampler = PcmResampler()
        val src = pcm(chunkFrames)
        // 43.07 块/秒（44.1k、每块 1024 帧）跑 30 秒
        val chunks = (44_100.0 / chunkFrames * 30).roundToInt()
        var total = 0
        repeat(chunks) { total += framesOf(resampler.convert(src, src.size, 44_100, 48_000)) }
        val perSecond = total.toDouble() / 30.0
        assertTrue("实测 $perSecond 样本/秒，应≈48000", abs(perSecond - 48_000.0) < 3.0)
    }
}
