package io.github.shaonialife.carptocar.iap2

import java.io.ByteArrayOutputStream

/** 手机端回显 SYN|ACK 时声明的发送窗口（真机 iPhone / AADisplay 同值 0x7F）。 */
private const val PHONE_DECLARED_MAX_OUTGOING = 0x7F

/**
 * iAP2 链路引擎（发送端实现；链路层协议对称，本实现按「发起方」工作）。
 *
 * 握手（无线模式，手机为发起方——DiPlay/xcertplay 的 openWireless 以
 * initiateNegotiation=false 等待手机发起）：
 *  1. start() 发送 marker `FF 55 02 00 EE 10`，随即发送同步包（FF5A 帧，
 *     control=0x80，载荷 = SynchronizationPayload）
 *  2. 对端收到 marker 后进入协商并发送自己的同步包；双方收到同步包即回 ACK
 *  3. 协商状态下收到 ACK 即进入 NORMAL（控制会话 10 可用）
 *
 * 数据帧：control=0x40（捎带 ACK），seq=发送序号（u8 回绕，**每条链路随机初值**），
 * ack=已按序收到的最新序号，sessionId=10。对端 marker 会被帧头扫描自然丢弃。
 *
 * 协议语义参照 reference/xcertplay transport/Iap2LinkEngine.kt（独立重写）。
 * 简化项（RFCOMM 为可靠有序流）：不做乱序缓冲与主动 EAK，保留重复帧检测、
 * 发送窗口与重传计时。
 */
