package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x1D01 能力清单日志增强（纯函数，不碰链路）。
 *
 * 这些断言保护的是**排查可用性**，不是协议行为：MIB3 4398 之后判断「车机收不收
 * CarPlay 导航数据」，全靠这几行日志。措辞一丢，现场就只剩一串不加解释的 ID。
 */
class Iap2CapabilityReportTest {

    private val lines = mutableListOf<String>()

    private fun report(
        kind: String,
        ids: List<Int>,
        isReceiveList: Boolean,
    ) = Iap2CapabilityReport.report({ lines += it }, "iap2 0x1d01 ", kind, ids, isReceiveList)

    @Test
    fun routeGuidanceIdsFallInNavigationGroup() {
        // 0x5200 是目录里已确认的 StartRouteGuidanceUpdates；
        // 4398 若新增导航能力，新号也落在 0x52xx 段，必须归到同一组才看得出规律
        assertEquals(Iap2CapabilityReport.Group.NAVIGATION, Iap2CapabilityReport.groupOf(0x5200))
        assertEquals(Iap2CapabilityReport.Group.NAVIGATION, Iap2CapabilityReport.groupOf(0x5203))
        assertEquals(Iap2CapabilityReport.Group.NAVIGATION, Iap2CapabilityReport.groupOf(0x52FF))
    }

    @Test
    fun otherGroupsStaySeparate() {
        assertEquals(Iap2CapabilityReport.Group.HID, Iap2CapabilityReport.groupOf(0x6800))
        assertEquals(Iap2CapabilityReport.Group.HID, Iap2CapabilityReport.groupOf(0x6802))
        assertEquals(Iap2CapabilityReport.Group.MEDIA, Iap2CapabilityReport.groupOf(0x5000))
        assertEquals(Iap2CapabilityReport.Group.WIRELESS, Iap2CapabilityReport.groupOf(0x4E0C))
        assertEquals(Iap2CapabilityReport.Group.WIRELESS, Iap2CapabilityReport.groupOf(0x5702))
        assertEquals(Iap2CapabilityReport.Group.IDENT, Iap2CapabilityReport.groupOf(0xAA00))
        assertEquals(Iap2CapabilityReport.Group.TELEPHONY, Iap2CapabilityReport.groupOf(0x4157))
        assertEquals(Iap2CapabilityReport.Group.EA, Iap2CapabilityReport.groupOf(0xEA00))
        // 边界：0x51FF 不是导航，0x5300 也不是
        assertEquals(Iap2CapabilityReport.Group.UNKNOWN, Iap2CapabilityReport.groupOf(0x51FF))
        assertEquals(Iap2CapabilityReport.Group.UNKNOWN, Iap2CapabilityReport.groupOf(0x5300))
    }

    @Test
    fun describeUsesCatalogueNameOnlyWhenKnown() {
        assertEquals("0x5200(StartRouteGuidanceUpdates)", Iap2CapabilityReport.describe(0x5200))
        // 规范里有、但本项目从没用过的号，也必须叫得出名字——否则「陌生 ID」会把
        // 车机的正常声明误报成新东西（0xAD00 曾在旧实现里被当成陌生号）
        assertTrue(Iap2CapabilityReport.hasName(0xAD00))
        assertEquals("0xad00(StartAppDiscoveryUpdates)", Iap2CapabilityReport.describe(0xAD00))
        // 规范里没有的号：不能编名字，原样输出
        assertFalse(Iap2CapabilityReport.hasName(0x4D00))
        assertEquals("0x4d00", Iap2CapabilityReport.describe(0x4D00))
    }

    @Test
    fun receiveListWithRouteGuidanceAnnouncesEntryPoint() {
        report("可收清单", listOf(0x5200, 0x5203, 0x6802), isReceiveList = true)

        // 第 1 层：原始清单必须逐项可 grep
        assertTrue(lines.any { it.contains("车机可收清单(3)=0x5200 0x5203 0x6802") })
        // 第 2 层：分组
        assertTrue(lines.any { it.contains("可收清单·导航引导(2)=") })
        assertTrue(lines.any { it.contains("可收清单·HID/输入(1)=0x6802") })
        // 导航专判：这是本次唯一想看到的那一行
        assertTrue(lines.any { it.contains("★") && it.contains("复现入口") })
    }

    @Test
    fun receiveListWithoutRouteGuidanceSaysNoArrow() {
        report("可收清单", listOf(0x5000, 0x6802), isReceiveList = true)

        assertTrue(lines.any { it.contains("无导航引导(0x52xx)") })
        assertTrue(lines.any { it.contains("不会出箭头") })
    }

    @Test
    fun sendListDoesNotClaimCarWillRefuse() {
        // 发送清单是「车机会发什么」。里面没有 0x52xx 不代表车机拒绝导航数据——
        // 措辞必须只在可收清单上说「不接受」，否则会把人带偏
        report("发送清单", listOf(0x5000, 0x6802), isReceiveList = false)

        assertFalse(lines.any { it.contains("不接受") })
        assertFalse(lines.any { it.contains("不会出箭头") })
    }

    @Test
    fun sendListWithRouteGuidancePointsAtPayloadSample() {
        report("发送清单", listOf(0x5200), isReceiveList = false)

        assertTrue(lines.any { it.contains("★") && it.contains("含导航引导(1)") })
        assertTrue(lines.any { it.contains("报文体") })
    }

    @Test
    fun unknownIdsAreListedSeparately() {
        // 0x4D00 / 0x4D03 不在 iAP2 规范里（4C=媒体库、4E=设备状态，4D 是空段）
        report("可收清单", listOf(0x5200, 0x4D00, 0x4D03), isReceiveList = true)

        assertTrue(lines.any { it.contains("陌生 ID(2)=0x4d00 0x4d03") })
        // 有名字的不能被算进陌生 ID
        assertFalse(lines.any { it.contains("陌生 ID(3)") })
    }

    @Test
    fun emptyListIsSaidOutLoud() {
        report("可收清单", emptyList(), isReceiveList = true)

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("空"))
    }

    @Test
    fun everyGroupGetsItsOwnLine() {
        report("可收清单", listOf(0x1D01, 0x4E0C, 0x5200, 0x5000, 0x4157, 0xAE00, 0x6802, 0xEA00), isReceiveList = true)

        // 8 个类各一行：单行几十个 ID 会被日志长度截断，分组就是为了避免这个
        val groupLines = lines.filter { it.contains("可收清单·") }
        assertEquals(8, groupLines.size)
    }
}
