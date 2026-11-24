package io.github.shaonialife.carptocar.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导航文案格式化。
 *
 * 两个数据源（CarLife `0x10030` 与高德 linksdk）共用这一份距离格式，
 * 所以它同时被媒体卡片和仪表链路依赖——改错一个边界（尤其 1000m 那一档）
 * 会让两路同时出现「1000m」这种写法。
 */
class NavigationTextTest {

    @Test
    fun `公里以内用米`() {
        assertEquals("0m", NavigationText.distance(0))
        assertEquals("81m", NavigationText.distance(81))
        assertEquals("999m", NavigationText.distance(999))
    }

    @Test
    fun `整公里去掉小数位`() {
        assertEquals("1km", NavigationText.distance(1000))
        assertEquals("19km", NavigationText.distance(19_000))
    }

    @Test
    fun `非整公里保留一位小数`() {
        assertEquals("1.5km", NavigationText.distance(1500))
        assertEquals("1.9km", NavigationText.distance(1910))
        assertEquals("26.4km", NavigationText.distance(26_424))
    }

    /** 高德停车/未定位时会发 -1，车机上显示「-1m 右转」比「0m 右转」更莫名其妙。 */
    @Test
    fun `负数按零处理`() {
        assertEquals("0m", NavigationText.distance(-1))
        assertEquals("0m", NavigationText.distance(Int.MIN_VALUE))
    }

    @Test
    fun `媒体卡片第一行是距离加转向名`() {
        assertEquals("200m 右转", NavigationText.maneuver(200, "右转"))
        assertEquals("1.2km 进入环岛", NavigationText.maneuver(1200, "进入环岛"))
        assertEquals("0m 到达目的地", NavigationText.maneuver(-1, "到达目的地"))
    }
}
