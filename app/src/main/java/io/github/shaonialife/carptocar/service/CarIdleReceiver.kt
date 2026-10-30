package io.github.shaonialife.carptocar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shaonialife.carptocar.bt.CarIdleGuard
import io.github.shaonialife.carptocar.util.DebugFileLog

/**
 * 车机远离后的收摊闹钟（2026-10-30）。
 *
 * 刻意与 [io.github.shaonialife.carptocar.bt.CarApproachReceiver] 分开：
 * 那个接收器必须 `exported="true"` 才收得到系统 ACL 广播，也就意味着任何一个 App
 * 都能给它发广播；而这个「结束会话 + 退进程」的动作用的是内部动作，放进去等于把
 * 关进程的权限开给全系统。这里 `exported="false"`，只有本应用自己的 PendingIntent
 * 能投递进来。
 */
class CarIdleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != CarIdleGuard.ACTION_IDLE_TIMEOUT) return
        DebugFileLog.append(context, "IDLE: 30 秒计时到点，开始判定是否收摊")
        CarIdleGuard.onTimeout(context)
    }
}
