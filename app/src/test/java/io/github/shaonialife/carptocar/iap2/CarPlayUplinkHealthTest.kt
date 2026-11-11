package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「车机端 CarPlay 已退出」判定的边界。
 *
 * 现场（2026-11-11 别克）：连上出画面 → 车机 AirPlay 组件崩 → 之后每轮蓝牙都能识别、
 * 都能发 0x4300，但永远没有 0x4301，16 秒一轮无限重连。
 *
 * 判据必须满足两条，否则会误伤「第一次配对就没成功」的正常场景：
 * 1. **本机在这台车机上曾经拿到过 0x4301**（说明它本来是好的）；
 * 2. 之后连续两轮要不到（或本轮已出现端口拒绝）。
 */
class CarPlayUplinkHealthTest {
    @Before
    fun setUp() {
        CarPlayUplinkHealth.reset()
    }

    @Test
    fun neverConnectedDeviceIsNotDeclaredDead() {
        // 从没拿到过 0x4301：可能是车机没开无线 CarPlay、也没配对过，不能判「已退出」。
        repeat(5) {
            assertFalse(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        }
        assertFalse(CarPlayUplinkHealth.isDead)
        assertEquals("尚未在这台车机上建立过无线会话", CarPlayUplinkHealth.describe())
    }

    @Test
    fun singleMissAfterSuccessIsNotDeadYet() {
        assertFalse(CarPlayUplinkHealth.finishRound(gotStartSession = true))
        assertFalse(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        assertFalse("一轮落空可能只是车机慢，不能立刻判死", CarPlayUplinkHealth.isDead)
    }

    @Test
    fun twoConsecutiveMissesAfterSuccessDeclareDead() {
        CarPlayUplinkHealth.finishRound(gotStartSession = true)
        CarPlayUplinkHealth.finishRound(gotStartSession = false)
        assertTrue(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        assertTrue(CarPlayUplinkHealth.isDead)
    }

    @Test
    fun portRefusalShortCircuitsToDeadInOneRound() {
        CarPlayUplinkHealth.finishRound(gotStartSession = true)
        // 事件口 ECONNREFUSED = 车机连监听都没了，不必再等第二轮。
        CarPlayUplinkHealth.notePortRefused()
        assertTrue(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        assertTrue(CarPlayUplinkHealth.isDead)
    }

    @Test
    fun portRefusalBeforeAnySuccessIsIgnored() {
        CarPlayUplinkHealth.notePortRefused()
        assertFalse(
            "从没连通过的车机，端口拒绝也只能算第一次失败——否则新车机第一轮就被判死",
            CarPlayUplinkHealth.finishRound(gotStartSession = false),
        )
        assertFalse(CarPlayUplinkHealth.isDead)
    }

    @Test
    fun receivingStartSessionRevivesAndClearsDead() {
        CarPlayUplinkHealth.finishRound(gotStartSession = true)
        CarPlayUplinkHealth.finishRound(gotStartSession = false)
        assertTrue(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        // 车机重启后终于回 0x4301 → 立刻恢复，下次判死要重新累积两轮。
        assertFalse(CarPlayUplinkHealth.finishRound(gotStartSession = true))
        assertFalse(CarPlayUplinkHealth.isDead)
        assertFalse(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        assertFalse(CarPlayUplinkHealth.isDead)
    }

    @Test
    fun resetGivesCleanSlateForManualRestart() {
        CarPlayUplinkHealth.finishRound(gotStartSession = true)
        CarPlayUplinkHealth.finishRound(gotStartSession = false)
        CarPlayUplinkHealth.finishRound(gotStartSession = false)
        assertTrue(CarPlayUplinkHealth.isDead)

        CarPlayUplinkHealth.reset()
        assertFalse(CarPlayUplinkHealth.isDead)
        // 用户刚重启过车机：第一次落空不能算数，仍要重新「成功过」才进入计数。
        assertFalse(CarPlayUplinkHealth.finishRound(gotStartSession = false))
        assertEquals("尚未在这台车机上建立过无线会话", CarPlayUplinkHealth.describe())
    }

    @Test
    fun deadRetryLadderStartsFastThenCapsAtSixty() {
        // 判死后不再一律 60 秒：刚判死时先 20/30/45 快速复核，多轮不过才放到 60。
        assertEquals(20_000L, CarPlayUplinkHealth.nextDeadRetryDelayMillis())
        assertEquals(30_000L, CarPlayUplinkHealth.nextDeadRetryDelayMillis())
        assertEquals(45_000L, CarPlayUplinkHealth.nextDeadRetryDelayMillis())
        assertEquals(60_000L, CarPlayUplinkHealth.nextDeadRetryDelayMillis())
        assertEquals(60_000L, CarPlayUplinkHealth.nextDeadRetryDelayMillis())
    }

    @Test
    fun deadRetryNeverGoesBelowCarDisconnectTimeout() {
        // 车机断开后要满 15 秒才判成「设备已移除」；任何一档都不能低于它，
        // 否则每轮重连都会把那个计时器续上，车机永远不重建会话。
        repeat(8) {
            assertTrue(CarPlayUplinkHealth.nextDeadRetryDelayMillis() > 15_000L)
        }
    }

    @Test
    fun startSessionResetsDeadLadderToFastestStep() {
        repeat(3) { CarPlayUplinkHealth.nextDeadRetryDelayMillis() }
        // 车机好了之后再坏，应该重新从最短档开始复核
        CarPlayUplinkHealth.noteStartSession()
        assertEquals(20_000L, CarPlayUplinkHealth.nextDeadRetryDelayMillis())
    }
}
