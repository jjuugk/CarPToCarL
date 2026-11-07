package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * iAP2 消息目录（[Iap2MessageCatalog]）与项目里手写的 [Iap2MessageIds] 的一致性。
 *
 * 这些断言锁的是**「车机声明的报文我们认不认识」这条判据**：
 *  - 目录条数掉了 → 现场日志把正常消息报成「陌生 ID」；
 *  - 目录里混进规范外的号 → 把「猜测」伪装成「规范」，比报陌生更糟。
 */
class Iap2MessageCatalogTest {
    @Test
    fun catalogueCoversTheFullSpecification() {
        // Apple 规格档案注册表共 144 条（catplay register_packet_family! 导出）
        assertEquals(144, Iap2MessageCatalog.size)
    }

    @Test
    fun namesTheMessagesWeActuallySendEverySession() {
        // 这四条「设备状态问候」以前都缺名字，被报成陌生 ID
        assertEquals("DeviceInformationUpdate", Iap2MessageCatalog.nameOf(0x4E09))
        assertEquals("DeviceLanguageUpdate", Iap2MessageCatalog.nameOf(0x4E0A))
        assertEquals("DeviceTimeUpdate", Iap2MessageCatalog.nameOf(0x4E0B))
        assertEquals("DeviceUUIDUpdate", Iap2MessageCatalog.nameOf(0x4E0C))
        // MFi 六条同理：每次认证都出现，缺名字等于每次连车都刷一堆「陌生 ID」
        assertEquals("RequestAuthenticationCertificate", Iap2MessageCatalog.nameOf(0xAA00))
        assertEquals("AuthenticationCertificate", Iap2MessageCatalog.nameOf(0xAA01))
        assertEquals("RequestAuthenticationChallengeResponse", Iap2MessageCatalog.nameOf(0xAA02))
        assertEquals("AuthenticationResponse", Iap2MessageCatalog.nameOf(0xAA03))
        assertEquals("AuthenticationFailed", Iap2MessageCatalog.nameOf(0xAA04))
        assertEquals("AuthenticationSucceeded", Iap2MessageCatalog.nameOf(0xAA05))
        assertEquals("AccessoryAuthenticationSerialNumber", Iap2MessageCatalog.nameOf(0xAA06))
    }

    @Test
    fun keepsExactNamesOfTheWirelessGroup() {
        assertEquals("CarPlayAvailability", Iap2MessageCatalog.nameOf(0x4300))
        assertEquals("CarPlayStartSession", Iap2MessageCatalog.nameOf(0x4301))
        assertEquals("RequestAccessoryWiFiConfigurationInformation", Iap2MessageCatalog.nameOf(0x5702))
        assertEquals("AccessoryWiFiConfigurationInformation", Iap2MessageCatalog.nameOf(0x5703))
        assertEquals("WirelessCarPlayUpdate", Iap2MessageCatalog.nameOf(0x4E0D))
        assertEquals("DeviceTransportIdentifierNotification", Iap2MessageCatalog.nameOf(0x4E0E))
    }

    @Test
    fun specificationHasNoEa04() {
        // EA 段只到 0xEA03（StatusExternalAccessoryProtocolSession）。
        // 0xEA04 是本项目的推断值，绝不能出现在目录里——否则日志会把它当成规范消息。
        assertNull(Iap2MessageCatalog.nameOf(0xEA04))
        assertFalse(Iap2MessageCatalog.contains(0xEA04))
        assertEquals("StatusExternalAccessoryProtocolSession", Iap2MessageCatalog.nameOf(0xEA03))
    }

    @Test
    fun everyIdWeDefineExistsInTheSpecification() {
        // 反射拿到 Iap2MessageIds 里所有 const Int，逐个查目录。
        // 唯一的例外是 EA_SESSION_DATA（0xEA04）：它自己已经标注「推断未确认」，
        // nameOf 也返回 "0xEA04(推断未确认)" 而不是伪装成规范名。
        val fields =
            Iap2MessageIds::class.java.declaredFields
                .filter { it.type == Int::class.javaPrimitiveType }
                .filter { Modifier.isStatic(it.modifiers) }
                .associate { it.name to it.apply { isAccessible = true }.getInt(null) }
        assertTrue("反射没拿到常量，断言会空转", fields.size > 30)
        val missing =
            fields
                .filterKeys { it != "EA_SESSION_DATA" }
                .filterValues { !Iap2MessageCatalog.contains(it) }
        assertTrue("这些常量在 iAP2 规范里不存在：$missing", missing.isEmpty())
    }

    @Test
    fun inferredIdsAreLabelledAsSuch() {
        // 断言的号必须自曝身份，不能在日志里看起来像真的
        assertEquals("0xEA04(推断未确认)", Iap2MessageIds.nameOf(0xEA04))
        // 规范里的号走目录，不许再写泛化名（0xAE02 是 StopPowerUpdates）
        assertEquals("StopPowerUpdates", Iap2MessageIds.nameOf(0xAE02))
        assertEquals("EndCall", Iap2MessageIds.nameOf(0x415C))
        assertEquals("StartOOBBTPairing", Iap2MessageIds.nameOf(0x00B0))
        // 规范外的号仍然原样输出
        assertEquals("0x4D00", Iap2MessageIds.nameOf(0x4D00))
    }
}
