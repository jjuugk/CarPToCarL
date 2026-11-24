package io.github.shaonialife.carptocar.media

import java.util.Locale

/**
 * 导航文案格式化。**零 Android 依赖，可单测。**
 *
 * 抽出来的理由：媒体卡片（NowPlaying 覆盖层）的文字有**两个生产者**——
 * CarLife 引擎的 `0x10030` 和高德 linksdk。两边的距离格式必须一模一样，
 * 否则用户切开关时会看到「200m」和「0.2km」两种写法，像是换了个 App。
 *
 * 转向**名称**没有抽到这里：CarLife 那份表（[NavigationDisplay] 的 `turnName`）
 * 吃的是 protobuf raw 码，而高德侧手上是 `CarLifeNaviGuide` 的 ICON 码，
 * 两套编码不同，各用各的表反而不会串。高德侧直接用 `CarLifeNaviGuide.label`。
 */
internal object NavigationText {
    /**
     * 米 → `"200m"` / `"1.3km"`。
     *
     * 负数（高德未定位时会给 -1）按 0 处理：车机上看到「-1m 右转」比看到
     * 「0m 右转」更让人怀疑人生。
     */
    fun distance(meters: Int): String {
        val m = meters.coerceAtLeast(0)
        return if (m < 1000) {
            "${m}m"
        } else {
            String.format(Locale.ROOT, "%.1f", m / 1000.0).removeSuffix(".0") + "km"
        }
    }

    /** 媒体卡片第一行：`"200m 右转"`。 */
    fun maneuver(
        meters: Int,
        turnLabel: String,
    ): String = "${distance(meters)} $turnLabel"
}
