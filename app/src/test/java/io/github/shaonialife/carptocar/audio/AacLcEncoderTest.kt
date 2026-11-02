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
     * 收尾竞态：会话断开时采集线程比 stop() 晚一拍，仍会喂帧进来。
     *
     * 2026-11-01 小米 → MIB3 日志里的 `Invalid to call during stop(); only valid in
     * executing state` 就是这一路；契约必须是「停止后喂帧只返回空表，绝不碰 codec」。
     */
    @Test
    fun encodeAfterStopDropsFramesWithoutTouchingCodec() {
        val encoder = AacLcEncoder(44_100, 2, log = {})
        assertTrue(!encoder.started)

        // 从未启动过：encode 只是丢弃，不抛异常（本机 JVM 里 MediaCodec 是空壳，
        // 一旦误碰就会 IllegalStateException/RuntimeException）
        assertTrue(encoder.encode(ByteArray(encoder.inputFrameBytes), encoder.inputFrameBytes).isEmpty())

        // stop() 幂等且不依赖已启动状态
        encoder.stop()
        encoder.stop()
        assertTrue(!encoder.started)

        // 停止后继续喂：仍旧静默丢弃
        assertTrue(encoder.encode(ByteArray(encoder.inputFrameBytes), encoder.inputFrameBytes).isEmpty())
    }
}
