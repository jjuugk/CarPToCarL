package io.github.shaonialife.carptocar.mib3

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import java.util.ArrayDeque

/**
 * MIB3 车机控制链路：把「iAP2 的 EA 隧道」包装成「发 viwi 请求 / 收 viwi 响应」。
 *
 * ## 为什么是单例
 *
 * 一条 BT 控制会话在一个时刻只有一个（[Iap2SenderSession] 由 BT 两条路径之一创建），
 * 而 UI（车机控制面板）随时可能打开/关闭，不该持有会话。所以由 BT 侧在会话就绪时
 * [attach]、链路断开时 [detach]，面板只跟这个单例打交道。
 *
 * ## 状态机（面板上「链路」那行就是它）
 *
 * ```
 * 未连接车机 ── attach ──> 车机未声明 EA 协议（不可用）
 *                      └─> 等 0xEA03（车机没回会话状态，写动作禁用）
 *                      └─> 可读写（收到 0xEA03 之后）
 * ```
 *
 * **只有收到 0xEA03 才允许写**：0xEA03 是车机对 0xEA00 的唯一回执。没有它就发数据，
 * 等于往一条没建起来的管道里灌字节——车机要么丢弃、要么把它当链路噪声，
 * 排障时会误判成「报文格式不对」，而真正的问题是会话没建。
 *
 * ## 线程
 *
 * [attach]/[detach] 在 BT 线程调，[perform]/[refreshReadOnly] 在 UI 线程调，
 * 收数据回调在 BT 保活线程。所有状态改动都在 [lock] 里，[onChange] 的调用方负责
 * 切回主线程（面板用 `post`）。
 */
object Mib3ControlLink {
    /** 面板「链路」行显示的状态。 */
    enum class Phase(
        val label: String,
    ) {
        /** 没有 BT 控制会话（车机没连）。 */
        NO_SESSION("未连接车机"),

        /** 会话在，但车机 0x1D01 里没声明任何 EA 协议。 */
        NO_PROTOCOL("车机未声明 EA 协议"),

        /** 协议声明了，但车机还没回 0xEA03（会话没建起来）。 */
        WAITING_STATUS("等待 0xEA03 会话状态"),

        /** 收到 0xEA03，隧道按「可用」处理。 */
        READY("EA 隧道已建立"),
    }

    /** 只读区的一行。[state] 是「未请求 / 已请求 / 已回 N 字节」。 */
    data class Row(
        val label: String,
        val path: String,
        val state: String,
        val sample: String?,
    )

    /** 面板一次刷新要的全部状态。 */
    data class Status(
        val phase: Phase,
        val detail: String,
        val protocols: String,
        val eaStatus: String,
        val txBytes: Int,
        val rxBytes: Int,
        val requests: Int,
        val responses: Int,
    )

    private val lock = Any()

    private var session: Iap2SenderSession? = null
    private var log: ((String) -> Unit)? = null

    private var nextRequestId = 1
    private var requestsSent = 0
    private var responsesSeen = 0

    /** id → (label, path)，用于把回来的行配回是哪条请求。 */
    private val pending = HashMap<Int, Pair<String, String>>()

    /**
     * 「有人想拉只读，但 EA 会话还没起来」的待办标记。
     *
     * 只在 [Phase.READY]（收到 0xEA03）时才真正发 0xEA04，所以这里要把用户的意图存住，
     * 等 0xEA03 一到立刻补一轮；否则面板开早一点就得手动再点一次「刷新」。
     */
    private var pendingRefreshOnReady = false

    /** path → 最近一次响应摘要（显示在只读区那一行上）。 */
    private val pathSamples = HashMap<String, String>()

    /** 每个只读端点的请求状态：path → "已请求" / "已回 N 字节"。 */
    private val pathStates = HashMap<String, String>()

    /** 收到的原始行（最多 [MAX_RECENT_LINES] 条，面板底部「原始报文」用）。 */
    private val recentLines = ArrayDeque<String>()

    /** EA 数据是字节流：半行留在这里等下一帧。 */
    private var rxBuffer = ByteArray(0)

    /**
     * 状态变化监听（面板与首页入口都注册，回调里自己切主线程）。
     *
     * 用监听器列表而不是单个 `onChange`：首页那张卡和弹窗可能同时在观察同一条链路，
     * 单槽位会被后注册的一方覆盖（弹窗关掉时把首页的监听一起清掉）。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** BT 侧会话就绪时调用：接上 EA 回调，并清掉上一轮的残留状态。 */
    fun attach(
        session: Iap2SenderSession,
        log: (String) -> Unit,
    ) {
        synchronized(lock) {
            this.session = session
            this.log = log
            nextRequestId = 1
            requestsSent = 0
            responsesSeen = 0
            pending.clear()
            pathSamples.clear()
            pathStates.clear()
            recentLines.clear()
            rxBuffer = ByteArray(0)
            pendingRefreshOnReady = false
        }
        // 回调挂在会话上：保活线程收到 0xEA03/0xEA04 就转进来
        session.onEaStatus = { raw ->
            samplesFromStatus(raw)
            notifyChange()
        }
        session.onEaData = { bytes -> onEaBytes(bytes) }
        log(
            "MIB3: 控制链路已接上（EA 协议=" +
                session.eaProtocols.joinToString { "${it.second}" }
                    .ifEmpty { "待 0x1D01 解析（此刻身份还没到）" } + "）",
        )
        notifyChange()
    }

