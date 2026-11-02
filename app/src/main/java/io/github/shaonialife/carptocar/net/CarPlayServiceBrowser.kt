package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS 服务发现：找到接收端（车机/xcertplay）广播的 _airplay._tcp，
 * 解析出 AirPlay 端点与 TXT 记录（deviceid/features/pi/pk/model…）。
 *
 * 真实时序中这一步发生在加入车机热点之后、发起 AirPlay 主会话之前。
 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    /** 结果验收：只缓存/上报真正的车机端点（手机双归属时 mDNS 会混入其它 AirPlay 设备）。 */
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * 是否正在发现。
     *
     * `@Volatile` 必不可少：写方是 NsdManager 的回调线程（onStartDiscoveryFailed /
     * onDiscoveryStopped），读方是会话线程（start/stop）。没有可见性保证时 stop() 可能
     * 读到陈旧 false 直接 return —— discovery 没注销，紧接着的 start() 撞
     * "listener already in use" 并把异常抛进配对流程。
     */
    @Volatile
    private var discovering = false

    /**
     * 已解析到的 `_airplay._tcp`（本网段）。在加入车机热点的瞬间就开始后台发现，
     * 到真正需要端点地址时通常已命中——对照 catplay：它用常驻 Bonjour cache +
     * `watch`，收到 ctrl 邀请时地址现成；我们此前才在配对前现查，白等好几秒。
     */
    @Volatile
    private var resolved: NsdServiceInfo? = null

    /** 新会话开始时清掉上一轮缓存（车机可能换了地址/端口）。 */
    fun resetCache() {
        resolved = null
    }

    /**
     * 等已解析结果（轮询缓存）。返回 null = 超时仍未发现。
     * 调用方通常在等待期间继续做别的事（绑定网络、握手），不是纯粹的阻塞 sleep。
     */
    fun awaitEndpoint(timeoutMillis: Long): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        return resolved
    }

    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            log("$TAG: 已在扫描中")
            return
        }
        log("$TAG: 开始发现 $serviceType")
        if (registerDiscovery(serviceType)) {
            discovering = true
            return
        }
        // "listener already in use"：上一次 stop 还没在 NSD 侧收尾。强制停一次再重试，
        // 仍失败就放弃发现（配对流程还有「直连端点」兜底），不要把异常抛进会话线程。
        log("$TAG: 发现启动失败——强制停止后重试一次")
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = registerDiscovery(serviceType)
        if (!discovering) log("$TAG: 发现仍未启动——本轮改为直连端点兜底")
    }

    /** 真正发起发现；任何异常都折成 false（不抛给调用方）。 */
    private fun registerDiscovery(serviceType: String): Boolean =
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            log("$TAG: discoverServices 异常 - ${it.message}")
        }.isSuccess

    fun stop() {
        if (!discovering) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = false
        log("$TAG: 停止发现")
    }

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                discovering = false
                log("$TAG: 发现启动失败 code=$errorCode")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                log("$TAG: 停止失败 code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("$TAG: 发现服务 ${serviceInfo.serviceName} (${serviceInfo.serviceType})，解析中…")
                resolveWithRetry(serviceInfo, attempts = 3)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("$TAG: 服务消失 ${serviceInfo.serviceName}")
            }
        }

    private fun resolveWithRetry(
        serviceInfo: NsdServiceInfo,
        attempts: Int,
    ) {
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                        Thread {
                            Thread.sleep(200)
                            resolveWithRetry(info, attempts - 1)
                        }.start()
                    } else {
                        log("$TAG: 解析失败 ${info.serviceName} code=$errorCode")
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: "no-host"
                    val txt =
                        info.attributes.entries.joinToString(", ") { (key, value) ->
                            val text =
                                value
                                    ?.toString(Charsets.UTF_8)
                                    ?.trimEnd { it.code == 0 }
                                    .orEmpty()
                            "$key=$text"
                        }
                    // 验收放在缓存之前：非车机端点（如同一 WiFi 上的 Mac）不得污染 awaitEndpoint
                    val accepted = accept(info)
                    log("$TAG: OK ${info.serviceName} -> $host:${info.port}${if (accepted) "" else "（非车机，忽略）"}")
                    if (txt.isNotEmpty()) log("$TAG: TXT $txt")
                    if (accepted) resolved = info
                    onEndpoint(info)
                }
            }
        nsdManager.resolveService(serviceInfo, listener)
    }
}
