package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.shaonialife.carptocar.util.Bg

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

        /** [restartDiscovery] 的尝试次数与时序：stop 是异步的，得等框架收尾。 */
        private const val RESTART_ATTEMPTS = 5
        private const val RESTART_FIRST_DELAY_MILLIS = 300L
        private const val RESTART_RETRY_DELAY_MILLIS = 500L
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

    /**
     * **跨轮保留**的最后一次解析结果（[resetCache] 不清它）。
     *
     * 为什么必须留（2026-11-11 宝马 iD8 实测）：`NsdManager` 不会为「已发现过」的服务
     * 补发 `onServiceFound`。原来每轮新会话都 `resetCache()` 把 [resolved] 清空，再
     * `start()` 又因为 `discovering=true` 直接返回「已在扫描中」，于是第 2 轮起永远
     * 「未发现」——同一次连车里第 1 轮解析到 `172.16.222.97:7000` 成功，后续所有轮次
     * 都退回网关兜底，而该车热点网关是占位值 `0.0.0.0`，等于整段时间一次真车机都没连过。
     *
     * 缓存可能过期（车机换 IP），所以只作**兜底候选**：[awaitEndpoint] 默认仍只返回
     * 本轮新结果，只有显式 `allowStale = true` 的调用方才会拿到它；地址本身还会被
     * `EndpointHosts` 排序去重、被主控按同网段筛选。
     */
    @Volatile
    private var lastKnown: NsdServiceInfo? = null

    @Volatile
    private var lastKnownAtMillis: Long = 0L

    /**
     * 清「本轮新解析」标记。**故意不清 [lastKnown]**——清掉就再也回不来（见其注释）。
     * 车机确实换了地址时，新结果会覆盖 [resolved]，[lastKnown] 随之更新。
     */
    fun resetCache() {
        resolved = null
    }

    /** 跨轮保留的最后一次解析结果（可能过期，供兜底候选使用）。 */
    fun lastKnownEndpoint(): NsdServiceInfo? = lastKnown

    /**
     * 等已解析结果（轮询缓存）。返回 null = 超时仍未发现。
     * 调用方通常在等待期间继续做别的事（绑定网络、握手），不是纯粹的阻塞 sleep。
     *
     * @param allowStale 本轮没有新结果时是否回退到 [lastKnown]（跨轮缓存）。
     *   只有「拿上轮地址试一下也比不试强」的端点解析该传 true；纯诊断性质的探测
     *   （要判断车机此刻到底有没有起 AirPlay）必须保持 false，否则会误报「已就绪」。
     */
    fun awaitEndpoint(
        timeoutMillis: Long,
        allowStale: Boolean = false,
    ): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        resolved?.let { return it }
        if (allowStale) {
            lastKnown?.let {
                val ageSec = (android.os.SystemClock.uptimeMillis() - lastKnownAtMillis) / 1000
                log(
                    "$TAG: 本轮无新结果——沿用上轮地址 ${it.host?.hostAddress}:${it.port}" +
                        "（${ageSec}s 前解析；NsdManager 不会为已知服务补发回调）",
                )
                return it
            }
        }
        return null
    }

    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            // 不能只打「已在扫描中」就返回：discoverServices 在同一个 listener 上重复调用
            // 会被框架拒掉，而已发现的服务也不会补发 onServiceFound。真停一次再重新
            // discoverServices 才会重新枚举（2026-11-11 宝马 iD8 第 2 轮起全部「未发现」）。
            log("$TAG: 已在扫描中——重启发现以重新枚举已知服务")
            restartDiscovery(serviceType)
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

    /**
     * 真重启发现：先 `stopServiceDiscovery`，再等框架收尾后重新 `discoverServices`。
     *
     * `stop` 是异步生效的，紧接着 `discoverServices` 会抛 "listener already in use"，
     * 所以分多次重试（首次 300ms，之后每 500ms，共 5 次）。整个过程在后台线程，
     * 不占会话线程——发现晚几百毫秒不影响本轮，端点解析另有缓存与邀请地址兜底。
     */
    private fun restartDiscovery(serviceType: String) {
        discovering = false
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        Bg.run("nsd-restart") {
            for (index in 0 until RESTART_ATTEMPTS) {
                if (discovering) return@run
                Thread.sleep(if (index == 0) RESTART_FIRST_DELAY_MILLIS else RESTART_RETRY_DELAY_MILLIS)
                if (registerDiscovery(serviceType)) {
                    discovering = true
                    log("$TAG: 发现已重启（第 ${index + 1} 次尝试）")
                    return@run
                }
            }
            log("$TAG: 发现重启失败——本轮靠上轮缓存 / ctrl 邀请来源地址兜底")
        }
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
                    if (accepted) {
                        resolved = info
                        lastKnown = info
                        lastKnownAtMillis = android.os.SystemClock.uptimeMillis()
                    }
                    onEndpoint(info)
                }
            }
        nsdManager.resolveService(serviceInfo, listener)
    }
}
