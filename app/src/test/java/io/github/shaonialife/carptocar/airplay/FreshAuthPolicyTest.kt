package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只复用网络、认证重做」的失败计数。
 *
 * 参考实现 OcarPlay 在复用 WLAN 时声明
 * `fresh_authentication_required=true`，并配 `no_fresh_integrity_permit` 拦下新会话：
 * 网络可以复用，认证不能。放到我们这条链路上，可继承的只剩 [PairingMemory]
 * 那条「车机确定性拒绝配对，15 分钟内别再试」的记忆——它一旦过时，就会把本来
 * 已经恢复的车机一直按「跳过认证」连，越连越连不上。
 *
 * 所以这里的规则要钉死：**同一接收端连续两轮没建立会话 → 下一轮强制重做认证**。
 */
class FreshAuthPolicyTest {
    @Test
    fun twoConsecutiveFailuresTriggerRedo() {
        val policy = FreshAuthPolicy(failureThreshold = 2)
        assertFalse("第一轮失败只是计数", policy.onResult("pk-a", established = false))
        assertTrue("第二轮失败必须触发下一轮重做认证", policy.onResult("pk-a", established = false))
        assertTrue("重做标记是一次性的，本轮读到就消费", policy.consumeRedo("pk-a"))
        assertFalse("消费后不能反复触发", policy.consumeRedo("pk-a"))
    }

    @Test
    fun successClearsFailures() {
        val policy = FreshAuthPolicy(failureThreshold = 2)
        policy.onResult("pk-a", established = false)
        policy.onResult("pk-a", established = true)
        assertFalse("中间成功过就不算连续失败", policy.onResult("pk-a", established = false))
        assertEquals("连续计数已归零", 1, policy.failuresFor("pk-a"))
    }

    @Test
    fun switchingReceiverResetsEverything() {
        val policy = FreshAuthPolicy(failureThreshold = 2)
        policy.onResult("pk-a", established = false)
        // 换了另一台车机：上一台的失败与待重做标记都不该带过来。
        assertEquals(0, policy.failuresFor("pk-b"))
        assertFalse(policy.consumeRedo("pk-b"))
    }

    @Test
    fun redoIsNotStickyAfterBeingConsumed() {
        val policy = FreshAuthPolicy(failureThreshold = 2)
        policy.onResult("pk-a", established = false)
        policy.onResult("pk-a", established = false)
        policy.consumeRedo("pk-a")
        // 重做那一轮若又失败，重新从 1 开始数，不能每轮都硬走 120s 配对窗口。
        assertFalse(policy.onResult("pk-a", established = false))
        assertTrue(policy.onResult("pk-a", established = false))
    }
}
