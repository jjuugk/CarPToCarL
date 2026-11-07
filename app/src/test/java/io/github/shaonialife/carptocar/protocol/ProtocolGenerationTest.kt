package io.github.shaonialife.carptocar.protocol

import io.github.shaonialife.carptocar.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 协议代际快照：只有「已知 → 已知且不同」才算换代。
 *
 * 这几条断言对应真机上真实会出现的到达顺序：0x1D01（只有收发清单）先到、mDNS（只有
 * features/srcvers）后到，两侧各自都带着 UNKNOWN。若把「补齐」当「换代」，每台车第一次
 * 连上都会误报一次并白清 MFi 记忆。
 */
class ProtocolGenerationTest {
    @Before
    fun setUp() {
        ProtocolGeneration.reset()
    }

    private fun profile(
        era: ReceiverProfile.Era,
        wifiGen: ReceiverProfile.WifiGen,
        identity: String = "AUDI 0000 Audi",
    ) = ReceiverProfile.Profile(era, wifiGen, identity, emptyList())

    @Test
    fun firstObservationOnlyRecordsBaseline() {
        val change =
            ProtocolGeneration.observe(
                null,
                "AUDI 0000 Audi",
                profile(ReceiverProfile.Era.UNKNOWN, ReceiverProfile.WifiGen.LEGACY),
            )
        assertNull(change)
    }

    @Test
    fun sameProfileIsNotAChange() {
        ProtocolGeneration.observe(null, "AUDI 0000 Audi", profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.LEGACY))
        val again =
            ProtocolGeneration.observe(null, "AUDI 0000 Audi", profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.LEGACY))
        assertNull(again)
    }

    @Test
    fun signalFillInIsNotAChange() {
        // 0x1D01 阶段：只有收发清单 → era 未知
        ProtocolGeneration.observe(null, "BMW iD9", profile(ReceiverProfile.Era.UNKNOWN, ReceiverProfile.WifiGen.NEW))
        // mDNS 阶段：拿到 features → era 变成 MODERN，但 wifiGen 变成 UNKNOWN（本轮没有清单）
        val change =
            ProtocolGeneration.observe(
                null,
                "BMW iD9",
                profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.UNKNOWN),
                srcvers = "450.14.2",
                features = 1L shl 38,
            )
        assertNull(change)
    }

    @Test
    fun wifiGenerationSwitchIsReported() {
        // 车机换了固件：上一轮走旧代 0x5702/0x5703，这一轮两清单完整却不再声明 0x57xx
        ProtocolGeneration.observe(null, "BMW iD9", profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.LEGACY))
        val change =
            ProtocolGeneration.observe(
                null,
                "BMW iD9",
                profile(
                    ReceiverProfile.Era.MODERN,
                    ReceiverProfile.WifiGen.NEW,
                    identity = "BMW iD9",
                ),
            )
        assertNotNull(change)
        assertTrue(change!!.wifiChanged)
        assertFalse(change.eraChanged)
        assertTrue(ProtocolGeneration.describe(change).contains("LEGACY → NEW"))
    }

    @Test
    fun airPlayEraSwitchIsReported() {
        // 反向：新固件回退成不声明 bit38（常见于售后刷回旧版本）
        ProtocolGeneration.observe(
            null,
            "AUDI 0000",
            profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.LEGACY, identity = "AUDI 0000"),
        )
        val change =
            ProtocolGeneration.observe(
                null,
                "AUDI 0000",
                profile(ReceiverProfile.Era.PRE_HOMEKIT, ReceiverProfile.WifiGen.LEGACY, identity = "AUDI 0000"),
                srcvers = "210.81",
            )
        assertNotNull(change)
        assertTrue(change!!.eraChanged)
        assertFalse(change.wifiChanged)
        val text = ProtocolGeneration.describe(change)
        assertTrue(text.contains("MODERN → PRE_HOMEKIT"))
        assertTrue(text.contains("srcvers=210.81"))
    }

    @Test
    fun mergedSnapshotKeepsPreviouslyKnownFields() {
        ProtocolGeneration.observe(null, "AUDI 0000", profile(ReceiverProfile.Era.UNKNOWN, ReceiverProfile.WifiGen.LEGACY, "AUDI 0000"))
        ProtocolGeneration.observe(
            null,
            "AUDI 0000",
            profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.UNKNOWN, "AUDI 0000"),
            features = 1L shl 38,
        )
        // 再观察一次「era 变回去、wifiGen 未知」：era 是已知→已知变化，必须报；wifiGen 保持 LEGACY
        val change =
            ProtocolGeneration.observe(
                null,
                "AUDI 0000",
                profile(ReceiverProfile.Era.PRE_HOMEKIT, ReceiverProfile.WifiGen.UNKNOWN, "AUDI 0000"),
            )
        assertNotNull(change)
        assertEquals(ReceiverProfile.WifiGen.LEGACY.name, change!!.from.wifiGen)
        assertEquals(ReceiverProfile.WifiGen.LEGACY.name, change.to.wifiGen)
    }

    @Test
    fun unknownIdentityIsIgnored() {
        assertNull(
            ProtocolGeneration.observe(
                null,
                "unknown",
                profile(ReceiverProfile.Era.MODERN, ReceiverProfile.WifiGen.NEW, identity = "unknown"),
            ),
        )
    }
}
