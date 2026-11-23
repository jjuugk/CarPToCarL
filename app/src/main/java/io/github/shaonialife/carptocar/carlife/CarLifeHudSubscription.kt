package io.github.shaonialife.carptocar.carlife

/** 在既有 CarLife 心跳上推进一次 HUD 订阅，每条控制连接独立维护进度。 */
internal class CarLifeHudSubscription(
    private val send: (Int, ByteArray) -> Boolean,
) {
    private var requestedAt: Long? = null
    private var reply: CarLifeHudProtocol.SubscriptionReply? = null
    private var finished = false

    @Synchronized
    fun tick(now: Long) {
        if (finished) return
        val requested = requestedAt
        if (requested == null) {
            requestedAt = now
            finished = !send(CarLifeHudProtocol.SUBSCRIBE, CarLifeHudProtocol.subscription(CarLifeHudProtocol.modules))
            return
        }

        val response = reply
        if (response == null && now - requested < 5_000L) return
        finished = true
        val modules =
            when {
                response == null -> CarLifeHudProtocol.modules
                response.error != null -> emptyList()
                else ->
                    response.entries.mapNotNull { (id, supported) ->
                        id?.takeIf { supported == 1 && it in CarLifeHudProtocol.modules }
                    }
            }
        if (modules.isNotEmpty()) {
            send(CarLifeHudProtocol.START, CarLifeHudProtocol.subscription(modules))
        }
    }

    @Synchronized
    fun receive(payload: ByteArray) {
        if (requestedAt != null && !finished) reply = CarLifeHudProtocol.reply(payload)
    }
}
