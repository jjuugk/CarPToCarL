package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机分辨率解析：真车 `/info` 的写法并不统一（Long/Double/字符串、`widthPixels`
 * 与 `WidthPhysical` 两套键名都见过），任何一处判空都会让整条链路退回手机分辨率。
 */
class CarDisplayGeometryTest {
    private fun info(display: Map<String, Any?>): Map<String, Any?> = mapOf("displays" to listOf(display))

    @Test
    fun readsApplePixelsKeys() {
        val size = CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to 1412L, "heightPixels" to 654L)))
        assertEquals(1412, size?.width)
        assertEquals(654, size?.height)
        assertEquals("1412×654", size?.text)
        assertTrue(size!!.source.contains("displays[0]"))
    }

    @Test
    fun acceptsAnyNumberType() {
        // 2 字节整数会解成 Long，个别固件把宽高写成 real（Double），都收
        assertEquals(800, CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to 800, "heightPixels" to 480)))?.width)
        assertEquals(
            1160,
            CarDisplayGeometry.fromInfo(
                info(mapOf("widthPixels" to 1160.0, "heightPixels" to 540.0)),
            )?.width,
        )
    }

    @Test
    fun acceptsNumericStrings() {
        val size = CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to "1920", "heightPixels" to "720")))
        assertEquals(1920, size?.width)
        assertEquals(720, size?.height)
    }

    @Test
    fun fallsBackToPhysicalKeys() {
        // DiPlay（Android 接收端）就按 WidthPhysical/HeightPhysical 声明
        val size = CarDisplayGeometry.fromInfo(info(mapOf("WidthPhysical" to 800L, "HeightPhysical" to 480L)))
        assertEquals(800, size?.width)
        assertEquals(480, size?.height)
        assertTrue(size!!.source.contains("WidthPhysical"))
    }

    @Test
    fun prefersPixelsOverGenericWidth() {
        val size =
            CarDisplayGeometry.fromInfo(
                info(mapOf("widthPixels" to 1412L, "width" to 4L, "heightPixels" to 654L, "height" to 3L)),
            )
        assertEquals(1412, size?.width)
        assertEquals(654, size?.height)
    }

    @Test
    fun returnsNullWhenAnySideMissingOrInvalid() {
        assertNull(CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to 1412L))))
        assertNull(CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to 0L, "heightPixels" to 654L))))
        assertNull(CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to -1L, "heightPixels" to 654L))))
        // 解析错位（字段里有别的数值）时不能当真：8K 上限之外一律判无效
        assertNull(CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to 99999L, "heightPixels" to 654L))))
        assertNull(CarDisplayGeometry.fromInfo(info(mapOf("widthPixels" to "x", "heightPixels" to 654L))))
    }

    @Test
    fun returnsNullWhenDisplaysMissing() {
        assertNull(CarDisplayGeometry.fromInfo(null))
        assertNull(CarDisplayGeometry.fromInfo(emptyMap<String, Any?>()))
        assertNull(CarDisplayGeometry.fromInfo(mapOf("displays" to emptyList<Any>())))
        assertNull(CarDisplayGeometry.fromInfo(mapOf("displays" to "1412x654")))
    }

    @Test
    fun skipsNonDictEntries() {
        val root =
            mapOf(
                "displays" to
                    listOf(
                        null,
                        "junk",
                        mapOf("widthPixels" to 1280L, "heightPixels" to 720L),
                    ),
            )
        val size = CarDisplayGeometry.fromInfo(root)
        assertEquals(1280, size?.width)
        assertTrue(size!!.source.contains("displays[2]"))
    }
}
