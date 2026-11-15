package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案校正的判据：只认「加入车机热点」这一阶段的三种写法，别的一律不动。
 *
 * 为什么值得单测：`phaseHint` 是所有阶段共用的一个字符串，改错判据就会把
 * 「建立明文会话…」「等待车机发出新的连接邀请」这类正常文案也覆盖掉——那种
 * 倒退在日志里看不出来，只有坐在车里才看得到。
 */
class HotspotHintTest {
    @Test
    fun recognisesAllThreeJoinWordingVariants() {
        assertTrue(HotspotHint.isJoining("正在加入车机热点 DIRECT-BMW 42056…（不影响移动数据）"))
        assertTrue(HotspotHint.isJoining("正在自动加入车机热点 DIRECT-BMW 42056…（无需操作）"))
        assertTrue(HotspotHint.isJoining("正在重试加入车机热点 DIRECT-BMW 42056…（无需操作）"))
        assertTrue(HotspotHint.isJoining("正在加入车机热点 DIRECT-BMW 42056…若被切回原 WiFi，请在 WLAN 中手动连接"))
    }

    @Test
    fun leavesOtherStagesAlone() {
        assertFalse(HotspotHint.isJoining(null))
        assertFalse(HotspotHint.isJoining(""))
        assertFalse(HotspotHint.isJoining("已加入车机热点"))
        assertFalse(HotspotHint.isJoining("建立明文会话…"))
        assertFalse(HotspotHint.isJoining("等待车机发出新的连接邀请"))
        assertFalse(HotspotHint.isJoining("蓝牙就绪，等待车机回连…"))
        assertFalse(HotspotHint.isJoining("未能加入车机热点——下一轮连接会自动再试"))
    }

    @Test
    fun readyHintCarriesIpWhenKnown() {
        assertEquals("已连上车机热点（172.16.222.105），正在等车机应答…", HotspotHint.ready("172.16.222.105"))
    }

    @Test
    fun readyHintOmitsBracketsWhenIpUnknown() {
        // 读不到 IP 是常态（权限不足 / DHCP 还没下发），不能写成「已连上车机热点（null）」
        assertEquals("已连上车机热点，正在等车机应答…", HotspotHint.ready(null))
        assertEquals("已连上车机热点，正在等车机应答…", HotspotHint.ready(""))
        assertEquals("已连上车机热点，正在等车机应答…", HotspotHint.ready("   "))
    }

    @Test
    fun readyHintIsNotMistakenForJoining() {
        // 两个判据不能互相误伤：改口之后的文案再判一次必须是「不是入网中」，
        // 否则每读一次就重写一次，日志会被刷屏
        assertFalse(HotspotHint.isJoining(HotspotHint.ready("172.16.222.105")))
        assertFalse(HotspotHint.isJoining(HotspotHint.ready(null)))
    }
}
