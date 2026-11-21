package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MFi 挑战长度的按车机持久化记忆。
 *
 * 背景（2026-10-19 奥迪 MMI 0000 实测）：20B 挑战该车机静默 6 秒无应答，32B 才在
 * 0.64 秒回签名。不同接收端偏好相反（DiPlay 只认 32B、MMI 0000 认 20B），所以
 * 记忆必须按车机身份分开存，且冷启动仍要有合理基线。
 */
class MfiLengthMemoryTest {
    private val baselineCar = listOf(20, 32, 16) // 真车系：20B 优先
    private val baselineTestBench = listOf(32, 20, 16) // 测试台：32B 优先

    @Test
    fun receiverKeyIsStableAndFilesystemFriendly() {
        // 0x1D01 的 name + model + manufacturer 拼接（会带空格与大小写差异）
        val a = MfiLengthMemory.receiverKey("audi_mmi_0000 audi 0000 delphi")
        val b = MfiLengthMemory.receiverKey("Audi_MMI_0000  AUDI 0000 DELPHI")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        // 不同车机必须落到不同键（型号或厂商不同都不能互相污染记忆）
        assertFalse(a == MfiLengthMemory.receiverKey("audi_mmi_0000 audi 0000 continental"))
    }

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(baselineCar, MfiLengthMemory.orderAttempts(baselineCar, MfiLengthMemory.Entry()))
        assertEquals(
            baselineTestBench,
            MfiLengthMemory.orderAttempts(baselineTestBench, MfiLengthMemory.Entry()),
        )
    }

    @Test
    fun rememberedSuccessGoesFirst() {
        // MMI 0000 记住了 32B 成功 → 下一轮直接 32B，不再先撞 20B 的 6 秒静默
        val entry = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineCar, entry))
        // 测试台场景同理（32B 本来就在最前，顺序不变）
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineTestBench, entry))
    }

    @Test
    fun silentAndRejectedLengthsSinkButStayAsFallback() {
        val entry = MfiLengthMemory.Entry(silent = setOf(20), rejected = setOf(16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        // 成功档位没有；20B 静默、16B 被拒都排到最后，32B 提前
        assertEquals(listOf(32, 20, 16), order)
        // 不丢档位：记忆只影响顺序，不会让某个长度永远不被尝试
        assertEquals(baselineCar.toSet(), order.toSet())
    }

    @Test
    fun allRejectedFallsBackToTryingEverything() {
        // 记忆与现状矛盾（换了固件/同一身份的不同车）：全部被拒过也要照常试，避免永久卡死
        val entry = MfiLengthMemory.Entry(rejected = setOf(20, 32, 16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(baselineCar, order)
    }

    @Test
    fun onlySucceededLengthIsDroppedFromDuplicateCandidates() {
        val entry = MfiLengthMemory.Entry(succeeded = 20)
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(20, order.first())
        assertEquals(order.size, order.distinct().size)
    }

    /**
     * 硬拒绝推翻「成功」记忆（上游 0.1.23 定谳的迟到应答污染）：
     * 32B 的迟到应答被记到 20B 头上 → 之后每轮 20B 都被对端断链。
     * 断链是强证据，必须把 20B 从成功档位摘掉、记进被拒档。
     */
    @Test
    fun hardRejectOverturnsSucceededMemory() {
        val identity = "hard-reject-test-car"
        // 先记成「20B 成功」（模拟迟到应答误记）
        MfiLengthMemory.recordSuccess(null, identity, 20)
        assertEquals(20, MfiLengthMemory.load(null, identity).succeeded)
        // 挑战期间被对端断链 → 硬拒绝必须推翻它
        MfiLengthMemory.recordHardRejected(null, identity, 20)
        val next = MfiLengthMemory.load(null, identity)
        assertEquals(null, next.succeeded)
        // 断链要记进独立的 hardRejected，而不是普通 rejected（后者仅表示 0xAA04 拒绝字）
        assertTrue(20 in next.hardRejected)
        assertFalse(20 in next.rejected)
        assertFalse(20 in next.silent)
        // 关键：断链档位在有其它可用档位时被真正剔除——真车基线 [20,32,16] → [32,16]，
        // 20B 不再参与尝试（BMW EntryEVO 16B 断链实测的通用化修复）。
        assertEquals(listOf(32, 16), MfiLengthMemory.orderAttempts(baselineCar, next))
    }

    /**
     * BMW EntryEVO 场景（2026-11-21 实车）：16B 断链、20/32B 静默。
     * 断链档位必须被剔除，且即便它曾被记为 succeeded 也要推翻。
     */
    @Test
    fun hardRejectedLengthIsExcludedWhileAlternativesExist() {
        val entry =
            MfiLengthMemory.Entry(silent = setOf(20, 32), hardRejected = setOf(16))
        // 车机身份序 prefer20First=false → 基线 [32,20,16]；剔除 16B 后仅剩 32/20B
        val order = MfiLengthMemory.orderAttempts(listOf(32, 20, 16), entry)
        assertEquals(listOf(32, 20), order)
        assertFalse(16 in order)
    }

    /** 兜底保护：若所有档位都断链过（换固件/同身份不同车），照常全试，避免永久卡死。 */
    @Test
    fun allHardRejectedFallsBackToTryingEverything() {
        val entry = MfiLengthMemory.Entry(hardRejected = setOf(20, 32, 16))
        assertEquals(baselineCar, MfiLengthMemory.orderAttempts(baselineCar, entry))
    }

    /**
     * 「成功 ∧ 被拒」自相矛盾的记忆：不能继续享受第一名，降级为普通档参与排序
     * （排在被拒档之前，仍会被试到），避免旧实现那种「每轮都先撞必断链的档位」。
     */
    @Test
    fun contradictorySuccessAndRejectIsDemotedToNormal() {
        val entry = MfiLengthMemory.Entry(succeeded = 20, rejected = setOf(20))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(listOf(32, 16, 20), order)
        // 不丢档位：20B 只是被降级，仍然会被尝试（万一固件又变回来）
        assertEquals(baselineCar.toSet(), order.toSet())
    }

    @Test
    fun describeShowsWhatWasRemembered() {
        assertEquals("无", MfiLengthMemory.describe(MfiLengthMemory.Entry()))
        val text =
            MfiLengthMemory.describe(
                MfiLengthMemory.Entry(succeeded = 32, silent = setOf(20), rejected = setOf(16)),
            )
        assertTrue(text.contains("成功=32B"))
        assertTrue(text.contains("静默=20B"))
        assertTrue(text.contains("被拒=16B"))
        val withHard =
            MfiLengthMemory.describe(
                MfiLengthMemory.Entry(rejected = setOf(20), hardRejected = setOf(16)),
            )
        assertTrue(withHard.contains("被拒=20B"))
        assertTrue(withHard.contains("断链=16B"))
    }
}
