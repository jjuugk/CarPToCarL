package io.github.shaonialife.carptocar.buick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接配置档位与别克 B233 判定。
 *
 * 这组用例守的是**「默认档不许改变其它车机」**这条底线：只要 AUTO/STANDARD 的
 * 判定出现回归，奥迪/宝马就会莫名走到别克的建链分支上——那是「本来就好好能连的
 * 车突然连不上」的典型来源，比别克连不上更难排查。
 */
class ConnectionProfileTest {
    @Test
    fun `默认档是自动识别`() {
        assertEquals(ConnectionProfile.AUTO, ConnectionProfile.byId(null))
        assertEquals(ConnectionProfile.AUTO, ConnectionProfile.byId("不认识的 id"))
    }

    @Test
    fun `蓝牙名只认四位数字的 BUICK 前缀`() {
        assertTrue(B233Identity.looksLikeBuick("BUICK_1234"))
        assertTrue(B233Identity.looksLikeBuick("buick_0001"))
        assertTrue(B233Identity.looksLikeBuick(" BUICK_9999 "))
        assertFalse(B233Identity.looksLikeBuick("BUICK_123"))
        assertFalse(B233Identity.looksLikeBuick("BUICK_12345"))
        assertFalse(B233Identity.looksLikeBuick("AUDI_1234"))
        assertFalse(B233Identity.looksLikeBuick(null))
    }

    @Test
    fun `身份文本必须三个标记齐全才算 B233`() {
        assertTrue(B233Identity.confirmsB233("buick_1234 buick b233 saic general motors"))
        // 少任何一个都不算：别的车型也可能带 b233 字样（机身编号之类）
        assertFalse(B233Identity.confirmsB233("buick_1234 buick saic general motors"))
        assertFalse(B233Identity.confirmsB233("buick 1234 b233"))
        assertFalse(B233Identity.confirmsB233(""))
        assertFalse(B233Identity.confirmsB233(null))
    }

    /**
     * 车型代号两种写法都认：模块型号 B233、平台代号 E2LB。
     *
     * 2026-10-29 BUICK_2874 真机日志：0x1D01 回的是 `name=BUICK_2874 model=E2LB
     * manufacturer=Saic General Motors`。只认 b233 时这台车判不进专用分支，
     * 于是发出通用单 group 的 0x4300 被车机静默（连链路层 ACK 都不回），
     * 0x4301 永远等不到——日志里的死循环就是这么来的。
     */
    @Test
    fun `E2LB 平台代号与 B233 同等识别`() {
        assertTrue(
            B233Identity.confirmsB233("buick_2874 buick e2lb saic general motors"),
        )
        // 车型代号缺失时仍不算（避免把「只要是台别克」都塞进专用分支）
        assertFalse(B233Identity.confirmsB233("buick_2874 buick saic general motors"))
        // 别的品牌带 e2lb 也不算
        assertFalse(B233Identity.confirmsB233("audi e2lb b233"))
    }

    @Test
    fun `通用档永不判定为别克`() {
        val hits =
            B233Selection.matches(
                profile = ConnectionProfile.STANDARD,
                selectedAddress = "AA:BB:CC:DD:EE:FF",
                peerAddress = "AA:BB:CC:DD:EE:FF",
                peerName = "BUICK_1234",
                observedB233 = true,
            )
        assertFalse(hits)
    }

    @Test
    fun `自动档按蓝牙名或历史记录判定`() {
        fun match(
            name: String?,
            observed: Boolean,
        ) = B233Selection.matches(ConnectionProfile.AUTO, null, "AA:BB:CC:DD:EE:FF", name, observed)

        assertTrue(match("BUICK_1234", false))
        assertTrue(match("车载蓝牙", true))
        assertFalse(match("AUDI_3315", false))
        assertFalse(match(null, false))
    }

    @Test
    fun `别克档选了目标设备时只认那个地址`() {
        val target = "AA:BB:CC:DD:EE:FF"
        assertTrue(
            B233Selection.matches(ConnectionProfile.BUICK_B233, target, target, "随便什么名字", false),
        )
        // 别的设备即使名字像别克也不算（用户已经指定了目标）
        assertFalse(
            B233Selection.matches(
                ConnectionProfile.BUICK_B233,
                target,
                "11:22:33:44:55:66",
                "BUICK_1234",
                false,
            ),
        )
    }

    @Test
    fun `别克档没选目标时按名字或历史判定`() {
        assertTrue(
            B233Selection.matches(ConnectionProfile.BUICK_B233, null, "AA:BB:CC:DD:EE:FF", "BUICK_1234", false),
        )
        assertTrue(
            B233Selection.matches(ConnectionProfile.BUICK_B233, null, "AA:BB:CC:DD:EE:FF", "车载蓝牙", true),
        )
        assertFalse(
            B233Selection.matches(ConnectionProfile.BUICK_B233, null, "AA:BB:CC:DD:EE:FF", "AUDI_3315", false),
        )
    }

    @Test
    fun `两个别克档的拨号方向相反`() {
        assertFalse(ConnectionProfile.BUICK_B233.b233PhoneInitiates)
        assertTrue(ConnectionProfile.BUICK_B233_ACTIVE.b233PhoneInitiates)
        // 两个别克档都必须「强制 B233」，否则 AUTO 的身份判定会把它们退回通用流程
        assertTrue(ConnectionProfile.BUICK_B233.forcesB233)
        assertTrue(ConnectionProfile.BUICK_B233_ACTIVE.forcesB233)
    }

