package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源归属状态机：`/info.modes` 是初始值、`changeModes` 按转移语义改、回报的是实际归属。
 *
 * 为什么锁这些：旧实现每次 `modesChanged` 都发同一段写死的常量（手机永久持有主屏+主音频），
 * 车机说「我接管」「我还给你」「我借一下」，我们一律回「屏永远是我的」。真车会发这两条——
 * 奥迪 4260 在 2026-11-09 09:38/09:42/09:43 共 7 次 `changeModes` + 1 次 `requestUI`，
 * 且 `/info.modes` 里就写着 `resourceID=1 transferType=1 takeConstraint=100`。
 */
class AirPlayResourceStateTest {
    /** 奥迪 4260 的 /info.modes 原文形态（截取 resources 部分，值取自 11-09 09:38 日志）。 */
    private fun audiModes() =
        mapOf<Any?, Any?>(
            "appStates" to
                listOf(
                    mapOf<Any?, Any?>("appStateID" to 2, "state" to false),
                    mapOf<Any?, Any?>("speechMode" to -1, "appStateID" to 1),
                    mapOf<Any?, Any?>("appStateID" to 3, "state" to false),
                ),
            "resources" to
                listOf(
                    mapOf<Any?, Any?>(
                        "transferPriority" to 100,
                        "takeConstraint" to 100,
                        "resourceID" to 1,
                        "transferType" to 1,
                    ),
                    mapOf<Any?, Any?>(
                        "takeConstraint" to 100,
                        "resourceID" to 2,
                        "transferType" to 1,
                    ),
                ),
        )

