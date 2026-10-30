package io.github.shaonialife.carptocar.bt

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.EXTRA_CAR_APPROACH
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.launcher.settings.AutoConnectPrefs
import io.github.shaonialife.carptocar.legal.DisclaimerPrefs
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.DeviceIdentity

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
 * 抢在车机自带「车联」之前的关键（2026-10-30 用户要求）：
 * 手机上车联（HiCar/CarWith 之类）同样监听蓝牙事件，谁先动手谁拿到这轮会话。所以
 * 本接收器**不等界面**，广播到达后按下面的顺序抢时间——
 * 1. 立刻 `startActivity`（拉界面是最慢的一环，越早排队越好，其余动作不阻塞它）；
 * 2. 紧接着在后台直接 `SessionController.startConnection`：RFCOMM 拨号立刻发出，
 *    只依赖已缓存的蓝牙地址，不做任何磁盘/网络准备；
 * 3. 最后才写文件日志（磁盘 IO 放在竞速动作之后，不拖慢前两步）。
 *
 * 注：荣耀/华为系 ROM 的「应用启动管理」若禁止本应用自启动，系统会拒绝为广播拉起进程，
 * 此时静默检测无效——需在系统设置里允许本应用自启动（并在「后台弹界面」放行）。
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

        // ---- 抢时间：先抢前台，再抢链路，最后才写日志 ----
        CarApproachPrefs.markTriggered(context)
        launchApp(context)
        val fastStarted = fastStartSession(context)
        DebugFileLog.append(
            context,
            "APPROACH: 检测到目标车机「${target.name}」靠近——已拉起 App" +
                (if (fastStarted) "并在后台直接发起连接" else "（后台直连未发起：需 App 前台完成前置检查）"),
        )
    }

    /**
     * 把 App 拉到前台。带 [EXTRA_CAR_APPROACH]，界面侧据此重新武装自动连接；
     * Activity 已在后台时走 onNewIntent，不重建界面。
     */
    private fun launchApp(context: Context) {
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(EXTRA_CAR_APPROACH, true)
            }
        runCatching { context.startActivity(launch) }
            .onFailure {
                // 后台启动 Activity 被系统拦截（Android 10+ 限制）：不弹通知、不刷日志，
                // App 下次被打开时仍会按「启动后自动连接」走一遍，功能不会丢。
                DebugFileLog.append(context, "APPROACH: 后台拉起 App 被系统拒绝（${it.javaClass.simpleName}）——请允许本应用「自启动/后台弹界面」")
            }
    }

    /**
     * 不等界面，直接在后台起一轮连接（这是跑赢车机自带车联的关键一步）。
     *
     * 只在「前面已经成功连过」的条件下做：免责声明已接受、且蓝牙地址可用
     * （用**已缓存**的地址，不触发 shell 解析，避免多花几百毫秒）。
     * 任一不满足就交给界面侧的完整前置检查——宁慢不半残。
     *
     * 后台启动前台服务在 Android 12+ 一般受限，但「收到需要 BLUETOOTH_CONNECT 权限的
     * 蓝牙广播」是官方豁免项，本次正属于该情形；仍做兜底，失败只记一行。
     */
    private fun fastStartSession(context: Context): Boolean {
        if (!AutoConnectPrefs.enabled(context)) return false
        // 没有 BLUETOOTH_CONNECT 时既连不上，也拿不到「后台启动前台服务」的豁免，直接交给界面引导授权
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!DisclaimerPrefs.hasAcceptedCurrent(context)) return false
        val mac =
            DeviceIdentity.overrideBluetoothMac(context)?.takeIf { it != DeviceIdentity.PLACEHOLDER_MAC }
                ?: DeviceIdentity.cachedBluetoothMac()?.takeIf { it != DeviceIdentity.PLACEHOLDER_MAC }
                ?: return false
        return runCatching {
            SessionController.ensure(context.applicationContext)
            SessionController.startConnection(context.applicationContext)
            DebugFileLog.append(context, "APPROACH: 后台已直接发起连接（地址 $mac，不等界面）")
            true
        }.getOrElse { error ->
            DebugFileLog.append(context, "APPROACH: 后台直连失败（${error.javaClass.simpleName}: ${error.message}）——交给界面重试")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
