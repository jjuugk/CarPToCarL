package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.protocol.ProtocolAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 车机 `0x1D01` 声明快照：跨链路合并、越权取证、只裁两条 MIB3 专用消息。
 *
 * 这些断言锁的是两条真机结论，别「顺手推广」：
 *  - 清单只能证明「有」不能证明「没有」→ 普通消息越权照发；
 *  - 0xA100 / 0xFFF0 是唯一有重启证据的例外 → 按声明硬裁，且不关整条隧道。
 */
class AccessoryDeclarationTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        AccessoryDeclaration.reset()
        ProtocolAudit.reset()
        lines.clear()
    }

    private fun note(
        id: Int,
        channel: String = "bt",
    ): Boolean = AccessoryDeclaration.noteSend(id, channel) { lines += it }

    private fun identification(
        receiveIds: Set<Int>?,
        sendIds: Set<Int>? = null,
        components: AccessoryDeclaration.Components = AccessoryDeclaration.Components(),
        source: String = "bt",
    ) = AccessoryDeclaration.onIdentification(sendIds, receiveIds, components, source)

    @Test
    fun noIdentificationMeansUnknownAndEverythingPasses() {
        assertNull(AccessoryDeclaration.declaresReceive(0xA100))
        assertNull(AccessoryDeclaration.acceptsRouteGuidance())
        assertTrue(note(0xA100))
        assertTrue(note(0xFFF0, "tunnel"))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun ordinaryUndeclaredMessageStillGoesOut() {
        identification(setOf(0x4300, 0x4E09))
        assertTrue(note(0x4E0D))
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("仍照常发送"))
    }

    @Test
    fun declaredMessagePassesWithoutAnyLog() {
        identification(setOf(0x4300, 0xA100))
        assertTrue(note(0xA100))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun mib3OnlyMessagesAreDroppedWhenNotDeclared() {
        identification(setOf(0x4300, 0x4E09, 0xFFFA))
        assertFalse(note(AccessoryDeclaration.MIB3_AUX_STATUS))
        assertFalse(note(AccessoryDeclaration.MIB3_GPRMC_STATUS, "tunnel"))
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.contains("裁掉") })
    }

    @Test
    fun mib3OnlyMessagesAreKeptWhenDeclared() {
        identification(setOf(0xA100, 0xFFF0))
        assertTrue(note(0xA100))
        assertTrue(note(0xFFF0, "tunnel"))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun emptyReceiveListIsDifferentFromMissingList() {
        identification(emptySet())
        assertFalse(AccessoryDeclaration.declaresReceive(0x4E09) ?: true)
        assertFalse(note(0xA100))
    }

    @Test
    fun twoLinksMergeTheirLists() {
        identification(setOf(0x4300), source = "bt")
        identification(setOf(0xA100), source = "tunnel")
        assertTrue(note(0x4300))
        assertTrue(note(0xA100))
        assertTrue(AccessoryDeclaration.describe().contains("来源=tunnel"))
    }

    @Test
    fun resetClearsEverythingIncludingDedup() {
        identification(setOf(0x4300))
        assertTrue(note(0x4E0D))
        assertEquals(1, lines.size)
        AccessoryDeclaration.reset()
        assertNull(AccessoryDeclaration.declaresReceive(0x4300))
        assertTrue(note(0xA100)) // 清单已清空：按未知放行，也不再被旧去重挡住
        assertEquals(1, lines.size)
    }

    @Test
    fun sameChannelAndIdIsLoggedOnlyOnce() {
        identification(setOf(0x4300))
        assertTrue(note(0x4E0D))
        assertTrue(note(0x4E0D))
        assertTrue(note(0x4E0D, "tunnel"))
        assertEquals(2, lines.size)
    }

    @Test
    fun routeGuidanceCapabilityFollowsTheReceiveList() {
        identification(setOf(0x5200, 0x5201))
        assertEquals(true, AccessoryDeclaration.acceptsRouteGuidance())
        identification(setOf(0x4300))
        // 并集语义：同轮另一条链路补进来的声明不会被覆盖掉
        assertEquals(true, AccessoryDeclaration.acceptsRouteGuidance())
        AccessoryDeclaration.reset()
        identification(setOf(0x4300))
        assertEquals(false, AccessoryDeclaration.acceptsRouteGuidance())
    }

    @Test
    fun parseComponentsReadsLanguageWirelessAndNavigationButNotEa() {
        val body =
            Iap2Csm.string(12, "zh-CN") +
                Iap2Csm.string(13, "zh") +
                Iap2Csm.string(13, "en") +
                Iap2Csm.param(24, Iap2Csm.u8(0, 3) + Iap2Csm.string(1, "WirelessCarPlay")) +
                Iap2Csm.param(30, Iap2Csm.u8(0, 7) + Iap2Csm.string(1, "HUD")) +
                // 参数 10 = EA 协议组：按需求不解析，出现也不该影响其它字段
                Iap2Csm.param(10, Iap2Csm.u8(0, 1) + Iap2Csm.string(1, "com.apple.external"))
        val c = AccessoryDeclaration.parseComponents(body)
        assertEquals("zh-CN", c.currentLanguage)
        assertEquals(listOf("zh", "en"), c.supportedLanguages)
        assertTrue(c.wirelessCarPlayDeclared)
        assertEquals(listOf("WirelessCarPlay"), c.wirelessCarPlayNames)
        assertTrue(c.navigationDisplayDeclared)
        assertEquals(listOf("HUD"), c.navigationDisplayNames)
        assertFalse(c.isEmpty)
    }

    @Test
    fun deviceLanguageUsesIso639OneCodeWhenDeclared() {
        assertEquals("zh", AccessoryDeclaration.normalizeIso639("zh-CN"))
        assertEquals("en", AccessoryDeclaration.normalizeIso639("en_US"))
        assertNull(AccessoryDeclaration.normalizeIso639("???"))
        assertEquals("zh-CN", AccessoryDeclaration.deviceLanguage("zh-CN")) // 无声明 → 兜底不动
        val c = AccessoryDeclaration.Components(currentLanguage = "de_DE")
        identification(setOf(0x4300), components = c)
        assertEquals("de", AccessoryDeclaration.deviceLanguage("zh-CN"))
    }
}
