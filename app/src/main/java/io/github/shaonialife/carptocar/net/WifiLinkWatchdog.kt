package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 网络链路的**签名**（纯函数，便于单测）。
 *
 * 只保留「换没换过」这件事需要的三个维度：
 *  - `wifi`：系统当前全部 WiFi 网络的 `接口/地址`（车机热点换频段、热点重启都会变）；
 *  - `car`：我们应用专属网络绑定的那一条（null = 没绑上）；
 *  - `default`：进程默认网络（`bindProcessToNetwork` 是否被别人顶掉）。
 *
 * 用 IPv4 而不是 netId：netId 是 @hide，且换一次关联就换号，读日志的人分辨不出
 * 「还是那条 192.168.1.5 的车机热点」和「换了一条新热点」。
 */
internal object WifiLinkSignature {
    fun of(
        wifi: Collection<String>,
        car: String?,
        default: String?,
        ssid: String?,
    ): String =
        "wifi=[${wifi.sorted().joinToString(",")}]" +
            " car=${car ?: "-"}" +
            " default=${default ?: "-"}" +
            " ssid=${ssid ?: "-"}"
}

/**
 * wlan0 状态心跳：把「WiFi 到底有没有动过」变成日志里能对着时间戳看的一行。
 *
 * ## 为什么需要（2026-11-02）
 *
 * 多份现场日志里 CarPlay 会话每 40~80 秒被拆一次，而断链前 3 秒都「毫无异常」。
 * 要证伪「WiFi 抖动 / 重绑把链路带走」这个假设，现有日志做不到：
 * [WifiCarPlayConnector] 只在自己主动 release 或收到 `onLost` 时说话，
 * **系统侧的被动变化**（ROM 踢关联、信道切换、热点重启、双 WLAN 争抢）全程静默。
 *
 * ## 两条信息互补
 *
 *  - `NetworkCallback`：系统级事件（onAvailable/onLost/onCapabilitiesChanged），
 *    毫秒级，能直接落在断链时刻前后；
 *  - 5 秒轮询签名：`bindProcessToNetwork` 的目标是否被别的网络顶掉——这类变化
 *    回调不一定报，但默认网络会变。
 *
 * 平时零噪声：**只有签名变了才记一行**。复现时那一行就是答案。
 *
 * 只监听、不请求：用 `registerNetworkCallback` 而不是 `requestNetwork`，
 * 不会因为开个诊断就多建一条网络（那会反过来干扰被测对象）。
 */
class WifiLinkWatchdog(
    context: Context,
    private val log: (String) -> Unit,
    /** 应用专属网络（车机热点）当前实例，由 [WifiCarPlayConnector.currentNetwork] 提供。 */
    private val carNetwork: () -> Network?,
    /** 当前认为已绑定的车机 SSID（诊断用，不参与判定）。 */
    private val boundSsid: () -> String?,
) {
    private val cm: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifiEndpoints = LinkedHashSet<String>()
    private var lastSignature: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    /** 事件累计数：收尾汇总用（0 = 从头到尾没动过，可直接排除 WiFi）。 */
    @Volatile
    var eventCount: Int = 0
        private set

    fun start() {
        val manager = cm ?: return
        if (running) return
        running = true
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(wifiEndpoints) { wifiEndpoints += endpoint(network) }
                    report("系统事件 onAvailable")
                }

                override fun onLost(network: Network) {
                    synchronized(wifiEndpoints) { wifiEndpoints -= endpoint(network) }
                    report("系统事件 onLost")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    // 关联参数变化（换信道 / 重新认证）不一定伴随 onAvailable/onLost，
                    // 但会导致帧发送卡顿——留一条，只在不重复时输出。
                    synchronized(wifiEndpoints) { wifiEndpoints += endpoint(network) }
                    report("系统事件网卡能力变化")
                }
            }
        callback = newCallback
        val registered =
            try {
                manager.registerNetworkCallback(request, newCallback)
                true
            } catch (error: Exception) {
                log("WIFI: 状态心跳注册失败 - ${error.message}（本次不带 wlan0 事件）")
                false
            }
        if (!registered) {
            running = false
            callback = null
            return
        }
        // 首次签名不计入「变化」，但要落一行，否则读日志的人不知道起点是什么。
        lastSignature = currentSignature()
        log("WIFI: 状态心跳已启动（起点 ${lastSignature}）")
        val thread =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "wifi-link-watchdog").apply { isDaemon = true }
            }
        scheduler = thread
        thread.scheduleWithFixedDelay({ poll() }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running) return
        running = false
        callback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        callback = null
        scheduler?.shutdownNow()
        scheduler = null
        if (eventCount > 0) {
            log("WIFI: 状态心跳已停止——本轮收到 $eventCount 次网络事件（明细见上文时间戳）")
        }
    }

    private fun poll() {
        if (!running) return
        report("心跳")
    }

    /** 签名变了才记一行；`reason` 说明这一行是系统事件还是轮询发现的差异。 */
    private fun report(reason: String) {
        val signature = currentSignature()
        val previous = lastSignature
        if (signature == previous) return
        lastSignature = signature
        eventCount++
        if (previous == null) {
            log("WIFI: $reason → $signature")
        } else {
            log("WIFI: $reason → $signature（上次 $previous）")
        }
    }

    private fun currentSignature(): String {
        val wifi =
            synchronized(wifiEndpoints) {
                if (running && wifiEndpoints.isEmpty()) {
                    // 回调还没跑过：显式取一次当前 WiFi，避免起点写成「没有任何网络」
                    endpoint(cm?.activeNetwork)?.takeIf { it != "-" }?.let { setOf(it) } ?: emptySet()
                } else {
                    wifiEndpoints.toSet()
                }
            }
        return WifiLinkSignature.of(
            wifi = wifi,
            car = endpoint(carNetwork()),
            default = endpoint(runCatching { cm?.activeNetwork }.getOrNull()),
            ssid = boundSsid(),
        )
    }

    /**
     * 网络的可读标识：`接口/IPv4`。
     *
     * 车机热点固定落在某个 `192.168.x.x` 上，换频段或热点重启都会换地址——
     * 这正好是我们要区分的那件事。拿不到地址时退回接口名，再不行用 `-`。
     */
    private fun endpoint(network: Network?): String {
        if (network == null) return "-"
        val manager = cm ?: return "-"
        val properties =
            runCatching { manager.getLinkProperties(network) }.getOrNull() ?: return "-"
        val iface = properties.interfaceName ?: "?"
        val address =
            properties.linkAddresses
                .firstOrNull { it.address is Inet4Address }
                ?.address
                ?.hostAddress
        return if (address != null) "$iface/$address" else iface
    }

    private companion object {
        /** 轮询间隔：够密（一次断链 30~80 秒，至少能采到几次）又不至于刷日志。 */
        const val POLL_INTERVAL_MS = 5_000L
    }
}
