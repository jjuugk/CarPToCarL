package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅应答：蓝牙侧与隧道侧必须发**同一份字节**。
 *
 * 最重要的一条是 [communicationsIdleRoundTripsThroughCsmEncoder]：隧道侧用 Frida
 * 逐字节常量直投完整帧，蓝牙侧走 `Iap2Csm.encode(id, body)` 重新编码；两者若差一个
 * 字节，车机就会把其中一条当畸形帧。
 */
class Iap2SubscriptionRepliesTest {

    @Test
    fun mapsExactlyTheFourSubscriptions() {
        assertEquals(
            Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE,
            Iap2SubscriptionReplies.replyIdFor(Iap2MessageIds.START_COMMUNICATIONS_UPDATES),
        )
        assertEquals(
            Iap2SubscriptionReplies.CALL_STATE_UPDATE,
            Iap2SubscriptionReplies.replyIdFor(Iap2MessageIds.START_CALL_STATE_UPDATES),
        )
        assertEquals(
            Iap2SubscriptionReplies.LIST_UPDATE,
            Iap2SubscriptionReplies.replyIdFor(Iap2MessageIds.START_LIST_UPDATES),
        )
        assertEquals(
            Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE,
            Iap2SubscriptionReplies.replyIdFor(Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES),
        )
        assertNull(Iap2SubscriptionReplies.replyIdFor(0x4300))
        assertNull(Iap2SubscriptionReplies.replyIdFor(Iap2MessageIds.START_NOW_PLAYING_UPDATES))
    }

    @Test
    fun communicationsIdleRoundTripsThroughCsmEncoder() {
        val body = Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE)!!
        val encoded = Iap2Csm.encode(Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE, body)
        assertArrayEquals(Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE_IDLE, encoded)
    }

    @Test
    fun callStateCarriesTheRealState() {
        val body = Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.CALL_STATE_UPDATE, 3)!!
        val params = Iap2Csm.parseParams(body)
        assertEquals(1, params.size)
        assertEquals(Iap2SubscriptionReplies.CALL_STATE_PARAM, params.single().id)
        assertArrayEquals(byteArrayOf(3), params.single().value)
    }

    @Test
    fun listUpdateHasTheSameFourParamsAsTunnelSide() {
        val body = Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.LIST_UPDATE)!!
        val params = Iap2Csm.parseParams(body)
        assertEquals(listOf(0x0000, 0x0002, 0x0005, 0x0007), params.map { it.id })
    }

    @Test
    fun routeGuidanceReplyIsAnEmptyBodyNotAnInventedManeuver() {
        val body = Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE)!!
        assertEquals(0, body.size)
        assertTrue(Iap2MessageCatalog.contains(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE))
    }

    @Test
    fun unknownReplyIdHasNoBody() {
        assertNull(Iap2SubscriptionReplies.idleBodyFor(0x9999))
    }
}
