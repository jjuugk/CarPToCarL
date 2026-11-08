package io.github.shaonialife.carptocar.bt

import android.content.Context

/**
 * 「车机只认苹果设备」兼容模式（默认 **开**，2026-11-08 起；此前默认关）。
 *
 * 解决的现象：宝马 iD6 在蓝牙设备列表里给本机打 `设备不支持 Apple CarPlay`。
 * 这句判定发生在 iAP2 之前——车机还没和我们跑任何协议就下了结论，所以改 0x4300、
 * 改识别顺序在这里全都无效。它看的是蓝牙**发现层**：
 *  - EIR 里有没有 `2d8d2466-…`（iPhone 广播的无线 CarPlay UUID）；
 *  - SDP 记录里有没有 Wireless iAP v2 / CarPlay 的服务。
 *
 * Android 应用层改不动 EIR（要 hook system/bt），能做的只有**多注册两条 SDP 记录**
 * 当占位（凯迪拉克那款同类 APK 就是这么干的：只占位、没有协议处理器）。
 * 打开本开关后：
 *  1. 蓝牙监听时额外注册下面两条占位 SDP 记录（见 [BtIdentityProbe]）；
 *  2. iAP2 监听**常开**——App 在跑就发布服务，不要求先点「启动连接」
 *     （先配蓝牙后开 App 的车机，那一瞥看不到任何服务，很容易把设备记成「不支持」）。
 *
 * 为什么默认开（2026-11-08 改）：宝马 NBT EVO 那份实现证明「标记 SDP 必须常驻」——
 * 车机是在**配对/discovery 阶段**就把设备记成「不支持 Apple CarPlay」的，等用户点了
 * 「启动连接」才注册记录已经晚了（那一瞥看到的服务列表里什么都没有）。所以现在
 * App 一起来就挂上占位记录 + 常开监听。奥迪 MIB3 这类固件不看 EIR/SDP 里的 Apple
 * UUID，多两条记录对它没有影响；真要退回去，用户在设置里关掉这个开关即可。
 */
object BtCompatPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_IDENTITY_PROBE = "bt_identity_probe"

    /** 是否开启「车机只认苹果设备」兼容模式（探针 SDP + 监听常开）。 */
    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IDENTITY_PROBE, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IDENTITY_PROBE, enabled)
            .apply()
    }
}
