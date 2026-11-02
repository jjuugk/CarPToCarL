package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 链路签名是「WiFi 到底动没动」的唯一判据：判错会让人把断链归因到错的地方
 * （2026-11-02 现场排查正是卡在这一步），所以边界全部钉死。
 */
class WifiLinkWatchdogTest {
    @Test
    fun sameStateProducesSameSignature() {
        val a = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), "wlan0/192.168.1.5", "wlan0/192.168.1.5", "Audi_5GHz")
        val b = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), "wlan0/192.168.1.5", "wlan0/192.168.1.5", "Audi_5GHz")
        assertEquals(a, b)
    }

    @Test
    fun wifiOrderDoesNotMatter() {
        // 回调到达顺序不固定（onAvailable 与心跳线程并发），签名必须与顺序无关，
        // 否则每次轮询都误报「变了一次」。
        val first = WifiLinkSignature.of(listOf("wlan0/192.168.1.5", "wlan1/10.0.0.2"), null, null, null)
        val second = WifiLinkSignature.of(listOf("wlan1/10.0.0.2", "wlan0/192.168.1.5"), null, null, null)
        assertEquals(first, second)
    }

    @Test
    fun hotspotSwitchChangesSignature() {
        // 换频段 / 热点重启：接口还是 wlan0，地址变了——这正是要靠签名抓住的那件事
        val before = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), "wlan0/192.168.1.5", "wlan0/192.168.1.5", "Audi_5GHz")
        val after = WifiLinkSignature.of(setOf("wlan0/192.168.1.20"), "wlan0/192.168.1.20", "wlan0/192.168.1.20", "Audi_5GHz")
        assertNotEquals(before, after)
    }

    @Test
    fun carNetworkLostIsVisible() {
        // 车机专属网络掉线、WiFi 还在（其它热点）：只有 car 位变化，也必须被记下
        val bound = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), "wlan0/192.168.1.5", "wlan0/192.168.1.5", "Audi_5GHz")
        val lost = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), null, "wlan0/192.168.1.5", "Audi_5GHz")
        assertNotEquals(bound, lost)
        assertEquals(true, lost.contains("car=-"))
    }

    @Test
    fun defaultNetworkChangeIsVisible() {
        // bindProcessToNetwork 被别的网络顶掉：回调不报，只有默认网络变了
        val a = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), null, "wlan0/192.168.1.5", null)
        val b = WifiLinkSignature.of(setOf("wlan0/192.168.1.5"), null, "rmnet0/10.1.2.3", null)
        assertNotEquals(a, b)
    }

    @Test
    fun emptyStateIsReadable() {
        val signature = WifiLinkSignature.of(emptySet(), null, null, null)
        assertEquals("wifi=[] car=- default=- ssid=-", signature)
    }
}
