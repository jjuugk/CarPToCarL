package io.github.shaonialife.carptocar.session

import io.github.shaonialife.carptocar.session.ConnectionPreflight.Check
import io.github.shaonialife.carptocar.session.ConnectionPreflight.Requirement
import io.github.shaonialife.carptocar.session.ConnectionPreflight.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体检结论的呈现约定（不碰 Android API，只测 Report 这一层）。
 *
 * 这些文案会直接进日志，是排障时唯一能一眼看到的「全景」，所以「缺什么、卡在哪一档」
 * 必须有稳定写法：`○` = 阻塞缺项，`·` = 会降级，`✓` = 通过。
 */
class ConnectionPreflightReportTest {
    private fun report(vararg checks: Check) = Report(checks.toList())

    @Test
    fun allReadyReportsAllPassed() {
        val subject =
            report(
                Check(Requirement.BLUETOOTH_PERMISSION, true),
                Check(Requirement.NOTIFICATIONS, true),
            )
        assertTrue(subject.ready)
        assertEquals("2/2 项就绪，全部通过", subject.headline())
        assertTrue(subject.describe().first().startsWith("PREFLIGHT: 连接前体检"))
        assertFalse(subject.describe().any { it.contains("阻塞项缺失") })
    }

    @Test
    fun blockingMissBlocksAndIsCalledOutByName() {
        val subject =
            report(
                Check(Requirement.BLUETOOTH_ENABLED, false, "已关闭"),
                Check(Requirement.WIFI_ENABLED, true),
            )
        assertFalse(subject.ready)
        assertEquals(listOf(Requirement.BLUETOOTH_ENABLED), subject.blockingMissing.map { it.requirement })
        assertEquals("1/2 项就绪，缺 手机蓝牙开关", subject.headline())
        assertTrue(subject.describe().any { it.contains("阻塞项缺失（手机蓝牙开关）") })
    }

    @Test
    fun warningOnlyStillCountsAsReady() {
        // 位置服务关着只降级（读不到 SSID），不能因此拦住连接——否则又多一个自家弹窗。
        val subject =
            report(
                Check(Requirement.BLUETOOTH_ENABLED, true),
                Check(Requirement.LOCATION_SERVICE, false, "已关闭"),
            )
        assertTrue(subject.ready)
        assertEquals(1, subject.warnings.size)
        assertEquals("1/2 项就绪，阻塞项全过（注意 位置服务）", subject.headline())
    }

    @Test
    fun describeUsesDistinctMarksPerSeverity() {
        val lines =
            report(
                Check(Requirement.BLUETOOTH_ENABLED, true),
                Check(Requirement.WIFI_ENABLED, false),
                Check(Requirement.LOCATION_SERVICE, false),
            ).describe()
        assertTrue(lines.any { it.contains("✓ 手机蓝牙开关") })
        assertTrue(lines.any { it.contains("○ 手机 WLAN 开关") })
        assertTrue(lines.any { it.contains("· 位置服务") })
    }

    @Test
    fun detailIsAppendedAfterDash() {
        val line =
            report(Check(Requirement.IDENTITY, true, "→ AA:BB:CC:DD:EE:FF"))
                .describe()
                .first { it.contains("本机蓝牙地址") }
        assertTrue(line.endsWith("——→ AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun signatureIgnoresWorkingItemsAndDetails() {
        val a =
            report(
                Check(Requirement.BLUETOOTH_ENABLED, false, "已关闭"),
                Check(Requirement.WIFI_ENABLED, true, "→ wlan0"),
            )
        val b =
            report(
                Check(Requirement.BLUETOOTH_ENABLED, false, "另一种措辞"),
                Check(Requirement.WIFI_ENABLED, true),
            )
        // 节流靠它：同一批未通过项在 20 秒内只打一行摘要，文案变化不该破功
        assertEquals(a.signature(), b.signature())
        assertEquals("BLUETOOTH_ENABLED:BLOCKING", a.signature())
    }
}
