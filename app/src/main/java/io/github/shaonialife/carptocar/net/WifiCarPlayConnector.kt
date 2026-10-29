package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * **两种入网方式，默认应用专属网络**：
 *  - [joinCarPlayNetwork]：`WifiNetworkSpecifier` 请求「只对本进程可见」的 WiFi。
 *    系统不会把它保存成普通网络、也不会设为默认网络——其它应用（微信/地图/导航）
 *    继续走 4G/5G。这是无互联网车机热点的正确形态：否则系统会把它当成「已连接但
 *    无网络」的默认 WiFi，部分 ROM 会连带关掉移动数据，用户一上车就断网。
 *  - [bindCurrentWifi]：旧路径，绑定「任意已连接的 WiFi」。仅在用户手动到 WLAN
 *    设置连了热点（系统级网络）时兜底用。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 应用专属网络必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS；
 *  - 首次连接会弹系统确认框；SSID 必须精确匹配（热点名可能带空格/中文）。
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
     * 应用专属网络被系统断开（热点关闭/离开范围）时回调。会话层据此提示用户；
     * 空实现 = 不关心（例如手动绑定路径）。
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径。
     */
    fun gatewayAddress(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

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
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
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
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) {
                release()
                return JoinResult.Unavailable
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                release()
                return JoinResult.Unavailable
            }
        }
        val network = result.get()
        if (network == null) {
            log(
                "WiFi: 等待加入 \"$ssid\" 超时（${timeoutMillis / 1000}s）" +
                    if (unavailable.get()) "——系统已判定不可用" else "——系统确认框可能仍在前台",
            )
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
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
     * 首次登记系统会发一条通知请用户「允许连接」，用户点允许后列表才生效；
     * 返回值 [WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS] 只代表「已登记」，
     * 不代表用户已授权——所以本函数**不能**用来判断网络已可用，必须配合
     * [bindCurrentWifiBlocking] 验证。
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
                val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
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
     * 阻塞版「绑定当前已连接的 WiFi」：等系统给出任意可用 WiFi 网络并绑定进程。
     *
     * 用途是接住 [registerSuggestion] 的成果——建议生效后系统会**自己**连上车机热点，
     * 此时不需要再发 specifier（也不会弹框），直接绑上即可。返回 null = 超时/取消。
     *
     * 与 [bindCurrentWifi] 的区别：那个是回调式、永不返回结果（给 UI 用）；
     * 这个是同步等待，供连接流程在有限窗口内判定成败。
     */
    fun bindCurrentWifiBlocking(
        timeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): Network? {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    boundNetwork.set(network)
                    result.set(network)
                    log("WiFi: 建议网络已生效——绑定当前 WiFi（$network bindProcessToNetwork=$bound）")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        connectivityManager.bindProcessToNetwork(null)
                        onNetworkLost?.invoke("")
                    }
                }
            }
        callback.set(newCallback)
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                true
            } catch (error: Exception) {
                log("WiFi: 绑定当前 WiFi 请求失败（${error.message}）")
                false
            }
        if (!accepted) {
            release()
            return null
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) {
                release()
                return null
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                release()
                return null
            }
        }
        val network = result.get()
        if (network == null) release()
        return network
    }

    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        if (boundNetwork.getAndSet(null) != null) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
