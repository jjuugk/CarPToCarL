package io.github.shaonialife.carptocar.carlife

import android.content.Context

/**
 * 「使用车机 GPS」开关（偏好 `carlife_car_gps`，默认开）。
 *
 * ## 它控制什么
 *
 * 只控制一件事：**要不要把车机经 iAP2 0xFFFB 推来的定位，转成 CarLife 的 0x18010
 * `CarlifeCarGps` 发给 CarLife+ 引擎**。
 *
 * - 开：车机有经纬度时（`$GPGGA`/`$GPRMC`）就转发，CarLife 地图在「车机定位优先」时
 *   用车的坐标——车内手机 GPS 差（贴膜、仪表台下、无线充电板上）时唯一能救导航定位的路；
 * - 关：一个字节都不发，CarLife 完全按手机自己的定位走。车机只发 `$PASCD`（无经纬度）
 *   时开不开都一样，日志会写明「样本无经纬度」。
 *
 * ## 为什么给用户留开关
 *
 * 车机定位不是永远更好：有的车机 GPS 天线在仪表台里、精度不如手机；也有车型给的是
 * 「上次熄火时的位置」。用户能一键回到「只用手机 GPS」，比我们替他决定更稳。
 *
 * 默认值取 true：这是用户明确要的功能；出问题时开关就在显示卡里，一眼能找到。
 */
object CarGpsOption {
    private const val PREFS = "car_gps_option_v1"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = true

    /**
     * 当前是否启用。用 `@Volatile` 而不是每次都读 SharedPreferences：iAP2 收帧线程
     * 每秒会问一次，读盘没必要；[load]/[save] 是唯一写入口，两处都同步更新它。
     */
    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    /** 启动时调用一次（[io.github.shaonialife.carptocar.SenderApp]）。 */
    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        enabled =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    /** 开关切换时调用（UI 层），立即生效——下一条定位就按新值走。 */
    fun save(
        context: Context?,
        value: Boolean,
    ) {
        enabled = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
