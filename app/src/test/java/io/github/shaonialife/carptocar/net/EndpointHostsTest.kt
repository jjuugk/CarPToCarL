package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接收端地址候选与 mDNS features 解析。
 *
 * 两条都是「老车机连不上」的根因候选（2026-10-19 奥迪 4xxx / CarPlay 210.81）：
 *  - 旧代码只认 IPv4（`contains('.')`）→ 只发布链路本地 IPv6 的车机被判成无地址；
 *  - 旧代码不看 TXT features → 无法识别 pre-HomeKit 车机，空跑 120s 配对窗口。
 */
class EndpointHostsTest {
    @Test
    fun ipv6OnlyEndpointIsUsable() {
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::f822:90ff:fe13:3b18%wlan0")))
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::1")))
        assertFalse(EndpointHosts.hasUsable(emptyList()))
        assertFalse(EndpointHosts.hasUsable(listOf("", "not-an-ip")))
    }

    @Test
    fun ipv4PreferredThenGlobalIpv6ThenLinkLocal() {
        val ordered =
            EndpointHosts.candidates(
                listOf(
                    "fe80::1%wlan0",
                    "2001:db8::5",
                    "10.174.189.1",
                ),
            )
        assertEquals(listOf("10.174.189.1", "2001:db8::5", "fe80::1%wlan0"), ordered)
    }

    /**
     * 2026-11-04 奥迪 4910（MIB3 4580）实车：`LinkProperties.routes` 里第一条默认路由
     * 是 IPv6 链路本地 `fe80::1086:3aff:fe8d:9239`，旧代码只取这一条就当成车机地址，
     * 8 次重连全打在它身上（每次 2s 超时）；同轮 mDNS 给出的 `10.174.189.1:7000`
     * 其实通。兜底必须把 IPv4 那条一起收进来并排到最前。
     */
    @Test
    fun gatewayFallbackKeepsIpv4AheadOfLinkLocal() {
        val ordered =
            EndpointHosts.candidates(
                listOf("fe80::1086:3aff:fe8d:9239", "10.174.189.1"),
            )
        assertEquals(listOf("10.174.189.1", "fe80::1086:3aff:fe8d:9239"), ordered)
        assertEquals("10.174.189.1", ordered.first())
    }

    @Test
    fun duplicateScopedAndUnscopedAddressesCollapse() {
        val ordered = EndpointHosts.candidates(listOf("fe80::1", "fe80::1%wlan0", "FE80::1%3"))
        assertEquals(1, ordered.size)
        assertEquals("fe80::1", ordered.first())
    }

    /**
     * 2026-11-11 宝马 iD8：车机热点 DHCP 下发的默认网关是占位值 `0.0.0.0`，
     * 旧代码用 `contains('.')` 判定 IPv4 放行，端点里只剩它 → 每次连接都打
     * `127.0.0.1:7000`（日志里四十多条 ECONNREFUSED），真正的车机一次都没被试过。
     *
     * 占位地址必须在候选这一层就被剔掉：`0.0.0.0` / `127.x` / `::` / `::1` 永远连不到车机，
     * 放进去只会烧 2s 超时并污染「7000 连续拒绝」计数。链路本地 IPv6 不是占位，别误伤。
     */
    @Test
    fun placeholderAddressesAreNeverCandidates() {
        assertTrue(EndpointHosts.isPlaceholder("0.0.0.0"))
        assertTrue(EndpointHosts.isPlaceholder("::"))
        assertTrue(EndpointHosts.isPlaceholder("::1"))
        assertTrue(EndpointHosts.isPlaceholder("127.0.0.1"))
        assertTrue(EndpointHosts.isPlaceholder("::ffff:0.0.0.0"))
        assertTrue(EndpointHosts.isPlaceholder(""))
        assertFalse(EndpointHosts.isPlaceholder("fe80::1%wlan0"))
        assertFalse(EndpointHosts.isPlaceholder("172.16.222.97"))

        assertEquals(emptyList<String>(), EndpointHosts.candidates(listOf("0.0.0.0")))
        assertEquals(
            listOf("172.16.222.97", "fe80::1%wlan0"),
            EndpointHosts.candidates(listOf("0.0.0.0", "172.16.222.97", "127.0.0.1", "fe80::1%wlan0")),
        )
        // 端点里只剩占位地址时，「没有可用地址」必须为真——这是 AirPlayClient 不把
        // 失败计成「车机拒绝」的判据。
        assertFalse(EndpointHosts.hasUsable(listOf("0.0.0.0", "::")))
    }

    @Test
    fun ipv4NetworkFilterUses24Prefix() {
        assertTrue(EndpointHosts.isSameNetwork("10.174.189.1", "10.174.189.57"))
        assertFalse(EndpointHosts.isSameNetwork("172.30.8.246", "10.174.189.57"))
        // 拿不到本地 IPv4 时不设限（旧行为）
        assertTrue(EndpointHosts.isSameNetwork("10.174.189.1", null))
    }

    @Test
    fun linkLocalIpv6AlwaysAcceptedGlobalIpv6NeedsMatchingPrefix() {
        assertTrue(EndpointHosts.isSameNetwork("fe80::1%wlan0", "10.0.0.2"))
        assertTrue(
            EndpointHosts.isSameNetwork(
                "2001:db8:1:2::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        assertFalse(
            EndpointHosts.isSameNetwork(
                "2001:db8:9:9::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        // 本地没有 IPv6 信息：拒绝全局 IPv6（宁可走网关兜底，也不误连家庭网络设备）
        assertFalse(EndpointHosts.isSameNetwork("2001:db8:1:2::5", "10.0.0.2"))
    }

    @Test
    fun ipv6Prefix64ExpandsCompressedForms() {
        assertEquals("2001:0db8:0001:0002", EndpointHosts.ipv6Prefix64("2001:db8:1:2::5"))
        assertEquals("fe80:0000:0000:0000", EndpointHosts.ipv6Prefix64("fe80::1%wlan0"))
        assertEquals("2001:0db8:0000:0000", EndpointHosts.ipv6Prefix64("2001:db8::"))
        assertNull(EndpointHosts.ipv6Prefix64("not-ipv6"))
    }
}

class AirPlayFeaturesTest {
    @Test
    fun parsesDiplayStyleFeatureBitmap() {
        // DiPlay: features=0x5653aee2,0x61
        val features = AirPlayFeatures.parse("0x5653aee2,0x61")
        assertEquals((0x61L shl 32) or 0x5653aee2L, features)
        // 高 32 位的 bit38 → 全局 bit38
        assertTrue(features!! and AirPlayFeatures.HK_PAIRING_AND_ENCRYPT != 0L)
        assertEquals(true, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun preHomeKitBitmapLacksPairingBit() {
        // CarPlay 210.81 时代（catplay README 兼容表）：无 HK_PAIRING_AND_ENCRYPT
        val features = AirPlayFeatures.parse("0x00000080,0x0")
        assertEquals(false, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun missingOrMalformedFeaturesStayUnknown() {
        assertNull(AirPlayFeatures.parse(null))
        assertNull(AirPlayFeatures.parse(""))
        assertNull(AirPlayFeatures.parse("not-hex"))
        assertNull(AirPlayFeatures.parse("0x1,0x2,0x3"))
        // 未知必须返回 null，绝不能被当成「不支持配对」
        assertNull(AirPlayFeatures.supportsPairing(null))
    }
}
