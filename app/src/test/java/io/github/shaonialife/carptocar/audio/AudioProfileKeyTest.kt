package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频档位键的**向后兼容**（通话适配 2026-10-29 引入 `isInput`）。
 *
 * [AudioProfileMemory] 是按 key 存「这台车机上哪些档位踩过雷」的名单：下行档的 key
 * 一旦改格式，历史结论全部作废，下次连接会把已经证明有问题的档位再撞一遍——
 * 每撞一次就是一轮 SETUP + 约 6.5 秒后被车机拆链。所以下行档 key 必须逐字符不变，
 * 只有新引入的上行档才追加后缀。
 */
class AudioProfileKeyTest {
    /** 下行档的键必须与引入 isInput 之前的格式逐字符一致。 */
    @Test
    fun downstreamKeyKeepsLegacyFormat() {
        val media = AudioProfile("media", 1L shl 15, 48_000, 250, 100L, AudioCodec.AAC_LC)
        assertEquals("100|media|32768|AAC_LC", media.key)
    }

    /** 上行档与下行档不能撞键：撞了会把「通话麦克风可用」当成「媒体档可用」。 */
    @Test
    fun inputProfileKeyIsDistinct() {
        val down = AudioProfile("telephony", 1L shl 15, 48_000, 250)
        val up = AudioProfile("telephony", 1L shl 15, 48_000, 250, isInput = true)
        assertNotEquals(down.key, up.key)
        assertTrue(up.key.endsWith("|in"))
    }

    /** 标签带 ↑：日志里一眼看出是上行（接收）档。 */
    @Test
    fun inputProfileLabelIsMarked() {
        val up = AudioProfile("telephony", 1L shl 15, 48_000, 250, isInput = true)
        assertTrue(up.label.startsWith("↑"))
    }
}
