package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.SystemClock
import io.github.shaonialife.carptocar.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用车机热点承载 CarPlay 流量。
 *
 * **入网按「打扰程度」分三层，永远从最安静的一层开始**（2026-11-08 起）：
 *  1. [adoptIfAlreadyConnected]：系统已连在该热点上（系统保存的网络 / 上轮残留 /
 *     建议网络自动关联）→ 只 `bindProcessToNetwork`，**零交互**；
 *  2. [joinSilentlyBySuggestion]：登记 `WifiNetworkSuggestion` 后等系统自己关联，
 *     **零交互**——建议被授权一次后长期有效，进范围即连；
 *  3. [joinCarPlayNetwork]：`WifiNetworkSpecifier`，**每次都会弹系统确认框**，
 *     只在前两层确定失败时用。
 *
 * 前两层合起来就是「静默自动连」：车上没人点屏也不会卡在确认框上。第一层之外还有
 * [bindCurrentWifi]（旧路径，绑「任意已连接的 WiFi」），只在手动兜底场景使用。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 应用专属网络必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS；
 *  - SSID 必须精确匹配（热点名可能带空格/中文），读不到 SSID 时一律当「没连」。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * WiFi 建议（[WifiNetworkSuggestion]）通道。null = 系统不支持（API < 29 或厂商裁剪）。
     *
     * 建议与 [joinCarPlayNetwork] 的 specifier 是两条**互补**的路：
     *  - specifier：本轮立刻入网，但每次都要用户在系统确认框上点「连接」；
     *  - suggestion：登记一次（首次经系统通知授权）后长期有效，进入范围由系统自动
     *    关联，**不再有任何确认框**——这才是车上无人点屏时的正确形态。
     * 所以两者要同时用：suggestion 先登记（异步生效），specifier 同步等结果。
     */
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    val currentNetwork: Network? get() = boundNetwork.get()
    private val boundNetwork = AtomicReference<Network?>(null)

    /**
     * 用 [adoptAssociated] 直接绑上去的那条网络，以及为它挂的「只看断线」回调。
     *
     * 为什么要单独记：specifier 路径靠 `requestNetwork` 的回调拿 `onLost`；而静默路径
     * 只是 `bindProcessToNetwork`，不发任何请求——不补这个回调，**被动断开的自动重绑
     * 整条链路会失效**（热点关闭时日志里连一行「已断开」都没有，只能等下一轮全量连接）。
     */
    private val watchedNetwork = AtomicReference<Network?>(null)

    /**
     * 应用专属网络被系统断开（热点关闭/离开范围）时回调。会话层据此提示用户；
     * 空实现 = 不关心（例如手动绑定路径）。
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * 当前绑定网络的**全部**默认网关（车机热点场景 = 车机 IP），**IPv4 排在最前**。
     *
     * mDNS 发现失败时的兜底路径。
     *
     * 为什么不能只取一条（2026-11-04 奥迪 4910 / MIB3 4580 实车）：
     * 原来写的是 `routes.firstOrNull { isDefaultRoute }`，而这台车机的默认路由里
     * 排第一的是 **IPv6 链路本地** `fe80::1086:3aff:fe8d:9239`，于是 mDNS 未发现时
     * 连续 8 次重连全打在这个地址上（每次 2s 超时，`failed to connect … after 2000ms`）；
     * 而同一份日志里 mDNS 解析出的 `10.174.189.1:7000` 才是通的——唯一的实证路径
     * 就这么被丢掉了。热点场景网关就是车机本身，把 IPv4 那条一起收进来几乎零成本。
     *
     * IPv4 优先的理由同 [EndpointHosts]：安卓上最稳，也是真车唯一验证过的路径。
     */
    fun gatewayAddresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        val gateways =
            try {
                connectivityManager
                    .getLinkProperties(network)
                    ?.routes
                    .orEmpty()
                    .filter { it.isDefaultRoute }
                    .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }
                    .distinct()
            } catch (_: Exception) {
                return emptyList()
            }
        // 占位网关必须剔掉：部分车机热点把默认路由的网关填成 `0.0.0.0`（2026-11-11 宝马
        // iD8 实测），`contains('.')` 判定会把它当合法 IPv4 放行，端点里于是只剩 `0.0.0.0`，
        // 每次连接都打到 127.0.0.1。剔除后端点为空，主控会走 ctrl 邀请来源地址 / mDNS，
        // 而不是拿一个「连自己」的地址空转。
        val usable = gateways.filterNot { EndpointHosts.isPlaceholder(it) }
        if (usable.size < gateways.size) {
            log(
                "WIFI: 忽略占位网关 ${gateways.filter { EndpointHosts.isPlaceholder(it) }.joinToString(" / ")}" +
                    "（不是车机地址，热点 DHCP 没下发真实网关）",
            )
        }
        return usable.filter { it.contains('.') } + usable.filter { !it.contains('.') }
    }

    /** [gatewayAddresses] 的首选（IPv4 优先）。 */
    fun gatewayAddress(): String? = gatewayAddresses().firstOrNull()

    /**
     * 本机在当前绑定网络上的 IPv4 地址（如 10.174.189.57）。
     * 用于 mDNS 结果过滤：手机双归属（连车机热点 + 自己开热点共享给电脑）时，
     * mDNS 会跨网发现电脑的 AirPlay 接收器——非本网段地址一律不是车机。
     */
    fun localIpv4Address(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 本机在当前绑定网络上的全部 IPv6 地址。
     *
     * mDNS 结果的网段验收用：奥迪 4xxx 的 `_airplay._tcp` 常只发链路本地 IPv6，
     * 旧过滤只按 IPv4 前缀判定，会把车机本身也当成「非本网段」丢掉（2026-10-19）。
     */
    fun localIpv6Addresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 用应用专属网络（WifiNetworkSpecifier）加入车机热点。
     *
     * 阻塞直到连接成功/系统拒绝/超时（内部 200ms 分片，[isCancelled] 可即时退出）。
     * 成功后进程网络已绑定，调用方可直接注册 mDNS / 建 socket。
     *
     * 安全类型映射按 0x5703（xcertplay Iap2WirelessSecurity）：
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 仅。
     * WEP 无对应 API（返回 [JoinResult.Unsupported]，调用方走旧路径）。
     *
     * **超时宽限 [graceMillis]**：无互联网的车机热点在部分机型上「关联 + DHCP」要 15 秒
     * 以上——2026-11-07 同机型 HONOR WKL-AN20 对 BMW94841 的 AirPlay 参考实现实测
     * 「尝试加入 13:05:23.407 → 已绑定 13:05:39.136」= **15.73 秒**，而我们只等 15 秒：
     * 每一次都是在系统即将建好网络的那一刻 `release()`，把已经到手的网络连同回调一起
     * 扔掉，现场表现正是「一直卡在加入车机热点」。宽限期只延长**等待**，不提前解绑，
     * 也不改变失败判定——真失败（确认框没人点、热点不在范围）仍会在这段时间后返回。
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        graceMillis: Long = 0L,
        isCancelled: () -> Boolean = { false },
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val unavailable = AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    boundNetwork.set(network)
                    result.set(network)
                    log("WiFi: 已加入应用专属网络 \"$ssid\"（$network bindProcessToNetwork=$bound）——不影响其它应用联网")
                    latch.countDown()
                }

                override fun onUnavailable() {
                    unavailable.set(true)
                    log("WiFi: 系统未允许加入 \"$ssid\"（用户拒绝或热点不在范围内）")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: 应用专属网络 \"$ssid\" 已断开（$network）")
                        onNetworkLost?.invoke(ssid)
                    }
                }
            }
        callback.set(newCallback)
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                true
            } catch (error: Exception) {
                log("WiFi: 发起应用专属网络请求失败（${error.message}）——可能需要位置权限或系统不支持")
                false
            }
        if (!accepted) {
            release()
            return JoinResult.Unavailable
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var state = awaitJoin(latch, deadline, isCancelled)
        // 到点先不解绑：系统常常就在我们放弃之后的几秒内把这条网络建好。
        // 只有「还没就绪」且「系统没说不可用」时才宽限——onUnavailable 已经是定论。
        if (state == 0 && result.get() == null && !unavailable.get() && graceMillis > 0) {
            log(
                "WiFi: \"$ssid\" ${timeoutMillis / 1000}s 内未就绪——不立刻解绑，" +
                    "再宽限 ${graceMillis / 1000}s 等系统把关联+DHCP 走完",
            )
            state = awaitJoin(latch, SystemClock.uptimeMillis() + graceMillis, isCancelled)
        }
        if (state < 0) {
            release()
            return JoinResult.Unavailable
        }
        val network = result.get()
        if (network == null) {
            val window =
                if (graceMillis > 0) {
                    "${timeoutMillis / 1000}s + 宽限 ${graceMillis / 1000}s"
                } else {
                    "${timeoutMillis / 1000}s"
                }
            val why =
                if (unavailable.get()) "——系统已判定不可用" else "——系统确认框可能仍在前台"
            log("WiFi: 等待加入 \"$ssid\" 超时（$window）$why")
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /**
     * 等 [latch] 到点或被取消：**1** = 已就绪，**0** = 等到 deadline，**-1** = 被取消/打断。
     *
     * 抽出来是为了让「正式窗口」和「宽限期」用同一套 200ms 分片逻辑与取消语义——
     * 两份几乎一样的循环最容易在其中一份漏掉 `isCancelled()`。
     */
    private fun awaitJoin(
        latch: CountDownLatch,
        deadline: Long,
        isCancelled: () -> Boolean,
    ): Int {
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return -1
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) return 1
            } catch (_: InterruptedException) {
                return -1
            }
        }
        return 0
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /**
     * 绑定「当前已连接的 WiFi」（用户手动在系统设置里加入热点后的兜底路径）。
     * 热点无互联网时系统可能不把它设为默认网络，必须显式 bindProcessToNetwork，
     * 我们的 socket 才会走 WiFi 而不是蜂窝。
     * 带看门狗：WiFi 未连接时 requestNetwork 会无限静默挂起——5 秒无果给出明确指引
     * （请求保持挂起，用户加入热点后仍会自动完成绑定并回调）。
     */
    fun bindCurrentWifi(onResult: (Boolean) -> Unit = {}) {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // 网络回调线程与 Bg 看门狗线程并发读写——原子标志保证可见性与单次投递
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork.set(network)
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    log("WiFi: 已绑定当前WiFi网络 $network bindProcessToNetwork=$bound")
                    if (reported.compareAndSet(false, true)) onResult(true)
                }

                override fun onUnavailable() {
                    log("WiFi: 没有可用的WiFi连接——请先在系统设置里加入接收端热点")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        log("WiFi: 绑定的 WiFi 网络已断开（$network）")
                    }
                }
            }
        callback.set(newCallback)
        connectivityManager.requestNetwork(request, newCallback)
        // 提示性看门狗（**不决定成败**）：requestNetwork 在「手机尚未连上 WiFi」时
        // 会一直挂起，等系统关联+DCHP 完成才回调 onAvailable——实测用户手动加入
        // 热点时可达 10s+。这里只在 5s 时给用户一句指引，超时判定由调用方的
        // WIFI_BIND_WAIT_S 负责；不要在这里取消请求（回调晚到仍会正确绑定）。
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!reported.get()) {
                log("WiFi: 5 秒尚未绑定（手机可能还没连上热点）——请到 设置>WLAN 加入接收端热点，加入后本轮绑定会自动完成")
            }
        }
    }

    /**
     * 把车机热点登记为「WiFi 建议」——**免确认框**入网的唯一途径。
     *
     * 背景（2026-10-29 BMW iD9 实测定谳）：`WifiNetworkSpecifier` 每次加入都要人在
     * 系统确认框上点「连接」；车机只等约 27 秒就拆蓝牙链，用户在车上看不到手机屏幕，
     * 于是每轮都「发起 → 无人确认 → 车机超时 → 重连」，表现为「连上就闪退」。
     * 建议登记一次后由系统自动关联，进范围即连，不再需要人。
     *
     * 幂等：已登记过（返回 [WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE]）
     * 视为成功——重复登记不是错误，调用方每轮都可以调。
     *
     * 首次登记系统会发一条通知请用户「允许连接」，用户点允许后这条建议才生效；
     * 返回值 [WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS] 只代表「已登记」，
     * 不代表已被授权——所以本函数**不能**用来判断网络已可用，必须配合
     * [awaitAssociation] / [isAssociatedTo] 验证（见 [joinSilentlyBySuggestion]）。
     *
     * 副作用提示：建议网络一旦生效，系统会把它保存并自动连接（属于系统级网络，
     * 会出现在「已保存的网络」里，不再是应用专属）。无互联网时会由系统决定是否
     * 回落移动数据——本应用始终 `bindProcessToNetwork` 到它，其它应用行为不受我们控制。
     */
    fun registerSuggestion(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): Boolean {
        val manager = wifiManager ?: return logSuggestion("不可用（系统无 WifiManager）")
        // 没有 isNetworkSuggestionsSupported 这种公开 API，用「每应用建议条数上限」
        // 判断：厂商裁剪掉该特性时返回 0。
        val capacity =
            runCatching { manager.maxNumberOfNetworkSuggestionsPerApp }.getOrDefault(-1)
        if (capacity <= 0) {
            return logSuggestion("跳过（系统不支持 WifiNetworkSuggestion，上限=$capacity）")
        }
        val suggestion =
            try {
                val builder =
                    WifiNetworkSuggestion
                        .Builder()
                        .setSsid(ssid)
                        // 车机热点没有互联网。系统对「连上但没网」的网络默认按强制门户处理，
                        // 会挂一条「登录到网络」提示等人点，进而把这条网络判成不可用。
                        // 这两个开关明确告诉系统：它不需要用户做任何事，连上即可用
                        // （API 默认值就是 false，显式写出来是为了不被 ROM 默认值带偏）。
                        .setIsAppInteractionRequired(false)
                        .setIsUserInteractionRequired(false)
                when (securityType) {
                    0 -> Unit
                    // WEP 无对应 API，也不该再出现在现代车机上
                    1 -> return logSuggestion("跳过（WEP 无法用建议表达）")
                    4 -> {
                        if (passphrase.isEmpty()) return logSuggestion("跳过（WPA3 但无密码）")
                        builder.setWpa3Passphrase(passphrase)
                    }
                    else -> {
                        if (passphrase.isEmpty()) return logSuggestion("跳过（无密码）")
                        builder.setWpa2Passphrase(passphrase)
                    }
                }
                builder.build()
            } catch (error: Exception) {
                return logSuggestion("构造失败（${error.message}）")
            }
        val status =
            try {
                manager.addNetworkSuggestions(listOf(suggestion))
            } catch (error: Exception) {
                // Android 13+ 缺 NEARBY_WIFI_DEVICES 会抛 SecurityException
                return logSuggestion("登记异常（${error.javaClass.simpleName}: ${error.message}）")
            }
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                log(
                    "WiFi: 已登记热点建议 \"$ssid\"（首次会弹系统通知请用户「允许」；" +
                        "授权后进范围自动连接，不再有确认框）",
                )
                true
            }

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> {
                // 每轮都会走到这里，日常不刷屏：只在调试级别留一行
                log("WiFi: 热点建议 \"$ssid\" 已登记过——无需重复登记")
                true
            }

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED -> {
                logSuggestion("失败（系统不允许现在登记——应用可能不在前台）")
            }

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN -> {
                logSuggestion("失败（被设备管理员/工作资料策略限制）")
            }

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> {
                logSuggestion("失败（本应用被系统禁止提交 WiFi 建议）")
            }

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID -> {
                logSuggestion("失败（建议参数无效：SSID/密码不符合系统要求）")
            }

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> {
                logSuggestion("失败（超过厂商允许的建议条数上限）")
            }

            else -> logSuggestion("失败（status=$status）")
        }
    }

    private fun logSuggestion(reason: String): Boolean {
        log("WiFi: 热点建议$reason")
        return false
    }

    /**
     * 找出「系统已连在 [ssid] 上」的那条 WiFi 网络（**不要求它是默认网络**）。
     *
     * 三种来源都算：用户手动在系统 WLAN 里连过、[registerSuggestion] 登记的建议网络被
     * 系统自动关联、上一轮的应用专属网络还没被系统拆掉。
     *
     * 读不到 SSID 时一律返回 null——Android 11+ 没给定位/附近设备权限时
     * `transportInfo.ssid` 是 `<unknown ssid>`，这时**不能**猜，猜错会把进程网络绑到
     * 家里的 WiFi 上（AirPlay 必然连不上，而且其它应用瞬间断网）。
     *
     * 为什么不直接用 `wifiManager.connectionInfo`：Android 12+ 上它对**应用专属网络**
     * （WifiNetworkSpecifier 建出来的那条）常常报 `<unknown ssid>` 或 netId=-1，
     * 于是「手机其实还挂在车机热点上」被误判成没连——白白再发一次 specifier、
     * 再等一次系统确认框（2026-11-04 奥迪 4910 第二段实录：16:22:37 判成没连，
     * 16:22:52 超时 15s，之后才靠建议网络兜回来）。它现在只作备用。
     */
    private fun findAssociatedNetwork(ssid: String): Network? {
        val viaConnectivity =
            try {
                connectivityManager.allNetworks.firstOrNull { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
                    val info = caps.transportInfo as? WifiInfo ?: return@firstOrNull false
                    plainSsid(info.ssid) == ssid
                }
            } catch (_: Exception) {
                null
            }
        if (viaConnectivity != null) return viaConnectivity

        // 备用：老路径。部分机型 transportInfo 不填 SSID，但 connectionInfo 有。
        val manager = wifiManager ?: return null
        val info =
            @Suppress("DEPRECATION")
            runCatching { manager.connectionInfo }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        if (info.networkId < 0) return null
        if (plainSsid(info.ssid) != ssid) return null
        // SSID 对上了但没有哪条网络的 transportInfo 带 SSID：退到「wlan0 那条」。
        return try {
            connectivityManager.allNetworks.firstOrNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    connectivityManager.getLinkProperties(network)?.interfaceName == WLAN_INTERFACE
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 系统当前是不是已经连在 [ssid] 这条热点上（见 [findAssociatedNetwork]）。 */
    fun isAssociatedTo(ssid: String): Boolean = findAssociatedNetwork(ssid) != null

    /**
     * 把进程网络绑到「系统已连在 [ssid] 上」的那条网络。**不发请求、不弹任何框。**
     *
     * 与 [bindCurrentWifi] 的区别：那个按「TRANSPORT_WIFI」泛匹配，手机同时
     * 连着家里 WiFi 时会绑到**错的那条**（AirPlay 必然连不上，其它应用瞬间断网）。
     * 这里先按 SSID 精确找到网络再绑，把绑错的可能消掉。
     *
     * @return 绑定成功返回那条网络；没连在该热点上 / 绑定被系统拒绝返回 null
     */
    fun adoptAssociated(ssid: String): Network? {
        val network = findAssociatedNetwork(ssid) ?: return null
        // 关联上了不等于能用：DHCP 还没回来的那一瞬网络已经出现在列表里，但没有任何
        // 地址，这时绑上去建 socket 必然失败。等它有地址再算「就绪」——调用方按 300ms
        // 轮询，晚一点拿到不影响，早一步拿到反而是错的。
        if (!linkReady(network)) return null
        return try {
            if (!connectivityManager.bindProcessToNetwork(network)) return null
            boundNetwork.set(network)
            watchLoss(ssid, network)
            network
        } catch (_: Exception) {
            null
        }
    }

    /** 这条网络是否已经拿到地址（说明关联 + 地址分配走完了，可以建 socket）。 */
    private fun linkReady(network: Network): Boolean =
        try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.any { it.address?.hostAddress != null }
                ?: false
        } catch (_: Exception) {
            false
        }

    /**
     * 给「已经绑上的那条网络」挂一个只看断线的回调（幂等，同一条网络只挂一次）。
     *
     * 不在这里 `requestNetwork`：那会按「任意 WiFi」泛匹配，`onAvailable` 可能给到
     * 家里那条；这里只关心 `onLost`，所以用 `registerNetworkCallback` 纯监听。
     */
    private fun watchLoss(
        ssid: String,
        network: Network,
    ) {
        if (watchedNetwork.get() == network && callback.get() != null) return
        callback.getAndSet(null)?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        val watcher =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(lost: Network) {
                    if (!boundNetwork.compareAndSet(lost, null)) return
                    watchedNetwork.compareAndSet(lost, null)
                    connectivityManager.bindProcessToNetwork(null)
                    log("WiFi: 车机热点 \"$ssid\" 已断开（$lost）")
                    onNetworkLost?.invoke(ssid)
                }
            }
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
        val ok =
            try {
                connectivityManager.registerNetworkCallback(request, watcher)
                true
            } catch (error: Exception) {
                log("WiFi: 挂断线监听失败（${error.message}）——热点断开将感知不到")
                false
            }
        if (!ok) return
        callback.set(watcher)
        watchedNetwork.set(network)
    }

    /**
     * 在 [timeoutMillis] 内等系统把手机关联到 [ssid]，并把进程网络绑上去。
     *
     * **全程不弹任何确认框**——这是「静默自动连」唯一能落地的方式。
     *
     * @return null = 超时 / 被取消（热点不在范围、建议还没被授权…）
     */
    fun awaitAssociation(
        ssid: String,
        timeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): Network? {
        adoptAssociated(ssid)?.let { return it }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            try {
                Thread.sleep(ASSOCIATION_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            adoptAssociated(ssid)?.let { return it }
        }
        return null
    }

    /**
     * **静默入网**：登记 WiFi 建议，等系统自己把手机连上车机热点。全程无弹框。
     *
     * 为什么这是唯一能做到「不用手点」的路：[joinCarPlayNetwork] 用的
     * `WifiNetworkSpecifier` **每加入一次都要人在系统确认框上点「连接」**；而 WiFi 建议
     * 被用户授权**一次**之后，系统进入范围就自动关联，之后再也没有任何提示框——
     * 正是车上无人点屏时的形态。
     *
     * 那一次授权是系统行为，应用绕不过去：首次登记时系统会发一条通知请用户「允许」。
     * 所以本函数有可能白等到超时（[timeoutMillis] 就是给它的上限），调用方据此降级。
     *
     * @return 已绑定好的网络；null = 系统没在窗口内连上（建议未授权 / 热点不在范围）
     */
    fun joinSilentlyBySuggestion(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): Network? {
        adoptAssociated(ssid)?.let {
            log("WiFi: 系统已连在 \"$ssid\" 上——直接绑定（无确认框）")
            return it
        }
        if (!registerSuggestion(ssid, passphrase, securityType)) return null
        log(
            "WiFi: 已登记热点建议 \"$ssid\"——等系统自己关联" +
                "（不弹确认框，最多 ${timeoutMillis / 1000}s）",
        )
        return awaitAssociation(ssid, timeoutMillis, isCancelled)
    }

    /** 去掉系统给的双引号，把「读不到」的占位值统一成 null。 */
    private fun plainSsid(raw: String?): String? {
        val plain = raw?.removePrefix("\"")?.removeSuffix("\"") ?: return null
        return if (plain.isEmpty() || plain == UNKNOWN_SSID) null else plain
    }

    /**
     * 目标热点已经在系统里连着时，**直接把它绑到本进程**——不发请求、不弹确认框。
     *
     * 为什么优先于 [joinCarPlayNetwork]：specifier 每发一次，系统就要用户在一个确认框上
     * 点「连接」；车上没人点屏，等到超时（我们 15s / 车机更早）就整轮失败，表现成
     * 「重连时卡在加入热点」。而断线重连的绝大多数场景里，手机本来就还在车机热点上——
     * 系统那张关联表还在，缺的只是 `bindProcessToNetwork`。
     *
     * 立刻判定、立刻返回：没连上就返回 null 交给静默路径去等，**不在这里白等**——
     * 它的窗口和 [joinSilentlyBySuggestion] 的窗口叠起来会把首帧拖得太晚。
     *
     * @return 绑定成功返回网络；未连在该 SSID 上返回 null（调用方走静默/降级路径）
     */
    fun adoptIfAlreadyConnected(ssid: String): Network? {
        val network = adoptAssociated(ssid) ?: return null
        log("WiFi: 系统已连在 \"$ssid\" 上——直接复用，不再发 specifier（不弹确认框）")
        return network
    }

    /**
     * 绑定「当前系统已连上的那条 WiFi」——**不要求知道 SSID**。
     *
     * 为什么必须有这条（2026-11-11 别克 E2LB 现场，网关 10.128.57.1）：用户没给
     * 「附近的设备 / 位置」权限，[findAssociatedNetwork] 一律返回 null（`<unknown ssid>`
     * 不能猜），于是整条保活期里 mDNS 一次都没探过、ctrl 广播也从没挪到这条网络上——
     * 车机在它自己的热点里永远看不到我们，`0x4301` 自然不来，两边对等空等 5 分钟。
     * 车机到底有没有起 AirPlay 服务，日志里连一条证据都没有。
     *
     * 与 [bindCurrentWifi] 的区别（那个也有、但不能用于这里）：它 `requestNetwork`
     * 泛匹配「任意 WiFi」，还会先 [release] 掉现绑定；这里只做「挑一条已经就绪的、
     * 把它绑给本进程」，不发请求、不弹框、不打断已注册的 mDNS 广播。
     *
     * 挑选顺序：已绑定且仍可用 → `wlan0` 那条 → 任意 TRANSPORT_WIFI 且拿到地址的。
     * 只认「已拿到地址」的网络：DHCP 没回来时绑上去建 socket 必然失败。
     *
     * 不挂 [watchLoss]：调用方（保活轮询）每 5s 会重新调一次，断了自然会拿到 null 或
     * 换一条新网络；挂上去反而要用一个假 SSID 去触发 [onNetworkLost] 的重绑流程。
     *
     * @return 已绑定的网络；手机没挂任何 WiFi 时返回 null
     */
    fun adoptCurrentWifi(): Network? {
        boundNetwork.get()?.let { current ->
            if (linkReady(current)) return current
        }
        val candidates =
            try {
                connectivityManager.allNetworks.filter { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && linkReady(network)
                }
            } catch (_: Exception) {
                return null
            }
        if (candidates.isEmpty()) return null
        val onWlan0 =
            candidates.firstOrNull { network ->
                runCatching {
                    connectivityManager.getLinkProperties(network)?.interfaceName == WLAN_INTERFACE
                }.getOrDefault(false)
            }
        val picked = onWlan0 ?: candidates.first()
        return try {
            if (!connectivityManager.bindProcessToNetwork(picked)) return null
            boundNetwork.set(picked)
            picked
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 拍一份「车机热点现在到底什么样」的快照，供 [CarNetworkReadiness] 判读。
     *
     * 只读：不请求、不绑定、不 release，因此可以在任何线程、任何时机调（包括每次
     * 轮询都调一次）。**这是「网络在但没就绪」与「真的掉了」能分开的唯一依据**——
     * 旧代码只有一句「绑定失败」，三种完全不同的现场在日志里长得一模一样。
     *
     * @param expectedSsid 目标热点名；null = 不按 SSID 判断（读不到 SSID 的探测路径）
     */
    fun networkSnapshot(expectedSsid: String?): CarNetworkSnapshot {
        val bound = boundNetwork.get()
        val boundCaps = bound?.let { runCatching { connectivityManager.getNetworkCapabilities(it) }.getOrNull() }
        val boundSsid = plainSsid((boundCaps?.transportInfo as? WifiInfo)?.ssid)
        // 按 SSID 精确找「系统已关联在目标热点上的那条」：读不到 SSID 时它一定返回 null，
        // 这时不猜（猜错会把进程绑到家里的 WiFi 上）。
        val associated = if (expectedSsid == null) null else findAssociatedNetwork(expectedSsid)
        val associatedSsid =
            when {
                associated != null -> expectedSsid
                boundSsid != null -> boundSsid
                else -> currentSystemSsid()
            }
        val link = bound?.let { runCatching { connectivityManager.getLinkProperties(it) }.getOrNull() }
        return CarNetworkSnapshot(
            expectedSsid = expectedSsid,
            boundSsid = boundSsid,
            associatedSsid = associatedSsid,
            hasBoundNetwork = bound != null,
            hasAddress = bound != null && linkReady(bound),
            associatedReady = associated != null && linkReady(associated),
            localIpv4 =
                link?.linkAddresses
                    ?.mapNotNull { it.address?.hostAddress }
                    ?.firstOrNull { it.contains('.') && !it.contains(':') }
                    ?.substringBefore('/'),
            gateway =
                link?.routes
                    ?.filter { it.isDefaultRoute }
                    ?.mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }
                    ?.firstOrNull { it.contains('.') && !EndpointHosts.isPlaceholder(it) },
            interfaceName = link?.interfaceName,
        )
    }

    /** 系统当前 WiFi 的 SSID（读不到就 null）。只作快照的补充信息，不参与猜测。 */
    @Suppress("DEPRECATION")
    private fun currentSystemSsid(): String? {
        val manager = wifiManager ?: return null
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        if (info.networkId < 0) return null
        return plainSsid(info.ssid)
    }

    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        watchedNetwork.set(null)
        if (boundNetwork.getAndSet(null) != null) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    private companion object {
        /** 没权限读 SSID 时系统给的占位值。 */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** 系统里 WiFi 的接口名：`connectionInfo` 读得到 SSID、但网络列表不带 SSID 时的最后手段。 */
        const val WLAN_INTERFACE = "wlan0"

        /**
         * 静默等待「系统把手机连上热点」的轮询间隔。
         *
         * 关联 + DHCP 是系统在后台做的，我们只能看结果；300ms 一次既不会让首帧明显
         * 变慢（比 1s 粒度细），也不至于把 CPU 拉起来（等待窗口最多十几秒）。
         */
        const val ASSOCIATION_POLL_MS = 300L
    }
}
