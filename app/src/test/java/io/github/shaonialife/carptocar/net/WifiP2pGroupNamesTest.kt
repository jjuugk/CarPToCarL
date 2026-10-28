package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2P 组名判定。
 *
 * 这条判定的作用不只是「多一条路径」：命中后会把连接从应用专属网络切到
 * WifiP2pManager。**误判的代价**是给普通热点白跑一轮 P2P（浪费十几秒且必然失败），
 * 所以 `DIRECT` 但格式异常的组名要能稳妥落到「不是 P2P」。
 */
class WifiP2pGroupNamesTest {
    @Test
    fun recognizesDirectPrefix() {
        assertTrue(WifiP2pGroupNames.isP2pGroupName("DIRECT-Ab-AudiPhone"))
        // 大小写在不同 ROM 上确实不一致
        assertTrue(WifiP2pGroupNames.isP2pGroupName("direct-Ab-AudiPhone"))
    }

    @Test
    fun rejectsOrdinaryHotspots() {
        assertFalse(WifiP2pGroupNames.isP2pGroupName("Audi MIB3 5G"))
        assertFalse(WifiP2pGroupNames.isP2pGroupName("DIRECTLY-误伤"))
        assertFalse(WifiP2pGroupNames.isP2pGroupName(""))
    }

    @Test
    fun extractsDeviceNameAfterTwoCharSuffix() {
        assertEquals("AudiPhone", WifiP2pGroupNames.deviceNameOf("DIRECT-Ab-AudiPhone"))
        assertEquals("AudiPhone", WifiP2pGroupNames.deviceNameOf("DIRECT-7F-AudiPhone"))
    }

    @Test
    fun keepsWholeTailWhenNoSuffixSeparator() {
        // 有的实现就是 DIRECT-名字
        assertEquals("AudiPhone", WifiP2pGroupNames.deviceNameOf("DIRECT-AudiPhone"))
    }

    @Test
    fun returnsNullForNonP2pName() {
        assertNull(WifiP2pGroupNames.deviceNameOf("Audi MIB3 5G"))
    }
}
