package io.github.shaonialife.carptocar.iap2

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「这台车收不收封面」的记忆。
 *
 * 判定本身发生在隧道链路（`SETUP` 超时没有 `START`），这里只锁住它的状态语义：
 * 只判一次、判了就是判了、新一轮连接重置。
 */
class Iap2ArtworkSupportTest {
    @Before
    fun setUp() = Iap2ArtworkSupport.reset()

    @After
    fun tearDown() = Iap2ArtworkSupport.reset()

    @Test
    fun `默认允许声明封面编号`() {
        assertFalse(Iap2ArtworkSupport.unsupported)
        assertNull(Iap2ArtworkSupport.reason)
    }

    /** 只有第一次标记返回 true —— 调用方据此只打一行日志，不会每拍刷屏。 */
    @Test
    fun `只第一次标记返回 true`() {
        assertTrue(Iap2ArtworkSupport.markUnsupported("封面 SETUP id=1 等了 8000ms 没有 START"))
        assertFalse(Iap2ArtworkSupport.markUnsupported("再来一次"))
        assertTrue(Iap2ArtworkSupport.unsupported)
        assertEquals("理由保留第一次的，后来的不改写", "封面 SETUP id=1 等了 8000ms 没有 START", Iap2ArtworkSupport.reason)
    }

    /** 新一轮连接必须重新试：换链路/换车/换固件都可能不一样。 */
    @Test
    fun `重置后重新允许声明`() {
        Iap2ArtworkSupport.markUnsupported("x")
        Iap2ArtworkSupport.reset()
        assertFalse(Iap2ArtworkSupport.unsupported)
        assertTrue("重置后可以再判一次", Iap2ArtworkSupport.markUnsupported("y"))
    }
}
