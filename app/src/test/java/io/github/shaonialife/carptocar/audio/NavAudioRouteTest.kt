package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导航播报落点（混音 / alert / speechRecognition）的持久化与循环切换。
 *
 * 这里最要紧的两条：默认必须是**混音**（老用户升级后行为一点不能变），以及脏数据
 * 必须回落到混音——持久化值认错一次就会去建一条没人要的独立流，而那条流的失败模式
 * 是「车机回 200、给端口、然后一声不出」，比直接报错难查得多。
 */
class NavAudioRouteTest {
    @Test
    fun unknownValueFallsBackToMix() {
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse(null))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse(""))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse("ALERT_TUNNEL"))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse("  "))
    }

    @Test
    fun roundTrip() {
        NavAudioRoute.entries.forEach { route ->
            assertEquals(route, NavAudioRoute.parse(route.name))
            assertEquals(route, NavAudioRoute.parse(" ${route.name.lowercase()} "))
        }
    }

    /** 循环顺序必须是 混音 → alert → 语音 → 混音，界面点一下就按下标走。 */
    @Test
    fun nextCycles() {
        assertEquals(NavAudioRoute.ALERT, NavAudioRoute.MIX.next())
        assertEquals(NavAudioRoute.SPEECH, NavAudioRoute.ALERT.next())
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.SPEECH.next())
    }

    /** 只有非混音才给出要建的 audioType；值必须与 AirPlayClient 的常量逐字节一致。 */
    @Test
    fun audioTypeMatchesProtocolStrings() {
        assertNull(NavAudioRoute.MIX.audioType)
        assertEquals("alert", NavAudioRoute.ALERT.audioType)
        assertEquals("speechRecognition", NavAudioRoute.SPEECH.audioType)
    }

    /** 界面三个位置的文案不能有空的（空说明等于用户不知道自己在切什么）。 */
    @Test
    fun labelsAndDetailsAreNotEmpty() {
        NavAudioRoute.entries.forEach { route ->
            assertNotEquals("", route.label.trim())
            assertNotEquals("", route.detail.trim())
        }
    }
}
