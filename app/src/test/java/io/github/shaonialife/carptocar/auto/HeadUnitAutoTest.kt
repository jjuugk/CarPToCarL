package io.github.shaonialife.carptocar.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动车机档位的识别与参数映射。
 *
 * 两条必须守住的线：
 * 1. 未识别 = 主线 1.2.24 的行为，任何字段偏移都算回归；
 * 2. `/info` 这类弱信号不能把固件串已经认出的代际冲掉。
 */
class HeadUnitAutoTest {

    @Before
    fun setUp() {
        HeadUnitAuto.resetForTest()
    }

    @Test
    fun `默认档位必须与主线常量逐字段一致`() {
        val d = HeadUnitAuto.DEFAULT
        assertEquals(15_000L, d.specifierJoinTimeoutMs)
        assertEquals(4_000L, d.ctrlInviteWaitMs)
        assertEquals(5 * 60_000L, d.pendingStartWaitMs)
        assertEquals(15L, d.bindWaitSeconds)
        assertEquals(2_500L, d.reactivateWaitMs)
        assertFalse(d.reactivateAlways)
        assertEquals(HeadUnitAuto.VideoCanvas.FOLLOW_DEFAULT, d.videoCanvas)
        assertFalse(d.videoOnly)
        // 新增的 BMW 字段在默认档位必须保持主线行为：断音频配置、主动拨号、不等激活。
        assertTrue(d.dropAudioProfiles)
        assertTrue(d.outboundIap2)
        assertFalse(d.requireAirplayActivation)
        assertEquals(7000, d.inviteFallbackPort)
        assertFalse(d.stayAliveWithoutEndpoint)
    }

