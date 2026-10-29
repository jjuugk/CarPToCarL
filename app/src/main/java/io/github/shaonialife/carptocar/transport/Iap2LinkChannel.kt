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
     * 对端 marker（链路层 DETECT `FF 55 02 00 EE 10`）个数。
     *
     * 与 [peerFrameCount] 的区别：控制帧计数**不含**心跳，所以车机停摆但链路还活着时
     * 它纹丝不动；这个计数恰好相反——只看心跳。别克 B233 用它区分「车机在探测态等
     * DETECT 回显」与「车机彻底不认这条路」（见 [Iap2LinkEngine.receivedMarkerCount]）。
     */
    @Volatile
    var peerMarkerCount: Int = 0
        private set

    /**
     * 是否允许在 NORMAL 下响应对端重新发起的 SYN（别克 B233 专用，默认关）。
     *
     * 别的车机保持默认：进入 NORMAL 后收到的 SYN 继续按噪声处理，行为与改动前一致。
     * 见 [Iap2LinkEngine.acceptPeerResyncInNormal]。
     */
    var acceptPeerResyncInNormal: Boolean
        get() = engine.acceptPeerResyncInNormal
        set(value) {
            engine.acceptPeerResyncInNormal = value
        }

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
        engine.onMarkerReceived = {
            peerMarkerCount++
            if (peerMarkerCount <= MARKER_LOG_LIMIT) {
                log("LINK rx: 对端 marker（DETECT 心跳，累计 $peerMarkerCount 个）")
            }
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            peerFrameCount++
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
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

    /**
     * 回一个链路层 DETECT（marker），**不附带会话表**。
     *
     * 只在「对端只发心跳、不发控制帧」时用（别克 B233 的探测态）。其它车机不要调——
     * 多发的 marker 会让它们的链路状态机多走一轮探测。
     */
    fun echoMarker(): Boolean {
        synchronized(lock) {
            if (terminated) return false
            if (!engine.sendMarker()) return false
            drainOutputLocked()
            return true
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
        finish(null)
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

        /** marker 日志预算：心跳每秒一个，全打印会把链路帧日志淹没。 */
        const val MARKER_LOG_LIMIT = 5

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
    }
}
