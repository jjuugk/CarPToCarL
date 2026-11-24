package io.github.shaonialife.carptocar.amap

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 高德 linksdk 字段映射与状态机。
 *
 * 这里钉的是**会静默出错**的东西：
 * - 转向映射错了不会报错，只会让仪表画错箭头（用户以为导航傻了）；
 * - 清除帧发早了/发晚了不会报错，只会让 HUD 挂着过期距离或提前消失。
 *
 * 单测全部零 Android 依赖（不碰 `org.json`），所以 `AmapNaviMapper` 只接收
 * 已解好的 [AmapNaviSnapshot]——JSON 解析留在客户端。
 */
class AmapNaviMapperTest {

    private fun snap(
        maneuverId: Int = 2,
        currentRoad: String = "银新东一路",
        nextRoad: String = "上海路",
        segment: Int = 81,
        routeRemain: Int = 18_910,
        routeTime: Int = 1_449,
    ) = AmapNaviSnapshot(
        maneuverId = maneuverId,
        currentRoad = currentRoad,
        nextRoad = nextRoad,
        segmentRemainMeters = segment,
        routeRemainMeters = routeRemain,
        routeRemainSeconds = routeTime,
    )

    // ---------- 映射表 ----------

    /**
     * 前 10 项与 CarLife 的「高德 ICON 转向码」数值重合，但**必须逐条断言**，
     * 不能只测一两个——重合是巧合而非契约，写死在这里才防得住将来改号。
     */
    @Test
    fun `maneuver 与 CarLife ICON 的对应关系逐条成立`() {
        val table =
            mapOf(
                2 to CarLifeNaviGuide.LEFT_TURN,
                3 to CarLifeNaviGuide.RIGHT_TURN,
                4 to CarLifeNaviGuide.LEFT_FRONT,
                5 to CarLifeNaviGuide.RIGHT_FRONT,
                6 to CarLifeNaviGuide.LEFT_BACK,
                7 to CarLifeNaviGuide.RIGHT_BACK,
                8 to CarLifeNaviGuide.U_TURN_LEFT,
                9 to CarLifeNaviGuide.STRAIGHT,
                11 to CarLifeNaviGuide.ROUNDABOUT_ENTER,
                12 to CarLifeNaviGuide.ROUNDABOUT_EXIT,
            )
        table.forEach { (maneuver, icon) ->
            assertEquals("maneuver=$maneuver", icon, AmapNaviMapper.toCarLifeIcon(maneuver))
        }
    }

    /** 高德把掉头拆成 8/19 两值、顺行是 20，都要归并；不归并就会画不出箭头。 */
    @Test
    fun `掉头与顺行归并到同一 ICON`() {
        assertEquals(CarLifeNaviGuide.U_TURN_LEFT, AmapNaviMapper.toCarLifeIcon(8))
        assertEquals(CarLifeNaviGuide.U_TURN_LEFT, AmapNaviMapper.toCarLifeIcon(19))
        assertEquals(CarLifeNaviGuide.STRAIGHT, AmapNaviMapper.toCarLifeIcon(9))
        assertEquals(CarLifeNaviGuide.STRAIGHT, AmapNaviMapper.toCarLifeIcon(20))
    }

    /** 未知码宁可不画箭头，也不猜一个方向——猜错比不显示更危险。 */
    @Test
    fun `未知 maneuver 返回 NONE`() {
        listOf(0, 1, 10, 13, 18, 21, 99, 255, -1).forEach {
            assertEquals("maneuver=$it", AmapNaviMapper.ICON_NONE, AmapNaviMapper.toCarLifeIcon(it))
        }
    }

    // ---------- 字段映射 ----------

    @Test
    fun `段距离进 remainDistance 全程剩余进 totalDistance`() {
        val turn = AmapNaviMapper.toNextTurn(snap(segment = 81, routeRemain = 18_910), CarLifeNaviGuide.ACTION_START)
        // remainDistance 喂 0x5201 的 0x0a（到下一个动作的距离），不能填全程剩余。
        assertEquals(81, turn.remainDistance)
        assertEquals(18_910, turn.totalDistance)
    }

