package io.github.shaonialife.carptocar.bt

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shaonialife.carptocar.util.DebugFileLog

/**
 * 蓝牙适配器状态翻转记录（2026-11-21 诊断，一次性观测工具）。
 *
 * ## 要回答的问题
 *
 * 建链期我们会反复断开车机的 HFP/A2DP。怀疑在**部分手机**上，「断开配置文件」会把整机
 * 蓝牙栈带崩（`com.android.bluetooth` 重启 / 射频 TURNING_OFF → ON），从而断掉我们自己的
 * iAP2 RFCOMM、让会话失效、触发下一轮重连——表现为「越断越停不下来」。
 *
 * 这个文件负责记录**最直接的证据**：适配器状态的变化与时刻。它写进 [DebugFileLog]（与
 * `BT-GUARD` / `BT-C` / `BT-PROFILE` 同一个 `main_debug.log`，同一把毫秒时钟），判读时把三
 * 类行对齐即可：
 *
 * ```
 * [xx:xx:xx.100] BT-PROFILE: 请求断开 [通话(HFP)] · 适配器态=ON(12)
 * [xx:xx:xx.300] BT-STATE: 适配器 ON(12) → TURNING_OFF(13)   ← 假设命中
 * [xx:xx:xx.400] BT-PROFILE: profile 服务断开（通话(HFP)）——蓝牙栈重启信号
 * ```
 *
 * 若断开动作后**始终没有** `BT-STATE` 的 TURNING_OFF / TURNING_ON，则可以排除「手机整机
 * 蓝牙重启」这条路径，问题回到车机侧拉回配置文件（那由 `BT-GUARD: 车机把 … 拉回来了` 记录）。
 *
 * ## 为什么动态注册而非清单静态注册
 *
 * 只需要进程活着期间的观测（断开动作只发生在连接尝试中）；进程都没了也无从谈因果链。
 * 动态注册不需要权限声明、不拉起进程、不制造后台噪音，随 [io.github.shaonialife.carptocar.SenderApp]
 * 的生命周期存在即可。
 */
class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        val state =
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        val previous =
            intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF)
        if (state == previous) return
        DebugFileLog.append(
            context.applicationContext,
            "BT-STATE: 适配器 ${BtProfiles.stateName(previous)} → ${BtProfiles.stateName(state)}",
        )
    }
}
