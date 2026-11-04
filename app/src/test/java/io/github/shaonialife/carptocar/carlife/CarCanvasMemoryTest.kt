package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CarCanvasMemory.sanitize] 的边界：只有「像车机画布」的值才允许进缓存。
 *
 * 背景：这份缓存是**下次开机首轮 98311** 的申请值，而引擎只在建编码器之前认它。
 * 一旦被 0 / 竖屏误报 / 离谱大值污染，下次连接会拿错尺寸申请，引擎 `reset2Default`
 * 降回 1280×720 —— 等于把本来正确的缓存也弄丢了，还得再连一次才能修回来。
 */
class CarCanvasMemoryTest {
    @Test
    fun `常见车机画布全部保留`() {
        assertEquals(1412 to 654, CarCanvasMemory.sanitize(1412, 654)) // 奥迪 MIB3 G35
        assertEquals(1280 to 720, CarCanvasMemory.sanitize(1280, 720)) // 引擎默认档
        assertEquals(1972 to 520, CarCanvasMemory.sanitize(1972, 520)) // 别克大连屏
        assertEquals(2240 to 976, CarCanvasMemory.sanitize(2240, 976)) // ID7
    }

    @Test
    fun `未初始化的零值不缓存`() {
        assertNull(CarCanvasMemory.sanitize(0, 0))
        assertNull(CarCanvasMemory.sanitize(1412, 0))
        assertNull(CarCanvasMemory.sanitize(0, 654))
    }

    @Test
    fun `范围内竖屏值照收——假画布靠调用点拦截`() {
        // sanitize 只做「像不像一块屏」的范围判断，不猜方向：确实有竖屏车机。
        // 「手机横屏兜底」的假画布由调用点（SessionController 只在 displayFromCar=true
        // 时调用 → setRequestedResolution）挡住，不在这里靠长宽比猜。
        assertEquals(654 to 1412, CarCanvasMemory.sanitize(654, 1412))
    }

    @Test
    fun `超出合理范围不缓存`() {
        assertNull(CarCanvasMemory.sanitize(199, 720)) // 宽度下限 320
        assertNull(CarCanvasMemory.sanitize(4098, 720)) // 宽度上限 4096
        assertNull(CarCanvasMemory.sanitize(1280, 179)) // 高度下限 180
        assertNull(CarCanvasMemory.sanitize(1280, 2305)) // 高度上限 2304
    }
}
