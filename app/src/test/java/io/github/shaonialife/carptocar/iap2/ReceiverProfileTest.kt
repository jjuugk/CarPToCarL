package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 车机画像判定（纯函数）：信号组合 → era/wifiGen + 可解释依据。 */
class ReceiverProfileTest {
    @Test
    fun unknownWhenNoSignals() {
        val p = ReceiverProfile.classify(ReceiverProfile.Signals())
        assertEquals(ReceiverProfile.Era.UNKNOWN, p.era)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertTrue(p.reasons.isNotEmpty())
    }

    @Test
    fun preHomeKitWhenBit38Clear() {
        // 奥迪 4xxx / CarPlay 210.81：features 无 bit38 → 明文 + AES-CTR 零密钥档
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) and
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT.inv()
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "210.81"),
            )
        assertEquals(ReceiverProfile.Era.PRE_HOMEKIT, p.era)
        assertTrue(p.reasons.any { it.contains("无 bit38") })
    }

    @Test
    fun modernWhenBit38Set() {
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) or
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "450.14.2"),
            )
        assertEquals(ReceiverProfile.Era.MODERN, p.era)
    }

    /**
     * **防回归的关键用例**：奥迪 3315 的清单同样声明「可收 0x4300 / 会发 0x4301」
     * （发送 0x4301、0xea03；可收 0x4e0c/0x4e09/0x4300/0xea00），但它走的是旧协议
     * 0x5702→0x5703。2026-10-28 大众车机的清单形态几乎一样（9 项、同样含 0x4300/0x4301），
     * 却是新版协议。
     *
     * 两者**只能靠实车行为区分，清单分不开**——所以这里必须保持 UNKNOWN，
     * 不允许「声明了 0x4300/0x4301 就判 NEW」（那会让奥迪跳过 0x5702，
     * 而它对 0x4300 只回 ACK 不出 0x4301，整轮必挂）。代际不确定由发送端
     * 「两条请求并行发、谁先回用谁」兜底。
     */
    @Test
    fun wifiGenLegacyWhenListsDeclareOldProtocol() {
        // 奥迪形态：发送清单含 0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Audi_MMI_0000",
                    model = "AUDI 0000",
                    manufacturer = "DELPHI",
                    sendIds = setOf(0x4301, 0xea03),
                    receiveIds = setOf(0x4e0c, 0x4e09, 0x4300, 0xea00),
                ),
            )
        // 奥迪清单稀疏（2+5 项）且未列 0x57xx——虽实际走旧协议，但清单不构成证据：
        // 稀疏清单判 UNKNOWN（保守旧协议），完整清单才判 NEW
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertEquals("Audi_MMI_0000 AUDI 0000 DELPHI", p.identityKey)
    }

    @Test
    fun wifiGenNewWhenFullListsLackOldProtocol() {
        // BMW iD9 形态：两清单完整（10+ 项）、均无 0x5702/0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.NEW, p.wifiGen)
        assertTrue(p.reasons.any { it.contains("0x4301 提供") })
    }

    @Test
    fun sparseSendListAloneStaysUnknown() {
        // 单份稀疏清单（2 项）无 0x5703：不判「新」——奥迪的反例证明稀疏清单
        // 不列 0x57xx 也可以走旧协议
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(sendIds = setOf(0x4301, 0xea03)),
            )
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
    }

    @Test
    fun describeCarriesReasons() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = 1L shl 38, srcvers = "450.14.2", sendIds = setOf(0x5703)),
            )
        val text = ReceiverProfile.describe(p)
        assertTrue(text.contains("era=MODERN"))
        assertTrue(text.contains("wifiGen=LEGACY"))
        assertTrue(text.contains("srcvers=450.14.2"))
    }
}
