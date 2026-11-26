package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeatureTrimOption.apply] 的裁剪语义。
 *
 * 重点是守住「关闭时逐字节不变」和「必需能力一个都不能被裁掉」这两条——
 * 奥迪 2026-10-11 那次漏提 `videoPlayback` 直接换来 400，这个坑不能再踩。
 */
class FeatureTrimOptionTest {
    /** 与 `AirPlayClient.CONTROLLER_FEATURES` 保持一致的 14 项（顺序也一致）。 */
    private val full =
        listOf(
            "uiContext",
            "viewAreas",
            "cornerMasks",
            "focusTransfer",
            "h.264Level5.1",
            "mainBuffered",
            "altScreen",
            "enhancedSiri",
            "hevc",
            "sessionManagement",
            "logTransfer",
            "iAPChannel",
            "vehicleStateProtocol",
            "videoPlayback",
        )

    @Test
    fun `关闭时不裁剪且返回同一实例`() {
        val result = FeatureTrimOption.apply(full, trim = false)
        assertSame("关闭时必须原样透传，不能新建列表", full, result)
        assertEquals(full, result)
    }

    @Test
    fun `开启时裁掉七项无实现的扩展`() {
        val result = FeatureTrimOption.apply(full, trim = true)
        assertEquals(7, result.size)
        assertEquals(
            listOf(
                "uiContext",
                "viewAreas",
                "focusTransfer",
                "h.264Level5.1",
                "enhancedSiri",
                "iAPChannel",
                "videoPlayback",
            ),
            result,
        )
        // 被裁掉的必须一项不剩
        FeatureTrimOption.TRIMMED.forEach { assertTrue("$it 应被裁掉", it !in result) }
    }

    @Test
    fun `必需能力绝不能被裁`() {
        val result = FeatureTrimOption.apply(full, trim = true)
        // videoPlayback：缺了车机直接回 400（奥迪实证）
        assertTrue(result.contains("videoPlayback"))
        // 屏幕几何与编码等级：视频 SETUP 依赖
        assertTrue(result.contains("viewAreas"))
        assertTrue(result.contains("h.264Level5.1"))
    }

    @Test
    fun `裁剪顺序保持不变`() {
        val result = FeatureTrimOption.apply(full, trim = true)
        assertEquals(full.filter { it in result }, result)
    }

    @Test
    fun `裁剪集合与清单的差集就是保留下来的七项`() {
        assertEquals(7, FeatureTrimOption.TRIMMED.size)
        assertEquals(full.toSet() - FeatureTrimOption.TRIMMED, FeatureTrimOption.apply(full, true).toSet())
    }

    @Test
    fun `清单里没有的多余名字不会影响保留下来的项`() {
        // 防御：将来有人往 CONTROLLER_FEATURES 加了新项，不会被 TRIMMED 误伤
        val withNew = full + "someFutureFeature"
        val result = FeatureTrimOption.apply(withNew, trim = true)
        assertTrue(result.contains("someFutureFeature"))
        assertEquals(8, result.size)
    }
}
