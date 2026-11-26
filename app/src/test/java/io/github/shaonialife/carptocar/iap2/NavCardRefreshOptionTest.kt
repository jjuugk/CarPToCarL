package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航卡片刷新策略的纯逻辑部分：区间夹取、选项集合、文案。
 *
 * 不构造 Context —— [NavCardRefreshOption.load] / `save*` 才需要它。
 */
class NavCardRefreshOptionTest {
    @Test
    fun `normalize 夹在 1 到 15 秒之间并归整到整秒`() {
        assertEquals(NavCardRefreshOption.UNLIMITED, NavCardRefreshOption.normalize(0L))
        assertEquals(NavCardRefreshOption.UNLIMITED, NavCardRefreshOption.normalize(-5L))
        assertEquals(1_000L, NavCardRefreshOption.normalize(999L))
        assertEquals(3_000L, NavCardRefreshOption.normalize(3_000L))
        assertEquals(2_000L, NavCardRefreshOption.normalize(2_500L))
        assertEquals(15_000L, NavCardRefreshOption.normalize(60_000L))
    }

    @Test
    fun `选项覆盖 1 到 15 秒再加不限一档`() {
        assertEquals(16, NavCardRefreshOption.STEPS.size)
        assertEquals(1_000L, NavCardRefreshOption.STEPS.first())
        assertEquals(15_000L, NavCardRefreshOption.STEPS[14])
        assertEquals(NavCardRefreshOption.UNLIMITED, NavCardRefreshOption.STEPS.last())
        assertTrue(NavCardRefreshOption.STEPS.contains(NavCardRefreshOption.DEFAULT_MILLIS))
    }

    @Test
    fun `默认三秒且标签可读`() {
        assertEquals(3_000L, NavCardRefreshOption.DEFAULT_MILLIS)
        assertEquals("3 秒", NavCardRefreshOption.label(3_000L))
        assertEquals("15 秒", NavCardRefreshOption.label(15_000L))
        assertEquals("不限", NavCardRefreshOption.label(NavCardRefreshOption.UNLIMITED))
    }
}
