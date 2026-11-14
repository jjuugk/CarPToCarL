package io.github.shaonialife.carptocar.transport

import io.github.shaonialife.carptocar.iap2.Iap2LinkEngine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 链路通道：一个读线程 + 一个节奏线程驱动 [Iap2LinkEngine]，向会话层暴露
 * 阻塞式的 sendControl/recvControl 与 awaitReady。
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    private val log: (String) -> Unit = {},
) : Closeable {
    private val engine = Iap2LinkEngine()
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false
    private var readySignalled = false

    /** 本端主动退役关闭时置 true，避免上层误判成对端新发起的断链。 */
    @Volatile
    var closedLocally = false
        private set

    /** 原始字节转储预算（每方向每连接），真车字节级对照用。 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    /** 逐帧摘要日志预算（每方向每连接）。 */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    val isAlive: Boolean get() = !terminated

    /**
     * 对端**控制帧**计数（任何 ctl 帧，含纯 ACK）；链路层心跳 `ff 55 02 00 ee 10` 不计。
     *
     * 用途：判断「车机是否还在处理我们发的帧」。2026-10-26 别克 E2LB 实测：发出 0x4300
     * 之后该计数再也不动，但 RFCOMM 连接还活着（心跳照发）——即车机的控制会话已经停摆，
     * 而不是链路断开。会话层据此把「对 0x4300 的报文体不兼容」记下来并自动换形态。
     */
    @Volatile
    var peerFrameCount: Int = 0
        private set

    /**
     * 对端**业务帧**计数（len > 0 的控制帧；纯 ACK 与心跳都不算）。
     *
     * 为什么还要这个：判「车机是否还在处理我们的帧」只看 [peerFrameCount] 会**漏判**。
     * 2026-11-10 别克 BUICK_3138 日志：0x4300 发出去之后车机连回 3 个纯 ACK
     * （ack=91/92/95，全是我们那串问候的迟到确认），[peerFrameCount] 因此涨了 3，
     * 于是「0x4300 报文体把车机打停」被误判成「车机还在回帧」，阶梯永远停在第一档，
     * 每轮都用同一份字节、死在同一处。业务帧计数在这 30 秒里**完全不动**，才是真静默。
     */
    @Volatile
    var peerDataFrameCount: Int = 0
        private set

    /**
     * 对端已确认到的我方序号（null = 本轮一次 ACK 都没收到过）。
     *
     * 会话层用「发送前后的水位差」判断某一帧到底有没有被读走：水位不动 = 车机已经不读
     * 这条链路（2026-11-11 21:12 别克 E2LB：0x4300 之后三档全被丢弃，ack 停在 43，
     * 我们空等保活 5 分钟）。与 [peerFrameCount] 的区别是它只认 ACK 位推进，不会被
     * 「车机自说自话的控制帧」掺进来。
     */
    fun peerAcknowledgedSequence(): Int? = synchronized(lock) { engine.acknowledgedSequence }

    /** 我方最后发出的控制帧序号（配合 [peerAcknowledgedSequence] 判「最后一帧被读走没」）。 */
    fun lastSentSequence(): Int = synchronized(lock) { engine.lastSentSequence }

    init {
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "LINK: 采纳对端会话表 maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength} " +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onUnknownSessionData = { sessionId, size ->
            log("LINK: 丢弃未知会话 $sessionId 的数据帧（$size 字节）")
        }
        engine.onFrameAbandoned = { detail ->
            log("LINK: ⚠ $detail（这一帧丢了，但链路没拆——车机不认它不等于链路坏了）")
        }
        // 对端窗口满而停发（发送窗口按 min(本方 0x7F, 对端声明) 计）。
        // 日志要给到「从第几帧起被卡住、卡了多少帧」，否则现场只能看到「发了没 ACK」。
        engine.onSendWindowHold = { peerMax, effective, backlog ->
            log(
                "LINK: 对端窗口 $peerMax 帧（本端声明 ${engine.declaredSendWindow}）→ 按 $effective 发，" +
                    "其余 $backlog 帧 holding 等 ACK——未确认帧已占满车机接收窗口",
            )
        }
        engine.onSendWindowReleased = { effective, heldMillis ->
            log("LINK: 对端窗口放行（窗口 $effective，停发 ${heldMillis}ms）——继续发积压帧")
        }
        // 非 DETECT 的握手应答（车机侧 sendLackOfBackwardCompatibility 就在这一族）。
        // 以前这类字节被帧头扫描逐字节丢弃，日志里只能看到「协商无进展」。
        engine.onPeerHandshake = { handshake ->
            log(
                "LINK: 对端握手应答（非 DETECT）raw=${handshake.hex()} " +
                    "len=${handshake.declaredPayloadLength}（累计 ${engine.peerHandshakeRejections} 次）",
            )
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            peerFrameCount++
            if (size > 0) peerDataFrameCount++
            // 真车调试关键观测：车机在识别阶段是否回帧、回什么（静默分歧全靠它定位）
            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log(
                    "LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log(
                    "LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** 等待链路协商完成（会话 10 可写）。 */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return !terminated
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和），协商前为 null。 */
    fun peerMaxControlPayload(): Int? = synchronized(lock) { engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1) }

    /** 排入一个完整控制载荷；链路已死返回 false。窗口满时内部积压，由 ACK 驱动发出。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
        retransmitLimit: Int? = null,
        dropWhenExhausted: Boolean = false,
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted =
                engine.sendControl(
                    bytes,
                    nowMillis(),
                    awaitAck,
                    retransmitLimit,
                    dropWhenExhausted,
                )
            drainOutputLocked()
            return accepted
        }
    }

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled === null || polled === POISON) null else polled
    }

    override fun close() {
        synchronized(lock) {
            closedLocally = true
            finishLocked(null)
        }
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            synchronized(lock) {
                engine.start(nowMillis())
                drainOutputLocked()
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    dumpInbound(buffer.copyOf(count))
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * 原始字节抓包（真车字节级对照的唯一手段）：每方向仅转储前
     * [RAW_DUMP_LIMIT] 字节，防止长期会话日志爆炸。
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
                if (deadline == null) {
                    try {
                        lock.wait(PACER_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
            if (deadline != null) {
                val sleep = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** 持锁调用：抽取引擎事件并搬运输出。 */
    private fun processEventsLocked() {
        while (true) {
            when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        dumpOutbound(bytes)
        try {
            stream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error)
        }
        signalReadyLocked()
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        txDumpBudget -= shown
    }

    private fun signalReadyLocked() {
        if (!readySignalled && engine.state == Iap2LinkEngine.State.NORMAL) {
            readySignalled = true
            readyLatch.countDown()
        }
    }

    private fun finish(reason: String?) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }) }
    }

    private fun finishLocked(failure: IOException?) {
        if (terminated) return
        terminated = true
        controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
        readyLatch.countDown()
        lock.notifyAll()
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** 原始字节转储预算：rx 需覆盖车机全部协商帧（Audi 实测约 140B），给 512B 余量。 */
        // 预算按字节计。曾为 512/256：一次完整 bring-up 都盖不住（0x1D01 单帧即 565B），
        // 提取重放 fixture（tools/extract_car_fixtures.py）会缺尾部——提到 4096 覆盖
        // 整个识别/MFi/凭据交换，长会话靠预算封顶防日志爆炸
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 40

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
    }
}