    @Test
    fun `CL37 固件保持已验证基线`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(firmware = "CL37_MIB3H_AU_AS_G4x_2302700PROD"))

        assertEquals(HeadUnitAuto.Family.AUDI_CL37, policy.family)
        assertEquals(HeadUnitAuto.DEFAULT.specifierJoinTimeoutMs, policy.specifierJoinTimeoutMs)
        assertEquals(HeadUnitAuto.DEFAULT.ctrlInviteWaitMs, policy.ctrlInviteWaitMs)
        assertEquals(HeadUnitAuto.DEFAULT.pendingStartWaitMs, policy.pendingStartWaitMs)
        assertEquals(HeadUnitAuto.DEFAULT.reactivateWaitMs, policy.reactivateWaitMs)
        assertFalse(policy.reactivateAlways)
        assertEquals(HeadUnitAuto.VideoCanvas.FOLLOW_DEFAULT, policy.videoCanvas)
    }

    @Test
    fun `CL39 缩短邀请窗口并放大等 0x4301 的窗口`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(firmware = "CL39_MIB3H_AU_AS_G4x_2302700PROD"))

        assertEquals(HeadUnitAuto.Family.AUDI_CL39, policy.family)
        assertEquals(1_000L, policy.ctrlInviteWaitMs)
        assertEquals(6 * 60_000L, policy.pendingStartWaitMs)
        assertTrue(policy.reactivateAlways)
        assertEquals(HeadUnitAuto.VideoCanvas.STREAM, policy.videoCanvas)
    }

    @Test
    fun `CL3D 放大入网与重激活窗口`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(firmware = "CL3D_MIB3P_AU_AS_G4x_2302700PROD"))

        assertEquals(HeadUnitAuto.Family.AUDI_CL3D, policy.family)
        assertEquals(25_000L, policy.specifierJoinTimeoutMs)
        assertEquals(6 * 60_000L, policy.pendingStartWaitMs)
        assertEquals(5_000L, policy.reactivateWaitMs)
        assertTrue(policy.reactivateAlways)
    }

    @Test
    fun `CL35 在车机拆链前缩短入网窗口`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(firmware = "CL35_MIB3H_AU_AS_G4x_2302700PROD"))

        assertEquals(HeadUnitAuto.Family.AUDI_CL35, policy.family)
        assertEquals(12_000L, policy.specifierJoinTimeoutMs)
        assertTrue(policy.reactivateAlways)
        assertEquals(HeadUnitAuto.VideoCanvas.STREAM, policy.videoCanvas)
    }

    @Test
    fun `蓝牙名里的 CLxx 与固件同权`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(bluetoothName = "Audi MIB3 CL39"))

        assertEquals(HeadUnitAuto.Family.AUDI_CL39, policy.family)
    }

    @Test
    fun `BMW 身份使用车机主导的连接策略`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(name = "BMW iD9", manufacturer = "BMW"))

        assertEquals(HeadUnitAuto.Family.BMW, policy.family)
        // 窗口仍沿用主线默认，只改连接方向/激活门槛/音频配置保护。
        assertEquals(HeadUnitAuto.DEFAULT.specifierJoinTimeoutMs, policy.specifierJoinTimeoutMs)
        assertEquals(HeadUnitAuto.DEFAULT.pendingStartWaitMs, policy.pendingStartWaitMs)
        assertFalse(policy.dropAudioProfiles)
        assertFalse(policy.outboundIap2)
        assertTrue(policy.requireAirplayActivation)
        assertEquals(7000, policy.inviteFallbackPort)
        assertTrue(policy.stayAliveWithoutEndpoint)
    }

    @Test
    fun `BMW 档位的日志必须写出车机主导策略`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(firmware = "MGU-55"))

        assertEquals(HeadUnitAuto.Family.BMW, policy.family)
        assertTrue(policy.describe().contains("保留HFP/A2DP"))
        assertTrue(policy.describe().contains("仅被动iAP2"))
        assertTrue(policy.describe().contains("等本轮激活"))
    }

    @Test
    fun `未知车机回落到通用参数`() {
        val policy = HeadUnitAuto.classify(HeadUnitAuto.Signals(name = "Some Head Unit"))

        assertEquals(HeadUnitAuto.Family.GENERIC, policy.family)
        assertEquals(HeadUnitAuto.DEFAULT.copy(reason = policy.reason), policy)
    }

    @Test
    fun `info 弱信号不能把固件代际降级`() {
        val strong =
            HeadUnitAuto.observe(
                null,
                HeadUnitAuto.Signals(
                    name = "Audi",
                    firmware = "CL3D_MIB3P_AU_AS_G4x_2302700PROD",
                ),
            )
        assertEquals(HeadUnitAuto.Family.AUDI_CL3D, strong.family)

        val afterInfo = HeadUnitAuto.observeInfo(null, model = "AppleTV6,2", sourceVersion = "220.68")

        assertEquals(HeadUnitAuto.Family.AUDI_CL3D, afterInfo.family)
        assertEquals(HeadUnitAuto.Family.AUDI_CL3D, HeadUnitAuto.current().family)
    }

    @Test
    fun `info 能在没有固件串时补出品牌`() {
        val policy = HeadUnitAuto.observeInfo(null, model = "BMW MGU", sourceVersion = "1.0")

        assertEquals(HeadUnitAuto.Family.BMW, policy.family)
    }

    @Test
    fun `换目标车机立刻回到通用参数`() {
        HeadUnitAuto.observe(null, HeadUnitAuto.Signals(firmware = "CL39_..."))
        assertEquals(HeadUnitAuto.Family.AUDI_CL39, HeadUnitAuto.current().family)

        HeadUnitAuto.beginAttempt(null, "AA:BB:CC:DD:EE:01", "旧车")
        HeadUnitAuto.beginAttempt(null, "AA:BB:CC:DD:EE:02", "新车")

        assertEquals(HeadUnitAuto.DEFAULT.family, HeadUnitAuto.current().family)
        assertEquals(HeadUnitAuto.DEFAULT.specifierJoinTimeoutMs, HeadUnitAuto.current().specifierJoinTimeoutMs)
    }
}
