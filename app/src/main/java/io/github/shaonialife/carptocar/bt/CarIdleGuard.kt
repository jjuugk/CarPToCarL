package io.github.shaonialife.carptocar.bt

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.service.CarIdleReceiver
import io.github.shaonialife.carptocar.service.CarPlayService
import io.github.shaonialife.carptocar.util.DebugFileLog

/**
 * 「车机远离后收摊」的执行者（2026-10-30）。
 *
 * ## 为什么需要它
 *
 * 靠近车机自动启动把 App 从口袋里唤醒后，这条链路并不会自己消失：界面退在后台、
 * 保活服务挂着唤醒锁与 WiFi 锁、进程常驻。人已经走了，手机还在替一辆不在跟前的车
 * 保持常亮——电量就是这么漏掉的。
 *
 * 所以车机蓝牙链路断开后起一个 30 秒的计时（[CarIdlePolicy.IDLE_TIMEOUT_MILLIS]）：
 * 到点仍未回连，就结束会话、停掉保活服务、清掉通知，**并把进程一并退出**。
 *
 * ## 为什么退出进程不会「杀死」静默检测
 *
 * 静默检测靠的是清单里静态注册的 [CarApproachReceiver]：系统在收到 `ACL_CONNECTED`
 * 时会为新进程拉起它，**不需要 App 预先在跑**。所以这里杀掉进程反而正合期待——
 * 下次靠近车机时系统重新拉起进程 → 接收器 → 把界面带到前台 → 自动连接，
 * 整条闭环不受影响。反过来说，杀进程前**必须**把偏好复位（见 [shutdown]），
 * 否则新进程会带着旧的「刚手动断开过」抑制状态醒来，那次靠近就白唤醒了。
 *
 * ## 计时为什么用 AlarmManager
 *
 * 断开的那一刻进程可能只活到广播结束，Handler 的计时不保险；而 AlarmManager 的
 * PendingIntent 到点会把进程重新拉起（此时进程里只剩静态接收器也能收）。
 * `setAndAllowWhileIdle` 不需要「精确闹钟」权限：正常场景就是 30 秒，
 * 只有设备已进 doze 时才会推迟——那种情况人已经走远，晚几分钟收摊没有影响。
 */
object CarIdleGuard {
    /** 与 [CarIdleReceiver] 约好的内部动作（只在应用内显式广播，不对外导出）。 */
    const val ACTION_IDLE_TIMEOUT = "io.github.shaonialife.carptocar.action.CAR_IDLE_TIMEOUT"

    private const val REQUEST_CODE = 4101

    /**
     * 结束会话到真正 kill 之间留的时间：disconnect 会在后台线程收尾
     * （RFCOMM close、虚拟屏释放、日志落盘），掐太快会丢最后几行日志，
     * 而这几行正是事后判断「为什么没连上」的唯一线索。
     */
    private const val KILL_DELAY_MILLIS = 600L

    /** 起 30 秒计时（重复调用只是把到点时刻往后推，不会叠加多个闹钟）。 */
    fun schedule(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending =
            pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + CarIdlePolicy.IDLE_TIMEOUT_MILLIS
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending,
            )
        }
    }

    /** 车机回连（或用户主动给了新指令）时撤掉计时。 */
    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        runCatching { manager.cancel(pending) }
        runCatching { pending.cancel() }
    }

    /** 闹钟到点：[CarIdleReceiver] 转交进来，这里判定要不要真的收摊。 */
    fun onTimeout(context: Context) {
        val verdict =
            CarIdlePolicy.onIdleTimeout(
                aclConnected = CarApproachPrefs.aclConnected(context),
                streaming = SessionController.isStreaming(),
                uiVisible = AppUiPresence.isVisible,
            )
        if (verdict != CarIdlePolicy.Verdict.CLOSE) {
            // 30 秒内回连是最常见的分支（车机重启/信号抖动），不值得写日志刷屏；
            // 只在「画面还在送」时留一行，解释为什么没有退出。
            if (verdict == CarIdlePolicy.Verdict.STREAMING) {
                DebugFileLog.append(context, "IDLE: 车机蓝牙已断开 30 秒，但画面仍在推送——暂不退出进程")
            }
            return
        }
        shutdown(
            context,
            reason = "车机已远离（蓝牙断开 30 秒未回连）",
            killProcess = true,
            // 车机确实走了：ACL 断开时已解除抑制，这里不必再动，保持「下次靠近可再拉起」。
            suppressAutoStart = false,
        )
    }

    /**
     * 结束会话、停服务、清通知，可选地把进程也退掉。
     *
     * @param killProcess true = 收尾后退出进程（静默检测不受影响，见类注释）
     * @param suppressAutoStart true = 置「刚断开过」抑制，避免车机仍连着时链路抖动
     *   立刻又把 App 拉起来（那会变成「断开 → 自动重连」的死循环）；车机真断开时
     *   这个标记会由 [CarApproachReceiver] 自动清除，不影响下次靠近。
     */
    fun shutdown(
        context: Context,
        reason: String,
        killProcess: Boolean,
        suppressAutoStart: Boolean,
    ) {
        cancel(context)
        DebugFileLog.append(
            context,
            "IDLE: $reason——结束会话、停止保活服务" + if (killProcess) "，随后退出进程（静默检测保留）" else "",
        )
        // 只有在车机**还连着**蓝牙时才需要抑制：那种情况下链路抖一下就会重发
        // ACL_CONNECTED，不压一下会变成「关掉 → 立刻又被拉起」。车机已经走了
        // （aclConnected = false）就没什么可抑制的，下次靠近理应能直接触发。
        if (suppressAutoStart && CarApproachPrefs.aclConnected(context)) {
            CarApproachPrefs.setSuppressed(context, true)
        }
        runCatching { SessionController.disconnect(context) }
            .onFailure {
                DebugFileLog.append(context, "IDLE: 断开过程异常（${it.javaClass.simpleName}），继续收尾")
            }
        // 再显式停一次保活服务：disconnect 中途抛异常时可能没走到停止那步，
        // 而服务是 START_STICKY——「还在跑的时候把进程杀掉」会被系统重新拉起，
        // 表现为「退出后通知栏又冒出来一条」，白忙一场。
        runCatching { context.stopService(Intent(context, CarPlayService::class.java)) }
        // 兜底清通知：服务正常停止时通知会随之消失，但 disconnect 中途抛异常就留住了。
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(CarPlayService.NOTIFICATION_ID)
            manager.cancel(CarApproachReceiver.BLOCKED_NOTIFICATION_ID)
        }
        if (!killProcess) return
        Handler(Looper.getMainLooper()).postDelayed(
            {
                // killProcess 而不是 exit：走的是同一套「进程被回收」路径，
                // 系统下一次投递 ACL_CONNECTED 时照样能把静态接收器拉起来。
                Process.killProcess(Process.myPid())
            },
            KILL_DELAY_MILLIS,
        )
    }

    private fun pendingIntent(
        context: Context,
        flags: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, CarIdleReceiver::class.java).setAction(ACTION_IDLE_TIMEOUT),
            flags,
        )
}
