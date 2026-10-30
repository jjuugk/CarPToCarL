package io.github.shaonialife.carptocar.bt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.shaonialife.carptocar.EXTRA_CAR_APPROACH
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.util.Bg
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
 * 拉起失败（Android 10+ 的后台启动限制 / ROM 禁止自启动）时**不一定能立刻知道**：
 * 系统是静默拒绝的——不报错、界面就是不出现。所以拉起后会隔 3.5 秒核对
 * [AppUiPresence]：没盖到章就发一条「车机已靠近，但系统没让 App 到前台」的通知，
 * 告诉用户去开「显示在其他应用上层」。这一条是兜底，不是常态：权限给到位时它不会出现。
 *
 * 通知只在**失败**时发；成功时通知栏里那条是保活服务（CarPlayService）的，不会重复。
 */
class CarApproachReceiver : BroadcastReceiver() {
    companion object {
        private const val BLOCKED_CHANNEL_ID = "carplay_approach"

        /** 「被系统拦下」提示的通知 id（[MainActivity] 起来后会主动撤掉它）。 */
        const val BLOCKED_NOTIFICATION_ID = 2
    }

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
        // 后台启动界面的豁免之一就是「持有显示在其他应用上层」——没有它，MagicOS 会
        // 静默拦掉这次启动（不抛异常、界面不出现）。这里先留一条线索，事后看日志能一眼定因。
        if (!android.provider.Settings.canDrawOverlays(context)) {
            DebugFileLog.append(
                context,
                "APPROACH: 未授予「显示在其他应用上层」——系统可能拒绝后台打开界面（静默，不报错）",
            )
        }
        val launchedAt = System.currentTimeMillis()
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(EXTRA_CAR_APPROACH, true)
            }
        val ok =
            runCatching { context.startActivity(launch) }
                .onFailure {
                    // 后台启动 Activity 被系统拦截（Android 10+ 限制）：不弹通知、不刷日志，
                    // App 下次被打开时仍会按「启动后自动连接」走一遍，功能不会丢。
                    DebugFileLog.append(
                        context,
                        "APPROACH: 后台拉起 App 被系统拒绝（${it.javaClass.simpleName}）——请允许本应用「自启动/后台弹界面」",
                    )
                }.isSuccess
        if (ok) verifyBroughtUp(context, launchedAt)
        return ok
    }

    /**
     * 核对「拉起是否真的生效」（2026-10-30）。
     *
     * 系统拦后台启动是**静默**的：不抛异常，界面就是不出现。用户看到的是
     * 「车机连上了、手机什么都没有」。这里 3.5 秒后比对 [AppUiPresence] 的盖章，
     * 没盖就发一条通知——至少让这次失败看得见、点得开，并说清要去开哪条权限。
     */
    private fun verifyBroughtUp(
        context: Context,
        launchedAt: Long,
    ) {
        Bg.run("approach-verify") {
            Thread.sleep(3_500L)
            if (AppUiPresence.lastBringUpAt >= launchedAt) return@run
            DebugFileLog.append(
                context,
                "APPROACH: 系统没有让 App 到前台（后台启动被拦）——已发通知提示授权",
            )
            runCatching { notifyLaunchBlocked(context) }
        }
    }

    private fun notifyLaunchBlocked(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BLOCKED_CHANNEL_ID,
                    "靠近车机提示",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "系统拦下后台打开界面时的提示"
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
        }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val hint =
            "系统拒绝了「后台打开界面」，本次没能自动连上。请到 设置 → 应用 → 特殊访问权限 → " +
                "显示在其他应用上层，允许本应用；或点这里手动打开。"
        manager.notify(
            BLOCKED_NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, BLOCKED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("车机已靠近，但系统没让 App 到前台")
                .setContentText(hint)
                .setStyle(NotificationCompat.BigTextStyle().bigText(hint))
                .setContentIntent(open)
                .addAction(0, "打开界面", open)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build(),
        )
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
