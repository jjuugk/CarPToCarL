package io.github.shaonialife.carptocar.airplay

import android.content.Context

/**
 * SETUP `features` 清单裁剪开关（默认关闭）。
 *
 * 背景：我们在会话 SETUP 里固定提议 14 项能力（见 [AirPlayClient] 的 `CONTROLLER_FEATURES`），
 * 其中至少 7 项**代码里没有任何实现路径**：`cornerMasks` / `mainBuffered` / `altScreen` /
 * `hevc` / `sessionManagement` / `logTransfer` / `vehicleStateProtocol`。
 *
 * 车机侧的逻辑是 `enabledFeatures = 车机支持 ∩ 手机提议`——**没被提议的一律不启用**。
 * 所以：
 *  - 少提 → 该项能力不启用。这是硬失败，奥迪 2026-10-11 那次漏提 `videoPlayback`
 *    直接被回 400，一眼就能看见。
 *  - 多提 → 车机按「你会」去开分支，然后发来我们不会处理的消息或等我们不会发的报文。
 *    表现是**半开、静默失效、等超时**，比 400 难查得多。
 *
 * 两个方向的代价不对称，所以「照抄 iPhone 全量清单」不等于更兼容，只是把不确定性
 * 从明处挪到暗处。hypercar 的做法是公共清单只留它实现了的 8 项，并把「不该提议
 * 那三项」写成单测固定（`sessionManagement` / `logTransfer` / `vehicleStateProtocol`）。
 *
 * 但它自己的 `docs/REFERENCE-REVIEW.md` 又留了一句「不能直接认定某项是 400 原因，
 * 不应全车型统一删减」——说明它内部也没把这条当定理。所以我们做成开关，不改良心默认。
 *
 * 打开后只裁掉上面那 7 项，**保留** `videoPlayback` / `viewAreas` / `h.264Level5.1` /
 * `uiContext` / `focusTransfer` / `enhancedSiri` / `iAPChannel` 七项——它们要么必需
 * （`videoPlayback` 缺了就 400）、要么是已实现或至少无害。关闭时清单与改动前逐字节一致。
 */
object FeatureTrimOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "feature_trim_v1"

    /**
     * 要裁掉的能力（都无对应实现路径）。
     *
     * `hevc` 也在这里：我们没有 HEVC 管线（CarLife 只给 H.264），提了也永远不会走；
     * hypercar 更严——只在「有效偏好允许」且车机回显时才用。
     */
    val TRIMMED: Set<String> =
        setOf(
            "cornerMasks",
            "mainBuffered",
            "altScreen",
            "hevc",
            "sessionManagement",
            "logTransfer",
            "vehicleStateProtocol",
        )

    /** 进程内当前值。启动时 [load] 一次；界面拨动时 [save] 立即改字段。 */
    @Volatile
    var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
    }

    fun save(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, value)
            .apply()
    }

    /**
     * 按开关裁剪提议清单。
     *
     * 顺序保持不变（车机侧不保证对顺序不敏感，但也没证据说它敏感——少动一项是一项）。
     * 关闭时返回原列表**同一个实例**，保证与改动前逐字节一致。
     */
    fun apply(base: List<String>): List<String> = apply(base, enabled)

    /**
     * 纯函数版本（开关值显式传入）：单测直接调它，不必构造 Context 或改全局状态。
     *
     * `trim = false` 时原样返回 `base`（同一实例），`trim = true` 时按 [TRIMMED] 过滤。
     */
    fun apply(
        base: List<String>,
        trim: Boolean,
    ): List<String> = if (trim) base.filterNot { it in TRIMMED } else base

    val label: String
        get() = if (enabled) "开" else "关"

    val note: String
        get() =
            if (enabled) {
                "已开启：不再提议 7 项无实现的扩展能力（cornerMasks / mainBuffered / altScreen / " +
                    "hevc / sessionManagement / logTransfer / vehicleStateProtocol）。下次连接起效。"
            } else {
                "默认关：按 iPhone 全量清单提议 14 项能力。若出现「连上后功能半开、个别操作无响应」" +
                    "可开启此项排查。下次连接起效。"
            }
}
