package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance
import io.github.shaonialife.carptocar.iap2.Iap2SubscriptionReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLife 0x10030 解析与 iAP2 0x5201/0x5202 编码回归。
 *
 * 字段号来自反编译 APK 的 `CarlifeNaviNextTurnInfoProto` 生成类；
 * 期望的 Apple 侧取值按实测格式：0x5202 直行 type=11、左转 type=1、到达 type=12、
 * drivingSide=2、0x5201 的 0x0d 是 4 字节 `00 00 00 02`。
 */
class CarLifeNaviGuideTest {

    private fun varint(value: Int): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun varintField(field: Int, value: Int): ByteArray =
        byteArrayOf((field shl 3).toByte()) + varint(value)

    private fun strField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(((field shl 3) or 2).toByte(), bytes.size.toByte()) + bytes
    }

    private fun guidance(
        action: Int,
        nextTurn: Int,
        road: String,
        remain: Int,
        total: Int,
    ): ByteArray =
        varintField(1, action) +
            varintField(2, nextTurn) +
            strField(3, road) +
            varintField(4, total) +
            varintField(5, remain)

    @Test
    fun `解析左转诱导的全部字段`() {
        // protobuf field2 的 raw=7 在车机端 b8/a.c 里映射到「左转」ICON=2。
        // 绝不能把 raw 7 当成高德 RIGHT_BACK(7) 直接发出去。
        val info = CarLifeNaviGuide.parse(guidance(2, 7, "测试路A", 300, 12000))
        assertNotNull(info)
        assertEquals(2, info!!.action)
        assertEquals(CarLifeNaviGuide.LEFT_TURN, info.nextTurn)
        assertEquals(7, info.rawNextTurn)
        assertEquals("测试路A", info.roadName)
        assertEquals(300, info.remainDistance)
        assertEquals(12000, info.totalDistance)
        assertEquals(2, CarLifeNaviGuide.amapIcon(info.nextTurn))
    }

    @Test
    fun `protobuf raw 码映射为高德 ICON`() {
        assertEquals(CarLifeNaviGuide.LEFT_TURN, CarLifeNaviGuide.rawToIcon(7))
        assertEquals(CarLifeNaviGuide.RIGHT_FRONT, CarLifeNaviGuide.rawToIcon(2))
        assertEquals(CarLifeNaviGuide.RIGHT_TURN, CarLifeNaviGuide.rawToIcon(3))
        assertEquals(CarLifeNaviGuide.RIGHT_BACK, CarLifeNaviGuide.rawToIcon(4))
        assertEquals(CarLifeNaviGuide.STRAIGHT, CarLifeNaviGuide.rawToIcon(1))
        assertEquals(CarLifeNaviGuide.ARRIVE_DEST, CarLifeNaviGuide.rawToIcon(24))
        assertEquals(0, CarLifeNaviGuide.rawToIcon(-1))
    }

    @Test
    fun `解析导航开始与结束`() {
        val start = CarLifeNaviGuide.parse(guidance(1, 0, "", 0, 0))
        assertEquals(CarLifeNaviGuide.ACTION_START, start!!.action)
        val end = CarLifeNaviGuide.parse(varintField(1, 3))
        assertEquals(CarLifeNaviGuide.ACTION_END, end!!.action)
    }

    @Test
    fun `畸形包返回 null 且不抛异常`() {
        assertNull(CarLifeNaviGuide.parse(byteArrayOf()))
        // field3 声明 100 字节，实际只有 2 字节：必须被静默吞掉
        assertNull(CarLifeNaviGuide.parse(byteArrayOf(0x1A, 0x64, 0x01, 0x02)))
        assertNull(CarLifeNaviGuide.parse(byteArrayOf(0x08)))
    }

    @Test
    fun `Apple 转向码映射符合实测`() {
        assertEquals(1, CarLifeNaviGuide.appleManeuverType(CarLifeNaviGuide.LEFT_TURN))
        assertEquals(2, CarLifeNaviGuide.appleManeuverType(CarLifeNaviGuide.RIGHT_TURN))
        assertEquals(11, CarLifeNaviGuide.appleManeuverType(CarLifeNaviGuide.STRAIGHT))
        assertEquals(12, CarLifeNaviGuide.appleManeuverType(CarLifeNaviGuide.ARRIVE_DEST))
        assertEquals(0, CarLifeNaviGuide.appleManeuverType(CarLifeNaviGuide.CAR_SELF))
    }

    @Test
    fun `编码出 Apple 形态的 0x5201 与两条 0x5202`() {
        Iap2RouteGuidance.reset()
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.TUNNEL)
        Iap2RouteGuidance.publish(
            CarLifeNaviNextTurn(
                action = CarLifeNaviGuide.ACTION_UPDATE,
                nextTurn = CarLifeNaviGuide.STRAIGHT,
                rawNextTurn = 1,
                roadName = "测试路B",
                remainDistance = 120,
                totalDistance = 2500,
            ),
        )
        val frames = Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.TUNNEL)
        assertEquals(3, frames.size)
        assertEquals(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE, frames[0].first)
        assertEquals(Iap2RouteGuidance.ROUTE_GUIDANCE_MANEUVER_UPDATE, frames[1].first)
        assertEquals(Iap2RouteGuidance.ROUTE_GUIDANCE_MANEUVER_UPDATE, frames[2].first)

        val update = Iap2Csm.parseParams(frames[0].second).associateBy { it.id }
        assertEquals(42, u16(update[0x00]!!.value))
        assertEquals(6, update[0x01]!!.value[0].toInt() and 0xFF) // 诱导中，真机值 6
        assertEquals(2500, u32(update[0x07]!!.value))
        assertEquals(120, u32(update[0x0a]!!.value))
        assertEquals(4, update[0x0d]!!.value.size) // Apple 实测 4 字节
        assertEquals(2, u32(update[0x0d]!!.value))
        assertEquals("高德地图", update[0x13]!!.value.decodeToString().trimEnd('\u0000'))

        val maneuver = Iap2Csm.parseParams(frames[1].second).associateBy { it.id }
        assertEquals(0, u16(maneuver[0x01]!!.value))
        assertEquals(11, maneuver[0x03]!!.value[0].toInt() and 0xFF)
        assertEquals(2, maneuver[0x07]!!.value[0].toInt() and 0xFF) // 右行实测值
        assertEquals("测试路B", maneuver[0x04]!!.value.decodeToString().trimEnd('\u0000'))
        assertEquals(2, u16(Iap2Csm.parseParams(frames[2].second).first { it.id == 0x01 }.value))

        // 同一条诱导不重复推
        assertTrue(Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.TUNNEL).isEmpty())
    }

    @Test
    fun `订阅应答直接带回已缓存的真载荷`() {
        Iap2RouteGuidance.reset()
        Iap2RouteGuidance.publish(
            CarLifeNaviNextTurn(
                action = 2,
                nextTurn = CarLifeNaviGuide.LEFT_TURN,
                rawNextTurn = 7,
                roadName = "测试路A",
            ),
        )
        val reply = Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.BT)
        assertTrue(reply.isNotEmpty())
        // 订阅应答已经带回 0x5201，下一次只补 0x5202（不能把 maneuver 一起吞掉）
        val pending = Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT)
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.first == Iap2RouteGuidance.ROUTE_GUIDANCE_MANEUVER_UPDATE })
    }

    @Test
    fun `导航结束立即推 clear 帧且心跳立即停`() {
        Iap2RouteGuidance.reset()
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.TUNNEL)
        Iap2RouteGuidance.publish(
            CarLifeNaviNextTurn(
                action = CarLifeNaviGuide.ACTION_UPDATE,
                nextTurn = CarLifeNaviGuide.STRAIGHT,
                rawNextTurn = 1,
                roadName = "测试路B",
            ),
        )
        // 先把首帧消费掉，确保后面测到的是「结束那一刻」冒出来的新帧
        assertEquals(3, Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.TUNNEL).size)

        Iap2RouteGuidance.publish(CarLifeNaviNextTurn(action = CarLifeNaviGuide.ACTION_END))
        val frames = Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.TUNNEL)
        assertEquals(1, frames.size)
        assertEquals(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE, frames[0].first)
        val params = Iap2Csm.parseParams(frames[0].second).associateBy { it.id }
        assertEquals(42, u16(params[0x00]!!.value))
        assertEquals(0, params[0x01]!!.value[0].toInt() and 0xFF) // 真机结束态 0
        assertEquals(0, u32(params[0x07]!!.value))
        assertTrue(params[0x0a] == null) // 结束帧不带 0x0a/0x0d/0x5202
        assertNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.TUNNEL))
        assertTrue(!Iap2RouteGuidance.hasActiveRoute())
        // 结束后晚订阅拿到的是 clear，而不是上一轮旧引导
        val late = Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.BT)
        assertTrue(late.isNotEmpty())
        val lateParams = Iap2Csm.parseParams(late).associateBy { it.id }
        assertEquals(0, lateParams[0x01]!!.value[0].toInt() and 0xFF)
    }

    @Test
    fun `CarLife 引擎断开立即推 clear 帧`() {
        Iap2RouteGuidance.reset()
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.BT)
        Iap2RouteGuidance.publish(
            CarLifeNaviNextTurn(
                action = CarLifeNaviGuide.ACTION_UPDATE,
                nextTurn = CarLifeNaviGuide.LEFT_TURN,
                rawNextTurn = 7,
                roadName = "测试路A",
            ),
        )
        assertEquals(3, Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT).size)

        // 引擎控制通道断开：走与 ACTION_END 相同的 clear 快照
        Iap2RouteGuidance.endRoute()
        val frames = Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT)
        assertEquals(1, frames.size)
        val params = Iap2Csm.parseParams(frames[0].second).associateBy { it.id }
        assertEquals(0, params[0x01]!!.value[0].toInt() and 0xFF)
        assertEquals(0, u32(params[0x07]!!.value))
        assertNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.BT))
        assertTrue(!Iap2RouteGuidance.hasActiveRoute())
    }

    @Test
    fun `心跳只刷 0x5201 不重发 0x5202`() {
        Iap2RouteGuidance.reset()
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.BT)
        Iap2RouteGuidance.publish(
            CarLifeNaviNextTurn(
                action = CarLifeNaviGuide.ACTION_UPDATE,
                nextTurn = CarLifeNaviGuide.LEFT_TURN,
                rawNextTurn = 7,
                roadName = "测试路A",
                remainDistance = 300,
                totalDistance = 12000,
            ),
        )
        val frames = Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT)
        assertEquals(3, frames.size)

        val heartbeat = Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.BT)
        assertNotNull(heartbeat)
        assertEquals(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE, heartbeat!!.first)
        val params = Iap2Csm.parseParams(heartbeat.second).associateBy { it.id }
        assertEquals(6, params[0x01]!!.value[0].toInt() and 0xFF) // 诱导中，不是 clear
        assertEquals(12000, u32(params[0x07]!!.value))
        assertEquals(300, u32(params[0x0a]!!.value))
        // 心跳已把这一版 update 记账，常规拍不能再重复发
        assertTrue(Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT).isEmpty())
        // 但下一拍心跳仍可取到同一帧（不走去重），这样才能持续刷新 DiPlay 计时器
        assertNotNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.BT))
    }

    private fun u16(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
}
