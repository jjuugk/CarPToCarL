package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.iap2.Iap2Csm.parseParams
import io.github.shaonialife.carptocar.iap2.Iap2Csm.string
import io.github.shaonialife.carptocar.iap2.Iap2Csm.u8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Iap2SenderSession.parseWirelessAp] 的聚焦单测。
 *
 * 背景：旧实现用「取值区间盲猜」区分信道(1..196)与安全类型(0..4)，
 * 二者在 1..4 区间重叠，导致 1 基车机 + 信道 1~4 时把信道误当安全类型、真实安全类型被漏掉，
 * WPA2 热点死活连不上。修复后改用 ID 定位（0 基读 id2=信道/id4=安全类型，1 基读 id3/id5）。
 *
 * 这里用与真机线格式一致的 TLV 构造无线组：Iap2Csm.string 会带 NUL 终止符，u8 是单字节。
 */
class ParseWirelessApTest {
    private fun group(vararg parts: ByteArray): List<Iap2Csm.Param> = parseParams(parts.fold(ByteArray(0)) { a, b -> a + b })

    /** 0 基（规范 + WinPlay 实测）：2=信道, 4=安全类型。与 MockAccessory.sendStartSession 同源。 */
    @Test
    fun zeroBasedChannelAndSecurity() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(0, "MyCar"),
                    string(1, "secret"),
                    u8(2, 36),
                    string(3, "192.168.49.1"),
                    u8(4, 2),
                ),
            )
        assertEquals("MyCar", cfg!!.ssid)
        assertEquals("secret", cfg.passphrase)
        assertEquals(36, cfg.channel)
        assertEquals(2, cfg.securityType)
    }

    /** 1 基车机：整套 ID +1（3=信道, 5=安全类型）。旧实现会把信道漏读成 0。 */
    @Test
    fun oneBasedChannelAndSecurity() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(1, "MyCar"),
                    string(2, "secret"),
                    u8(3, 6),
                    string(4, "192.168.49.1"),
                    u8(5, 2),
                ),
            )
        assertEquals("MyCar", cfg!!.ssid)
        assertEquals("secret", cfg.passphrase)
        assertEquals(6, cfg.channel)
        assertEquals(2, cfg.securityType)
    }

    /**
     * 1 基车机 + 信道落在 1..4 重叠区：这正是旧实现确定性把信道误当安全类型、连不上 WPA2 的根因。
     * 期望：信道=3、安全类型=2（而不是旧实现的信道=0、安全类型=3）。
     */
    @Test
    fun oneBasedChannelInOverlapRangeMustNotSwap() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(1, "MyCar"),
                    string(2, "secret"),
                    u8(3, 3),
                    u8(5, 2),
                ),
            )
        assertEquals(3, cfg!!.channel)
        assertEquals(2, cfg.securityType)
    }

    /** 0 基 + 信道落在 1..4 重叠区，确认 0 基本身也读对（信道=4, 安全类型=2）。 */
    @Test
    fun zeroBasedChannelInOverlapRange() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(0, "MyCar"),
                    string(1, "secret"),
                    u8(2, 4),
                    u8(4, 2),
                ),
            )
        assertEquals(4, cfg!!.channel)
        assertEquals(2, cfg.securityType)
    }

    /** SSID 缺失（没有可用字符串）必须返回 null，让调用方报错而不是用空 SSID 盲连。 */
    @Test
    fun missingSsidReturnsNull() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(u8(2, 36), u8(4, 2)),
            )
        assertNull(cfg)
    }

    /** 非 IPv4 的 4 字节字段（如裸 IP 字节）必须被排除出单字节候选，不污染信道/安全类型。 */
    @Test
    fun fourByteIpExcludedFromSingles() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(0, "MyCar"),
                    string(1, "secret"),
                    u8(2, 11),
                    Iap2Csm.param(3, byteArrayOf(192.toByte(), 168.toByte(), 49.toByte(), 1.toByte())),
                    u8(4, 2),
                ),
            )
        assertEquals(11, cfg!!.channel)
        assertEquals(2, cfg.securityType)
    }
}
