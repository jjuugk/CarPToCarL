package io.github.shaonialife.carptocar.service

import android.os.SystemClock

/**
 * 「进程刚被系统冻结过」的**全局标记**（任何时长，包括不足 10 秒、不触发重建的短冻结）。
 *
 * ## 解决什么问题
 *
 * 2026-11-06 实车（vivo V2405A / 奥迪 CL3D）：
 *
 * ```
 * 18:31:16.733 PROC: 进程被系统冻结 3 秒（心跳中断 4s）
 * 18:31:16.740 SESSION 保活结束（链路断开）        ← 7 毫秒后车机撤会话
 * 18:31:16.789 AUDIO: ⚠ 档位 t102/media/0x800000(48000Hz,AAC_LC) 起流 11s 后
 *             整条会话被车机拆掉——判定为本车机不兼容档位（已记入名单）
 * ```
 *
 * 拆链的**唯一原因**是冻结期间没回 keepAlive（车机按超时撤会话），跟音频档位毫无关系。
 * 但 [io.github.shaonialife.carptocar.SessionController.attributeAudioTeardown] 只看
 * 「存活 11s < 20s 的探针窗口」就判该档位不兼容并写进本车机名单 —— 一次冻结就能把
 * 刚被提为首选的 48K AAC 永久排到队尾，而且界面还会弹「该音频档位导致断开」，
 * 把以后所有排查都往音频上引。
 *
 * [FreezeWatch] 只对 ≥ [FreezeWatch.RECOVER_MIN_FROZEN_MS] 的冻结回调重建流程，
 * 短冻结根本进不到会话层；所以这里单独记一笔「最近一次被判定为冻结的时刻」，
 * 由拆链归因方自己在窗口内怀疑冻结、放弃归因。
 *
 * 纯状态 + 一个纯判定函数（[suspect]），便于单测。
 */
object ProcFreeze {
    /**
     * 冻结算不算「这次拆链的嫌疑人」的时间窗。
     *
     * 取 30s：冻结 3s 就能让车机撤会话（实测），而音频探针窗口是 20s，
     * 归因发生在拆链那一刻、通常紧跟在解冻后几秒内。
     */
    const val ATTRIBUTION_WINDOW_MS = 30_000L

    /** 最近一次被判定为冻结的时刻（`SystemClock.uptimeMillis()`；0 = 从未）。 */
    @Volatile
    var lastAtUptime: Long = 0L
        private set

    /** 心跳发现冻结时调用（[FreezeWatch.tick] 返回 > 0 的那一刻）。 */
    fun noteDetected() {
        lastAtUptime = SystemClock.uptimeMillis()
    }

    /** 距最近一次冻结过了多久（ms）；从未冻结过返回 -1。 */
    fun sinceLast(nowUptime: Long): Long {
        val last = lastAtUptime
        return if (last <= 0L) -1L else (nowUptime - last).coerceAtLeast(0L)
    }

    /**
     * 现在能不能把「会话被拆」怀疑到冻结头上（纯函数，单测覆盖）。
     *
     * @param lastFreezeUptime 最近一次冻结时刻；0 = 从未（不怀疑）
     */
    fun suspect(
        nowUptime: Long,
        lastFreezeUptime: Long = lastAtUptime,
        windowMillis: Long = ATTRIBUTION_WINDOW_MS,
    ): Boolean {
        if (lastFreezeUptime <= 0L) return false
        val elapsed = nowUptime - lastFreezeUptime
        // 时钟回退（异常）时不怀疑：宁可归因音频，也不要凭空放过真正不兼容的档位
        if (elapsed < 0L) return false
        return elapsed <= windowMillis
    }

    /** 测试与「断开后复位」用。 */
    fun reset() {
        lastAtUptime = 0L
    }
}