    /** 链路断开时调用。 */
    fun detach() {
        val previous =
            synchronized(lock) {
                val current = session
                session = null
                rxBuffer = ByteArray(0)
                pending.clear()
                current
            }
        previous?.onEaStatus = null
        previous?.onEaData = null
        if (previous != null) log?.invoke("MIB3: 控制链路已断开")
        notifyChange()
    }

    // ---- 查询 ----

    fun status(): Status {
        val current = synchronized(lock) { session }
        val phase =
            when {
                current == null -> Phase.NO_SESSION
                current.eaDataProtocolId == null -> Phase.NO_PROTOCOL
                current.eaStatusRaw == null -> Phase.WAITING_STATUS
                else -> Phase.READY
            }
        val detail =
            when (phase) {
                Phase.NO_SESSION -> "先连上车机（蓝牙 iAP2 控制会话）"
                Phase.NO_PROTOCOL -> "0x1D01 里没有 EA 协议，车机控制不可用"
                Phase.WAITING_STATUS -> "已发 0xEA00，车机未回 0xEA03；写动作禁用"
                Phase.READY -> "0xEA03 已回，可发 viwi 请求"
            }
        val protocols =
            current?.eaProtocols?.joinToString(" / ") { "${it.second}(${it.first})" }?.ifEmpty { "无" }
                ?: "—"
        val eaStatus =
            current?.eaStatusRaw?.let { raw ->
                if (raw.isEmpty()) "空回执" else raw.joinToString(" ") { "%02x".format(it) }
            } ?: "未收到"
        val counters =
            synchronized(lock) {
                Triple(current?.eaTxBytes ?: 0, current?.eaRxBytes ?: 0, requestsSent to responsesSeen)
            }
        return Status(
            phase = phase,
            detail = detail,
            protocols = protocols,
            eaStatus = eaStatus,
            txBytes = counters.first,
            rxBytes = counters.second,
            requests = counters.third.first,
            responses = counters.third.second,
        )
    }

    /** 只读区的行（按 [Mib3Endpoints.READ_ONLY] 顺序）。 */
    fun rows(): List<Row> =
        Mib3Endpoints.READ_ONLY.map { (label, path) ->
            val (state, sample) =
                synchronized(lock) {
                    (pathStates[path] ?: "未请求") to pathSamples[path]
                }
            Row(label, path, state, sample)
        }

    /** 最近的原始响应行（面板底部展示，最多 8 条）。 */
    fun recentLines(): List<String> = synchronized(lock) { recentLines.toList() }

    // ---- 动作 ----

    /**
     * 拉一轮只读数据（8 条 GET）。
     *
     * **必须等 0xEA03**：0xEA04（EA 数据）是隧道建立之后才合法的载荷。2026-11-04 奥迪
     * MIB3 实车实测（`docs/audi-mib3-ea-tunnel-20261104.md`）：EA 会话没建起来就写 0xEA04，
     * 车机从那一个包起**再也不 ACK**（ACK 停在 seq=114，第一个 0xEA04 是 115），
     * 约 40 秒后被我们的重传看门狗判 `packet 115 not acknowledged` 整条 iAP2 链路拆掉——
     * 面板本来就读不到数据，还顺手把「车机歌名/媒体键/控制隧道」一起带走，且**不会自愈**
     * （CarPlay 会话在，出站重连被 [BtSessionGate] 静音闸门挡住）。三轮会话全部 115，
     * 完全可复现。所以这里宁可晚发，也绝不抢跑。
     *
     * 抢跑的诉求（「早点把隧道激活」）用另一条路满足：调用方要的这轮会被记成待办，
     * 0xEA03 一到立刻自动补发（[samplesFromStatus]）。
     *
     * 返回 false = 链路还不可用（[status] 里能看出原因，面板据此提示）。
     */
    fun refreshReadOnly(): Boolean {
        val current = synchronized(lock) { session }
        if (current == null) {
            log?.invoke("MIB3: 未连接车机，无法拉取只读数据")
            return false
        }
        val phase = status().phase
        if (phase != Phase.READY) {
            synchronized(lock) { pendingRefreshOnReady = true }
            log?.invoke(
                "MIB3: ${phase.label}——本轮不发只读 GET（EA 会话未建立就写 0xEA04，" +
                    "车机会停止 ACK 并把整条 iAP2 链路拖死）；0xEA03 到达后自动补发",
            )
            notifyChange()
            return false
        }
        var sent = 0
        Mib3Endpoints.READ_ONLY.forEach { (label, path) ->
            val id = send(current, "GET", path, null) ?: return@forEach
            synchronized(lock) {
                pathStates[path] = "已请求"
                pending[id] = label to path
            }
            sent++
        }
        log?.invoke("MIB3: 已发 $sent 条只读 GET（${Mib3Endpoints.READ_ONLY.joinToString { it.second }}）")
        notifyChange()
        return sent > 0
    }

