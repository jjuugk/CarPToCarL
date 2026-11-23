package io.github.shaonialife.carptocar.input

import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.input.TouchReports.Contact
import io.github.shaonialife.carptocar.protocol.Capability
import io.github.shaonialife.carptocar.protocol.Delivery
import io.github.shaonialife.carptocar.protocol.ProtocolEvent
import io.github.shaonialife.carptocar.protocol.ProtocolId
import io.github.shaonialife.carptocar.protocol.ProtocolRegistry

/** 将车机 HID 触点经协议路由转发到 CarLife 触控通道。 */
object TouchRouter {
    fun onContact(contact: Contact) {
        // 不能在分派前把 (0,0) 的抬起事件当空槽过滤：车机抬起时常这样报告。
        // 丢掉 UP 会让引擎的 eventIng 一直为 true，后续拖拽被当成同一次手势的 MOVE。
        dispatchTouch(contact)
    }

    /**
     * CarLife 本地劫持生效时，经其触控通道回传触点。
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
