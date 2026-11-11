package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机热点的五种状态必须能分开。
 *
 * 钉死这件事的原因很具体：2026-11-11 那几轮别克的日志里，「手机已经挂在车机热点上但
 * 进程没绑」「关联上了但 DHCP 没回来」「真的没连任何 WiFi」三种现场全写成同一句
 * 「绑定当前 WiFi 失败」，事后只能靠猜——而这三者的正确处置完全不同（绑一下 / 等两秒 /
 * 重新入网）。
 */
class CarNetworkReadinessTest {
    private fun snapshot(
        expected: String? = "BUICK_3767",
        bound: String? = null,
        associated: String? = null,
        hasBound: Boolean = false,
        hasAddress: Boolean = false,
        associatedReady: Boolean = false,
    ) = CarNetworkSnapshot(
        expectedSsid = expected,
        boundSsid = bound,
        associatedSsid = associated,
        hasBoundNetwork = hasBound,
        hasAddress = hasAddress,
        associatedReady = associatedReady,
    )

    @Test
    fun boundAndAddressedIsReady() {
        val subject =
            CarNetworkReadiness.inspect(
                snapshot(bound = "BUICK_3767", associated = "BUICK_3767", hasBound = true, hasAddress = true),
            )
        assertEquals(CarNetworkReadiness.State.READY, subject.state)
        assertTrue(subject.detail.contains("BUICK_3767"))
    }

    @Test
    fun absentIsNotTheSameAsNotReady() {
        val absent = CarNetworkReadiness.inspect(snapshot())
        assertEquals(CarNetworkReadiness.State.ABSENT, absent.state)

        // 关联上了但没地址：以前和 absent 长得一模一样，现在必须是 NOT_READY
        val notReady =
            CarNetworkReadiness.inspect(
                snapshot(associated = "BUICK_3767", associatedReady = false),
            )
        assertEquals(CarNetworkReadiness.State.NOT_READY, notReady.state)
        assertTrue(notReady.detail.contains("DHCP"))
        assertFalse(notReady.ready)
    }

    @Test
    fun associatedButNotBoundNeedsOnlyOneBind() {
        val subject =
            CarNetworkReadiness.inspect(
                snapshot(associated = "BUICK_3767", associatedReady = true),
            )
        assertEquals(CarNetworkReadiness.State.ADOPT, subject.state)
        assertTrue(subject.detail.contains("绑定即可用"))
    }

    @Test
    fun boundToAnotherNetworkIsWrongNetwork() {
        // 「手机提前挂在车机热点上」之外的另一半现场：系统把我们绑在了家里那条上
        val subject =
            CarNetworkReadiness.inspect(
                snapshot(bound = "HomeWiFi", associated = "HomeWiFi", hasBound = true, hasAddress = true),
            )
        assertEquals(CarNetworkReadiness.State.WRONG_NETWORK, subject.state)
        assertTrue(subject.detail.contains("HomeWiFi"))
        assertTrue(subject.detail.contains("BUICK_3767"))
    }

    @Test
    fun boundWithoutAddressIsNotReadyNotAbsent() {
        val subject =
            CarNetworkReadiness.inspect(snapshot(bound = "BUICK_3767", hasBound = true, hasAddress = false))
        assertEquals(CarNetworkReadiness.State.NOT_READY, subject.state)
    }

    @Test
    fun unknownSsidStillCountsAsReadyWhenAddressed() {
        // 读不到 SSID（缺权限）时不能猜：有绑定有地址就是能用的最好状态
        val subject =
            CarNetworkReadiness.inspect(
                snapshot(expected = null, bound = null, hasBound = true, hasAddress = true),
            )
        assertEquals(CarNetworkReadiness.State.READY, subject.state)
        assertTrue(subject.detail.contains("有地址"))
    }

    @Test
    fun readableButUnnamedBoundNetworkIsReportedHonestly() {
        val subject =
            CarNetworkReadiness.inspect(
                snapshot(bound = null, hasBound = true, hasAddress = true),
            )
        assertEquals(CarNetworkReadiness.State.READY, subject.state)
        assertTrue(subject.detail.contains("无法核对"))
    }

    @Test
    fun summarizeKeepsStateAndDetailTogether() {
        val observation = CarNetworkReadiness.inspect(snapshot())
        val line = CarNetworkReadiness.summarize(observation)
        assertTrue(line.startsWith(CarNetworkReadiness.State.ABSENT.label))
        assertTrue(line.contains("没连任何 WiFi"))
    }
}
