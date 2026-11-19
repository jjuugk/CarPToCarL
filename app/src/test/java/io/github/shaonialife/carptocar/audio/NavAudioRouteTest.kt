package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导航播报落点（混音 / speechRecognition）的持久化与循环切换。
 *
 * 这里最要紧的三条：默认必须是**混音**（老用户升级后行为一点不能变）、脏数据必须
 * 回落混音（持久化值认错一次就会去建一条没人要的独立流，而那条流的失败模式是
 * 「车机回 200、给端口、然后一声不出」），以及**老版本存下的 `ALERT` 必须回落混音**
 * ——那个档位会让整条会话断掉（见 [NavAudioRoute] 的说明）。
 */
class NavAudioRouteTest {
    @Test
    fun unknownValueFallsBackToMix() {
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse(null))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse(""))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse("ALERT_TUNNEL"))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse("  "))
    }

    /** 1.2.58 移除了 alert 档：升级用户机器上还存着 "ALERT"，必须回落成混音。 */
    @Test
    fun removedAlertValueFallsBackToMix() {
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse("ALERT"))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse("alert"))
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.parse(" Alert "))
    }

    @Test
    fun roundTrip() {
        NavAudioRoute.entries.forEach { route ->
            assertEquals(route, NavAudioRoute.parse(route.name))
            assertEquals(route, NavAudioRoute.parse(" ${route.name.lowercase()} "))
        }
    }

    /** 循环顺序必须是 混音 → 语音 → 只推音乐 → 混音，界面点一下就按下标走。 */
    @Test
    fun nextCycles() {
        assertEquals(NavAudioRoute.SPEECH, NavAudioRoute.MIX.next())
        assertEquals(NavAudioRoute.DIRECT, NavAudioRoute.SPEECH.next())
        assertEquals(NavAudioRoute.MIX, NavAudioRoute.DIRECT.next())
    }

    /** 只有 speechRecognition 才给出要建的 audioType；值必须与 AirPlayClient 的常量逐字节一致。 */
    @Test
    fun audioTypeMatchesProtocolStrings() {
        assertNull(NavAudioRoute.MIX.audioType)
        assertEquals("speechRecognition", NavAudioRoute.SPEECH.audioType)
        assertNull(NavAudioRoute.DIRECT.audioType)
    }

    /** 直通档只认 DIRECT 自己：别让 `isDirect` 跟着枚举顺序漂。 */
    @Test
    fun onlyDirectIsDirect() {
        assertEquals(true, NavAudioRoute.DIRECT.isDirect)
        assertEquals(false, NavAudioRoute.MIX.isDirect)
        assertEquals(false, NavAudioRoute.SPEECH.isDirect)
    }

    /** 已移除的档位不能再出现在枚举里（防止有人把 alert 加回来）。 */
    @Test
    fun alertIsGone() {
        assertEquals(listOf("MIX", "SPEECH", "DIRECT"), NavAudioRoute.entries.map { it.name })
    }

    /** 界面各位置的文案不能有空的（空说明等于用户不知道自己在切什么）。 */
    @Test
    fun labelsAndDetailsAreNotEmpty() {
        NavAudioRoute.entries.forEach { route ->
            assertNotEquals("", route.label.trim())
            assertNotEquals("", route.detail.trim())
        }
    }
}
