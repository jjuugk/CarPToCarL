package io.github.shaonialife.carptocar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「拆链是不是冻结造成的」这条判定。
 *
 * 现场（2026-11-06 18:31，vivo V2405A / 奥迪 CL3D）：
 * ```
 * 18:31:16.733 PROC: 进程被系统冻结 3 秒（心跳中断 4s）
 * 18:31:16.740 SESSION 保活结束（链路断开）        ← 7 毫秒后
 * 18:31:16.789 AUDIO: ⚠ 档位 t102/media/0x800000(48000Hz,AAC_LC) 起流 11s 后整条会话被拆
 *             ——判定为本车机不兼容档位（已记入名单）
 * ```
 * 冻结期间不回 keepAlive，车机按超时撤会话——和音频档毫无关系。旧代码只看「存活 < 20s」
 * 就写名单，把刚提为首选的 48K AAC 永久排到队尾。
 */
class ProcFreezeTest {
    @Test
    fun `从未冻结过就不怀疑`() {
        assertFalse(ProcFreeze.suspect(nowUptime = 1_000_000L, lastFreezeUptime = 0L))
    }

    @Test
    fun `拆链紧跟冻结时怀疑冻结`() {
        val freezeAt = 5_000_000L
        // 现场：冻结 3s，7ms 后拆链
        assertTrue(ProcFreeze.suspect(nowUptime = freezeAt + 7L, lastFreezeUptime = freezeAt))
        // 解冻后 5s 内拆链（重建/重连常常就发生在这段时间）
        assertTrue(ProcFreeze.suspect(nowUptime = freezeAt + 5_000L, lastFreezeUptime = freezeAt))
        // 窗口边界
        assertTrue(
            ProcFreeze.suspect(
                nowUptime = freezeAt + ProcFreeze.ATTRIBUTION_WINDOW_MS,
                lastFreezeUptime = freezeAt,
            ),
        )
    }

    @Test
    fun `窗口外不怀疑——真不兼容的档位仍要被记下来`() {
        val freezeAt = 5_000_000L
        assertFalse(
            ProcFreeze.suspect(
                nowUptime = freezeAt + ProcFreeze.ATTRIBUTION_WINDOW_MS + 1,
                lastFreezeUptime = freezeAt,
            ),
        )
        // 隔了很久才拆链：必是别的原因
        assertFalse(ProcFreeze.suspect(nowUptime = freezeAt + 10 * 60_000L, lastFreezeUptime = freezeAt))
    }

    @Test
    fun `时钟回退时宁可归因音频`() {
        // uptime 不应回退；真回退了说明环境异常，此时放过一个不兼容档位比漏记更糟
        assertFalse(ProcFreeze.suspect(nowUptime = 4_000L, lastFreezeUptime = 5_000L))
    }

    @Test
    fun `sinceLast 在从未冻结时返回负一`() {
        ProcFreeze.reset()
        assertTrue(ProcFreeze.sinceLast(1_000L) < 0L)
    }
}
