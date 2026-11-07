package io.github.shaonialife.carptocar.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 协议一致性审计：去重、分级、汇总、输出格式。 */
class ProtocolAuditTest {
    @Before
    fun setUp() {
        ProtocolAudit.reset()
        ProtocolAudit.sink = null
    }

    @After
    fun tearDown() {
        ProtocolAudit.reset()
        ProtocolAudit.sink = null
    }

    @Test
    fun emptyAuditReportsPass() {
        assertTrue(ProtocolAudit.isEmpty())
        assertFalse(ProtocolAudit.hasError())
        assertTrue(ProtocolAudit.summary().contains("通过"))
    }

    @Test
    fun sameCodeSameDetailRecordedOnce() {
        // 链路会反复触发同一个问题（0x5702 重发、端点热更新），日志刷屏没有价值
        assertNotNull(ProtocolAudit.record(ProtocolAudit.Code.LEGACY_WIFI_SILENT, "0x5702 无应答"))
        assertNull(ProtocolAudit.record(ProtocolAudit.Code.LEGACY_WIFI_SILENT, "0x5702 无应答"))
        assertEquals(1, ProtocolAudit.snapshot().size)
    }

    @Test
    fun sameCodeDifferentDetailBothKept() {
        // 「发了哪些未声明可收的消息」每条都是独立事实，不能因为同码被吞掉
        ProtocolAudit.record(ProtocolAudit.Code.UNSOLICITED_MESSAGE, "0x4e0e")
        ProtocolAudit.record(ProtocolAudit.Code.UNSOLICITED_MESSAGE, "0x4e0d")
        assertEquals(2, ProtocolAudit.snapshot().size)
    }

    @Test
    fun severityCountsAndHasError() {
        ProtocolAudit.record(ProtocolAudit.Code.FEATURES_TXT_VS_INFO, "/info=0x61 TXT=0x60")
        ProtocolAudit.record(ProtocolAudit.Code.LEGACY_WIFI_SILENT, "0x5702 无应答")
        ProtocolAudit.record(ProtocolAudit.Code.ERA_SWITCHED, "LEGACY → NEW")
        assertEquals(1, ProtocolAudit.count(ProtocolAudit.Severity.ERROR))
        assertEquals(1, ProtocolAudit.count(ProtocolAudit.Severity.WARN))
        assertEquals(1, ProtocolAudit.count(ProtocolAudit.Severity.INFO))
        assertTrue(ProtocolAudit.hasError())
        val summary = ProtocolAudit.summary()
        assertTrue(summary.contains("1 错误"))
        assertTrue(summary.contains("FEATURES_TXT_VS_INFO"))
    }

    @Test
    fun formatCarriesSpecReference() {
        val issue =
            ProtocolAudit.record(
                ProtocolAudit.Code.DEVICE_ID_MISMATCH,
                detail = "/info deviceID=A，TXT deviceid=B",
            )
        val line = ProtocolAudit.format(issue!!)
        // 排查时「依据」必须能直接翻到规范章节，否则日志只是一句抱怨
        assertTrue(line.startsWith("PROTO! ERROR [DEVICE_ID_MISMATCH]"))
        assertTrue(line.contains("依据 SPEC §3.2"))
    }

    @Test
    fun sinkReceivesOnlyNewIssues() {
        val lines = ArrayList<String>()
        ProtocolAudit.sink = { lines += it }
        ProtocolAudit.record(ProtocolAudit.Code.ERA_SWITCHED, "LEGACY → NEW")
        ProtocolAudit.record(ProtocolAudit.Code.ERA_SWITCHED, "LEGACY → NEW")
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("PROTO!"))
    }

    @Test
    fun resetClearsEverything() {
        ProtocolAudit.record(ProtocolAudit.Code.LEGACY_WIFI_SILENT, "0x5702 无应答")
        ProtocolAudit.reset()
        assertTrue(ProtocolAudit.isEmpty())
        assertTrue(ProtocolAudit.render().contains("通过"))
    }
}
