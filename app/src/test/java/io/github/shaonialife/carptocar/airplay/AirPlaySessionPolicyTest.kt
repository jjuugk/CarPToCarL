package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-06 实车两条「整轮连接被自己打死」的回归守卫。
 *
 * 现场（`CarPToCarL-log-20261106-164713.txt`，奥迪 CL3D / AUDI 3810）：
 * ```
 * 16:46:38.164  iAP2 0x4301 晚到
 * 16:46:38.170  端点更新（0x4301 晚到）…          ← 无条件 close() 打断在飞的 pair-setup
 * 16:46:38.171  pair-setup 失败（控制通道已关闭（CSeq=2 POST /pair-setup））
 * 16:46:38.693  pair-setup Error=3（车机要求退避）
 * 16:46:38.724  pair-verify Error=2（认证失败）
 * 16:46:38.725  车机确定性拒绝配对 → 15 分钟内不再重复尝试 → 降级明文会话
 * ```
 * 正确的行为：① 有交换在飞时不关通道；② 退避先重试同一阶段，不抢先 pair-verify。
 */
class AirPlaySessionPolicyTest {
    // ---- ① 端点热更新不能打断在飞的交换 ----

    @Test
    fun `配对进行中绝不因端点热更新而关闭通道`() {
        assertTrue(AirPlaySessionPolicy.deferEndpointClose(pairingInProgress = true, pendingRequests = 0))
        // 现场那一帧：pair-setup 的 CSeq=2 正等着应答
        assertTrue(AirPlaySessionPolicy.deferEndpointClose(pairingInProgress = true, pendingRequests = 1))
    }

    @Test
    fun `有请求在飞也要推迟关闭`() {
        assertTrue(AirPlaySessionPolicy.deferEndpointClose(pairingInProgress = false, pendingRequests = 1))
        assertTrue(AirPlaySessionPolicy.deferEndpointClose(pairingInProgress = false, pendingRequests = 3))
    }

    @Test
    fun `空闲时才允许立即关掉旧连接`() {
        // 空闲闭掉没问题：配对循环每轮起步前本来就会 close()，旧的半开连接不该留着
        assertFalse(AirPlaySessionPolicy.deferEndpointClose(pairingInProgress = false, pendingRequests = 0))
    }

    // ---- ② 退避要重试同一阶段，而不是抢跑 pair-verify ----

    @Test
    fun `退避先重试同一阶段`() {
        assertEquals(PairSetupNext.RETRY_SAME_STAGE, pairSetupNext(PairingErrorCode.BACKOFF, false))
        assertEquals(PairSetupNext.RETRY_SAME_STAGE, pairSetupNext(PairingErrorCode.BUSY, false))
        assertEquals(PairSetupNext.RETRY_SAME_STAGE, pairSetupNext(PairingErrorCode.UNAVAILABLE, false))
    }

    @Test
    fun `退避只多给一次重试机会`() {
        // 第二次仍退避就回到既有行为（继续 pair-verify），不把 120s 窗口空转掉
        assertEquals(PairSetupNext.CONTINUE_VERIFY, pairSetupNext(PairingErrorCode.BACKOFF, true))
        assertEquals(PairSetupNext.CONTINUE_VERIFY, pairSetupNext(PairingErrorCode.BUSY, true))
    }

    @Test
    fun `pair-setup 的认证失败仍走已配对快路径`() {
        assertEquals(
            PairSetupNext.PAIRED_FAST_PATH,
            pairSetupNext(PairingErrorCode.AUTHENTICATION, false),
        )
    }

    @Test
    fun `容量类拒绝不再重试`() {
        assertEquals(PairSetupNext.TERMINAL, pairSetupNext(PairingErrorCode.MAX_PEERS, false))
        assertEquals(PairSetupNext.TERMINAL, pairSetupNext(PairingErrorCode.MAX_TRIES, false))
    }

    @Test
    fun `规范外私有码维持既有行为`() {
        // 未知码既不能当终态，也不能据此退避重试——按老路继续 pair-verify
        assertEquals(PairSetupNext.CONTINUE_VERIFY, pairSetupNext(null, false))
    }

    @Test
    fun `任何错误码都有确定分派`() {
        for (code in PairingErrorCode.entries + null) {
            for (retried in listOf(false, true)) {
                // 只为确保不会漏分支（枚举加成员时编译期就会提醒，这里再兜一次）
                pairSetupNext(code, retried)
            }
        }
    }

    // ---- ③ bit37 门控降级：声明「车机发起」却从不邀请的车机 ----

    @Test
    fun `未声明 bit37 的车机维持既有放行行为`() {
        // 不看端口探测结果——老车机本来就按 mDNS 端点放行，门控不作用于它
        assertTrue(
            AirPlaySessionPolicy.allowReceiverControlledFallback(
                receiverControlled = false,
                endpointReachable = false,
            ),
        )
        assertTrue(
            AirPlaySessionPolicy.allowReceiverControlledFallback(
                receiverControlled = false,
                endpointReachable = true,
            ),
        )
    }

    @Test
    fun `bit37 车机端口实测可连才放行直连`() {
        // 2026-11-17 BMW 28041：从不下发 0x4301/邀请，但 mDNS 端点 172.16.224.1:7000 可连
        assertTrue(
            AirPlaySessionPolicy.allowReceiverControlledFallback(
                receiverControlled = true,
                endpointReachable = true,
            ),
        )
    }

    @Test
    fun `bit37 车机端口连不上时继续等邀请不抢跑`() {
        // c12d30e 要防的：车机窗口没开就直连会 ECONNREFUSED 并把重试搅乱
        assertFalse(
            AirPlaySessionPolicy.allowReceiverControlledFallback(
                receiverControlled = true,
                endpointReachable = false,
            ),
        )
    }

    // ---- ④ RECORD 后蓝牙控制通道交接（disableBluetooth） ----

    @Test
    fun `disableBluetooth 仅在加密会话且车机声明 bluetoothIDs 与本机 MAC 有效时发送`() {
        assertTrue(
            AirPlaySessionPolicy.shouldSendDisableBluetooth(
                encryptedSession = true,
                bluetoothIds = listOf("0C:71:D9:16:D8:E8"),
                deviceId = "B0:DE:31:01:65:22",
                placeholderMac = "02:00:00:00:00:00",
            ),
        )
        assertFalse(
            AirPlaySessionPolicy.shouldSendDisableBluetooth(
                encryptedSession = false,
                bluetoothIds = listOf("0C:71:D9:16:D8:E8"),
                deviceId = "B0:DE:31:01:65:22",
                placeholderMac = "02:00:00:00:00:00",
            ),
        )
        assertFalse(
            AirPlaySessionPolicy.shouldSendDisableBluetooth(
                encryptedSession = true,
                bluetoothIds = emptyList(),
                deviceId = "B0:DE:31:01:65:22",
                placeholderMac = "02:00:00:00:00:00",
            ),
        )
        assertFalse(
            AirPlaySessionPolicy.shouldSendDisableBluetooth(
                encryptedSession = true,
                bluetoothIds = listOf("0C:71:D9:16:D8:E8"),
                deviceId = "02:00:00:00:00:00",
                placeholderMac = "02:00:00:00:00:00",
            ),
        )
        assertFalse(
            AirPlaySessionPolicy.shouldSendDisableBluetooth(
                encryptedSession = true,
                bluetoothIds = listOf("0C:71:D9:16:D8:E8"),
                deviceId = "",
                placeholderMac = "02:00:00:00:00:00",
            ),
        )
    }
}
