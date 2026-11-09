package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条流能不能挂麦克风上行端口」的判据。
 *
 * 判据只认车机 `/info` 对**这一档**声明的 `audioInputFormats`，不认 `audioType` 的名字。
 * 两台实车把这个区别钉死了（`docs/carplay-mic-uplink-20261108.md` 第 11 节）：
 *  - 宝马 G38 `t102/media` 声明 `input=0x0`，塞端口后车机 17ms 关通道、整机重启；
 *  - 奥迪 MIB3H 的媒体通路 `t100/media` 同样叫 media，却声明了输入位，属于真能用的通路。
 */
class AudioProfileMicInputTest {
    /** 宝马 G38：media 档，车机明说没有输入能力，不得声明端口。 */
    @Test
    fun mediaWithoutInputBitsIsRejected() {
        val profile = AudioProfile("media", 1L shl 22, 44_100, 250, 102L, AudioCodec.AAC_LC, inputFormats = 0L)
        assertEquals(false, profile.micInputDeclared)
    }

    /** 奥迪 3873/3874：同样叫 media，但声明了输入位（bit26/27），必须保住麦克风。 */
    @Test
    fun mediaWithInputBitsIsAccepted() {
        val profile =
            AudioProfile("media", 1L shl 15, 48_000, 250, 100L, AudioCodec.PCM, inputFormats = 0xc000000L)
        assertEquals(true, profile.micInputDeclared)
    }

    /** `/info` 没给这一档的位图：未知，保守不声明（不拿猜的判据去冒重启的险）。 */
    @Test
    fun unknownInputBitsIsNeither() {
        val profile = AudioProfile("media", 1L shl 15, 48_000, 250)
        assertNull(profile.inputFormats)
        assertNull(profile.micInputDeclared)
        assertFalse(profile.micInputDeclared == true)
    }

    /** 输入位图不参与 key：改判据不能把历史踩雷名单作废。 */
    @Test
    fun inputBitsDoNotChangeKey() {
        val blind = AudioProfile("media", 1L shl 15, 48_000, 250)
        val withBits = blind.copy(inputFormats = 0xc000000L)
        assertEquals(blind.key, withBits.key)
        assertTrue(blind.label == withBits.label)
    }
}
