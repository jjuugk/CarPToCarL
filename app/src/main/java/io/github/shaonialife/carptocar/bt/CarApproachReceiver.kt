package io.github.shaonialife.carptocar.bt

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.shaonialife.carptocar.EXTRA_CAR_APPROACH
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.util.DebugFileLog

/**
 * 靠近车机自动启动（2026-10-30）——**静默检测**的实现方式。
 *
 * 为什么用系统 ACL 广播而不是扫描：
 * - 车机（尤其自带热点的车机）在进入范围后会**自己回连手机蓝牙**，系统随即发出
 *   `ACTION_ACL_CONNECTED`；这是「靠近」最准、最省电、且完全无声的信号。
 * - 主动 `startDiscovery()` 会持续占用射频、弹系统扫描提示、耗电，且 Android 12+
 *   还要 BLUETOOTH_SCAN 权限——与「静默」相悖，故本项目不用。
 * - 本接收器是**清单静态注册**：App 没打开、进程没起来也能收（系统投递时拉起进程），
 *   所以不需要常驻前台服务，也就没有常驻通知。
 *
 * ## 命中后做什么：只把 App 叫到前台，连接由前台发起
 *
 * 2026-10-30 实车反馈：本接收器原先还会「不等界面，直接在后台起一轮连接」。
 * 那样确实把链路建了起来，但**会话是残缺的**——车机端收不到任何数据。
 * 原因是整条管线并不只是 iAP2 拨号：CarLife 劫持、虚拟屏/编码器、以及
 * 界面侧的启动自检（残留虚拟屏清理、权限门禁）都挂在 Activity 这一路上，
 * 绕开界面直接连，握手能过，出流的那一段却起不来。
 *
 * 所以现在这里只做一件事：**把 App 带到前台**，并在 Intent 里带
 * [EXTRA_CAR_APPROACH]，前台据此重新武装「启动后自动连接」、走完整的
 * `ensureBluetoothReady → startConnection` 流程（含权限、自检、换流）。
 * 界面已经在后台时走 `onNewIntent`，只重新武装一次，不重建界面。
 *
 * 拉起失败（Android 10+ 的后台启动限制 / ROM 禁止自启动）时只记一行线索，
 * 不弹通知：下次用户手动打开 App 仍会照常自动连接，功能不会丢。
 */
class CarApproachReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(context, intent)
            // 链路断开 = 人走远了（或车机熄火）：解除「手动断开后安静」的抑制，
            // 并清掉节流时间戳，下次靠近可以正常再触发一次。
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (CarApproachPrefs.suppressed(context) || CarApproachPrefs.lastTriggerAt(context) > 0L) {
                    CarApproachPrefs.setSuppressed(context, false)
                    CarApproachPrefs.markTriggered(context, 0L)
                    DebugFileLog.append(context, "APPROACH: 车机蓝牙链路断开——已复位，下次靠近可再次自动启动")
                }
            }
        }
    }

    private fun onConnected(
        context: Context,
        intent: Intent,
    ) {
        val target = TargetDevicePrefs.get(context) ?: return
        val address =
            runCatching { deviceOf(intent)?.address }.getOrElse { error ->
                // 没有 BLUETOOTH_CONNECT 时读不到地址（Android 12+）——只记一次线索，不打扰
                if (error is SecurityException) {
                    DebugFileLog.append(context, "APPROACH: 缺少蓝牙权限，无法识别靠近的车机（请在 App 内授权后重试）")
                }
                null
            }
        val sessionRunning = SessionController.isSessionRunning() || SessionController.sessionStarting.get()
        val decision =
            CarApproachPolicy.onAclConnected(
                eventAddress = address,
                targetAddress = target.address,
                enabled = CarApproachPrefs.enabled(context),
                sessionRunning = sessionRunning,
                suppressed = CarApproachPrefs.suppressed(context),
                lastTriggerAt = CarApproachPrefs.lastTriggerAt(context),
                now = System.currentTimeMillis(),
            )
        if (decision != CarApproachPolicy.Decision.TRIGGER) {
            // 静默原则：非目标设备、开关关闭等常态一律不写日志（耳机/手表每天都在连）。
            if (decision == CarApproachPolicy.Decision.SUPPRESSED ||
                decision == CarApproachPolicy.Decision.SESSION_RUNNING
            ) {
                DebugFileLog.append(
                    context,
                    "APPROACH: 车机已靠近，但" +
                        (if (decision == CarApproachPolicy.Decision.SESSION_RUNNING) "已有会话" else "刚手动断开过") +
                        "——跳过本次自动启动",
                )
            }
            return
        }

        // ---- 只做一件事：把界面叫到前台，连接交给前台 ----
        // 顺序仍按「先动作、后写日志」：磁盘 IO 不挡在拉起界面前面。
        CarApproachPrefs.markTriggered(context)
        val launched = launchApp(context)
        DebugFileLog.append(
            context,
            "APPROACH: 检测到目标车机「${target.name}」靠近——" +
                if (launched) "已拉起 App，交给前台自动发起连接" else "拉起 App 被系统拒绝（需允许本应用「自启动/后台弹界面」）",
        )
    }

    /**
     * 把 App 带到前台。带 [EXTRA_CAR_APPROACH]，界面侧据此重新武装自动连接；
     * Activity 已在后台时走 `onNewIntent`，不重建界面。
     *
     * @return true = 已成功把启动请求交给系统；false = 被后台启动限制拦下
     */
    private fun launchApp(context: Context): Boolean {
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(EXTRA_CAR_APPROACH, true)
            }
        return runCatching { context.startActivity(launch) }
            .onFailure {
                // 后台启动 Activity 被系统拦截（Android 10+ 限制）：不弹通知、不刷日志，
                // App 下次被打开时仍会按「启动后自动连接」走一遍，功能不会丢。
                DebugFileLog.append(
                    context,
                    "APPROACH: 后台拉起 App 被系统拒绝（${it.javaClass.simpleName}）——请允许本应用「自启动/后台弹界面」",
                )
            }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
