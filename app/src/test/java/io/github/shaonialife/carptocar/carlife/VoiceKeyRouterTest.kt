package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机语音键状态机回归（1.2.22）。
 *
 * 触发依据：手机上 `CarPToCarL-log-20261103-085250.txt` 08:52:12~42 的
 * `requestSiri siriAction=1/2/3` 序列；取值来自 Apple `AirPlayCommon.h`
 * `AirPlaySiriAction`：0=NotApplicable / 1=Prewarm / 2=ButtonDown / 3=ButtonUp。
 */
class VoiceKeyRouterTest {
    private var now = 1_000L
    private val sent = mutableListOf<Int>()
    private val logs = mutableListOf<String>()
    private val router =
        VoiceKeyRouter(
            nowMillis = { now },
            send = { sent.add(it) },
            log = { logs.add(it) },
        )

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun prewarmDoesNotStartVoice() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_PREWARM)
        assertTrue(sent.isEmpty())
        assertTrue(logs.single().contains("Prewarm"))
    }

    @Test
    fun buttonDownSendsVrStart() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
    }

    @Test
    fun shortPressLeavesStopToCarLife() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        advance(890L) // 实测日志里最短的一次按住是 0.89s：等提示音就松手
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
        assertTrue(logs.last().contains("不发 VR_STOP"))
    }

    @Test
    fun longPressSendsVrStopOnRelease() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        advance(3_870L) // 实测日志里最长的一次按住
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertEquals(
            listOf(CarLifeProtocol.HardKey.VR_START, CarLifeProtocol.HardKey.VR_STOP),
            sent,
        )
        assertTrue(logs.last().contains("PTT 收尾"))
    }

    @Test
    fun thresholdIsInclusive() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        advance(VoiceKeyRouter.PTT_HOLD_MILLIS)
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertEquals(2, sent.size)
    }

    @Test
    fun repeatedButtonDownDoesNotResendStart() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        repeat(20) {
            advance(100L)
            router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        }
        assertEquals(1, sent.size)
    }

    @Test
    fun buttonUpWithoutDownIsIgnored() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun staleReleaseDoesNotSendStop() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        advance(VoiceKeyRouter.STALE_PRESS_MILLIS + 1)
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
        assertTrue(logs.last().contains("不补 VR_STOP"))
        // 状态作废后，下一次按下要能重新发 VR_START
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        assertEquals(2, sent.size)
    }

    @Test
    fun hidAndRequestSiriShareOneEdge() {
        router.onHidDown("0x6802 0xCF")
        advance(200L)
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
        advance(2_000L)
        router.onHidUp("0x6802 0xCF")
        assertEquals(
            listOf(CarLifeProtocol.HardKey.VR_START, CarLifeProtocol.HardKey.VR_STOP),
            sent,
        )
        // 松开后重复的 ButtonUp 不能再补一次 VR_STOP
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertEquals(2, sent.size)
    }

    @Test
    fun resetClearsPendingPress() {
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_DOWN)
        router.reset()
        advance(2_000L)
        router.onSiriAction(VoiceKeyRouter.SIRI_ACTION_BUTTON_UP)
        assertEquals(listOf(CarLifeProtocol.HardKey.VR_START), sent)
    }

    @Test
    fun unknownSiriActionIsIgnored() {
        router.onSiriAction(null)
        router.onSiriAction(0)
        router.onSiriAction(99)
        assertTrue(sent.isEmpty())
        assertEquals(3, logs.size)
    }

    @Test
    fun realLogSequenceProducesStartStopPairs() {
        // 08:52:12.092=1 / 12.643=2 / 16.512=3 / 19.076=2 / 22.766=3 /
        // 30.410=2 / 32.564=3 / 41.953=2 / 42.846=3
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
                893L to 3,
            )
        sequence.forEach { (delta, action) ->
            advance(delta)
            router.onSiriAction(action)
        }
        assertEquals(4, sent.count { it == CarLifeProtocol.HardKey.VR_START })
        // 三次长按补 VR_STOP，最后那次 0.89s 短按不补
        assertEquals(3, sent.count { it == CarLifeProtocol.HardKey.VR_STOP })
    }
}