    /**
     * 执行一个控制动作。只有 [Phase.READY] 才允许写——见类注释。
     *
     * 返回 false 时原因一定写进日志（面板据此提示），不静默失败。
     */
    fun perform(action: Mib3Endpoints.Action): Boolean {
        val current = synchronized(lock) { session }
        val phase = status().phase
        if (current == null) {
            log?.invoke("MIB3: 未连接车机，${action.label} 未发送")
            return false
        }
        if (phase != Phase.READY) {
            log?.invoke("MIB3: 链路未就绪（${phase.label}），${action.label} 未发送")
            return false
        }
        val id = send(current, action.method, action.path, action.body)
        if (id != null) {
            synchronized(lock) {
                pending[id] = action.label to action.path
                pathStates[action.path] = "已写（等待应答）"
            }
            log?.invoke(
                "MIB3: ${action.method} ${action.path} ${action.body ?: ""} —— ${action.label}",
            )
        }
        notifyChange()
        return id != null
    }

    // ---- 内部 ----

    /** 编码 + 交给会话；成功返回请求 id（用于把响应配回来），失败返回 null。 */
    private fun send(
        session: Iap2SenderSession,
        method: String,
        path: String,
        body: String?,
    ): Int? {
        val id =
            synchronized(lock) {
                val value = nextRequestId++
                requestsSent++
                value
            }
        val request = Mib3Viwi.Request(id, method, path, body)
        val line = request.toJsonLine()
        log?.invoke("MIB3 tx: $line")
        val ok = session.sendEaData(request.encode())
        if (!ok) {
            synchronized(lock) { requestsSent-- }
            log?.invoke("MIB3: 发送失败（EA 隧道不可用）$line")
            return null
        }
        return id
    }

    private fun onEaBytes(bytes: ByteArray) {
        val lines: List<String>
        synchronized(lock) {
            val (complete, rest) = Mib3Viwi.splitLines(rxBuffer + bytes)
            rxBuffer = rest
            // 半行堆积防御：车机若一直吐不带换行的数据，别把内存吃光
            if (rxBuffer.size > MAX_BUFFER_BYTES) {
                rxBuffer = rxBuffer.copyOfRange(rxBuffer.size - MAX_BUFFER_BYTES, rxBuffer.size)
            }
            lines = complete
        }
        lines.forEach { handleLine(it) }
        if (lines.isNotEmpty()) notifyChange()
    }

    private fun handleLine(line: String) {
        if (line.isBlank()) return
        val response = Mib3Viwi.parseLine(line)
        val sample = line.take(MAX_SAMPLE_CHARS)
        synchronized(lock) {
            responsesSeen++
            while (recentLines.size >= MAX_RECENT_LINES) recentLines.removeFirst()
            recentLines.addLast(sample)
            val owner =
                response.id?.let { pending.remove(it) }
                    ?: pending.entries.firstOrNull()?.let { pending.remove(it.key) }
            if (owner != null) {
                pathStates[owner.second] = "已回 ${line.length} 字符"
                pathSamples[owner.second] = sample
            }
        }
        log?.invoke("MIB3 rx: ${response.summary} | $sample")
    }

    /** 0xEA03 本身没有可读文本，只把状态记进日志（面板「EA 状态」行直接显示原始字节）。 */
    private fun samplesFromStatus(raw: ByteArray) {
        log?.invoke(
            "MIB3: 0xEA03 会话状态 ${raw.size}B [" + raw.joinToString(" ") { "%02x".format(it) } + "]",
        )
        // EA 会话刚建立：把之前被挡下的那轮只读请求补上。
        // 不能在 BT 收包线程里直接发（发送路径与接收共用同一条链路，容易自锁），
        // 交给一条一次性后台线程。
        val resume =
            synchronized(lock) {
                val pending = pendingRefreshOnReady
                pendingRefreshOnReady = false
                pending
            }
        if (!resume) return
        Thread(
            {
                log?.invoke("MIB3: 0xEA03 已到——补发被挡下的那轮只读 GET")
                refreshReadOnly()
            },
            "mib3-refresh-on-ready",
        ).apply { isDaemon = true }.start()
    }

    private fun notifyChange() {
        listeners.forEach { listener -> runCatching { listener() } }
    }

    private const val MAX_RECENT_LINES = 8
    private const val MAX_SAMPLE_CHARS = 320
    private const val MAX_BUFFER_BYTES = 64 * 1024
}
