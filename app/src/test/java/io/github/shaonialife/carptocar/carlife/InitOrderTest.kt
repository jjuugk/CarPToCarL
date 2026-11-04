package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首轮初始化顺序的闸门（[CarLifeHuClient.shouldDeferHotResend]）。
 *
 * 规则只有一条，但它决定了这台车有没有画面：
 * **98386（DISABLE_RESOLUTION_OPTION=1 分辨率直通）必须先于第一条 98311 被引擎确认**，
 * 否则引擎按白名单定档 1280×720，之后整轮会话再怎么重发都回 720p。
 *
 * 而车机 `/info` 一到就会调 `setRequestedResolution`，它原来 300ms 就抢跑发 98311
 * ——正好卡在「98386 已发、还没确认」的窗口里。这个测试把「不许抢跑」钉死。
 */
class InitOrderTest {

    @Test
    fun `有序模式下首条 98311 未发出时必须让位`() {
        assertTrue(CarLifeHuClient.shouldDeferHotResend(orderedInit = true, firstInitSent = false))
    }

    @Test
    fun `有序模式下首条已发出后可以直接重发`() {
        assertFalse(CarLifeHuClient.shouldDeferHotResend(orderedInit = true, firstInitSent = true))
    }

    @Test
    fun `关掉有序模式回到老行为（热更新立刻重发）`() {
        assertFalse(CarLifeHuClient.shouldDeferHotResend(orderedInit = false, firstInitSent = false))
        assertFalse(CarLifeHuClient.shouldDeferHotResend(orderedInit = false, firstInitSent = true))
    }
}
