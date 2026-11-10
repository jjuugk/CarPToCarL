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

    // ---- 2026-11-07：通用系（Cinemo 栈）先试「双标识体」 ----

    @Test
    fun cinemoReceiverPrefersDualGroupsBody() {
        val key = "buick_8948 e2lb saic general motors dual_${System.nanoTime()}"
        assertEquals(
            AvailabilityBodyMemory.Variant.DUAL_GROUPS,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true),
        )
    }

    @Test
    fun nonCinemoReceiverKeepsDefaultLadder() {
        val key = "bmw_92438 g08 bmw dual_${System.nanoTime()}"
        assertEquals(
            AvailabilityBodyMemory.Variant.MODERN_NESTED,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = false),
        )
    }

    @Test
    fun silentDualGroupsFallsBackToModernNested() {
        val key = "cadillac_7731 a2ll general motors dual_${System.nanoTime()}"
        val next =
            AvailabilityBodyMemory.recordSilent(
                null,
                key,
                AvailabilityBodyMemory.Variant.DUAL_GROUPS,
                preferDual = true,
            )
        assertEquals(AvailabilityBodyMemory.Variant.MODERN_NESTED, next)
        assertEquals(
            AvailabilityBodyMemory.Variant.MODERN_NESTED,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true),
        )
        // 已经记过「双标识静默」的车机，日志里要能看到它（否则排查时以为阶梯没走）
        assertEquals(
            AvailabilityBodyMemory.Variant.DUAL_GROUPS.name,
            AvailabilityBodyMemory.silentDescription(null, key),
        )
    }

    @Test
    fun dualGroupsSuccessIsPinnedAndReadBack() {
        val key = "buick_8948 e2lb saic general motors pin_${System.nanoTime()}"
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.DUAL_GROUPS, preferDual = true)
        // 车机终于回 0x4301 → 定档双标识体，并清掉静默记录
        AvailabilityBodyMemory.recordSuccess(null, key, AvailabilityBodyMemory.Variant.DUAL_GROUPS)
        assertEquals(
            AvailabilityBodyMemory.Variant.DUAL_GROUPS,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true),
        )
        assertEquals("无", AvailabilityBodyMemory.silentDescription(null, key))
    }

    // ---- 2026-11-10 BUICK_3138：声明能收 0x4300 的车机不许被静默记忆堵死 ----

    @Test
    fun declaringReceiverIsNeverSilencedByHistory() {
        val key = "buick_3138 b233 saic general motors silent_${System.nanoTime()}"
        // 这台车的真实状态：三种形态各被判过一次静默 → 旧逻辑每轮都不发 0x4300
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.DUAL_GROUPS, preferDual = true)
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.MODERN_NESTED, preferDual = true)
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.LEGACY_FLAT, preferDual = true)
        assertEquals(
            "未声明可收时才允许退到「不发」",
            AvailabilityBodyMemory.Variant.NONE,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true),
        )
        // 可收清单里有 0x4300 → 必须回到首选形态继续发：不发等于关掉唯一的热点交换入口
        assertEquals(
            AvailabilityBodyMemory.Variant.DUAL_GROUPS,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true, acceptsAvailability = true),
        )
    }

    @Test
    fun declaringReceiverWithPartialSilentHistoryStillEscalates() {
        val key = "buick_3138 b233 saic general motors partial_${System.nanoTime()}"
        AvailabilityBodyMemory.recordSilent(null, key, AvailabilityBodyMemory.Variant.DUAL_GROUPS, preferDual = true)
        // 只有第一档被判过静默 → 仍然往下一档走，不是一律回到首选（否则阶梯白设）
        assertEquals(
            AvailabilityBodyMemory.Variant.MODERN_NESTED,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true, acceptsAvailability = true),
        )
    }

    @Test
    fun pinnedNoSendConclusionOutranksDeclaringReceiver() {
        val key = "audi_mmi_9310 au_d5 saic_${System.nanoTime()}"
        // 「不发 0x4300 也拿到了 0x4301」是有实证的结论，优先级高于「声明可收」
        AvailabilityBodyMemory.recordSuccess(null, key, AvailabilityBodyMemory.Variant.NONE)
        assertEquals(
            AvailabilityBodyMemory.Variant.NONE,
            AvailabilityBodyMemory.nextVariant(null, key, preferDual = true, acceptsAvailability = true),
        )
    }
}
