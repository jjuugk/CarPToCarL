package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级 SETUP 报文体的回归保护。
 *
 * WinPlay（2026-10-19 实测）按「用户选中的 iPhone 蓝牙地址」过滤会话，
 * 报文体里的 deviceID 对不上直接 403——曾用 catplay 的写死假值
 * ff:ee:dd:cc:bb:aa，表象是连上约 45 秒后自动断开（接收端等视频超时）。
 */
class SessionSetupBodyTest {
    private fun client() =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "DESKTOP-XXXXXXX",
                passphrase = "secret",
                channel = 0,
                securityType = 2,
                airPlayPort = 17000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("192.168.137.1"),
            ),
            log = {},
        )

    @Test
    fun deviceIdIsCarriedVerbatimIntoSetupBody() {
        val body = client().buildSessionSetupBody("AA:BB:CC:00:00:07", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertEquals("AA:BB:CC:00:00:07", root["deviceID"])
    }

    @Test
    fun deviceIdIsNotTheCatplayPlaceholder() {
        val body = client().buildSessionSetupBody("AA:BB:CC:00:00:07", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertNotEquals("ff:ee:dd:cc:bb:aa", root["deviceID"])
    }

    @Test
    fun bodyKeepsTheFieldsReceiversNegotiateOn() {
        val port = 35449
        val body = client().buildSessionSetupBody("AA:BB:CC:00:00:07", port)
        val root = BinaryPlist.parse(body) as Map<*, *>
        // timingPort 是媒体时钟同步入口；features 决定 enabledFeatures（缺失即视频管线不启用）
        assertEquals(port.toLong(), root["timingPort"])
        assertTrue((root["features"] as List<*>).contains("viewAreas"))
        assertEquals(true, root["keepAliveLowPower"])
    }

    /**
     * 收帧统计开关必须一直开着。
     *
     * 4580/CL3D 的 libairplay 只有在这个键为 true 时才在 `/feedback` 里回
     * `statsDictionary`（屏幕流实收帧率 `rfps`）——那是手机侧唯一能回答
     * 「车机到底收没收到视频帧」的探针（2026-11-06 黑屏排查就缺这一条）。
     * 谁把它关回 false，黑屏排查就又回到盲猜。
     */
    @Test
    fun bodyEnablesReceiverStatsProbe() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("AA:BB:CC:00:00:07", 35449),
            ) as Map<*, *>
        assertEquals(true, root["statsCollectionEnabled"])
    }

    /**
     * 未配对（et=NONE）会话必须显式带 `et=1`（catplay `AirPlayEncryptionType::NONE`）。
     * pre-HomeKit 老车机只认明文会话；缺这个键时严格接收端会把它当成
     * 「声明加密但没有密钥」而拒绝。
     */
    @Test
    fun unpairedSessionDeclaresEtNone() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("AA:BB:CC:00:00:07", 35449, encrypted = false),
            ) as Map<*, *>
        assertEquals(1L, root["et"])
        assertEquals(false, root["updateSessionRequest"])
    }

    @Test
    fun pairedSessionOmitsEt() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("AA:BB:CC:00:00:07", 35449, encrypted = true),
            ) as Map<*, *>
        assertNull(root["et"])
    }

    @Test
    fun disableBluetoothBodyCarriesPhoneDeviceId() {
        val root =
            BinaryPlist.parse(
                client().buildDisableBluetoothBody("AA:BB:CC:00:00:07"),
            ) as Map<*, *>
        assertEquals("disableBluetooth", root["type"])
        val params = root["params"] as Map<*, *>
        assertEquals("AA:BB:CC:00:00:07", params["deviceID"])
    }

    @Test
    fun setupBodySeparatesWifiMacAddressFromBluetoothDeviceId() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody(
                    deviceId = "B0:DE:31:01:65:22",
                    timingPort = 35449,
                    encrypted = true,
                    macAddress = "0A:A2:DF:C8:E6:1A",
                ),
            ) as Map<*, *>
        assertEquals("B0:DE:31:01:65:22", root["deviceID"])
        assertEquals("0A:A2:DF:C8:E6:1A", root["macAddress"])
    }

    @Test
    fun extractEui64MacDecodesRealSlaacLinkLocalAndRejectsInvalidAddresses() {
        val valid = java.net.InetAddress.getByName("fe80::8a2:dfff:fec8:e61a").address
        assertEquals(
            "0A:A2:DF:C8:E6:1A",
            io.github.shaonialife.carptocar.util.DeviceIdentity.extractEui64Mac(valid),
        )
        // 非 EUI-64（中间不是 ff:fe）
        val opaque = java.net.InetAddress.getByName("fe80::1234:5678:9abc:def0").address
        assertNull(io.github.shaonialife.carptocar.util.DeviceIdentity.extractEui64Mac(opaque))
        // Android 占位 MAC 02:00:00:00:00:00（对应 EUI-64 接口 ID 0000:00ff:fe00:0000）必须过滤
        val placeholder = java.net.InetAddress.getByName("fe80::ff:fe00:0").address
        assertNull(io.github.shaonialife.carptocar.util.DeviceIdentity.extractEui64Mac(placeholder))
        // 组播 MAC（bit0=1）必须过滤
        val multicast = java.net.InetAddress.getByName("fe80::100:00ff:fe00:1").address
        assertNull(io.github.shaonialife.carptocar.util.DeviceIdentity.extractEui64Mac(multicast))
    }
}
