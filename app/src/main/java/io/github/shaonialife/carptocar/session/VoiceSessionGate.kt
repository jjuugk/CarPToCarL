package io.github.shaonialife.carptocar.session

/**
 * 一次语音请求独占一份待建输入流资源，直到取消或失败。
 *
 * 从 AMcar 的 `VoiceSessionGate` 原样移植：代次令牌让「上一轮还没 SETUP 完、这一轮已经结束」
 * 的异步开流无法污染新会话，锁内只允许做非阻塞动作（入队/改状态），绝不做网络 I/O。
 */
class VoiceSessionGate<T> {
    enum class Phase { IDLE, PREPARING, WAITING_FOR_PCM, FORWARDING, FAILED }

    private var generation = 0L
    private var resource: T? = null

    var phase: Phase = Phase.IDLE
        @Synchronized get
        private set

    @Synchronized
    fun begin(): Long? {
        if (phase == Phase.PREPARING || phase == Phase.WAITING_FOR_PCM || phase == Phase.FORWARDING) return null
        generation++
        phase = Phase.PREPARING
        return generation
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = generation == token && phase != Phase.IDLE && phase != Phase.FAILED

    @Synchronized
    fun attach(
        token: Long,
        value: T,
    ): Boolean {
        if (!isCurrent(token) || phase != Phase.PREPARING || resource != null) return false
        resource = value
        return true
    }

    /** SETUP/RECORD 成功；已挂上的接收端可以开始等首帧认证 PCM。 */
    @Synchronized
    fun markWaiting(token: Long): Boolean {
        if (!isCurrent(token) || resource == null) return false
        return when (phase) {
            Phase.PREPARING -> {
                phase = Phase.WAITING_FOR_PCM
                true
            }
            Phase.WAITING_FOR_PCM -> true
            else -> false
        }
    }

    /** 原子地校验归属并把 PCM 交给有界下游；offer 不得阻塞、关 socket 或做网络 I/O。 */
    @Synchronized
    fun offerIfCurrent(
        token: Long,
        offer: () -> Boolean,
    ): Boolean {
        if (!canForward(token)) return false
        return offer()
    }

    @Synchronized
    fun forwarding(
        token: Long,
        publish: () -> Unit,
    ): Boolean {
        if (!canForward(token)) return false
        phase = Phase.FORWARDING
        publish()
        return true
    }

    private fun canForward(token: Long): Boolean =
        isCurrent(token) &&
            resource != null &&
            (phase == Phase.WAITING_FOR_PCM || phase == Phase.FORWARDING)

    @Synchronized
    fun cancel(publish: () -> Unit = {}): T? {
        generation++
        phase = Phase.IDLE
        publish()
        return resource.also { resource = null }
    }

    @Synchronized
    fun fail(
        token: Long,
        publish: () -> Unit = {},
    ): T? {
        if (!isCurrent(token)) return null
        phase = Phase.FAILED
        publish()
        return resource.also { resource = null }
    }
}