    @Test
    fun `车机没给 modes 时保持手机持有，与改动前的常量一致`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        assertTrue("没有 modes 就必须是手机持屏", state.phoneOwnsScreen)
        assertTrue(state.phoneOwnsAudio)
        val body = state.command().toString()
        // 旧常量的形状：主屏/主音频 entity=1 permanentEntity=1，speechMode=-1
        assertTrue(body, body.contains("resourceID=1, entity=1, permanentEntity=1"))
        assertTrue(body, body.contains("resourceID=2, entity=1, permanentEntity=1"))
        assertTrue(body, body.contains("speechMode=-1"))
    }

    @Test
    fun `奥迪 modes 里 transferType=1 表示主屏已被车机接管`() {
        val state = AirPlayResourceState()
        state.initialize(audiModes())
        assertFalse("transferType=1 = 车机接管，此时不该声称手机持屏", state.phoneOwnsScreen)
    }

    @Test
    fun `takeConstraint 允许时要得回主屏，不允许时不能硬抢`() {
        val allowed = AirPlayResourceState()
        allowed.initialize(audiModes()) // takeConstraint=100 在白名单里
        allowed.requestPhoneScreen()
        assertTrue("takeConstraint=100 允许申请主屏", allowed.phoneOwnsScreen)

        val blocked = AirPlayResourceState()
        blocked.initialize(
            mapOf<Any?, Any?>(
                "resources" to
                    listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 1, "takeConstraint" to 300)),
            ),
        )
        blocked.requestPhoneScreen()
        assertFalse("takeConstraint=300 表示暂不允许，不能硬声明持有", blocked.phoneOwnsScreen)
    }

    @Test
    fun `changeModes 的四种转移语义分别生效`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        // 1 接管
        state.applyCarChange(mapOf<Any?, Any?>("resources" to listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 1))))
        assertFalse("车机接管后主屏不归手机", state.phoneOwnsScreen)
        // 2 归还
        state.applyCarChange(mapOf<Any?, Any?>("resources" to listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 2))))
        assertTrue("车机归还后主屏归手机", state.phoneOwnsScreen)
        // 3 借用 → 当前归属车机，但永久归属不变
        state.applyCarChange(
            mapOf<Any?, Any?>("resources" to listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 3, "borrowID" to "b1"))),
        )
        assertFalse("借用期间主屏在车机手里", state.phoneOwnsScreen)
        // 4 结束借用 → 回到永久归属（手机）
        state.applyCarChange(
            mapOf<Any?, Any?>("resources" to listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 4, "borrowID" to "b1"))),
        )
        assertTrue("借用结束后回到永久归属", state.phoneOwnsScreen)
    }

    @Test
    fun `未知 transferType 不改变任何状态`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        val before = state.command().toString()
        state.applyCarChange(mapOf<Any?, Any?>("resources" to listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 9))))
        assertEquals("猜错方向比不动更糟", before, state.command().toString())
    }

    @Test
    fun `每轮初始化会清掉上一轮被借走的屏`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        state.applyCarChange(
            mapOf<Any?, Any?>("resources" to listOf(mapOf<Any?, Any?>("resourceID" to 1, "transferType" to 3, "borrowID" to "old"))),
        )
        assertFalse(state.phoneOwnsScreen)
        // 新会话：车机什么都没借，不能带着上一轮的借用状态
        state.initialize(null)
        assertTrue(state.phoneOwnsScreen)
    }

    @Test
    fun `报文形状与旧常量一致，只有取值来自状态`() {
        val state = AirPlayResourceState()
        state.initialize(audiModes())
        val params = state.command()["params"] as Map<*, *>
        assertEquals("resources 顺序必须是主屏在前", listOf(1L, 2L), (params["resources"] as List<*>).map { (it as Map<*, *>)["resourceID"] })
        assertEquals("appStates 顺序沿用旧常量", listOf(2L, 3L, 1L), (params["appStates"] as List<*>).map { (it as Map<*, *>)["appStateID"] })
        val speech = (params["appStates"] as List<*>)[2] as Map<*, *>
        assertEquals(-1L, speech["speechMode"])
    }

    // ---- 本机应用态（AIS Table 23-56 / 23-72）----

    @Test
    fun `本机既不说话也不通话时，报文与改动前逐字节一致`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        val states = (state.command()["params"] as Map<*, *>)["appStates"] as List<*>
        // 一个 entity 都不该被我们顶成 Controller，2 那条也不该多出 speechMode
        assertEquals(listOf(0L, 0L, 0L), states.map { (it as Map<*, *>)["entity"] })
        assertEquals(null, (states[0] as Map<*, *>)["speechMode"])
        assertEquals(null, (states[1] as Map<*, *>)["speechMode"])
        assertEquals(-1L, (states[2] as Map<*, *>)["speechMode"])
    }

    @Test
    fun `收音时报 Speech=Controller 且 speechMode 为 RecognizingSpeech`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        assertTrue(state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING))
        val speech = appState(state, 1)
        // entity=1 是 Controller（= 手机），2 是 Accessory（= 车机）；这里必须是手机
        assertEquals(1L, speech["entity"])
        assertEquals(2L, speech["speechMode"])
        // 同值重复设置不该再触发一次上报
        assertFalse(state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING))
    }

    @Test
    fun `说话时报 Speaking，结束后回到 None`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        state.setOwnSpeech(AirPlaySpeechMode.SPEAKING)
        assertEquals(1L, appState(state, 1)["speechMode"])
        state.setOwnSpeech(AirPlaySpeechMode.NONE)
        assertEquals(0L, appState(state, 1)["entity"])
        assertEquals(-1L, appState(state, 1)["speechMode"])
    }

    @Test
    fun `通话中报 PhoneCall=Controller，并补上规范要求的 speechMode`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        assertTrue(state.setOwnPhoneCall(true))
        val call = appState(state, 2)
        assertEquals(1L, call["entity"])
        assertEquals("Table 23-72 要求 PhoneCall 带 speechMode", -1L, call["speechMode"])
        state.setOwnPhoneCall(false)
        assertEquals(0L, appState(state, 2)["entity"])
    }

    @Test
    fun `本机语音态优先于车机自己报的那条`() {
        val state = AirPlayResourceState()
        state.initialize(
            mapOf<Any?, Any?>(
                "appStates" to listOf(mapOf<Any?, Any?>("appStateID" to 1, "speechMode" to 1)),
            ),
        )
        assertEquals("车机在说话时先照抄", 2L, appState(state, 1)["entity"])
        state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING)
        assertEquals("我们自己收音时以本机为准", 1L, appState(state, 1)["entity"])
        assertEquals(2L, appState(state, 1)["speechMode"])
    }

    @Test
    fun `新一轮初始化会清掉本机上一轮的语音态`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING)
        state.setOwnPhoneCall(true)
        state.initialize(null)
        assertEquals(AirPlaySpeechMode.NONE, state.ownSpeechMode)
        assertFalse(state.ownPhoneCallActive)
        assertEquals(0L, appState(state, 1)["entity"])
    }

    private fun appState(
        state: AirPlayResourceState,
        id: Int,
    ): Map<*, *> =
        ((state.command()["params"] as Map<*, *>)["appStates"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["appStateID"] == id.toLong() }

    @Test
    fun `claimAll 是回退路径，把两块资源都收回手机`() {
        val state = AirPlayResourceState()
        state.initialize(audiModes())
        assertFalse(state.phoneOwnsScreen)
        state.claimAll()
        assertTrue(state.phoneOwnsScreen)
        assertTrue(state.phoneOwnsAudio)
    }
}
