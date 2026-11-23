package io.github.shaonialife.carptocar.input

import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.input.TouchReports.Contact
import io.github.shaonialife.carptocar.protocol.Capability
import io.github.shaonialife.carptocar.protocol.Delivery
import io.github.shaonialife.carptocar.protocol.ProtocolEvent
import io.github.shaonialife.carptocar.protocol.ProtocolId
import io.github.shaonialife.carptocar.protocol.ProtocolRegistry

/**
 * 触摸路由：车机 `hidSendReport` 触点 → 画面来源的触控通道。
 *
 * **2026-11-23 起只剩一条去向**：本地劫持原生 CarLife（`CarLifeHijack.handleTouch`，
 * 9340 socket 回传）。原先「按 dock/app/地图屏分流 + Shizuku 注入虚拟屏」的整条链路
 * 已随隐形屏一并删除，本类不再需要坐标缩放、move 节流与跨屏状态机。
 */
object TouchRouter {
    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    fun onContact(contact: Contact) {
        // 先派发、后过滤空槽：车机抬起时常常把坐标报成 (0,0)，若先过滤，UP 会被
        // 直接丢掉，引擎侧 eventIng 一直是 true，后续拖拽全被当成同一次手势的 MOVE
        // （表现为「点一下之后拖不动、只能再点一下复位」）。
        if (dispatchTouch(contact)) return
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        diag("触点无处可去——本次会话没有 CarLife 劫持（虚拟屏注入通道已随隐形屏删除）")
    }

    /**
     * 触摸的第一道分派：CarLife 本地劫持生效时走它的触控通道回传。
     *
     * 装配了协议门面就走 Registry 的 FIRST_MATCH（`CarLifeModule.onTouch` 委托回
     * `CarLifeHijack.handleTouch`），未装配直接调同一个函数——两种运行态互斥，行为一致。
     */
    private fun dispatchTouch(contact: Contact): Boolean =
        if (ProtocolRegistry.hasConsumer(Capability.TOUCH)) {
            ProtocolRegistry
                .route(
                    ProtocolEvent.Touch(
                        down = contact.down,
                        x = contact.xPixels,
                        y = contact.yPixels,
                        pointerId = contact.slot,
                        from = ProtocolId.CARPLAY,
                    ),
                    Delivery.FIRST_MATCH,
                ).handled
        } else {
            CarLifeHijack.handleTouch(contact.slot, contact.xPixels, contact.yPixels, contact.down)
        }
}
