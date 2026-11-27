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
    fun communicationsUpdateFiltersToRequestedFieldsAndRejectsMalformedNonEmptyRequest() {
        val requestBody =
            listOf(0, 1, 7, 9, 17).fold(ByteArray(0)) { acc, id -> acc + Iap2Csm.param(id, ByteArray(0)) }
        val filtered =
            Iap2SubscriptionReplies.idleBodyFor(
                Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE,
                requestBody = requestBody,
            )!!
        val params = Iap2Csm.parseParams(filtered)
        assertEquals(listOf(0, 1, 7, 9, 17), params.map { it.id })
        assertEquals(25, filtered.size)

        val unknownOnly =
            Iap2SubscriptionReplies.communicationsUpdateBody(Iap2Csm.param(0x0099, ByteArray(0)))
        assertEquals(0, unknownOnly.size)

        // 非空畸形载荷（截断的 TLV 头）绝不回退为 100B 完整模板
        val malformed = Iap2SubscriptionReplies.communicationsUpdateBody(byteArrayOf(0x00, 0x08, 0x00))
        assertEquals(0, malformed.size)
    }

    @Test
    fun listUpdateReturnsMinimalEmptyStateMatchingWireSelectors() {
        val defaultParams = Iap2Csm.parseParams(Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.LIST_UPDATE)!!)
        assertEquals(listOf(0x0000), defaultParams.map { it.id })
        assertArrayEquals(byteArrayOf(0), defaultParams.single().value)

        // 0x4170 线协议 Recents 选择参数：1 (Properties) / 3 (Max) / 4 (Combine)
        val recentsRequest =
            Iap2Csm.param(0x0001, ByteArray(0)) +
                Iap2Csm.u16(0x0003, 0) +
                Iap2Csm.u8(0x0004, 0)
        val recentsReply = Iap2SubscriptionReplies.listUpdateBody(recentsRequest)
        assertEquals(5, recentsReply.size)
        assertEquals(listOf(0x0000), Iap2Csm.parseParams(recentsReply).map { it.id })

        // 0x4170 线协议 Favorites 选择参数：6 (Properties) / 8 (Max)；5 是应答字段号而非请求字段号
        val favoritesPropsOnly = Iap2SubscriptionReplies.listUpdateBody(Iap2Csm.param(0x0006, ByteArray(0)))
        assertEquals(listOf(0x0005), Iap2Csm.parseParams(favoritesPropsOnly).map { it.id })

        val favoritesMaxOnly = Iap2SubscriptionReplies.listUpdateBody(Iap2Csm.u16(0x0008, 0))
        assertEquals(listOf(0x0005), Iap2Csm.parseParams(favoritesMaxOnly).map { it.id })

        val both =
            Iap2SubscriptionReplies.listUpdateBody(
                Iap2Csm.param(0x0001, ByteArray(0)) + Iap2Csm.param(0x0006, ByteArray(0)),
            )
        assertEquals(listOf(0x0000, 0x0005), Iap2Csm.parseParams(both).map { it.id })

        // 非空请求既不含 Recents(1/3/4) 也不含 Favorites(6/8) 时不凭空回 Recents
        val unmatched = Iap2SubscriptionReplies.listUpdateBody(Iap2Csm.param(0x0005, ByteArray(0)))
        assertEquals(0, unmatched.size)
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

    @Test
    fun powerUpdateBodyEncodesDefaultAndPartiallyDischargedUnpluggedBatterySnapshots() {
        val defaultBody = Iap2SubscriptionReplies.powerUpdateBody()
        assertEquals(16, defaultBody.size)
        val defaultParsed = Iap2Csm.parseParams(defaultBody)
        assertEquals(listOf(4, 5, 6), defaultParsed.map { it.id })
        assertArrayEquals(byteArrayOf(1), defaultParsed[0].value)
        assertArrayEquals(byteArrayOf(1), defaultParsed[1].value)
        assertArrayEquals(byteArrayOf(0, 100), defaultParsed[2].value)

        val unplugged =
            Iap2SubscriptionReplies.powerUpdateBody(
                Iap2SubscriptionReplies.BatterySnapshot(
                    externalPowerMode = 0,
                    chargingState = 0,
                    chargeLevel = 47,
                ),
            )
        assertEquals(16, unplugged.size)
        val unpluggedParsed = Iap2Csm.parseParams(unplugged)
        assertEquals(listOf(4, 5, 6), unpluggedParsed.map { it.id })
        assertArrayEquals(byteArrayOf(0), unpluggedParsed[0].value)
        assertArrayEquals(byteArrayOf(0), unpluggedParsed[1].value)
        assertArrayEquals(byteArrayOf(0, 47), unpluggedParsed[2].value)
    }

    @Test
    fun batterySnapshotPreservesExplicitNotChargingEvenWhenPlugged() {
        val paused =
            Iap2SubscriptionReplies.batterySnapshotFrom(
                level = 80,
                scale = 100,
                status = android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                plugged = android.os.BatteryManager.BATTERY_PLUGGED_USB,
            )
        assertEquals(1, paused.externalPowerMode)
        assertEquals(0, paused.chargingState)
        assertEquals(80, paused.chargeLevel)
    }
}
