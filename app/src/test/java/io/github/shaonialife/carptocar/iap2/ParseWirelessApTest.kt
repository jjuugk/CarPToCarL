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
 * 二者在 1..4 区间重叠，导致信道 1~4 被误当安全类型、WPA2 热点死活连不上。
 * 现按**字段形态**在两套真实布局间二选一（catplay 规范定义逐字段印证）：
 *  - A：`0x4301 CarPlayStartSessionWirelessAttributes` → 0=SSID,1=密码,2=信道,3=IP,4=安全类型；
 *  - B：`0x5703 AccessoryWiFiConfigurationInformation` 的顺序 → 1=SSID,2=密码,3=安全类型,4=信道。
 * A 里 id3 恒为 IP（字符串/4 字节），B 里 id3 是单字节安全类型，据此判定，不猜取值。
 *
 * 这里用与真机线格式一致的 TLV 构造无线组：Iap2Csm.string 会带 NUL 终止符，u8 是单字节。
 */
class ParseWirelessApTest {
    private fun group(vararg parts: ByteArray): List<Iap2Csm.Param> = parseParams(parts.fold(ByteArray(0)) { a, b -> a + b })

    /** 布局 A（0x4301 规范 + WinPlay 实测 + 实车日志）：2=信道, 4=安全类型。 */
    @Test
    fun standardLayoutChannelAndSecurity() {
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

    /**
     * 布局 B（0x5703 式）：3=安全类型, 4=信道。
     * 旧版的「整套 ID +1 → 3=信道/5=安全类型」兜底会把安全类型读成信道，这里锁死正确顺序。
     */
    @Test
    fun otherMessageLayoutSecurityAndChannel() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(1, "MyCar"),
                    string(2, "secret"),
                    u8(3, 2),
                    u8(4, 149),
                ),
            )
        assertEquals("MyCar", cfg!!.ssid)
        assertEquals("secret", cfg.passphrase)
        assertEquals(149, cfg.channel)
        assertEquals(2, cfg.securityType)
    }

    /**
     * 布局 B + 信道落在 1..4 重叠区：最容易出错的一格。
     * 若先按 A 取值，id4（信道=3）会被当成合法安全类型，从而永远不会去判 B。
     * 期望：信道=3、安全类型=2。
     */
    @Test
    fun otherMessageLayoutChannelInOverlapRangeMustNotSwap() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(1, "MyCar"),
                    string(2, "secret"),
                    u8(3, 2),
                    u8(4, 3),
                ),
            )
        assertEquals(3, cfg!!.channel)
        assertEquals(2, cfg.securityType)
    }

    /** 布局 A + 信道落在 1..4 重叠区，确认主路径本身也读对（信道=4, 安全类型=2）。 */
    @Test
    fun standardLayoutChannelInOverlapRange() {
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

    /**
     * 布局 A 但缺信道字段：id3 是 IP（字符串），不得被判成 B，
     * 也不得把 id4 的安全类型（2）当成信道。期望信道=0（未知）、安全类型=2。
     */
    @Test
    fun standardLayoutMissingChannelMustNotTreatSecurityAsChannel() {
        val cfg =
            Iap2SenderSession.parseWirelessAp(
                group(
                    string(0, "MyCar"),
                    string(1, "secret"),
                    string(3, "192.168.49.1"),
                    u8(4, 2),
                ),
            )
        assertEquals(0, cfg!!.channel)
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
