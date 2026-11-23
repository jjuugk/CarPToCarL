package io.github.shaonialife.carptocar.input

import android.content.Context

/**
 * 触摸注入客户端门面。
 *
 * **2026-10-24 起 Shizuku 依赖已整体移除**：本类不再绑定任何常驻 shell 进程，
 * [service] 恒为 null，注入调用一律失败并降级。
 *
 * 为什么可以直接停用：现在车机画面唯一来源是「本地劫持原生 CarLife」，车机触控由
 * CarLife 自己的 9340 socket 回传（见 `CarLifeHijack.handleTouch`），完全不需要
 * 系统级 MotionEvent 注入；虚拟屏/桌面那套已在 2026-11-23 整体删除。
 *
 * 保留这个门面只为 `MainActivity` 的「旧版本残留虚拟屏清理」还有调用点，
 * 它们对 `service == null` 本来就有降级分支。
 */
object CarInputClient {
    /** 恒为 null（Shizuku 已移除）。保留字段只为类型占位。 */
    @Volatile
    var service: ICarInput? = null
        private set

    /** 诊断日志出口（接入文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    @Volatile
    private var announced = false

    /** 曾经的绑定入口，现仅提示一次「已停用」。 */
    fun ensure(context: Context) {
        if (announced) return
        announced = true
        logSink?.invoke("INPUT: 触摸注入已停用（Shizuku 已移除）——车机触控走 CarLife 通道")
    }

    /** 旧版残留 shell 进程回收：Shizuku 移除后无对象可回收，恒返回 0。 */
    fun reapStaleUserServices(): Int = 0
}
