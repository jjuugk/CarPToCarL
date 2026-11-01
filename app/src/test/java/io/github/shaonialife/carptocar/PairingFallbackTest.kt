package io.github.shaonialife.carptocar

import io.github.shaonialife.carptocar.airplay.pairingRetryBackoffMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对失败兜底路径决策测试：场景全部来自真机 sysdiagnose 对照
 * （2026-10-21/17，docs/PROTOCOL-NOTES.md §13）。
 */
class PairingFallbackTest {
    @Test
    fun `配对成功则无兜底`() {
        val f =
            pairingFallback(
                true,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `奥迪首连 403 - verify 段被拒 - 禁断 BT 且禁明文 - 原地退避等重邀`() {
        // 真机抓包：pair-verify 通过后认证段 403（对客户端表现为 verify/认证 IOException）
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse("403=车机未就绪，断 BT 会错过 18~20s 后的自动重邀", f.useBtRetireHack)
        assertFalse("403=信任已建立，明文必被拒", f.usePlainSession)
    }

    @Test
    fun `pair-setup 403 - 走快路径后仍失败 - 同样禁断 BT`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = true,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `DiPlay 断链形态 - 无 403 - 保留二段 hack`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertTrue(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `pre-HomeKit 老车机 - 不支持配对 - 明文兜底`() {
        val f =
            pairingFallback(
                false,
                noPairing = true,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = true,
            )
        assertFalse(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `端点未解析 - RTSP 未连上 - 两条兜底都不启用`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `车机确定性拒绝配对 - 禁断 BT - 保留明文兜底`() {
        // 日志 221747：pair-setup / pair-verify 都回 {Error:2, State:4}。
        // 断 BT 只会再失败一次（真车还会拆掉 BT 控制通道），但明文会话仍能给画面。
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
                pairingTerminal = true,
            )
        assertFalse("确定性拒绝与链路层状态无关，断 BT 无意义", f.useBtRetireHack)
        assertTrue("画面不需要共享密钥，明文会话继续兜底", f.usePlainSession)
    }

    @Test
    fun `重试退避 - 403 长 - 其余短`() {
        assertEquals(5_000L, pairingRetryBackoffMs("RTSP/1.0 403 Forbidden"))
        assertEquals(5_000L, pairingRetryBackoffMs("HTTP/1.1 403"))
        assertEquals(500L, pairingRetryBackoffMs("RTSP/1.0 500 Internal"))
        assertEquals(500L, pairingRetryBackoffMs("Connection reset"))
    }
}
