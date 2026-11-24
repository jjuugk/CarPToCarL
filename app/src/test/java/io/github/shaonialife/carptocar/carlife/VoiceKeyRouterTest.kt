package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 方控语音键状态机（AMcar 语义：1 按下 / 2 激活 / 3 松开，500ms 确认，60s 租约）。 */
class VoiceKeyRouterTest {
    private var now = 1_000L
    private val sent = mutableListOf<Int>()
    private val logs = mutableListOf<String>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

    private val router =
        VoiceKeyRouter(
            nowMillis = { now },
            send = { sent.add(it) },
            log = { logs.add(it) },
            scheduleDelay = { delay, action -> scheduled.add(now + delay to action) },
        )

    private fun advance(ms: Long) {
        now += ms
        scheduled
            .filter { it.first <= now }
            .forEach { it.second() }
        scheduled.removeAll { it.first <= now }
    }

    private fun press() = router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_PRESS)

    private fun activate() = router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_ACTIVATE)

    private fun release() = router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_RELEASE)

    @Test
    fun prewarmOnlyRecordsPress() {
        press()
        assertTrue(sent.isEmpty())
        assertTrue(logs.last().contains("等待长按确认"))
    }

    @Test
    fun longPressStartsImmediately() {
        press()
        advance(VoiceKeyRouter.HOLD_MILLIS)
        activate()
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
        assertTrue(logs.last().contains("硬按键 33"))
    }

    @Test
    fun earlyActivationIsDeferredUntilHoldComplete() {
        press()
        activate()
        assertTrue(sent.isEmpty())
        advance(VoiceKeyRouter.HOLD_MILLIS - 1)
        assertTrue(sent.isEmpty())
        advance(1)
        assertEquals(1, sent.size)
    }

    @Test
    fun releaseBeforeHoldCancelsPendingStart() {
        press()
        activate()
        release()
        advance(VoiceKeyRouter.HOLD_MILLIS + 100)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun releaseNeverSendsStop() {
        press()
        advance(2_000)
        activate()
        release()
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
        assertTrue(sent.none { it == CarLifeProtocol.HardKey.VR_STOP })
    }

    @Test
    fun leaseSendsStopWhenEngineGivesNoState() {
        press()
        advance(VoiceKeyRouter.HOLD_MILLIS)
        activate()
        advance(VoiceKeyRouter.LEASE_MILLIS)
        assertEquals(
            listOf(CarLifeProtocol.HardKey.VR_START, CarLifeProtocol.HardKey.VR_STOP),
            sent,
        )
        assertTrue(logs.last().contains("租约到期"))
    }

    @Test
    fun newActivationInvalidatesOldLease() {
        press()
        advance(VoiceKeyRouter.HOLD_MILLIS)
        activate()
        press()
        advance(VoiceKeyRouter.HOLD_MILLIS)
        activate() // 上一轮先补 34，再发新的 33
        assertEquals(
            listOf(
                CarLifeProtocol.HardKey.VR_START,
                CarLifeProtocol.HardKey.VR_STOP,
                CarLifeProtocol.HardKey.VR_START,
            ),
            sent,
        )
        advance(VoiceKeyRouter.LEASE_MILLIS)
        // 只有第二轮自己的租约补 34；第一轮租约被令牌作废，
        // 否则这里会多出一条（共 5 条 / 3 个 34）。
        assertEquals(4, sent.size)
        assertEquals(2, sent.count { it == CarLifeProtocol.HardKey.VR_STOP })
    }

    @Test
    fun releaseWithoutPressIsIgnored() {
        release()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun unknownActionIsIgnored() {
        router.onSiriAction(null)
        router.onSiriAction(0)
        router.onSiriAction(99)
        assertTrue(sent.isEmpty())
        assertEquals(3, logs.size)
    }

    @Test
    fun hidPathUsesSameStateMachine() {
        router.onHidDown("0x6802 0xCF")
        advance(VoiceKeyRouter.HOLD_MILLIS)
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
        advance(2_000)
        router.onHidUp("0x6802 0xCF")
        assertEquals(1, sent.size)
    }

    @Test
    fun realLogSequence() {
        // 08:52:12.092=1 / 12.643=2 / 16.512=3 / 19.076=2 / 22.766=3 /
        // 30.410=2 / 32.564=3 / 41.953=2 / 42.893=3
        val sequence =
            listOf(
                1L to 1,
                551L to 2,
                3_869L to 3,
                2_564L to 2,
                3_690L to 3,
                7_644L to 2,
                2_154L to 3,
                9_389L to 2,
                940L to 3,
            )
        sequence.forEach { (delta, action) ->
            advance(delta)
            router.onSiriAction(action)
        }
        // 按 AMcar 原样：只有第一次带 action=1（按下），后续 2/3 成对时
        // 补足长按的回调拿不到按下时刻，不会起识别——这是照抄保留的行为。
        assertEquals(1, sent.count { it == CarLifeProtocol.HardKey.VR_START })
        assertEquals(0, sent.count { it == CarLifeProtocol.HardKey.VR_STOP })
    }

    @Test
    fun resetClearsPendingState() {
        press()
        activate()
        router.reset()
        advance(VoiceKeyRouter.HOLD_MILLIS + 100)
        assertTrue(sent.isEmpty())
    }
}
