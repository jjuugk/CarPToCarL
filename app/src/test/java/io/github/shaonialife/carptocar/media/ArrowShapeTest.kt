package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转向码 → 箭头形状。
 *
 * 这张表画错不会抛异常，只会让用户在路口看到反方向的箭头——所以逐条钉死。
 * 重点防的是 **ICON 码被当成 protobuf raw 码**这个坑：两套编码在 2..9 上数值撞车，
 * 一旦有人把 `rawNextTurn` 传进来，左转会静默变成右前方。
 */
class ArrowShapeTest {

    @Test
    fun `可绘制的 ICON 码逐条映射`() {
        val table =
            listOf(
                CarLifeNaviGuide.STRAIGHT to ArrowShape.STRAIGHT,
                CarLifeNaviGuide.LEFT_TURN to ArrowShape.LEFT,
                CarLifeNaviGuide.RIGHT_TURN to ArrowShape.RIGHT,
                CarLifeNaviGuide.LEFT_FRONT to ArrowShape.LEFT_FRONT,
                CarLifeNaviGuide.RIGHT_FRONT to ArrowShape.RIGHT_FRONT,
                CarLifeNaviGuide.LEFT_BACK to ArrowShape.LEFT_BACK,
                CarLifeNaviGuide.RIGHT_BACK to ArrowShape.RIGHT_BACK,
                CarLifeNaviGuide.U_TURN_LEFT to ArrowShape.U_TURN,
                CarLifeNaviGuide.ROUNDABOUT_ENTER to ArrowShape.ROUNDABOUT_IN,
                CarLifeNaviGuide.ROUNDABOUT_EXIT to ArrowShape.ROUNDABOUT_OUT,
                CarLifeNaviGuide.ARRIVE_DEST to ArrowShape.ARRIVE_DEST,
                CarLifeNaviGuide.ARRIVE_TOLL to ArrowShape.ARRIVE_TOLL,
            )
        for ((code, expected) in table) {
            assertEquals("ICON $code", expected, arrowShapeFor(code))
        }
    }

    /** 自车、途经点、服务区、隧道这几类没有对应图标，必须落到 NONE（只显示文字）。 */
    @Test
    fun `没有图标的码一律 NONE`() {
        val none =
            listOf(
                CarLifeNaviGuide.CAR_SELF,
                CarLifeNaviGuide.ARRIVE_WAYPOINT,
                CarLifeNaviGuide.ARRIVE_SERVICE,
                CarLifeNaviGuide.ENTER_TUNNEL,
            )
        for (code in none) {
            assertEquals("ICON $code", ArrowShape.NONE, arrowShapeFor(code))
        }
    }

    @Test
    fun `未知码与零码都是 NONE`() {
        assertEquals(ArrowShape.NONE, arrowShapeFor(0))
        assertEquals(ArrowShape.NONE, arrowShapeFor(-1))
        assertEquals(ArrowShape.NONE, arrowShapeFor(99))
        assertEquals(ArrowShape.NONE, arrowShapeFor(Int.MAX_VALUE))
    }

    /**
     * 反向防呆：ICON 7 是**右后方**，不是左转。
     *
     * 若哪天有人把 `CarLifeNaviNextTurn.rawNextTurn`（raw 7 = 左转）直接喂进渲染层，
     * 这条会立刻红——这是历史上真实踩过的坑（raw 7 被当成右后发出去，车机画右后方，
     * 而投屏上明明是左转）。
     */
    @Test
    fun `ICON 7 是右后方 不是左转`() {
        assertEquals(ArrowShape.RIGHT_BACK, arrowShapeFor(CarLifeNaviGuide.RIGHT_BACK))
        // 同一个数字在 raw 码表里是左转，两者不可互换。
        assertEquals(CarLifeNaviGuide.LEFT_TURN, CarLifeNaviGuide.rawToIcon(7))
    }
}
