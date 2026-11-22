package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import java.io.IOException

/**
 * 发送端会话（cardeck 0.1.29 固件引擎驱动层）。
 *
 * 协议逻辑 100% 在 Rust `libcardeck_protocol.so`：链路 FSM、识别、MFi、
 * 0x5703 热点、0x4301 端点、编排泵全由 `nativeSessionFlowStart` 推进。
 * Kotlin 只做两件事：启动 flow、把负 id 编排事件（-1 WIFI / -2 ENDPOINT /
 * -3 PHASE1_DONE）解成平台动作。
 *
 * 本分支的调用契约保持 v2 形态（`run(timeoutMillis)` 返回端点 →
 * `pumpIncomingForever()` 保活），因此这里在 cardeck 218 行薄层基础上补了
 * 兼容入口；后段的 no-op 成员是给主控多年迭代的守卫/媒体回调留的落点，
 * 这些职责在固件引擎里已由 Rust 承担（详见各成员注释）。
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 传输标识；占位值会被真车静默丢弃）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 收到 EV_WIFI（0x5703 消化产物）时回调：主控立即绑 WiFi + 广播 _carplay-ctrl。 */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /** 端点在保活阶段晚到的回调（Rust ENDPOINT 事件可重复到达）。 */
    private val onEndpointUpdate: ((CarPlayEndpoint) -> Unit)? = null,
    /** 仅用于兼容旧调用契约；固件引擎不需要 Context 记忆。 */
    private val context: android.content.Context? = null,
    /** 媒体信息提供者；Rust 侧媒体库推送口当前未接线，保留参数避免调用方改动。 */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
) {
    /** 从 EV_ENDPOINT（0x4301 消化产物）解出的接收端信息。 */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        val advertisedFeatures: Long? = null,
        val mutualAuth: Int = 0,
        val sdkVersion: Long = 0,
        val asset: String? = null,
    )

    /** EV_WIFI 载荷解析产物。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    @Volatile
    private var flowStarted = false

    /**
     * 本轮拿到过 0x4301（Rust ENDPOINT 事件）没有——上层用它判定车机端 CarPlay
     * 是否已退出。语义对齐 v2 的同类状态位。
     */
    @Volatile
    var startSessionReceived: Boolean = false
        private set

    private var firstEndpoint: CarPlayEndpoint? = null

    /**
     * Phase 1：等待链路就绪 → 启动 Rust 编排泵 → 收第一个 ENDPOINT 事件返回。
     *
     * Rust 编排事件里 ENDPOINT 是唯一的连接触发点；PHASE1_DONE 只是诊断。
     * awaitReady 的 0 是固件引擎的无限期哨兵（不做纳秒乘法，避免溢出）。
     */
    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        val deadline =
            if (timeoutMillis <= 0L) Long.MAX_VALUE else System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(timeoutMillis)) throw IOException("iAP2 链路协商超时")
        if (!flowStarted) {
            channel.startFlow(
                "iPhone".toByteArray(),
                bluetoothMac.toByteArray(),
                java.util.Locale.getDefault().toLanguageTag().toByteArray(),
                java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000,
                if (java.util.TimeZone.getDefault().inDaylightTime(java.util.Date())) 1 else 0,
                1, // wirelessAllowed
                1, // carplayPaired
            )
            flowStarted = true
        }
        while (channel.isAlive) {
            val remaining = if (deadline == Long.MAX_VALUE) 200L else (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) break
            channel.pollFlowEvent(minOf(200L, remaining))?.let { event ->
                when (event.id) {
                    -1 -> {
                        val cfg = decodeWifiEvent(event.bytes)
                        log("iap2 flow wifi ssid=${cfg.ssid} channel=${cfg.channel} security=${cfg.securityType}")
                        onWifiCredentials?.invoke(cfg)
                    }
                    -2 -> {
                        startSessionReceived = true
                        val endpoint = decodeEndpointEvent(event.bytes)
                        log(
                            "iap2 flow endpoint port=${endpoint.airPlayPort} " +
                                "ssid=${endpoint.ssid} ips=${endpoint.ipAddresses}",
                        )
                        if (firstEndpoint == null) {
                            firstEndpoint = endpoint
                            return endpoint
                        }
                        onEndpointUpdate?.invoke(endpoint)
                    }
                    -3 -> log("iap2 flow phase-1 done")
                }
            }
        }
        throw IOException("iAP2 编排泵未产出端点（链路已关闭或超时）")
    }

    /**
     * Phase 2：保活泵。固件引擎自行驱动链路活性，这里只继续消费编排事件；
     * 晚到的 ENDPOINT 交给 [onEndpointUpdate]，不再有 CSM 解析层。
     */
    fun pumpIncomingForever() {
        while (channel.isAlive) {
            channel.pollFlowEvent(200L)?.let { event ->
                when (event.id) {
                    -1 -> onWifiCredentials?.invoke(decodeWifiEvent(event.bytes))
                    -2 -> {
                        startSessionReceived = true
                        onEndpointUpdate?.invoke(decodeEndpointEvent(event.bytes))
                    }
                    -3 -> Unit
                    else -> log("iap2 flow event id=${event.id} len=${event.bytes.size}")
                }
            }
        }
        log("iap2 会话循环结束（链路关闭）")
    }

    // ---- 以下为旧调用契约的兼容落点；职责已下沉 Rust，不再有独立动作 ----

    /** 媒体库推送：cardeck 0.1.29 的 nativeSessionSendMediaLibrary 在 Kotlin 侧零调用。 */
    fun pushNowPlaying() = Unit

    /** 远端会话结束记账由 Rust 会话引擎负责。 */
    fun noteRemoteSessionEnded(reason: String) = Unit

    /** 固件链路层自带重传上限，无 Kotlin 侧探测泵。 */
    fun stopProbe() = Unit

    val isProbeRunning: Boolean get() = false

    /** 0x4300 催问由 Rust 编排泵按自身节拍推进。 */
    fun nudgeCarPlayAvailability(reason: String): Boolean = false

    /** 会话重建请求由 Rust flow 决策机处理。 */
    fun requestImmediateRetry(reason: String): Boolean = false

    fun clearRemoteDisconnectGrace(reason: String) = Unit

    fun noteManualStop(reason: String) = Unit

    val passiveGraceRemainingMillis: Long get() = 0L

    // ---- 链路事件载荷解码（Rust 平台动作事件体契约，cardeck 0.1.29 原样） ----

    private fun nulIndexOf(
        b: ByteArray,
        from: Int,
    ): Int {
        var i = from
        while (i < b.size) {
            if (b[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    /** EV_WIFI 载荷：ssid\0 psk\0 ch(u8) sec(u8)。 */
    private fun decodeWifiEvent(b: ByteArray): WifiConfiguration {
        val nul = nulIndexOf(b, 0)
        val ssid = String(b, 0, if (nul < 0) b.size else nul)
        val nul2 = nulIndexOf(b, nul + 1)
        val pskEnd = if (nul2 < 0) b.size else nul2
        val psk = String(b, nul + 1, pskEnd - nul - 1)
        val ch = b.getOrNull(pskEnd + 1)?.toInt()?.and(0xFF) ?: 0
        val sec = b.getOrNull(pskEnd + 2)?.toInt()?.and(0xFF) ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = psk, channel = ch, securityType = sec)
    }

    /** EV_ENDPOINT：port(u32be) + ident\0 + pubkey\0 + srcvers\0 + ips + wifi 尾块 + 参数 6/7/8。 */
    private fun decodeEndpointEvent(b: ByteArray): CarPlayEndpoint {
        var off = 4
        val port =
            ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

        fun nulString(): String {
            val end = nulIndexOf(b, off)
            if (end < 0) {
                val s = String(b, off, b.size - off)
                off = b.size
                return s
            }
            val s = String(b, off, end - off)
            off = end + 1
            return s
        }
        val ident = nulString()
        val pubkey = nulString()
        val srcvers = nulString()
        val ips = ArrayList<String>()
        while (off < b.size) {
            val ip = nulString()
            if (ip.isEmpty()) break
            ips += ip
        }
        val ssid = nulString()
        val pass = nulString()
        val channel = b.getOrNull(off)?.toInt()?.and(0xFF) ?: 0
        val security = b.getOrNull(off + 1)?.toInt()?.and(0xFF) ?: 0
        val mutualAuth = b.getOrNull(off + 2)?.toInt()?.and(0xFF) ?: 0
        var sdkVersion = 0L
        var asset: String? = null
        if (b.size >= off + 7) {
            sdkVersion =
                ((b[off + 3].toInt() and 0xFF).toLong() shl 24) or
                    ((b[off + 4].toInt() and 0xFF).toLong() shl 16) or
                    ((b[off + 5].toInt() and 0xFF).toLong() shl 8) or
                    (b[off + 6].toInt() and 0xFF).toLong()
            if (b.size > off + 7) {
                asset = String(b, off + 7, b.size - off - 7).trimEnd('\u0000').takeIf { it.isNotEmpty() }
            }
        }
        return CarPlayEndpoint(
            ssid = ssid,
            passphrase = pass,
            channel = channel,
            securityType = security,
            airPlayPort = port,
            deviceIdentifier = ident,
            publicKey = pubkey,
            sourceVersion = srcvers,
            ipAddresses = ips,
            mutualAuth = mutualAuth,
            sdkVersion = sdkVersion,
            asset = asset,
        )
    }
}