    @Test
    fun `路名取下一段路 不是当前所在路`() {
        val turn = AmapNaviMapper.toNextTurn(snap(currentRoad = "银新东一路", nextRoad = "上海路"), CarLifeNaviGuide.ACTION_UPDATE)
        assertEquals("上海路", turn.roadName)
    }

    /** rawNextTurn 留高德原始 id，日志里才能看出「原始 19 被归并成 8」。 */
    @Test
    fun `rawNextTurn 保留原始 maneuver id`() {
        val turn = AmapNaviMapper.toNextTurn(snap(maneuverId = 19), CarLifeNaviGuide.ACTION_UPDATE)
        assertEquals(19, turn.rawNextTurn)
        assertEquals(CarLifeNaviGuide.U_TURN_LEFT, turn.nextTurn)
    }

    @Test
    fun `NUL 与超长路名被清洗`() {
        assertEquals("上海路", AmapNaviMapper.sanitizeRoad("上海路\u0000\u0000"))
        assertEquals("", AmapNaviMapper.sanitizeRoad(null))
        val long = "路".repeat(80)
        assertEquals(40, AmapNaviMapper.sanitizeRoad(long).length)
    }

    // ---------- 状态机 ----------

    @Test
    fun `首帧是 START 之后都是 UPDATE`() {
        val m = AmapNaviStateMachine()
        val first = m.onNavigation(snap(), 1_000L)
        assertEquals(CarLifeNaviGuide.ACTION_START, (first as AmapNaviEmission.Guide).turn.action)
        val second = m.onNavigation(snap(segment = 70), 2_000L)
        assertEquals(CarLifeNaviGuide.ACTION_UPDATE, (second as AmapNaviEmission.Guide).turn.action)
    }

    /** 结束事件之后 2s 内高德会补发最后一帧真实诱导，照发会把清除帧顶掉。 */
    @Test
    fun `结束后的迟到帧被丢弃`() {
        val m = AmapNaviStateMachine()
        m.onNavigation(snap(), 1_000L)
        assertNotNull(m.onStop(2_000L))
        assertNull("结束瞬间的补发帧必须丢", m.onNavigation(snap(), 3_000L))
        assertNull("1.9s 仍在静默窗内", m.onNavigation(snap(), 3_900L))
        assertNotNull("静默窗过后可以接受新导航", m.onNavigation(snap(), 4_100L))
    }

    @Test
    fun `没在引导时收到停止不产出清除帧`() {
        val m = AmapNaviStateMachine()
        assertNull(m.onStop(1_000L))
    }

    @Test
    fun `15 秒无帧判定沉默并清除`() {
        val m = AmapNaviStateMachine()
        m.onNavigation(snap(), 1_000L)
        assertNull(m.onTick(1_000L + AmapNaviStateMachine.STALE_TIMEOUT_MS))
        val stale = m.onTick(1_000L + AmapNaviStateMachine.STALE_TIMEOUT_MS + 1)
        assertEquals(AmapNaviEmission.Clear, stale)
        assertNull("清过一次就不再重复清", m.onTick(1_000L + 30_000L))
    }

    /** segmentRemainDist 为负是「未定位」，带 -1 米上仪表比不发更糟。 */
    @Test
    fun `负距离帧被丢弃`() {
        val m = AmapNaviStateMachine()
        assertNull(m.onNavigation(snap(segment = -1), 1_000L))
        assertNull("丢弃后不应进入引导态", m.onTick(1_000L + 60_000L))
    }

    @Test
    fun `reset 之后重新从 START 开始`() {
        val m = AmapNaviStateMachine()
        m.onNavigation(snap(), 1_000L)
        m.reset()
        val again = m.onNavigation(snap(), 2_000L)
        assertEquals(CarLifeNaviGuide.ACTION_START, (again as AmapNaviEmission.Guide).turn.action)
    }
}
