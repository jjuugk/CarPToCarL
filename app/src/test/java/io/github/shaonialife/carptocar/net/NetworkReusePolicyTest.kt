package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.net.CarNetworkReadiness.State
import io.github.shaonialife.carptocar.net.NetworkReusePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态 → 动作的映射必须与「能不能复用」严格对应。
 *
 * 最容易做错的一条：把「网络在但没就绪」也当成「重新入网」。那会调用
 * `joinCarPlayNetwork`，而它第一行就是 `release()`——把正在建立的网络拆掉，
 * 于是「再等两秒就好」变成「白等一轮 15 秒超时」。
 */
class NetworkReusePolicyTest {
    private fun decide(
        expected: String? = "BUICK_3767",
        bound: String? = null,
        associated: String? = null,
        hasBound: Boolean = false,
        hasAddress: Boolean = false,
        associatedReady: Boolean = false,
    ): Decision =
        NetworkReusePolicy.decide(
            CarNetworkReadiness.inspect(
                CarNetworkSnapshot(
                    expectedSsid = expected,
                    boundSsid = bound,
                    associatedSsid = associated,
                    hasBoundNetwork = hasBound,
                    hasAddress = hasAddress,
                    associatedReady = associatedReady,
                ),
            ),
        )

    @Test
    fun readyReuses() {
        val subject = decide(bound = "BUICK_3767", hasBound = true, hasAddress = true)
        assertTrue(subject is Decision.Reuse)
        assertFalse(NetworkReusePolicy.shouldLog(subject))
    }

    @Test
    fun associatedOnlyAdoptsWithoutRejoining() {
        val subject = decide(associated = "BUICK_3767", associatedReady = true)
        assertTrue(subject is Decision.AdoptNow)
        assertTrue(NetworkReusePolicy.shouldLog(subject))
    }

    @Test
    fun notReadyWaitsInsteadOfRejoining() {
        val subject = decide(associated = "BUICK_3767", associatedReady = false)
        assertTrue(subject is Decision.WaitForAddress)
        assertEquals(
            NetworkReusePolicy.WAIT_FOR_ADDRESS_MILLIS,
            (subject as Decision.WaitForAddress).waitMillis,
        )
        assertTrue(NetworkReusePolicy.describe(subject).contains("不重新入网"))
    }

    @Test
    fun boundWithoutAddressWaitsToo() {
        assertTrue(decide(bound = "BUICK_3767", hasBound = true) is Decision.WaitForAddress)
    }

    @Test
    fun wrongNetworkRejoins() {
        val subject = decide(bound = "HomeWiFi", hasBound = true, hasAddress = true)
        assertTrue(subject is Decision.Rejoin)
        assertTrue(NetworkReusePolicy.describe(subject).endsWith("重新入网"))
    }

    @Test
    fun absentRejoins() {
        assertTrue(decide() is Decision.Rejoin)
    }

    @Test
    fun describeAlwaysCarriesStateAndAction() {
        val subject = decide(associated = "BUICK_3767", associatedReady = true)
        val line = NetworkReusePolicy.describe(subject)
        assertTrue(line.startsWith("WIFI 复用判定："))
        assertTrue(line.contains("绑定即可用"))
    }

    @Test
    fun everyStateHasADecision() {
        // 枚举扩了而 decide 忘了补分支时，when 是穷尽的 → 编译期就会失败；这里再钉住数量
        assertEquals(5, State.values().size)
    }
}
