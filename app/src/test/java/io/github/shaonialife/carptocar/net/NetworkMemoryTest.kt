package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkMemory] 的纯逻辑单测（不碰 Android：编解码 / 合并 / 过期 / 挑选 / 可存性）。
 *
 * 现场背景：别克 BUICK_6369（Saic GM）四次 0x4301 的无线组参数逐字节相同，
 * 但 2026-11-21 08:17 那轮整轮不下发，只能干等 90 秒——本记忆就是给那种轮次留的后路。
 */
class NetworkMemoryTest {
    private fun endpoint(
        ssid: String = "BUICK_6369",
        passphrase: String = "vrFgKUVGR9Q4UB9Q",
        channel: Int = 149,
        securityType: Int = 3,
        port: Int = 7000,
        addresses: List<String> = listOf("fe80::c800:8cff:fe9a:da9b"),
        publicKey: String? = null,
    ) = Iap2SenderSession.CarPlayEndpoint(
        ssid = ssid,
        passphrase = passphrase,
        channel = channel,
        securityType = securityType,
        airPlayPort = port,
        deviceIdentifier = "AA:BB:CC:DD:EE:FF",
        publicKey = publicKey,
        sourceVersion = "450.14",
        ipAddresses = addresses,
    )

    @Test
    fun `encode decode 往返保留全部连接字段`() {
        val entry = NetworkMemory.Entry(endpoint(), savedAtMillis = 1_763_000_000_000L)
        val decoded = NetworkMemory.Entry.decode(entry.encode())!!

        assertEquals("BUICK_6369", decoded.endpoint.ssid)
        assertEquals("vrFgKUVGR9Q4UB9Q", decoded.endpoint.passphrase)
        assertEquals(149, decoded.endpoint.channel)
        assertEquals(3, decoded.endpoint.securityType)
        assertEquals(7000, decoded.endpoint.airPlayPort)
        assertEquals(listOf("fe80::c800:8cff:fe9a:da9b"), decoded.endpoint.ipAddresses)
        assertNull(decoded.endpoint.publicKey)
        assertEquals(1_763_000_000_000L, decoded.savedAtMillis)
    }

    @Test
    fun `多地址与公钥都能往返`() {
        val entry =
            NetworkMemory.Entry(
                endpoint(
                    addresses = listOf("fe80::1", "192.168.1.5"),
                    publicKey = "a".repeat(64),
                ),
                savedAtMillis = 100L,
            )
        val decoded = NetworkMemory.Entry.decode(entry.encode())!!
        assertEquals(listOf("fe80::1", "192.168.1.5"), decoded.endpoint.ipAddresses)
        assertEquals("a".repeat(64), decoded.endpoint.publicKey)
    }

    @Test
    fun `坏数据一律解成 null 而不是抛异常`() {
        assertNull(NetworkMemory.Entry.decode(null))
        assertNull(NetworkMemory.Entry.decode(""))
        assertNull(NetworkMemory.Entry.decode("只有一段"))
        assertNull(NetworkMemory.Entry.decode("BUICK_6369\u0000psk\u0000不是数字\u00003\u00007000\u0000\u0000\u0000100"))
        assertNull(NetworkMemory.Entry.decode("\u0000psk\u0000149\u00003\u00007000\u0000\u0000\u0000100"))
    }

    @Test
    fun `decodeAll 跳过坏行保留好行`() {
        val good = NetworkMemory.Entry(endpoint(), 500L).encode()
        val raw = listOf("垃圾行", good, "").joinToString("\u0001")
        val decoded = NetworkMemory.decodeAll(raw)
        assertEquals(1, decoded.size)
        assertEquals("BUICK_6369", decoded.first().endpoint.ssid)
        assertTrue(NetworkMemory.decodeAll(null).isEmpty())
    }

    @Test
    fun `merge 同 SSID 覆盖并置顶`() {
        val old =
            NetworkMemory.Entry(endpoint(passphrase = "old-pass"), savedAtMillis = 100L)
        val other =
            NetworkMemory.Entry(endpoint(ssid = "BMW_1234", passphrase = "p2"), savedAtMillis = 200L)
        val fresh =
            NetworkMemory.Entry(endpoint(passphrase = "new-pass"), savedAtMillis = 300L)

        val merged = NetworkMemory.merge(listOf(other, old), fresh)

        assertEquals(2, merged.size)
        assertEquals("BUICK_6369", merged[0].endpoint.ssid)
        assertEquals("new-pass", merged[0].endpoint.passphrase)
        assertEquals("BMW_1234", merged[1].endpoint.ssid)
    }

    @Test
    fun `merge 截断到上限`() {
        val existing =
            (1..NetworkMemory.MAX_ENTRIES).map {
                NetworkMemory.Entry(endpoint(ssid = "CAR_$it"), savedAtMillis = it.toLong())
            }
        val merged = NetworkMemory.merge(existing, NetworkMemory.Entry(endpoint(), 999L))
        assertEquals(NetworkMemory.MAX_ENTRIES, merged.size)
        assertEquals("BUICK_6369", merged.first().endpoint.ssid)
    }

    @Test
    fun `fresh 过滤过期条目`() {
        val now = 10_000_000L
        val alive = NetworkMemory.Entry(endpoint(ssid = "A"), savedAtMillis = now - 1_000L)
        val dead =
            NetworkMemory.Entry(
                endpoint(ssid = "B"),
                savedAtMillis = now - NetworkMemory.TTL_MS - 1,
            )
        val kept = NetworkMemory.fresh(listOf(alive, dead), now)
        assertEquals(1, kept.size)
        assertEquals("A", kept.first().endpoint.ssid)
    }

    @Test
    fun `pick 优先当前 SSID 否则取最新`() {
        val a = NetworkMemory.Entry(endpoint(ssid = "A"), savedAtMillis = 300L)
        val b = NetworkMemory.Entry(endpoint(ssid = "B"), savedAtMillis = 200L)
        val list = listOf(a, b)

        assertEquals("B", NetworkMemory.pick(list, currentSsid = "B")?.endpoint?.ssid)
        assertEquals("A", NetworkMemory.pick(list, currentSsid = null)?.endpoint?.ssid)
        assertEquals("A", NetworkMemory.pick(list, currentSsid = "不存在的网络")?.endpoint?.ssid)
        assertNull(NetworkMemory.pick(emptyList(), currentSsid = "A"))
    }

    @Test
    fun `合成端点与无密码端点不进记忆`() {
        assertFalse(NetworkMemory.isCacheable(endpoint(ssid = "")))
        assertFalse(NetworkMemory.isCacheable(endpoint(passphrase = "")))
        assertTrue(NetworkMemory.isCacheable(endpoint()))
        assertTrue(NetworkMemory.isCacheable(endpoint(ssid = "BMW_1234")))
    }

    @Test
    fun `describeAge 四档都正常`() {
        val now = 100L * 24 * 60 * 60 * 1000
        assertEquals("刚刚", NetworkMemory.describeAge(now - 30_000L, now))
        assertEquals("5 分钟前", NetworkMemory.describeAge(now - 5 * 60_000L, now))
        assertEquals("3 小时前", NetworkMemory.describeAge(now - 3 * 60 * 60_000L, now))
        assertEquals("2 天前", NetworkMemory.describeAge(now - 2L * 24 * 60 * 60_000L, now))
    }
}
