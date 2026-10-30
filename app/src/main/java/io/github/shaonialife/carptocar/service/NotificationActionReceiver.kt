package io.github.shaonialife.carptocar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.util.DebugFileLog

/**
 * 通知栏动作的接盘者（2026-10-30）。
 *
 * 为什么需要它：靠近车机自动启动后，连接跑在**后台**，界面上没有任何窗口——
 * 通知栏因此是用户唯一看得见、也唯一点得到的地方。断开这个动作必须能在
 * 「界面不存在 / 进程里只有服务」的情况下独立完成，所以走广播而不是
 * 依赖 MainActivity 的按钮。
 *
 * 只做一件事：调 [SessionController.disconnect]（它内部会停保活服务、
 * 通知随之消失），然后把「谁点的」写进日志，便于事后对账。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != CarPlayService.ACTION_DISCONNECT) return
        DebugFileLog.append(context, "SVC: 用户在通知栏点了「断开」——停止会话并退出保活服务")
        SessionController.disconnect(context)
    }
}
