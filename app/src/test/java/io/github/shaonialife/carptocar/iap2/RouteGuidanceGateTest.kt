package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「车机不支持导航引导就不发」的闸门回归。
 *
 * 判据只有一条：`0x1D01` 可收清单**到过**且里面没有 0x52xx → false（不支持）。
 * 清单未到（null）仍按未知放行，沿用「清单只能证明有、不能证明没有」的长期结论。
 * 三条发送路径（0x5200 订阅应答、主动帧、心跳）必须共用同一判据。
 */
class RouteGuidanceGateTest {

    @Before
    fun setUp() {
        AccessoryDeclaration.reset()
        Iap2RouteGuidance.reset()
    }

    private fun declare(receiveIds: Set<Int>?) {
        AccessoryDeclaration.onIdentification(
            sendIds = null,
            receiveIds = receiveIds,
            components = AccessoryDeclaration.Components(),
            source = "test",
        )
    }

    private fun update() =
        CarLifeNaviNextTurn(
            action = CarLifeNaviGuide.ACTION_UPDATE,
            rawNextTurn = 7,
            nextTurn = CarLifeNaviGuide.LEFT_TURN,
            roadName = "测试路",
            totalDistance = 1000,
            remainDistance = 200,
        )

    @Test
    fun `清单明确无 52xx 时一律不产出帧`() {
        declare(setOf(0x5001, 0x4E09))
        assertTrue(Iap2RouteGuidance.blockedByDeclaration())
        assertEquals(false, AccessoryDeclaration.acceptsRouteGuidance())

        Iap2RouteGuidance.publish(update())
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.BT)
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.TUNNEL)

        // 即使上层漏判、订阅已记下，也不要产出 0x5201/0x5202 与心跳
        assertTrue(Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT).isEmpty())
        assertTrue(Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.TUNNEL).isEmpty())
        assertNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.BT))
        assertNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.TUNNEL))
    }

    @Test
    fun `清单未到时按未知放行`() {
        assertNull(AccessoryDeclaration.acceptsRouteGuidance())
        assertFalse(Iap2RouteGuidance.blockedByDeclaration())

        Iap2RouteGuidance.publish(update())
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.BT)

        assertTrue(Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.BT).isNotEmpty())
        assertNotNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.BT))
    }

    @Test
    fun `清单声明支持时正常产出帧与心跳`() {
        declare(setOf(0x5201, 0x5202))
        assertFalse(Iap2RouteGuidance.blockedByDeclaration())

        Iap2RouteGuidance.publish(update())
        Iap2RouteGuidance.onSubscribe(Iap2RouteGuidance.Link.TUNNEL)
        val frames = Iap2RouteGuidance.nextFramesFor(Iap2RouteGuidance.Link.TUNNEL)
        assertTrue(frames.isNotEmpty())
        assertEquals(0x5202, frames.last().first)
        assertNotNull(Iap2RouteGuidance.nextHeartbeatFor(Iap2RouteGuidance.Link.TUNNEL))
    }
}
