package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多屏选择（大连屏/仪表）。
 *
 * 核心约束：**车机不声明 `type` 时行为必须与旧实现完全一致**（取第一块有效屏），
 * 因为绝大多数车机只报一块屏或根本不报 type，改掉它们的选屏结果等于换 bug。
 */
class CarDisplaySelectTest {
    private fun info(vararg displays: Map<String, Any?>): Map<String, Any?> = mapOf("displays" to displays.toList())

    private fun screen(
        w: Long,
        h: Long,
        type: Int? = null,
        maxFps: Long? = null,
        uuid: String? = null,
    ): Map<String, Any?> =
        buildMap {
            put("widthPixels", w)
            put("heightPixels", h)
            if (type != null) put("type", type)
            if (maxFps != null) put("maxFPS", maxFps)
            if (uuid != null) put("uuid", uuid)
        }

    @Test
    fun singleDisplayBehavesAsBefore() {
        val size = CarDisplayGeometry.fromInfo(info(screen(1920, 720, uuid = "main")))
        assertEquals(1920, size!!.width)
        assertEquals(720, size.height)
        assertTrue(size.source.contains("displays[0]"))
    }

    @Test
    fun withoutDeclaredTypeTheFirstValidDisplayWins() {
        // 两块屏但都没声明 type：保持旧行为（先声明的先用），别自作聪明按面积挑
        val root = info(screen(800, 480), screen(1920, 720))
        val screens = CarDisplayGeometry.screens(root)
        assertEquals(2, screens.size)
        assertEquals(0, CarDisplayGeometry.select(screens)!!.index)
    }

    @Test
    fun declaredMainTypeWinsOverDeclarationOrder() {
        // 先声明仪表（type=1）、后声明主屏（type=0）：旧实现会把画面推到仪表上
        val root = info(screen(800, 480, type = 1), screen(1920, 720, type = 0))
        val selected = CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!
        assertEquals(1, selected.index)
        assertEquals(1920, selected.width)
    }

    @Test
    fun sameTypePicksLargerPanel() {
        val root = info(screen(1280, 480, type = 0), screen(1920, 720, type = 0))
        val selected = CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!
        assertEquals(1, selected.index)
    }

    @Test
    fun samePanelPicksHigherFrameRate() {
        val root = info(screen(1920, 720, type = 0, maxFps = 30), screen(1920, 720, type = 0, maxFps = 60))
        val selected = CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!
        assertEquals(60, selected.maxFps)
        assertEquals(1, selected.index)
    }

    @Test
    fun undeclaredTypeSortsAfterDeclared() {
        // 一块没声明 type、一块声明 type=0：声明过主屏的优先（没声明的那块多半是占位项）
        val root = info(screen(3840, 1080), screen(1920, 720, type = 0))
        val selected = CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!
        assertEquals(1, selected.index)
    }

    @Test
    fun selectionIsStableForTheSameInfo() {
        // 同样的 /info 必须永远选出同一块屏：比较器最后按声明下标兜底，不能靠排序随机性
        val root = info(screen(1920, 720, type = 0), screen(1920, 720, type = 0))
        repeat(20) {
            assertEquals(0, CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!.index)
        }
    }

    @Test
    fun invalidDisplayIsSkippedButOthersStillUsable() {
        val root =
            info(
                mapOf("widthPixels" to 0L, "heightPixels" to 654L),
                mapOf("widthPixels" to 1412L),
                screen(1920, 720),
            )
        val screens = CarDisplayGeometry.screens(root)
        assertEquals(1, screens.size)
        assertEquals(2, screens.first().index)
        assertTrue(CarDisplayGeometry.describe(screens).contains("displays[2]"))
    }

    @Test
    fun noDisplaysReportsWhyInsteadOfSilence() {
        assertNull(CarDisplayGeometry.fromInfo(emptyMap<String, Any?>()))
        assertNull(CarDisplayGeometry.fromInfo(null))
        assertTrue(CarDisplayGeometry.describe(CarDisplayGeometry.screens(null)).contains("无可解析项"))
    }

    @Test
    fun uuidAndMaxFpsComeFromTheSelectedDisplay() {
        // 关键：displayUuid / maxFps 曾固定读 displays[0]，多屏车机上会拿到副屏的值
        val root = info(screen(800, 480, type = 1, maxFps = 30, uuid = "cluster"), screen(1920, 720, type = 0, maxFps = 60, uuid = "main"))
        val selected = CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!
        assertEquals("main", selected.uuid)
        assertEquals(60, selected.maxFps)
    }

    @Test
    fun byteArrayUuidIsNormalisedToDashedHex() {
        // bplist 的 16 字节 UUID（0xB）直接 toString() 会变成 "[B@1f2e3d4" 这种垃圾
        val raw = ByteArray(16) { (0x10 + it).toByte() }
        val root = mapOf("displays" to listOf(mapOf("widthPixels" to 1920L, "heightPixels" to 720L, "uuid" to raw)))
        val uuid = CarDisplayGeometry.select(CarDisplayGeometry.screens(root))!!.uuid
        assertEquals("10111213-1415-1617-1819-1a1b1c1d1e1f", uuid)
    }
}
