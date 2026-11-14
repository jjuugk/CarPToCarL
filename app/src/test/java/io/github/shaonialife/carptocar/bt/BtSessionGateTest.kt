package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.transport.DuplexStream
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 蓝牙控制会话门闸单测：单一控制通道交接与对端断链后的被动回连避让窗口。 */
class BtSessionGateTest {
    private val originalClock = BtSessionGate.nowMillis

    @After
    fun tearDown() {
        BtSessionGate.nowMillis = originalClock
        BtSessionGate.clearRemoteDisconnectGrace()
        BtSessionGate.clearLinkLost()
        BtSessionGate.sessionActive = false
        BtSessionGate.inboundActive = false
        BtSessionGate.iap2Ready = false
    }

    @Test
    fun `后建立的控制通道接管所有权，旧通道释放不会误清当前通道`() {
        val outbound = dummyChannel("bt-client")
        val inbound = dummyChannel("bt-server")
        try {
            BtSessionGate.claimControl(outbound, inbound = false)
            BtSessionGate.markControlReady(outbound)
            assertTrue(BtSessionGate.ownsControl(outbound))
            assertTrue(BtSessionGate.iap2Ready)
            assertFalse(BtSessionGate.inboundActive)

            val displaced = BtSessionGate.claimControl(inbound, inbound = true)
            assertSame(outbound, displaced)
            assertTrue(BtSessionGate.ownsControl(inbound))
            assertTrue(BtSessionGate.inboundActive)
            assertFalse("切换通道时必须先重置 iap2Ready", BtSessionGate.iap2Ready)

            assertFalse("旧通道退出不得清掉新通道所有权", BtSessionGate.releaseControl(outbound))
            assertTrue(BtSessionGate.ownsControl(inbound))
            assertTrue(BtSessionGate.releaseControl(inbound))
            assertFalse(BtSessionGate.inboundActive)
        } finally {
            outbound.close()
            inbound.close()
        }
    }

    @Test
    fun `对端结束已建立会话后开启 15s 被动避让窗口，手动启动连接立即清除避让`() {
        var clock = 10_000L
        BtSessionGate.nowMillis = { clock }

        assertEquals(0L, BtSessionGate.passiveGraceRemainingMillis())
        BtSessionGate.noteRemoteSessionEnded()
        assertEquals(BtSessionGate.PASSIVE_RECONNECT_GRACE_MILLIS, BtSessionGate.passiveGraceRemainingMillis())

        clock += 6_000L
        assertEquals(9_000L, BtSessionGate.passiveGraceRemainingMillis())

        clock += 9_000L
        assertEquals(0L, BtSessionGate.passiveGraceRemainingMillis())

        // 再次触发对端断链后，用户手动点击「启动连接」应零延迟放行主动拨号
        BtSessionGate.noteRemoteSessionEnded()
        assertTrue(BtSessionGate.passiveGraceRemainingMillis() > 0L)
        BtSessionGate.clearRemoteDisconnectGrace()
        assertEquals(0L, BtSessionGate.passiveGraceRemainingMillis())
    }

    @Test
    fun `车机已回连时出站拨号不再避让，避让窗口本身保留`() {
        var clock = 10_000L
        BtSessionGate.nowMillis = { clock }
        BtSessionGate.noteRemoteSessionEnded()
        clock += 2_000L
        assertEquals(13_000L, BtSessionGate.outboundPassiveWaitMillis())

        BtSessionGate.inboundActive = true
        assertEquals("车机已入站回连仍报避让，出站循环会空转", 0L, BtSessionGate.outboundPassiveWaitMillis())
        assertEquals("热点断开判定仍依赖避让窗口", 13_000L, BtSessionGate.passiveGraceRemainingMillis())

        BtSessionGate.inboundActive = false
        BtSessionGate.sessionActive = true
        assertEquals(0L, BtSessionGate.outboundPassiveWaitMillis())
    }

    private fun dummyChannel(name: String): Iap2LinkChannel =
        Iap2LinkChannel(
            object : DuplexStream {
                override fun read(buffer: ByteArray): Int = -1

                override fun write(bytes: ByteArray) = Unit

                override fun close() = Unit
            },
            name = name,
            log = {},
        )
}