    @Test
    fun `被动档永远不主动拨号`() {
        assertFalse(
            B233DialPolicy.mayDial(
                active = false,
                sessionBusy = false,
                runningForMs = 60_000L,
                sinceLastAttemptMs = 60_000L,
                cooldownRemainingMs = 0L,
            ),
        )
    }

    @Test
    fun `主动档要等过入站优先窗口与最短重试间隔`() {
        val ok = { running: Long, sinceLast: Long ->
            B233DialPolicy.mayDial(
                active = true,
                sessionBusy = false,
                runningForMs = running,
                sinceLastAttemptMs = sinceLast,
                cooldownRemainingMs = 0L,
            )
        }
        assertTrue(ok(B233DialPolicy.INBOUND_PRIORITY_MS, B233DialPolicy.MIN_ACTIVE_RETRY_INTERVAL_MS))
        // 刚启动，先给车机反向连入的机会
        assertFalse(ok(B233DialPolicy.INBOUND_PRIORITY_MS - 1, Long.MAX_VALUE))
        // 上一次拨号太近：E5 连拨会把 iAP2 窗口拖死
        assertFalse(ok(60_000L, B233DialPolicy.MIN_ACTIVE_RETRY_INTERVAL_MS - 1))
        // 会话忙 / 还在冷却
        assertFalse(
            B233DialPolicy.mayDial(true, true, 60_000L, 60_000L, 0L),
        )
        assertFalse(
            B233DialPolicy.mayDial(true, false, 60_000L, 60_000L, 1L),
        )
    }

    @Test
    fun `大连屏档强制 B233 且默认等车机接入`() {
        val big = ConnectionProfile.BUICK_BIGSCREEN
        assertEquals(big, ConnectionProfile.byId("buick_bigscreen"))
        assertTrue(big.forcesB233)
        // 车机没报 maxFPS 时按 30 兜底（大屏车机普遍 30）
        assertEquals(DisplayTuning.FALLBACK_CAR_MAX_FPS, big.carMaxFpsFallback)
        // 与「别克 E5（手机拨号）」相反：大屏档默认等车机接入
        assertFalse(big.b233PhoneInitiates)
    }

    /**
     * 大屏档的车型身份未知（很可能不叫 `BUICK_xxxx`），所以**选了档位即认**：
     * 没选目标设备时不再要求「名字像别克」，否则用户选了档位等于白选。
     * 但一旦指定了目标，仍只认那一个地址——防止连到旁边的耳机或另一台车。
     */
    @Test
    fun `大连屏档没选目标也命中`() {
        assertTrue(
            B233Selection.matches(ConnectionProfile.BUICK_BIGSCREEN, null, "11:22:33:44:55:66", "车机蓝牙", false),
        )
        val target = "AA:BB:CC:DD:EE:FF"
        assertTrue(
            B233Selection.matches(ConnectionProfile.BUICK_BIGSCREEN, target, target, "随便", false),
        )
        assertFalse(
            B233Selection.matches(
                ConnectionProfile.BUICK_BIGSCREEN,
                target,
                "11:22:33:44:55:66",
                "BUICK_1234",
                false,
            ),
        )
    }

    /**
     * 帧率规则：**车机报了就跟随**，与档位无关。
     *
     * 车机 `/info` 的 maxFPS 是接收端解码能力的自述，发得比它快只会丢帧堆队列。
     * 唯一例外是「车机没报」（<=0）——那时只有声明了兜底的档位（大连屏）退回 30，
     * 其余档位保持本地偏好，保证没有车机信息时行为与旧版一致。
     */
    @Test
    fun `车机报了就跟随帧率上限`() {
        // 车机报 30：三个档位都跟随
        assertEquals(30, DisplayTuning.targetFps(ConnectionProfile.AUTO, 60, 30))
        assertEquals(30, DisplayTuning.targetFps(ConnectionProfile.BUICK_B233, 60, 30))
        assertEquals(30, DisplayTuning.targetFps(ConnectionProfile.BUICK_BIGSCREEN, 60, 30))
        // 车机报得比偏好高 → 不抬高（上限由本地偏好决定）
        assertEquals(60, DisplayTuning.targetFps(ConnectionProfile.AUTO, 60, 120))
        assertEquals(60, DisplayTuning.targetFps(ConnectionProfile.BUICK_BIGSCREEN, 60, 120))
        // 荒谬值（10fps）→ 有下限，不至于卡成 PPT
        assertEquals(DisplayTuning.MIN_TARGET_FPS, DisplayTuning.targetFps(ConnectionProfile.AUTO, 60, 10))
    }

    @Test
    fun `车机没报时只有大屏档按 30 兜底`() {
        assertEquals(60, DisplayTuning.targetFps(ConnectionProfile.AUTO, 60, 0))
        assertEquals(60, DisplayTuning.targetFps(ConnectionProfile.BUICK_B233, 60, 0))
        assertEquals(30, DisplayTuning.targetFps(ConnectionProfile.BUICK_BIGSCREEN, 60, 0))
    }

    @Test
    fun `只有大屏档给出缩放建议`() {
        assertEquals(
            DisplayTuning.BIGSCREEN_SUGGESTED_SCALE_PERCENT,
            DisplayTuning.suggestedScalePercent(ConnectionProfile.BUICK_BIGSCREEN),
        )
        assertNull(DisplayTuning.suggestedScalePercent(ConnectionProfile.BUICK_B233))
        assertNull(DisplayTuning.suggestedScalePercent(ConnectionProfile.AUTO))
    }
}
