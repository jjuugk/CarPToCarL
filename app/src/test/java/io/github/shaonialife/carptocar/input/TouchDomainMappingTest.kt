package io.github.shaonialife.carptocar.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「触摸域自适应」纯计算单测。
 *
 * 现场数据（2026-11-21 BUICK_6369 / 通用 Cinemo）：车机画布通告 1972×520，
 * HID 实际上报到 X=2705、Y=555，旧逻辑 `coerceIn(0, viewW-1)` 把这些点全钳到 1971/519，
 * 表现为「点右侧总落到右边缘」。这里固定这套数字，防止以后改公式时悄悄回归。
 */
class TouchDomainMappingTest {
    private val viewW = 1972
    private val viewH = 520

    // ---- 开关关闭：必须与历史行为完全一致（原样透传）----

    @Test
    fun `开关关闭时即使上报域更大也不映射`() {
        val dw = TouchDomainMapping.domainFor(viewW, observedMax = 2705, enabled = false)
        val dh = TouchDomainMapping.domainFor(viewH, observedMax = 555, enabled = false)
        assertEquals(viewW, dw)
        assertEquals(viewH, dh)
        assertEquals(2705, TouchDomainMapping.map(2705, viewW, dw))
        assertEquals(555, TouchDomainMapping.map(555, viewH, dh))
    }

    // ---- 上报域未超出视图域（含少量余量）：不映射 ----

    @Test
    fun `观测上限没超过视图时保持原样`() {
        val dw = TouchDomainMapping.domainFor(viewW, observedMax = 1500, enabled = true)
        assertEquals(viewW, dw)
        assertEquals(1500, TouchDomainMapping.map(1500, viewW, dw))
    }

    @Test
    fun `余量以内的超出不触发映射`() {
        // 有的车机把 [0, w] 当闭区间，最大值正好等于 w 甚至多几像素
        assertEquals(viewW, TouchDomainMapping.domainFor(viewW, viewW, enabled = true))
        val boundary = viewW + TouchDomainMapping.EXPAND_MARGIN
        assertEquals(viewW, TouchDomainMapping.domainFor(viewW, boundary, enabled = true))
        // 超过余量一个像素就要映射
        val over = boundary + 1
        assertEquals(over, TouchDomainMapping.domainFor(viewW, over, enabled = true))
    }

    // ---- 现场那台车：1972/2705（X 轴）----

    @Test
    fun `现场域 X 轴按 1972 对 2705 映射`() {
        val dw = TouchDomainMapping.domainFor(viewW, observedMax = 2705, enabled = true)
        assertEquals(2705, dw)
        // 上报域最大值 → 视图最后一像素（不再被 coerceIn 堆在右缘）
        assertEquals(1971, TouchDomainMapping.map(2705, viewW, dw))
        // 中点：1352*1971/2705 = 985
        assertEquals(985, TouchDomainMapping.map(1352, viewW, dw))
        assertEquals(0, TouchDomainMapping.map(0, viewW, dw))
        // 超出域上限的坏值仍然钳在最后一像素
        assertEquals(1971, TouchDomainMapping.map(3000, viewW, dw))
    }

    @Test
    fun `现场域 Y 轴按 520 对 555 映射`() {
        val dh = TouchDomainMapping.domainFor(viewH, observedMax = 555, enabled = true)
        assertEquals(555, dh)
        // 日志里那笔 (2705,509)：Y 从「域内直通 509」变成 509*519/555 = 475
        assertEquals(475, TouchDomainMapping.map(509, viewH, dh))
        assertEquals(519, TouchDomainMapping.map(555, viewH, dh))
    }

    // ---- 防御：离谱的观测值不参与映射 ----

    @Test
    fun `观测值超过视图四倍视为坏帧不映射`() {
        val absurd = viewW * TouchDomainMapping.MAX_EXPAND_RATIO + 1
        assertEquals(viewW, TouchDomainMapping.domainFor(viewW, absurd, enabled = true))
    }

    @Test
    fun `视图或域非法时原样返回`() {
        assertEquals(100, TouchDomainMapping.map(100, viewSize = 0, domain = 2705))
        assertEquals(100, TouchDomainMapping.map(100, viewSize = 1972, domain = 0))
        assertEquals(viewW, TouchDomainMapping.domainFor(viewW, observedMax = 0, enabled = true))
        assertEquals(0, TouchDomainMapping.domainFor(viewSize = 0, observedMax = 2705, enabled = true))
    }
}
