package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `0x4300` 报文体的按车机试错阶梯。
 *
 * 背景（2026-10-26 别克 E2LB 实测）：同一份新版嵌套体，凯迪拉克正常 ACK，别克收到后
 * 整个控制会话停止 ACK（只剩链路心跳），23 秒后被重传看门狗拆链。既然无法离线判断
 * 哪台车吃哪种形态，就只能「一次连接试一种」，且必须在**没拿到 0x4301 也不能证明
 * 形态有错**（这个家族从来不发 0x4301）的前提下，用「对端是否还回 ACK」作为唯一判据。
 *
 * 这里只测纯排序/记忆逻辑（无 Context 走进程内表）：默认新版 → 判静默后换老版 →
 * 再静默就不发；一旦成功即定档并清空静默记录。
 */
class AvailabilityBodyMemoryTest {
    private val buick = "buick_3799 e2lb saic general motors"

    @Test
    fun freshReceiverStartsWithModernNestedBody() {
        assertEquals(
            AvailabilityBodyMemory.Variant.MODERN_NESTED,
            AvailabilityBodyMemory.nextVariant(null, "fresh_${System.nanoTime()}"),
        )
    }

    @Test
    fun silentModernEscalatesToLegacyThenNone() {
        val key = "$buick ${System.nanoTime()}"
        assertEquals(AvailabilityBodyMemory.Variant.MODERN_NESTED, AvailabilityBodyMemory.nextVariant(null, key))

        // 新版嵌套体判静默 → 下一轮换老版平铺体
        val next = AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.MODERN_NESTED)
        assertEquals(AvailabilityBodyMemory.Variant.LEGACY_FLAT, next)
        assertEquals(AvailabilityBodyMemory.Variant.LEGACY_FLAT, AvailabilityBodyMemory.nextVariant(null, key))

        // 老版也静默 → 干脆不发，只等车机自己开口
        val last = AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.LEGACY_FLAT)
        assertEquals(AvailabilityBodyMemory.Variant.NONE, last)
        assertEquals(AvailabilityBodyMemory.Variant.NONE, AvailabilityBodyMemory.nextVariant(null, key))

        // 「不发」这一档没有再退的空间（不能再被记为静默并回绕到新版）
        assertEquals(
            AvailabilityBodyMemory.Variant.NONE,
            AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.NONE),
        )
    }

    @Test
    fun successPinsVariantAndClearsSilentHistory() {
        val key = "$buick ok_${System.nanoTime()}"
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.MODERN_NESTED)
        assertEquals(AvailabilityBodyMemory.Variant.LEGACY_FLAT, AvailabilityBodyMemory.nextVariant(null, key))

        // 老版拿到 0x4301 → 定档老版，并清掉「新版本静默」的旧结论
        AvailabilityBodyMemory.recordSuccess(null, key, AvailabilityBodyMemory.Variant.LEGACY_FLAT)
        assertEquals(AvailabilityBodyMemory.Variant.LEGACY_FLAT, AvailabilityBodyMemory.nextVariant(null, key))
        assertEquals("无", AvailabilityBodyMemory.silentDescription(null, key))
    }

    @Test
    fun overIntervalVariantIsReportedForLogs() {
        val key = "$buick desc_${System.nanoTime()}"
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.MODERN_NESTED)
        assertEquals(
            AvailabilityBodyMemory.Variant.MODERN_NESTED.name,
            AvailabilityBodyMemory.silentDescription(null, key),
        )
    }

    /**
     * 曾经成功定档的形态**又**静默时，定档必须作废、阶梯继续往前走。
     *
     * 2026-10-29 别克 E2LB 真机日志：`0x4300 报文体形态：新版嵌套体` 与
     * `（该车机历史静默档=新版嵌套体）` 同时出现——本轮选的正是已被判静默的那一档。
     * 修前 `recordSilent` 保留旧定档，`nextVariant` 优先返回它，于是每轮都在重复
     * 最坏的那一档，「老版平铺体」永远轮不到。
     */
    @Test
    fun silentAgainOnPinnedVariantClearsThePin() {
        val key = "$buick repin_${System.nanoTime()}"
        // 历史：新版嵌套体成功过一次 → 定档
        AvailabilityBodyMemory.recordSuccess(null, key, AvailabilityBodyMemory.Variant.MODERN_NESTED)
        assertEquals(AvailabilityBodyMemory.Variant.MODERN_NESTED, AvailabilityBodyMemory.nextVariant(null, key))

        // 现在它又静默了：定档作废，必须换到老版平铺体
        val next = AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.MODERN_NESTED)
        assertEquals(
            "定档形态再次静默时必须往下换档，不能原地打转",
            AvailabilityBodyMemory.Variant.LEGACY_FLAT,
            next,
        )
        assertEquals(AvailabilityBodyMemory.Variant.LEGACY_FLAT, AvailabilityBodyMemory.nextVariant(null, key))
    }
}
