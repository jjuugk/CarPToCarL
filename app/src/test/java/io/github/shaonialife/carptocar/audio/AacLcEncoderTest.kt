package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADTS 头拼装与音频方案切换的单测。
 *
 * 背景：车机（Honda HFT / Alpine 8A250-30A）走 type=102 + AAC-LC 时「能连、能消费、
 * 没声音」，剩下没法用日志区分的差异就是负载封装（裸帧 vs ADTS）。这里把 ADTS 头的
 * 每一位固定下来，避免真车试错时把「封装写错」误当成「车机不认」。
 */
class AacLcEncoderTest {
    /** 从帧头解析 13 位帧长。 */
    private fun frameLength(header: ByteArray): Int =
        ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)

    /** 从帧头解析声道配置。 */
    private fun channelConfig(header: ByteArray): Int = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xFF) shr 6)

    @Test
    fun adtsHeaderFor44100Stereo() {
        val payload = 592
        val header = AacLcEncoder.adtsHeader(payload, 44_100, 2)

        assertEquals(7, header.size)
        // 同步字 FFF + MPEG-4 + layer 0 + protection_absent=1
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        // profile = AAC-LC（存 AOT-1 = 1）
        assertEquals(1, (header[2].toInt() and 0xFF) shr 6)
        // 采样率索引：44100 → 4
        assertEquals(4, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        // 声道配置：立体声 → 2
        assertEquals(2, channelConfig(header))
        // 帧长（含 7 字节头）
        assertEquals(payload + 7, frameLength(header))
        // buffer_fullness = 0x7FF（11 位：h5 低 5 位 + h6 高 6 位），num_raw_data_blocks = 0
        assertEquals(
            0x7FF,
            ((header[5].toInt() and 0x1F) shl 6) or ((header[6].toInt() and 0xFC) shr 2),
        )
        assertEquals(0, header[6].toInt() and 0x03)
    }

    @Test
    fun adtsHeaderTracksSampleRateAndChannels() {
        // 48k → 索引 3；单声道 → 1
        val header = AacLcEncoder.adtsHeader(100, 48_000, 1)
        assertEquals(3, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        assertEquals(1, channelConfig(header))
        assertEquals(107, frameLength(header))
    }

    @Test
    fun frequencyIndexTable() {
        assertEquals(4, AacLcEncoder.frequencyIndex(44_100))
        assertEquals(3, AacLcEncoder.frequencyIndex(48_000))
        assertEquals(8, AacLcEncoder.frequencyIndex(16_000))
        // 未收录的采样率退回 44100，避免写出非法索引
        assertEquals(4, AacLcEncoder.frequencyIndex(37_000))
    }

    @Test
    fun tuningIsFixedToRawFrames() {
        // 定案：裸 AAC 帧 + spf=1024 + 到达时间戳，只有这一组，不再提供切换
        assertEquals(1, AudioTuning.entries.size)
        assertTrue(!AudioTuning.RAW_SPF.adts)
        assertEquals(1024, AudioTuning.RAW_SPF.spf)
        assertTrue(!AudioTuning.RAW_SPF.highAccuracyTimestamps)
    }

    /**
     * AudioSpecificConfig 的位布局（ISO/IEC 14496-3，2 字节版）。
     *
     * 它只在自检里用（给解码器补 csd-0），但取值错了会让「自检」永远失败或解出错的东西——
     * 2026-11-17 之前正是因为没有这个字段，自检在五六份日志里一直报「没解出样本」，
     * 把「码流可能有问题」这个假象维持了整整一周。
     */
    @Test
    fun audioSpecificConfigForAacLc48kStereo() {
        // 2 = AAC-LC, 3 = 48000Hz, 2 = 立体声 → (2<<11)|(3<<7)|(2<<3) = 0x1190
        val asc = AacLcEncoder.audioSpecificConfig(48_000, 2)
        assertEquals(2, asc.size)
        assertEquals(0x11, asc[0].toInt() and 0xFF)
        assertEquals(0x90, asc[1].toInt() and 0xFF)
    }

    @Test
    fun audioSpecificConfigForAacLc44100Stereo() {
        // 4 = 44100Hz → (2<<11)|(4<<7)|(2<<3) = 0x1210
        val asc = AacLcEncoder.audioSpecificConfig(44_100, 2)
        assertEquals(0x12, asc[0].toInt() and 0xFF)
        assertEquals(0x10, asc[1].toInt() and 0xFF)
    }

    @Test
    fun audioSpecificConfigMatchesAdtsFrequencyIndex() {
        // 两处索引必须同源：否则「ADTS 头说 44.1k、asc 说 48k」这种组合会让解码时钟错位
        //
        // ASC 的 4 bit 采样率索引跨字节边界：
        //   byte0 = [objectType 5 bit][freqIndex 高 3 bit]
        //   byte1 = [freqIndex 低 1 bit][channelConfig 4 bit][0 3 bit]
        listOf(48_000, 44_100, 32_000, 24_000).forEach { rate ->
            val asc = AacLcEncoder.audioSpecificConfig(rate, 2)
            val hi = (asc[0].toInt() and 0xFF) and 0x07
            val lo = ((asc[1].toInt() and 0xFF) and 0x80) shr 7
            assertEquals("采样率 $rate 的索引对不上", AacLcEncoder.frequencyIndex(rate), (hi shl 1) or lo)
        }
    }
}