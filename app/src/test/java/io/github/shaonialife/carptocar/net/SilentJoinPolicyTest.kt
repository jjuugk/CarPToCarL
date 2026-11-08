package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「静默入网」的尝试预算必须钉死。
 *
 * 判错的代价是时间：静默路径在**建议未被授权**时一定白等满窗口（系统不会自己连），
 * 而车机（BMW iD9 实测）只等约 27 秒就拆蓝牙链。预算太松 = 每轮白等十几秒、把窗口
 * 吃光、连车机的邀请都等不到；预算太紧 = 建议刚被授权、系统正要连上时我们却提前
 * 降级去弹确认框，静默等于白做。
 */
class SilentJoinPolicyTest {
    @Test
    fun freshSsidTriesSilentlyFirst() {
        val policy = SilentJoinPolicy(maxConsecutiveMisses = 2)
        assertTrue("第一次必须给静默路径机会", policy.shouldTrySilently("BMW94841 CarPlay"))
    }

    @Test
    fun givesUpAfterConfiguredMisses() {
        val policy = SilentJoinPolicy(maxConsecutiveMisses = 2)
        policy.recordResult("BMW94841 CarPlay", joined = false)
        assertTrue("第二次仍要给机会（可能刚被授权）", policy.shouldTrySilently("BMW94841 CarPlay"))
        policy.recordResult("BMW94841 CarPlay", joined = false)
        assertFalse("连续两次未成就不再白等", policy.shouldTrySilently("BMW94841 CarPlay"))
    }

    @Test
    fun successResetsBudget() {
        val policy = SilentJoinPolicy(maxConsecutiveMisses = 1)
        policy.recordResult("Audi_5GHz", joined = false)
        assertFalse(policy.shouldTrySilently("Audi_5GHz"))

        policy.recordResult("Audi_5GHz", joined = true)
        assertTrue("成功过就证明建议通道可用，之后每轮都应该先走静默", policy.shouldTrySilently("Audi_5GHz"))
        assertEquals(0, policy.missesFor("Audi_5GHz"))
    }

    @Test
    fun switchingSsidResetsBudget() {
        val policy = SilentJoinPolicy(maxConsecutiveMisses = 1)
        policy.recordResult("BMW94841 CarPlay", joined = false)
        assertFalse(policy.shouldTrySilently("BMW94841 CarPlay"))

        assertTrue("换了一台车机就是全新的一套建议，值得重新试", policy.shouldTrySilently("Audi_5GHz"))
    }

    @Test
    fun switchingBackAndForthStartsFromZero() {
        // 预算记在「当前 SSID 上连续失败多少次」，不是全局计数：两车间来回切时
        // 不能因为 A 失败过就把 B 的静默机会也算掉（反之亦然）。
        val policy = SilentJoinPolicy(maxConsecutiveMisses = 1)
        policy.recordResult("A", joined = false)
        policy.recordResult("B", joined = false)
        assertTrue(policy.shouldTrySilently("A"))
        assertTrue(policy.shouldTrySilently("B"))
    }
}
