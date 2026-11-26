package io.github.shaonialife.carptocar.airplay

import android.content.Context

/**
 * 「音频优先 · 只发一次 RECORD」开关（默认关 = 按车机记忆/自动学习）。
 *
 * 打开后本轮会话强制走 [StreamOrderMemory.Order.AUDIO_FIRST]：
 * 屏幕 SETUP → 连数据口 → **音频 SETUP** → **一次 RECORD** 同时激活视频+音频，
 * 之后再加入的流一律不再补发 RECORD（`RecordBudget.restrictToSingle()`）。
 *
 * 为什么需要它：项目本来靠「已知型号白名单 + 失败后自动学习」按接收端记忆顺序，
 * 但有一类接收端在**补发 RECORD 的同一毫秒**就 `control EOF`（奔驰 MBUX，2026-11-26
 * issue），归因链来不及记录，学习永远学不出来，于是每一轮都重复同一次死亡。
 * 对这类车机只能由用户显式指定。
 *
 * 做成全局开关而不是给白名单加 `mb_d-m`：
 * - 品牌串在建链早期拿不到，`/info` 的 model 与蓝牙名（`MBUX 35885`）也不一致；
 * - 同品牌不同固件行为可能相反（奥迪 MIB3 要补发、宝马 iD6/MBUX 不能补发），
 *   按车型写死容易误伤；
 * - 用户要求「不做车型门控」，出问题时能用开关立刻回退。
 *
 * 打开后仍需第 4 条（音频延迟取车机声明值）配合：音频 SETUP 要排在 RECORD 之前，
 * 若延迟仍硬报 250ms，部分车机会在 SETUP 阶段就 EOF。
 */
object AudioFirstOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "audio_first_forced_v1"

    /** 进程内当前值。启动时 [load] 一次；界面拨动时 [save] 立即改字段。 */
    @Volatile
    var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled = prefs(context).getBoolean(KEY, false)
    }

    fun save(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val label: String
        get() = if (enabled) "开" else "关"

    val note: String
        get() =
            if (enabled) {
                "已开启：先 SETUP 音频，再一次 RECORD 同时激活视频+音频，全程只发一次 RECORD。" +
                    "奔驰 MBUX 等「一放音频就断（补发 RECORD 后立刻 control EOF）」的车机适用。下次连接起效。"
            } else {
                "默认关：按车机记忆/自动学习决定顺序（奥迪 MIB3 走「RECORD 优先 + 补发」）。" +
                    "奔驰 MBUX、或一放音频就断的车机，尝试打开此项。下次连接起效。"
            }
}
