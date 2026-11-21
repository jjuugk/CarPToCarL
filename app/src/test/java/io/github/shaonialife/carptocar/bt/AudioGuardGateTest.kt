package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音频配置守卫的收工判定（纯逻辑）。
 *
 * 2026-11-19 的现场：车机已经在推流，日志里却还能看到 HFP/A2DP 被反复断开。
 * 这组用例把「什么状态下守卫必须停」钉死——会话一激活就必须连一次断开动作都
 * 不再做，否则车机的音频路径会被反复掐断再拉回，用户听到的就是声音在车机与
 * 手机之间来回跳。
 */
class AudioGuardGateTest {
    @Test
    fun keepsGuardingWhileNothingIsReady() {
        assertNull(
            AudioGuardGate.stopReason(
                sessionWanted = true,
                iap2Ready = false,
                sessionActive = false,
                streaming = false,
            ),
        )
    }

    @Test
    fun stopsOnceIap2IsUp() {
        assertEquals(
            "iAP2 已 bring-up",
            AudioGuardGate.stopReason(
                sessionWanted = true,
                iap2Ready = true,
                sessionActive = false,
                streaming = false,
            ),
        )
    }

    @Test
    fun stopsOnceCarPlayEndpointIsReady() {
        assertEquals(
            "CarPlay 端点已拿到",
            AudioGuardGate.stopReason(
                sessionWanted = true,
                iap2Ready = false,
                sessionActive = true,
                streaming = false,
            ),
        )
    }

    @Test
    fun stopsWhileStreaming() {
        assertEquals(
            "已在推流",
            AudioGuardGate.stopReason(
                sessionWanted = true,
                iap2Ready = false,
                sessionActive = false,
                streaming = true,
            ),
        )
    }

    @Test
    fun userDisconnectWinsOverStaleFlags() {
        // 用户断开时 iap2Ready 可能还是上一轮的残留 true——必须优先报「用户已断开」，
        // 否则日志会误导成「iAP2 已 bring-up，守卫正常收工」。
        assertEquals(
            "用户已断开",
            AudioGuardGate.stopReason(
                sessionWanted = false,
                iap2Ready = true,
                sessionActive = true,
                streaming = true,
            ),
        )
    }

    @Test
    fun stopsAsSoonAsRfcommConnectsBeforeFullBringUp() {
        // 别克 08:17 那轮：RFCOMM 0.4s 就连上，但 iap2Ready 卡在 0x4300 ACK 一直不来，
        // 旧守卫因此连踢 HFP 6 次跨 90 秒。车机一连入就该停手。
        assertEquals(
            "RFCOMM 已连入",
            AudioGuardGate.stopReason(
                sessionWanted = true,
                iap2Ready = false,
                sessionActive = false,
                streaming = false,
                controlConnected = true,
            ),
        )
    }

    @Test
    fun rfcommStopWinsOverLaterFlagsButNotOverUserDisconnect() {
        assertEquals(
            "RFCOMM 已连入",
            AudioGuardGate.stopReason(
                sessionWanted = true,
                iap2Ready = true,
                sessionActive = true,
                streaming = true,
                controlConnected = true,
            ),
        )
        assertEquals(
            "用户已断开",
            AudioGuardGate.stopReason(
                sessionWanted = false,
                iap2Ready = true,
                sessionActive = true,
                streaming = true,
                controlConnected = true,
            ),
        )
    }
}