class Iap2LinkEngine(
    private val maxOutgoing: Int = 4,
    private val retransmissionTimeoutMillis: Long = 4_000L,
    /**
     * 本地声明的重传次数上限（协商取双方较小值）。
     *
     * 取 10 而不是历史上的 4：MIB3H SW3874 固件在 BT 通道声明
     * `maxNumberOfRetransmissions: 30`、RTO 3000ms（docs/mib3-firmware-3874.md）。
     * 我们只给 4 次 = 12 秒就自己拆链，而车机愿意等 90 秒——蓝牙侧偶发一次丢帧
     * （车机正在切窗口/协处理器忙）时，4 次很容易在车机还没反应过来时就放弃。
     * 10 次（30 秒）既留出抗抖动的余量，也不会把整轮 60 秒预算耗光。
     */
    private val maxRetransmissions: Int = 10,
) {
    enum class State { IDLE, NEGOTIATING, NORMAL, DEAD }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    )

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * 3)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < 10 || u8(bytes[0]) != SYNCHRONIZATION_VERSION) return null
                val sessions = ArrayList<SessionDescriptor>()
                var offset = 10
                while (offset + 3 <= bytes.size) {
                    sessions +=
                        SessionDescriptor(
                            u8(bytes[offset]),
                            u8(bytes[offset + 1]),
                            u8(bytes[offset + 2]),
                        )
                    offset += 3
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = sessions,
                )
            }
        }
    }

    sealed class Event {
        class Control(
            val bytes: ByteArray,
        ) : Event()

        class Dead(
            val reason: String?,
        ) : Event()
    }

    private class Packet(
        var sequence: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
        val awaitAck: Boolean = true,
        /**
         * 这一帧自己的重传上限（null = 用链路级的 [effectiveMaxRetransmissions]）。
         *
         * 给「补一次就好、补不上也别为它拆链」的帧用——0x4300 是典型：它是无线会话
         * 唯一的钥匙，值得等确认；但它也是历史上唯一一类「发出去之后车机整个控制会话
         * 停摆」的帧，按链路级上限（10 次 ≈ 40 秒）走到头会 die，把还活着的 RFCOMM
         * 亲手拆掉（2026-10-26 别克 E2LB 那 6 轮 42 秒循环就是这么来的）。
         */
        val retransmitLimit: Int? = null,
        /** 重传到上限后的处置：true = 丢掉这一帧、链路继续；false = 按链路级语义 die。 */
        val dropWhenExhausted: Boolean = false,
    )

    var state: State = State.IDLE
        private set
    var peerSynchronization: SynchronizationPayload? = null
        private set

    /**
     * 本条链路的初始发送序号：**每次新建链路随机取 0–255**。
     *
     * 手册《CarPlay 车机开发手册》4.3 与真机抓包（catplay `take_own_seq()`、iPhone
     * sysdiagnose）都是随机；旧实现固定 99，导致同一台车每次重连的 SYN 字节完全一样
     * （2026-11-07 myCadillac 13 轮全是 `ctl=0x80 seq=99`，车机零回复——做了会话缓存
     * 或重放判定的车机会把重复序号当重传帧丢掉）。序号只是本端账法，随机不影响
     * 现有车型：对端用 ack 回显「期望的下一个」，与初值无关。
     */
    private var sentSequence = randomInitialSequence()
    private var lastSentAcknowledged: Int? = null
    private var lastReceivedInOrder = 0

    /** 本轮「窗口满、停发等 ACK」的起始时刻（null = 没在停）；只用于诊断日志去重。 */
    private var heldSinceMillis: Long? = null

    /**
     * 采纳的对端控制会话 ID（收到对端 sync 载荷时确定）。
     * Audi 真车字节级实测（2026-10-09）：车机（MIB3）以发起方姿态发 SYN，声明
     * 会话表 {1:control, 2:EA}，手机回显其表后它以 **1 号会话**通信——
     * CSM 发在 10 号会被静默丢弃（链路层照常 ACK，极具迷惑性）。
     * 测试台 DiPlay/xcertplay 声明 10，采纳后与本表默认一致，行为不变。
     */
    private var adoptedPeerControlSessionId: Int? = null

    /** 采纳的对端 sync 载荷（协商回显/诊断用）。 */
    private var adoptedSynchronization: SynchronizationPayload? = null

    /**
     * 隧道（type-130）协商模式。DiPlay 的隧道链路引擎（Iap2LinkEngine.openTunnel，
     * initiateNegotiation=true）有两个硬性期望（2026-08 字节级对照源码）：
     *  1. 收到「ACK 但尚未收到对端 SYN」时立即判链路失败（die）——因此回显必须**先于**
     *     裸 ACK 发出（回显帧 control=SYN|ACK 自带确认位，一帧即可完成对端协商）；
     *  2. 其隧道为零 ACK 模式（maxRetransmissions=0/maxAcknowledgements=0），不会回
     *     任何 ACK——等待「对端 ACK」完成协商会死锁，回显即视为协商完成。
     * 默认 false：蓝牙（RFCOMM/事件通道）路径语义不变（Audi 需 ACK+回显、等最终 ACK）。
     */
    var echoFirst: Boolean = false

    /** 诊断：首次采纳对端会话表时回调（打印对端提议的会话表，便于真车排查）。 */
    var onPeerSynchronizationAdopted: ((SynchronizationPayload) -> Unit)? = null

    /** 诊断：丢弃未知会话 ID 的数据帧时回调（(sessionId, bytes)）。 */
    var onUnknownSessionData: ((Int, Int) -> Unit)? = null

    /**
     * 诊断：「可丢弃」的帧重传到上限、被主动放弃时回调（参数为人类可读说明）。
     *
     * 只对 [Packet.dropWhenExhausted] 的帧触发——链路级帧到上限仍然 die，语义不变。
     * 这条日志必须存在：不然「0x4300 发出去了、车机也收到过，但没回 0x4301」与
     * 「0x4300 压根没被确认」在日志里长得一模一样，排查时无法区分。
     */
    var onFrameAbandoned: ((String) -> Unit)? = null

    /**
     * 诊断：**首次**因对端窗口满而停发时回调一次（(对端声明窗口, 实际生效窗口, 还在排队的帧数)）。
     *
     * 只在对端窗口**小于**本方声明时触发；同一段停发只回调一次，重新开闸时由
     * [onSendWindowReleased] 收尾——否则每次 ACK 都会再触发一遍，日志会被刷满。
     */
    var onSendWindowHold: ((peerMax: Int, effective: Int, backlog: Int) -> Unit)? = null

    /** 诊断：停发结束（重新开闸）时回调一次，带这段停发的持续时间（毫秒）。 */
    var onSendWindowReleased: ((effective: Int, heldMillis: Long) -> Unit)? = null

    /** 诊断：每个入向链路帧的摘要（control/seq/ack/sessionId/载荷长度），真车排查用。 */
    var onFrameReceived: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    /** 诊断：每个出向链路帧的摘要（仅链路层帧，用于对照真车协商时序）。 */
    var onFrameSent: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    /**
     * 对端握手族（`FF 55` 前缀）报文。
     *
     * 只有 [isDetect] = true 的标准 marker（`FF 55 02 00 EE 10`）是「正常」形态；
     * 其余任何一种都是对端在握手阶段给我们的应答，而本端**没有实现握手应答**。
     * 车机固件（MIB3 SW3874，`iap2::CIAP2LinkHandshake`）的路径是
     * `sendHandshake → evaluateHandshakeAnswer → 不兼容时 sendLackOfBackwardCompatibility`，
     * 也就是说「我不支持你」正是以这种非 marker 的握手报文出现的。
     */
    class PeerHandshake(
        val raw: ByteArray,
        /** 报文自带的载荷长度字段（能读到时）；读不出为 0（已按「形态未知」处理）。 */
        val declaredPayloadLength: Int,
        val isDetect: Boolean,
    ) {
        fun hex(): String = raw.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * 诊断：收到**非 DETECT** 的握手应答时回调（DETECT marker 每秒重发，不回调以免刷屏）。
     * 上层接进日志即可把「车机拒绝了链路」从 60 秒静默变成一行证据。
     */
    var onPeerHandshake: ((PeerHandshake) -> Unit)? = null

    /**
     * 是否在「对端明显拒绝链路」时立即终止（默认开）。
     *
     * 判据不是猜控制字节，而是**两件事**（见 [reportHandshakeAnswer] /
     * [rejectUnsupportedLinkVersion]）：① 对端 LSP 的 LinkVersion 与本端不同；
     * ② 反复收到非 DETECT 的握手应答、且一条 LSP 都没采纳（参数协商根本没开始）。
     * 关掉后只记日志、行为与改动前一致（继续等到上层预算耗尽）。
     */
    var failFastOnHandshakeRejection: Boolean = true

    /** 已收到的非 DETECT 握手应答数量（诊断/单测用）。 */
    var peerHandshakeRejections: Int = 0
        private set

    private var unknownSessionDropped = 0

    private val unacknowledged = ArrayList<Packet>()
    private val sendBacklog = ArrayDeque<Packet>()
    private val pendingEvents = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val receive = ReceiveBuffer()

    private var markerDeadline: Long? = null
    private var syncDeadline: Long? = null
    private var retransmitDeadline: Long? = null

    /** 上一次回显 SYN 时对端的 SYN 序号与我方序号（重传同一 SYN 时原样重放）。 */
    private var lastEchoPeerSequence: Int? = null
    private var lastEchoOwnSequence = 0

    val isDead: Boolean get() = state == State.DEAD

    /** 本引擎是否发起过 SYN（start=发起方；enterNegotiating/startResponder=响应方）。 */
    private var originatedSyn = false

    /**
     * **本方声明的发送窗口**（LSP maxOutgoing 语义 = 声明方自己的未确认包上限）。
     *
     * 回显时我们声明 0x7F（ownSyncOf），但**实际发帧按 `min(本方, 对端)` 计**
     * ——见 [effectiveSendWindow]。对端没声明、或声明得更大时，等价于本方值。
     */
    private var declaredMaxOutgoing = maxOutgoing

    /** 本方当前声明的发送窗口（0x7F；仅诊断日志用）。 */
    val declaredSendWindow: Int get() = declaredMaxOutgoing

    /**
     * 对端在 sync 里声明的 RTO（毫秒）。采纳后**用它当本地重传节拍**，不再用构造默认值。
     *
     * 2026-10-28 大众实测：车机声明 2000ms，我们按本地 4000ms 等——车机发 EAK
     * （ctl=0x60）要求重传时，我们仍在等自己的 RTO，白丢 4 秒；而整轮 bring-up
     * 只有十几秒预算，这 4 秒直接吃掉 1/4。
     */
    private var peerRetransmissionTimeoutMillis: Long? = null

    /** 实际重传间隔：对端声明值优先，夹在 [MIN_RTO_MILLIS]..[MAX_RTO_MILLIS]（防声明值离谱）。 */
    private fun retransmissionDelayMillis(): Long =
        (peerRetransmissionTimeoutMillis ?: retransmissionTimeoutMillis)
            .coerceIn(MIN_RTO_MILLIS, MAX_RTO_MILLIS)

    /**
     * 实际生效的重传次数：本地声明值与对端声明值**取较小**（LSP 协商语义）。
     *
     * 以前只认本地固定值，对端声明多少完全没用上——车机声明 30 我们也只重传 4 次。
     * 对端没声明（sync 没带或为 0）时仍用本地值，行为与改动前一致。
     */
    private fun effectiveMaxRetransmissions(): Int {
        val peerLimit = peerSynchronization?.maxRetransmissions ?: 0
        return if (peerLimit > 0) minOf(maxRetransmissions, peerLimit) else maxRetransmissions
    }

    /** 进入协商：发 marker + 同步包（发送端=发起方，不等待对端 marker）。 */
    fun start(nowMillis: Long) {
        if (state != State.IDLE) return
        originatedSyn = true
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /**
     * 补发协商开场：marker + 本表同步包（允许从 [State.NEGOTIATING] 再次触发）。
     *
     * 用于「对端只发 marker、不发同步包」的形态——它在等**我们**的会话表。
     * Honda HFT（Alpine 8A250-30A）2026-10-20 事件通道实测：车机在 60 秒里发了
     * 60 个 marker `FF 55 02 00 EE 10`，一条 FF5A 同步包都没有（说明它卡在探测态），
     * 而我们此前只回 marker，于是车机一直重发、链路永远建不起来。
     * 对照同一条车机的蓝牙 iAP2 链路：手机首帧正是「marker + FF5A SYN」，车机
     * 50ms 内即回自己的会话表——事件通道上补齐同一组合即可推进。
     */
    fun initiate(nowMillis: Long) {
        if (state == State.DEAD) return
        originatedSyn = true
        if (state == State.IDLE) state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /**
     * 停止协商期的自动重发（marker / 同步包），状态保持不变。
     *
     * 用于「对端一直不回同步包」的场景：控制通道上一旦无限 500ms 打点，会把对端
     * CarPlay 栈刷懵（2026-10-11 事件通道实测过的坑），所以由上层按轮次重开、轮次
     * 用尽后显式停手。
     */
    fun stopNegotiationRetries() {
        markerDeadline = null
        syncDeadline = null
    }

    /**
     * 应答者模式：只发 marker，**不发 SYN**——等对端（车机）自己发 SYN 再 ACK+回显。
     * 奥迪事件通道虚拟链路实测（2026-10-11）：车机发 marker 开场后由它发起 SYN；
     * 我们主动发 SYN（且被 500ms 重发洪水放大）会把车机 CarPlay 栈打懵——
     * 表现为事件通道再无响应、连蓝牙链路的 ACK 都停了。
     */
    fun startResponder(nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        // syncDeadline 保持 null：不主动发 SYN、不重发 SYN
    }

    /**
     * 静默进入协商（DETECT 语义，对照 catplay LinkLayer server 角色）：
     * 不发任何字节——对端（客户端）每 200ms 重发 DETECT，我们**收到一次回一次**、
     * 绝不主动重发（服务器重发 DETECT 会把客户端状态机打回 Detecting，
     * SYN 永远出不来——2026-10-11 事件通道实测）。
     */
    fun enterNegotiating() {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
    }

    fun feed(
        bytes: ByteArray,
        nowMillis: Long,
    ) {
        if (state == State.DEAD) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                die("inbound frame exceeds limit")
                return
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // 排空所有完整帧
            }
        }
    }

    fun feedEof() = die(null)

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (pendingEvents.isEmpty()) null else pendingEvents.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(markerDeadline, syncDeadline, retransmitDeadline).minOrNull()

    fun advanceTime(nowMillis: Long) {
        if (state == State.DEAD) return
        if (markerDeadline != null && markerDeadline!! <= nowMillis) {
            markerDeadline = null
            if (state == State.NEGOTIATING && appendOutput(MARKER)) {
                markerDeadline = nowMillis + MARKER_RESEND_MILLIS
            }
        }
        if (syncDeadline != null && syncDeadline!! <= nowMillis) {
            syncDeadline = null
            if (state == State.NEGOTIATING) {
                // 已采纳对端表：只补裸 ACK（回显仅首次发一次，避免协商轮次膨胀）；
                // 未采纳：继续重发本表 SYN（测试台时序）
                if (adoptedSynchronization != null) {
                    sendAcknowledgement()
                } else {
                    sendSynchronization()
                }
                syncDeadline = nowMillis + SYNC_RESEND_MILLIS
            }
        }
        if (retransmitDeadline != null && retransmitDeadline!! <= nowMillis) {
            retransmitDeadline = null
            if (state == State.NORMAL) retransmitDue(nowMillis)
        }
    }

    /**
     * 排入一个完整的控制会话载荷。窗口满时进入积压队列，由 ACK 或 pump 驱动重试。
     * 返回 false 仅代表链路已死。
     *
     * [awaitAck]=false：发后不管——不计入未确认清单、不重传，**链路不会因它未被 ACK
     * 而死亡**。用于 Audi 实测中「接受但从不 ACK」的消息（0x4E0E 传输标识：车机对
     * 其他包毫秒级 ACK、唯独不认它，重传看门狗曾据此在 +20s 杀掉整条蓝牙链路）。
     */
    fun sendControl(
        bytes: ByteArray,
        nowMillis: Long,
        awaitAck: Boolean = true,
        retransmitLimit: Int? = null,
        dropWhenExhausted: Boolean = false,
    ): Boolean {
        if (state == State.DEAD) return false
        val limit = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1
        if (limit <= 0 || bytes.size > limit) {
            die("control payload ${bytes.size} exceeds negotiated limit $limit")
            return false
        }
        sendBacklog +=
            Packet(
                0,
                bytes.copyOf(),
                awaitAck = awaitAck,
                retransmitLimit = retransmitLimit,
                dropWhenExhausted = dropWhenExhausted,
            )
        drainSendWindow(nowMillis)
        return true
    }

    // ---- 接收解析 ----

    private fun parseAvailable(nowMillis: Long): Boolean {
        // 帧头扫描：`FF5A` 是链路帧，`FF55` 是握手族。
        //
        // **FF55 必须在这里截住**：旧实现把它当杂字节逐字节丢弃，于是「车机用非 DETECT
        // 的握手应答拒绝我们」这件事在日志里根本不存在，只能干等到上层预算耗尽
        // （车机固件侧对应 `sendLackOfBackwardCompatibility`）。
        while (true) {
            while (receive.size >= 2 &&
                !(receive[0] == 0xFF.toByte() &&
                    (receive[1] == 0x5A.toByte() || receive[1] == HANDSHAKE_PREFIX_SECOND))
            ) {
                receive.discard(1)
            }
            if (receive.size < 2) break
            if (receive[1] == 0x5A.toByte()) break
            if (!consumeHandshakePacket()) return false
            if (state == State.DEAD) return false
        }
        if (receive.size < HEADER_BYTES) return false
        val header = receive.peek(HEADER_BYTES)
        if (!checksumValid(header)) {
            receive.discard(1)
            return true
        }
        val length = readU16(header, 2)
        if (length < HEADER_BYTES) {
            receive.discard(1)
            return true
        }
        if (receive.size < length) return false
        val frame = receive.take(length)
        val payloadWithChecksum = frame.copyOfRange(HEADER_BYTES, length)
        if (payloadWithChecksum.isNotEmpty() && !checksumValid(payloadWithChecksum)) {
            receive.discard(1)
            return true
        }
        val payload =
            if (payloadWithChecksum.isEmpty()) {
                null
            } else {
                payloadWithChecksum.copyOf(payloadWithChecksum.size - 1)
            }
        processFrame(
            control = u8(frame[4]),
            sequence = u8(frame[5]),
            ack = u8(frame[6]),
            sessionId = u8(frame[7]),
            payload = payload,
            nowMillis = nowMillis,
        )
        return true
    }

    private fun processFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        sessionId: Int,
        payload: ByteArray?,
        nowMillis: Long,
    ) {
        onFrameReceived?.invoke(control, sequence, ack, sessionId, payload?.size ?: 0)
        if (control and CONTROL_RESET != 0) {
            die("peer sent reset")
            return
        }
        if (control and CONTROL_SYNCHRONIZE != 0) {
            val sync = SynchronizationPayload.decode(payload ?: ByteArray(0))
            // 解不出来不一定是垃圾帧：也可能是对端用**别的 LinkVersion** 发的 LSP。
            // 那种情况旧实现静默丢弃、继续 500ms 重发本表，直到预算耗尽。
            if (sync == null) rejectUnsupportedLinkVersion(payload)
            // echoFirst 模式下即使本引擎已 NORMAL 也继续处理对端 SYN 重发：对端引擎
            // （DiPlay openTunnel）只从 TCP 读，若其 SYN 到达时我方隧道尚未建立，
            // 首次回显会走错通道；其 500ms 的 SYN 重发让我们在隧道就绪后补上回显
            // （parseAvailable 的帧扫描会丢弃重发中的 FF55 marker，无副作用）。
            //
            // **只有纯 SYN（不带 ACK 位）才回 SYN|ACK**：SYN|ACK 说明对端在回应我们的
            // 发起方 SYN，属于「它已应答」的信号（见下方 ACK 分支），再回一条就会形成
            // 双方互回 SYN|ACK 的无限乒乓（单测暴露：两个引擎互相喂帧时协商永不收敛）。
            val isPureSyn = (control and CONTROL_ACKNOWLEDGEMENT) == 0
            if (sync != null && isPureSyn && (state == State.NEGOTIATING || echoFirst)) {
                val firstAdoption = adoptedSynchronization == null
                peerSynchronization = sync
                adoptPeerTimings(sync)
                if (firstAdoption) adoptedSynchronization = sync
                // Audi 真车实测（2026-10-09，字节级对照）：车机以发起方姿态发 SYN 声明其会话表
                // {1:control,2:EA}，手机必须「ACK + 原样回显其表（SYN|ACK）」——车机见到回显才发
                // 最终 ACK 完成协商（只回裸 ACK 时它永不回 ACK）。回显即声明「我采纳你的表」，
                // 之后 CSM 走采纳表的控制会话 ID（1）；发 10 会被静默丢弃。
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                lastReceivedInOrder = sequence
                // **每次** SYN 都只回一帧 SYN|ACK（不回裸 ACK）：
                //  ① 语义完整：SYN|ACK 自带确认位与 ack 字段，裸 ACK 是冗余的
                //     （catplay `on_recv_neg_host` 正是只发 SYN+ACK）；
                //  ② 序号不能撞：裸 ACK 用当前 sentSequence，而 SYN|ACK 会自增，下一次
                //     裸 ACK 就复用上一帧 SYN|ACK 的序号——BMW 把同序号帧当重复丢弃；
                //  ③ 重传同一 SYN 时整帧原样重放，字节级幂等。
                // 2026-10-19 BMW 00000 定谳：旧实现在首次采纳后只回裸 ACK，该车机每轮
                // 重试都用新序号重开协商（161→191→40…），因此 14 轮全部卡在协商、
                // 一次 0x1D00 都没走到（日志：133 条 SYN / 0 条 ACK）。
                sendSynchronizationEcho(sync, sequence)
                if (firstAdoption) onPeerSynchronizationAdopted?.invoke(sync)
                // 响应方（未发过 SYN）回显即视为协商完成：对端见到 SYN|ACK 后即认为
                // 链路可用（MIB3 真机 iPhone 发完 SYN|ACK 立即发 0x1D00，不等 ACK）；
                // 发起方仍等对端 ACK/数据（原语义）
                if (echoFirst || !originatedSyn) completeNegotiationIfPending(nowMillis)
            } else if (sync != null &&
                !isPureSyn &&
                adoptedSynchronization == null &&
                state == State.NEGOTIATING
            ) {
                // 发起方收到 SYN|ACK（对端对我们 SYN 的应答，载荷=对端表）：
                // 只采纳、**不回显**（回显会形成互回 SYN|ACK 的乒乓）。此前只在纯
                // SYN 上采纳，对端是「纯响应方」时发起方永远到不了 NORMAL——
                // MIB3 虚拟链路（手机 arm detect → 车机 SYN → 手机 SYN|ACK）正是
                // 这个形态：车机侧由本分支收敛，随后按超时机制补裸 ACK。
                peerSynchronization = sync
                adoptedSynchronization = sync
                adoptPeerTimings(sync)
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                // Watermark must also advance (the pure SYN branch does the same): when left at 0,
                // the initiator treats the responder's first data frame as an "expired frame", only re-sending ack=0 —
                // the responder's send window gets poisoned by this bogus ack (backlog never flushes, unit test located)
                lastReceivedInOrder = sequence
                completeNegotiationIfPending(nowMillis)
            }
        }
        if (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT != 0) {
            handleExtendedAcknowledgement(ack, nowMillis)
        }
        if (control and CONTROL_ACKNOWLEDGEMENT != 0) {
            handleAcknowledgement(ack, nowMillis)
        }
        val isDataFrame =
            payload != null &&
                (control and CONTROL_ACKNOWLEDGEMENT) != 0 &&
                (control and CONTROL_SYNCHRONIZE) == 0 &&
                (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) == 0
        if (isDataFrame) {
            // 控制消息的会话 ID：本国表 10 或采纳表（Audi=1；测试台两者一致）
            if (sessionId == SESSION_CONTROL || sessionId == adoptedPeerControlSessionId) {
                // 收到对端的数据即代表对端认为链路可用；ACK 帧若丢失/被跳过，这里兜底进入 NORMAL
                completeNegotiationIfPending(nowMillis)
                handleData(sequence, payload!!)
            } else if (sessionId == SESSION_EA) {
                // EA（ExternalAccessory）会话数据：本项目**已移除 EA 车机控制**，这里不再
                // 上抛给任何消费者。但链路层仍要 ACK——不确认会让对端不停重传同一个包，
                // 连带把控制会话也拖慢（EA 的序号账法与控制会话独立，只补 ACK 不前移自身序号）。
                sendAcknowledgement()
            } else {
                reportUnknownSessionData(sessionId, payload!!.size)
            }
        }
    }

    /**
     * 消费一个 `FF 55` 握手族报文。返回 false = 字节不够，等更多输入。
     *
     * 处理原则：**先记下来，再定性**。第一次只记日志（个别车机会先发一个私有握手包
     * 再进入参数协商，一刀切会把本可用的链路杀掉）；同一种应答累计出现
     * [HANDSHAKE_REJECTION_LIMIT] 次、且此时我们连一条对端 LSP 都还没采纳
     * （= 参数协商根本没开始），才判「对端拒绝链路」并立即终止。
     */
    private fun consumeHandshakePacket(): Boolean {
        if (receive.size >= MARKER.size &&
            (0 until MARKER.size).all { receive[it] == MARKER[it] }
        ) {
            receive.discard(MARKER.size)
            return true
        }
        if (receive.size < 4) return false
        val declared = u8(receive[2]) or (u8(receive[3]) shl 8)
        if (declared == 0 || declared > MAX_HANDSHAKE_PAYLOAD) {
            // 长度读不出合理值（可能是被截断的 FF5A、或对端用了别的长度端序）：
            // **只记诊断、不计入拒绝**——连长度都读不出来，拿它拆链太冒险；
            // 丢 2 字节继续扫描，后面的真帧照常能找回来。
            onPeerHandshake?.invoke(PeerHandshake(receive.peek(4), 0, isDetect = false))
            receive.discard(2)
            return true
        }
        val total = 4 + declared
        if (receive.size < total) return false
        reportHandshakeAnswer(PeerHandshake(receive.take(total), declared, isDetect = false))
        return true
    }

    /**
     * 非 DETECT 握手应答的定性。
     *
     * 只在「还在协商、且一条 LSP 都没采纳」时才算拒绝——那种状态下对端除了 marker
     * 不该发任何东西；它发了，就说明它在等一个本端没实现的握手应答（或直接拒绝）。
     * 已经采纳过 LSP（进入参数协商/正常态）后收到的一律只记日志，不动链路。
     */
    private fun reportHandshakeAnswer(handshake: PeerHandshake) {
        peerHandshakeRejections++
        onPeerHandshake?.invoke(handshake)
        if (!failFastOnHandshakeRejection) return
        if (state != State.NEGOTIATING) return
        if (adoptedSynchronization != null) return
        if (peerHandshakeRejections < HANDSHAKE_REJECTION_LIMIT) return
        die(
            "peer rejected link：收到非 DETECT 的握手应答 ×$peerHandshakeRejections" +
                "（车机侧 sendLackOfBackwardCompatibility），raw=${handshake.hex()}",
        )
    }

    /**
     * 对端 LSP 的 LinkVersion 与本端不同 → 立即定性。
     *
     * LSP 首字节就是 7 个链路参数里的第一项 `LinkVersion`（车机建议值 1）。
     * [SynchronizationPayload.decode] 只认本端版本，别的版本返回 null——旧实现就此
     * 把这条 SYN 静默丢掉。这里读出对端版本：这是**确定性证据**（不是猜控制字节），
     * 所以不等第二次、直接终止，把 60 秒静默变成 1 秒定位。
     *
     * 版本字节离谱（> [MAX_PLAUSIBLE_LINK_VERSION]）时不判——那更可能是载荷不是 LSP，
     * 交给上层当未知帧，别拿它拆链。
     */
    private fun rejectUnsupportedLinkVersion(payload: ByteArray?) {
        if (payload == null || payload.size < LSP_MIN_BYTES) return
        val version = u8(payload[0])
        if (version == SYNCHRONIZATION_VERSION) return
        if (version > MAX_PLAUSIBLE_LINK_VERSION) return
        if (!failFastOnHandshakeRejection) return
        die(
            "peer rejected link：LSP LinkVersion=$version，本端只支持 $SYNCHRONIZATION_VERSION" +
                "（车机侧 sendLackOfBackwardCompatibility）",
        )
    }

    private fun completeNegotiationIfPending(nowMillis: Long) {
        if (state == State.NEGOTIATING && peerSynchronization != null) {
            state = State.NORMAL
            markerDeadline = null
            syncDeadline = null
            drainSendWindow(nowMillis)
        }
    }

    private fun reportUnknownSessionData(
        sessionId: Int,
        size: Int,
    ) {
        unknownSessionDropped++
        if (unknownSessionDropped <= 5) onUnknownSessionData?.invoke(sessionId, size)
    }

    /** 诊断：出向同步包回显已生成（隧道模式用于「回显已送达后才允许发 CSM」门闸）。 */
    var onSynchronizationEchoSent: (() -> Unit)? = null

    /**
     * 回显对端 sync（SYN|ACK，载荷与对端完全一致）——声明「手机采纳车机会话表」。
     *
     * **必须消耗一个新发送序号**：对端按自己的序号账法校验回包（catplay
     * `NegotiationTx::SynAck` 用 `take_own_seq()` 自增）。旧实现复用 `sentSequence`
     * （发 SYN 后从未自增），于是 SYN 与 SYN-ACK 同为 seq=99，BMW 判为重复帧丢弃——
     * 14 轮协商全部超时、车机一条 ACK 都没回（2026-10-19 实车日志定谳）。
     *
     * 对**同一个** SYN 的重发保持字节级幂等：重放上次那一帧，不推进序号。
     * DiPlay 隧道模式每 500ms 重发同一 SYN，靠这条维持原有行为。
     */

    /**
     * 回显用的「我方参数」：会话表/超时沿用对端声明（手机端无特殊要求），
     * 但 **maxOutgoing 提到 0x7F**——原样回显会把我们自己的发送窗口限死在对端的
     * 小窗口（MIB3 突发 8 条在 maxOutgoing=4 下积压；真机 iPhone/AADisplay 均声明
     * 0x7F）。maxLength 保持对端值（=我们的收包上限，勿放大）。
     */
    private fun ownSyncOf(peer: SynchronizationPayload): SynchronizationPayload =
        peer.copy(maxOutgoing = PHONE_DECLARED_MAX_OUTGOING).also {
            declaredMaxOutgoing = PHONE_DECLARED_MAX_OUTGOING
        }

    private fun sendSynchronizationEcho(
        sync: SynchronizationPayload,
        peerSequence: Int,
    ) {
        if (lastEchoPeerSequence == peerSequence) {
            writePacket(ownSyncOf(sync).encode(), lastEchoOwnSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
            onSynchronizationEchoSent?.invoke()
            return
        }
        val ownSequence = nextSequence(sentSequence).also { sentSequence = it }
        writePacket(ownSyncOf(sync).encode(), ownSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
        lastEchoPeerSequence = peerSequence
        lastEchoOwnSequence = ownSequence
        onSynchronizationEchoSent?.invoke()
    }

    /** 采纳对端 sync 里的重传节拍（见 [peerRetransmissionTimeoutMillis]）。 */
    private fun adoptPeerTimings(sync: SynchronizationPayload) {
        peerRetransmissionTimeoutMillis = sync.retransmissionTimeoutMillis.toLong().takeIf { it > 0 }
    }

    /**
     * 扩展确认（EAK，control 位 0x20）：对端用它说「我从 `ack` 这个序号开始没收到」。
     *
     * 与裸 ACK 的区别只在**时机**：裸 ACK 只做「清掉已确认的」，缺口等本地 RTO 到点再补；
     * EAK 是对端**明确告诉你缺了**，没有理由再等一个 RTO。2026-10-28 大众实测
     * （车机 ctl=0x60、ack=106）：EAK 在 12.595 到达，我们靠本地 4 秒 RTO 到 16.515 才补，
     * 白丢 4 秒——而那轮 bring-up 总共只有 17 秒。
     *
     * 只立即重传 `ack` 之后**最靠前**的那一个未确认包：EAK 缺的是一段窗口，但 RFCOMM
     * 是可靠有序流，补上第一个后对端若仍缺会再发 EAK。一次 EAK 触发整窗重传会把链路
     * 打成洪水，也会把每个包的重传计数一次刷满（`maxRetransmissions` 到顶就拆链）。
     */
    private fun handleExtendedAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        completeNegotiationIfPending(nowMillis)
        drainSendWindow(nowMillis)
        // `ack` 的语义各家不一：有的实现指「期望的下一个序号」，有的指「缺的第一个」。
        // 两种都兼容——先找 seq 正好等于 ack 的未确认包，找不到再取 ack 之后最近的那个。
        // （**不要**在这里按 ACK 语义清未确认清单：ack 若正指着缺口起点，一清就把要重传的
        //  包删掉了，等于什么都没做。窗口推进交给同一帧里的 ACK 位走 handleAcknowledgement。）
        val target =
            unacknowledged.firstOrNull { it.sequence == acknowledgement }
                ?: unacknowledged.minByOrNull { sequenceDistance(it.sequence, acknowledgement) }
                ?: return
        target.retransmissions += 1
        if (exhausted(target, maxRetransmissions)) {
            if (!abandonIfDisposable(target)) die("packet ${target.sequence} not acknowledged")
            return
        }
        target.deadlineMillis = nowMillis + retransmissionDelayMillis()
        writePacket(target.payload, target.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    private fun handleAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        completeNegotiationIfPending(nowMillis)
        lastSentAcknowledged = acknowledgement
        val window = (peerSynchronization?.maxOutgoing ?: maxOutgoing) + ACK_SLACK
        unacknowledged.removeAll { sequenceDistance(it.sequence, acknowledgement) !in 1..window }
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
        drainSendWindow(nowMillis)
    }

    private fun handleData(
        sequence: Int,
        payload: ByteArray,
    ) {
        val distance = sequenceDistance(sequence, lastReceivedInOrder)
        val peerWindow = peerSynchronization?.maxOutgoing ?: maxOutgoing
        if (distance == 0 || distance > peerWindow + ACK_SLACK) {
            sendAcknowledgement() // 重复或过期：重新确认当前序号
            return
        }
        // RFCOMM 为可靠有序流，不会丢包；序号跳变只可能是对端把 ACK/SYN 也计入了序号
        // （各家实现账法不一致）。因此接受任意前进量并推进水位，避免死锁式丢帧。
        lastReceivedInOrder = sequence
        if (pendingEvents.size >= 256) {
            die("event queue overflow")
            return
        }
        pendingEvents += Event.Control(payload)
        sendAcknowledgement()
    }

    // ---- 发送 ----

    /**
     * 实际生效的发送窗口（未确认帧上限）= **`min(本方声明, 对端声明)`**。
     *
     * 对端声明多少就收多少单：别克 BUICK_3138 声明 5，我们回显 0x7F 后一次连发 7 帧
     * （2026-11-10 18:34 日志 seq 97~103），车机对这 7 帧一条 ACK 都没回。
     *
     * 唯一例外是**零重传对端**（`maxRetransmissions=0`，隧道侧隐式确认）：它根本不回
     * ACK，套小窗口会让 backlog 永久卡死，所以仍按本方值。这条守卫不能省——2026-11-10
     * 早上按 maxOutgoing 限流时漏了它，MIB3 隧道突发整段被削、7 个用例挂掉。
     *
     * 已知风险：**奥迪 MMI 4260 也声明 5**，而它 2026-11-09 两轮是 7 帧连发
     * （seq 77~83、89~95）连上的；改成按 5 发之后，那 7 帧会切成 5+2、中间要等车机
     * ACK。若哪天「奥迪忽然连不上」，先回到这里看是不是这一步引起的。
     */
    private fun effectiveSendWindow(peer: SynchronizationPayload?): Int =
        if (peer != null && peer.maxRetransmissions > 0) {
            minOf(declaredMaxOutgoing, peer.maxOutgoing.coerceAtLeast(1))
        } else {
            declaredMaxOutgoing
        }

    /**
     * 记录「窗口满 → 停发等 ACK」的起点，同一段停发只回调一次。
     *
     * 只在窗口确实被对端收紧（`< 本方声明`）时记：本方 0x7F 自身上限被顶到是另一回事
     * （真车从未出现），不该混进这条日志里误导读日志的人。
     */
    private fun noteWindowHold(
        window: Int,
        peer: SynchronizationPayload?,
        nowMillis: Long,
    ) {
        if (window >= declaredMaxOutgoing) return
        if (heldSinceMillis != null) return
        heldSinceMillis = nowMillis
        onSendWindowHold?.invoke(peer?.maxOutgoing ?: 0, window, sendBacklog.size)
    }

    /** 重新开闸：有积压的帧要发了，收尾这段停发（没有停发就直接返回）。 */
    private fun noteWindowRelease(
        window: Int,
        nowMillis: Long,
    ) {
        val since = heldSinceMillis ?: return
        heldSinceMillis = null
        onSendWindowReleased?.invoke(window, nowMillis - since)
    }

    private fun drainSendWindow(nowMillis: Long) {
        if (state != State.NORMAL) return
        while (sendBacklog.isNotEmpty()) {
            val packet = sendBacklog.first()
            val peer = peerSynchronization
            val window = effectiveSendWindow(peer)
            if (lastSentAcknowledged != null &&
                sequenceDistance(sentSequence, lastSentAcknowledged!!) >= window
            ) {
                noteWindowHold(window, peer, nowMillis)
                return
            }
            noteWindowRelease(window, nowMillis)
            sendBacklog.removeFirst()
            sentSequence = nextSequence(sentSequence)
            packet.sequence = sentSequence
            packet.deadlineMillis = nowMillis + retransmissionDelayMillis()
            writePacket(packet.payload, packet.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
            if (!packet.awaitAck) continue // 发后不管：不追踪、不重传（见 sendControl 注释）
            if (peer == null || peer.maxRetransmissions > 0) {
                unacknowledged += packet
                retransmitDeadline = packet.deadlineMillis
            } else {
                lastSentAcknowledged = sentSequence
            }
        }
    }

    /** 出向控制消息的会话 ID：采纳了对端表就用其 control ID（Audi=1），否则本表 10。 */
    private fun controlSessionId(): Int = adoptedPeerControlSessionId ?: SESSION_CONTROL

    private fun retransmitDue(nowMillis: Long) {
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        next.deadlineMillis = nowMillis + retransmissionDelayMillis()
        next.retransmissions += 1
        if (exhausted(next, effectiveMaxRetransmissions())) {
            if (!abandonIfDisposable(next)) die("packet ${next.sequence} not acknowledged")
            return
        }
        writePacket(next.payload, next.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    /** 这一帧的重传次数是否已到上限（[Packet.retransmitLimit] 优先于链路级上限）。 */
    private fun exhausted(
        target: Packet,
        defaultLimit: Int,
    ): Boolean = target.retransmissions > (target.retransmitLimit ?: defaultLimit)

    /**
     * 上限到了但这一帧是「可丢弃」的：把它从待确认队列摘掉，链路继续跑。
     *
     * 与 die 的区别只在**责任归属**：走到上限说明对端不认这一帧，不是链路坏了
     * （RFCOMM 还在收心跳、还在回别的 ACK）。为了一个请求拆掉整条链路，会让车机
     * 把「手机掉线」重新走一遍配对，代价远大于这一帧本身。
     *
     * @return true = 已丢弃、调用方不要再 die；false = 不可丢弃，调用方按原语义 die
     */
    private fun abandonIfDisposable(target: Packet): Boolean {
        if (!target.dropWhenExhausted) return false
        unacknowledged.remove(target)
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
        onFrameAbandoned?.invoke(
            "帧 seq=${target.sequence}（${target.payload.size}B）重传 ${target.retransmissions} 次仍未被确认——" +
                "已放弃这一帧，链路保持",
        )
        return true
    }

    private fun sendSynchronization() {
        val payload =
            SynchronizationPayload(
                maxOutgoing = maxOutgoing,
                maxLength = 0xFFFF,
                retransmissionTimeoutMillis = retransmissionTimeoutMillis.toInt().coerceIn(0, 0xFFFF),
                acknowledgementTimeoutMillis = 500,
                maxRetransmissions = maxRetransmissions,
                maxAcknowledgements = 3,
                sessions =
                    listOf(
                        SessionDescriptor(SESSION_CONTROL, 0, 2),
                        SessionDescriptor(SESSION_EA, 2, 1),
                        SessionDescriptor(SESSION_FILE_TRANSFER, 1, 2),
                    ),
            ).encode()
        writePacket(payload, sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun writePacket(
        payload: ByteArray?,
        sequence: Int,
        control: Int,
        sessionId: Int,
    ) {
        if (state == State.DEAD) return
        onFrameSent?.invoke(control, sequence, lastReceivedInOrder, sessionId, payload?.size ?: 0)
        val length = payload?.size?.plus(HEADER_BYTES + 1) ?: HEADER_BYTES
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = control.toByte()
        frame[5] = sequence.toByte()
        frame[6] = lastReceivedInOrder.toByte()
        frame[7] = sessionId.toByte()
        var sum = 0
        for (index in 0 until HEADER_BYTES - 1) sum += u8(frame[index])
        frame[HEADER_BYTES - 1] = (-sum and 0xFF).toByte()
        if (payload != null) {
            payload.copyInto(frame, HEADER_BYTES)
            var payloadSum = 0
            payload.forEach { payloadSum += u8(it) }
            frame[frame.size - 1] = (-payloadSum and 0xFF).toByte()
        }
        appendOutput(frame)
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        state = State.DEAD
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        output.reset()
        pendingEvents.clear()
        sendBacklog.clear()
        pendingEvents += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        output.write(bytes)
        return true
    }

    private class ReceiveBuffer {
        private val bytes = ByteArray(0xFFFF + 8)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(
            source: ByteArray,
            offset: Int,
            count: Int,
        ) {
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun peek(count: Int): ByteArray = bytes.copyOfRange(head, head + count)

        fun take(count: Int): ByteArray {
            val out = peek(count)
            discard(count)
            return out
        }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val SESSION_CONTROL = 10
        const val SESSION_EA = 11
        const val SESSION_FILE_TRANSFER = 12

        /** 会话描述符里的类型字段：0 = 控制会话（xcertplay/extractor/catplay 一致）。 */
        private const val CONTROL_KIND = 0

        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        const val HEADER_BYTES = 9
        private const val SYNCHRONIZATION_VERSION = 1

        /** 握手族（`FF 55`）的第二个同步字节；`FF5A` 才是链路帧。 */
        private const val HANDSHAKE_PREFIX_SECOND: Byte = 0x55

        /** `FF 55 | u16LE 载荷长度 | 载荷` 能接受的最大载荷（防对端给个离谱长度卡住解析）。 */
        private const val MAX_HANDSHAKE_PAYLOAD = 512

        /** 非 DETECT 握手应答累计到这个次数才判「对端拒绝链路」（一次可能是私有握手包）。 */
        private const val HANDSHAKE_REJECTION_LIMIT = 2

        /** LSP 最小长度（10 字节定长头）；短于此不可能是同步包，不参与版本判定。 */
        private const val LSP_MIN_BYTES = 10

        /**
         * 可当作「就是 LSP 版本号」的最大值。
         *
         * 超过它更可能是载荷根本不是 LSP（版本字节是随机内容），那种情况只记日志、
         * 不拿它拆链。1..15 这一段覆盖所有已知的 LinkVersion 取值（现实里只有 1）。
         */
        private const val MAX_PLAUSIBLE_LINK_VERSION = 0x0F
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        /** 初始序号随机（见 [sentSequence] 注释）：固定值会被做重放判定的车机当重复帧。 */
        private fun randomInitialSequence(): Int = java.security.SecureRandom().nextInt(0x100)
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNC_RESEND_MILLIS = 500L
        private const val ACK_SLACK = 10

        /** 重传间隔的夹取区间：对端声明值离谱（0 或 60000）时仍要有可用节拍。 */
        private const val MIN_RTO_MILLIS = 500L
        private const val MAX_RTO_MILLIS = 8_000L

        private fun sequenceDistance(
            sequence: Int,
            previous: Int,
        ): Int = (sequence - previous) and 0xFF

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFF

        private fun checksumValid(bytes: ByteArray): Boolean {
            var sum = 0
            bytes.forEach { sum += u8(it) }
            return sum and 0xFF == 0
        }

        private fun readU16(
            bytes: ByteArray,
            offset: Int,
        ): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun writeU16(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value ushr 8)
            out.write(value)
        }

        private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
    }
}
