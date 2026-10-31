package io.github.shaonialife.carptocar.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用 **Wi-Fi Direct（P2P）** 加入车机自建的组。
 *
 * 为什么需要单独一条路：部分接收端（车机 / 自制测试台）不用普通热点，而是开 P2P GO，
 * 组名固定 `DIRECT-xy-<设备名>`。这类组**不能**用 `WifiNetworkSpecifier` 加入——
 * 它由 `WifiP2pManager` 独占管理，站模式（wlan0）关联上去也拿不到组的加密参数与 IP，
 * 表现为「一直连不上、日志里连失败原因都没有」。
 *
 * 车机给的凭据里**没有** P2P 的 deviceAddress（`0x5703`/`0x4301` 只有 SSID/密码/BSSID），
 * 所以对等体地址只能自己找，按可靠性依次是：
 *  1. `0x5703` param 0 的 **BSSID**——P2P GO 的 BSSID 通常就是它的设备地址，最可靠；
 *  2. 扫描到的对等体里 **deviceName 与组名后缀匹配**的那个；
 *  3. 扫描结果**只有一个对等体**时直接用（小范围内往往就是那台车）。
 *
 * 加入成功后本进程绑定到 p2p 接口对应的网络（按 `interfaceName` 前缀 `p2p` 精确匹配，
 * 不做「随便绑一个 WiFi」——那会把流量绑到家里的路由器上）。
 *
 * 全程失败都返回 [Result.Unavailable]，由调用方退回应用专属网络的老路，
 * 因此这条路径**不会让现在能连上的车机变差**。
 */
class WifiP2pCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    sealed interface Result {
        /** 已加入组。[network] 为 null 表示没找到 p2p 网络对象，但组已成立（靠路由直连）。 */
        data class Joined(
            val network: Network?,
        ) : Result

        /** 未加入：P2P 未开启、扫描不到、连不上、超时。调用方继续走站模式。 */
        data object Unavailable : Result

        /** 本机不支持 P2P / 没有权限 / 不是 P2P 组名——不必再试。 */
        data object NotApplicable : Result
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    @Volatile
    private var channel: WifiP2pManager.Channel? = null

    private val groupFormed = AtomicReference<WifiP2pInfo?>(null)
    private val peers = AtomicReference<List<WifiP2pDevice>>(emptyList())

    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** 组是否已建立（供外部判断是否需要清理）。 */
    val isGroupJoined: Boolean get() = groupFormed.get()?.groupFormed == true

    /**
     * 加入一个 Wi-Fi Direct 组。阻塞到成功/失败/超时。
     *
     * @param bssidHint `0x5703` param 0 给的 BSSID，作 deviceAddress 首选
     * @param channelHint `0x5703` 的 channel，用于指定 GO 工作频段（0/未知则不指定）
     */
    fun joinGroup(
        ssid: String,
        passphrase: String,
        bssidHint: String? = null,
        channelHint: Int = 0,
        timeoutMillis: Long = 45_000L,
        isCancelled: () -> Boolean = { false },
    ): Result {
        if (!WifiP2pGroupNames.isP2pGroupName(ssid)) return Result.NotApplicable
        val p2p = manager ?: run {
            log("P2P: 本机没有 WifiP2pManager（不支持 Wi-Fi Direct）")
            return Result.NotApplicable
        }
        if (!hasPermission()) {
            log("P2P: 缺少附近设备/位置权限——无法扫描与加入 Wi-Fi Direct 组（请到系统设置授权后重试）")
            return Result.NotApplicable
        }
        val ch = ensureChannel(p2p) ?: run {
            log("P2P: 无法建立 WifiP2pManager 通道——退回站模式")
            return Result.Unavailable
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        // 上一轮可能留下 groupFormed=true（持久组/未清理干净），不清掉会让 awaitGroup 立刻返回旧结果
        groupFormed.set(null)
        try {
            // 1) 先按 BSSID 直接连（GO 的 BSSID 通常等于其设备地址），省掉一轮扫描。
            //    这一趟只给 15s：失败要留足时间走扫描，不能把整个窗口吃光。
            val hint = bssidHint?.takeIf { isMacAddress(it) }
            if (hint != null) {
                log("P2P: 用 0x5703 的 BSSID $hint 作为 GO 地址直接连接")
                val directDeadline = minOf(deadline, SystemClock.uptimeMillis() + 15_000L)
                if (connect(p2p, ch, ssid, passphrase, hint, channelHint)) {
                    awaitGroup(directDeadline, isCancelled)?.let { return finish(it) }
                    if (isCancelled()) return Result.Unavailable
                    // 请求已下发但组没起来：必须撤销，否则下一次 connect 直接吃 BUSY
                    runCatching { p2p.cancelConnect(ch, null) }
                    log("P2P: BSSID 直连未成（组未建立）——撤销请求，改走扫描")
                }
            } else {
                log("P2P: 车机未给 BSSID——先扫描对等体再连接")
            }

            // 2) 扫描对等体，按名字/唯一性挑一个
            val peer = discoverPeer(p2p, ch, ssid, bssidHint, deadline, isCancelled) ?: run {
                log("P2P: 扫描不到与 \"$ssid\" 匹配的对等体——退回站模式")
                return Result.Unavailable
            }
            log("P2P: 选中对等体 ${peer.deviceName}（${peer.deviceAddress}）")
            if (!connect(p2p, ch, ssid, passphrase, peer.deviceAddress, channelHint)) {
                return Result.Unavailable
            }
            val info = awaitGroup(deadline, isCancelled) ?: run {
                log("P2P: 已发起连接但 ${timeoutMillis / 1000}s 内组未建立——退回站模式")
                return Result.Unavailable
            }
            return finish(info)
        } catch (error: Exception) {
            log("P2P: 加入过程异常（${error.message}）——退回站模式")
            return Result.Unavailable
        }
    }

    /** 断开并释放（会话结束时调用；失败不影响主流程）。 */
    fun release() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        val p2p = manager
        val ch = channel
        if (p2p != null && ch != null) {
            // 只清理我们加入的组；没加入过时这个调用会报错，忽略即可
            runCatching { p2p.removeGroup(ch, null) }
            runCatching { p2p.stopPeerDiscovery(ch, null) }
        }
        channel = null
        groupFormed.set(null)
        peers.set(emptyList())
    }

    // ---------------------------------------------------------------- 内部

    private fun ensureChannel(p2p: WifiP2pManager): WifiP2pManager.Channel? {
        channel?.let { return it }
        val created =
            p2p.initialize(context, Looper.getMainLooper()) {
                // 通道断开（P2P 服务重启/被系统回收）：置空，下次连接重建
                log("P2P: 通道断开——下次连接会重建")
                channel = null
            }
        channel = created
        if (created == null) return null
        registerReceiver()
        return created
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val filter =
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
        val newReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val enabled =
                                intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                    WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            log("P2P: Wi-Fi Direct ${if (enabled) "已开启" else "未开启（将无法加入组）"}")
                        }

                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            // 扫描结果只能主动取；这里不阻塞，取到就存最新一份。
                            // requestPeers 需要 NEARBY_WIFI_DEVICES（Android 13+）/ 定位权限，
                            // 缺权限时系统抛 SecurityException —— 显式 catch 而不是靠外层
                            // runCatching：lint 的 MissingPermission 只认 checkPermission
                            // 或 catch SecurityException，认不出 runCatching。
                            try {
                                channel?.let { ch ->
                                    manager?.requestPeers(ch) { list ->
                                        peers.set(list?.deviceList?.toList().orEmpty())
                                    }
                                }
                            } catch (_: SecurityException) {
                                log("P2P: 缺「附近的设备」权限，拿不到扫描结果（不影响已发起的手动加入）")
                            }
                        }

                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            // 组信息在 EXTRA_WIFI_P2P_INFO 里；NB：网络状态另在 EXTRA_NETWORK_INFO
                            val info =
                                if (Build.VERSION.SDK_INT >= 33) {
                                    intent.getParcelableExtra(
                                        WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                                        WifiP2pInfo::class.java,
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                                }
                            if (info != null && info.groupFormed) groupFormed.set(info)
                        }
                    }
                }
            }
        receiver = newReceiver
        runCatching {
            ContextCompat.registerReceiver(
                context,
                newReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun connect(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        ssid: String,
        passphrase: String,
        deviceAddress: String,
        channelHint: Int,
    ): Boolean {
        val config =
            try {
                WifiP2pConfig
                    .Builder()
                    .setDeviceAddress(MacAddress.fromString(deviceAddress))
                    .apply {
                        if (ssid.isNotEmpty()) setNetworkName(ssid)
                        if (passphrase.isNotEmpty()) setPassphrase(passphrase)
                        // 上车即连：记住这辆车，下次不用再走一遍完整协商
                        enablePersistentMode(true)
                        frequencyOf(channelHint)?.let { setGroupOperatingFrequency(it) }
                    }.build()
            } catch (error: Exception) {
                log("P2P: 构造连接参数失败（${error.message}）——退回站模式")
                return false
            }
        val latch = CountDownLatch(1)
        val ok = AtomicReference(false)
        val reason = AtomicReference(-1)
        try {
            p2p.connect(
                ch,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        ok.set(true)
                        latch.countDown()
                    }

                    override fun onFailure(error: Int) {
                        reason.set(error)
                        latch.countDown()
                    }
                },
            )
        } catch (_: SecurityException) {
            // 缺 NEARBY_WIFI_DEVICES（Android 13+）/ 定位权限
            log("P2P: 缺「附近的设备」权限，无法发起 Wi-Fi Direct 加入——退回站模式")
            return false
        }
        // connect() 只表示「请求已下发」，真正建组要等广播；这里只等它接受请求
        latch.await(10, TimeUnit.SECONDS)
        if (!ok.get()) {
            log("P2P: connect 被拒（${reasonText(reason.get())}）")
            return false
        }
        return true
    }

    /** 扫描并挑出目标对等体。返回 null = 窗口内没找到。 */
    private fun discoverPeer(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        ssid: String,
        bssidHint: String?,
        deadline: Long,
        isCancelled: () -> Boolean,
    ): WifiP2pDevice? {
        val wantedName = WifiP2pGroupNames.deviceNameOf(ssid)
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            peers.set(emptyList())
            try {
                p2p.discoverPeers(ch, null)
            } catch (_: SecurityException) {
                // 缺 NEARBY_WIFI_DEVICES（Android 13+）/ 定位权限：扫描发不出去，
                // 继续轮询也没有意义
                log("P2P: 缺「附近的设备」权限，无法扫描对等体——退回站模式")
                return null
            }
            // 单轮扫描 8s；期间 peers 由广播异步填充
            val roundEnd = minOf(deadline, SystemClock.uptimeMillis() + 8_000L)
            while (SystemClock.uptimeMillis() < roundEnd) {
                if (isCancelled()) return null
                val list = peers.get()
                if (list.isNotEmpty()) {
                    selectPeer(list, wantedName, bssidHint)?.let { return it }
                }
                Thread.sleep(200)
            }
        }
        return null
    }

    private fun selectPeer(
        list: List<WifiP2pDevice>,
        wantedName: String?,
        bssidHint: String?,
    ): WifiP2pDevice? {
        if (bssidHint != null) {
            list.firstOrNull { it.deviceAddress.equals(bssidHint, ignoreCase = true) }?.let { return it }
        }
        if (wantedName != null) {
            list.firstOrNull { it.deviceName.equals(wantedName, ignoreCase = true) }?.let { return it }
            list
                .firstOrNull {
                    it.deviceName.isNotEmpty() &&
                        (it.deviceName.contains(wantedName, true) || wantedName.contains(it.deviceName, true))
                }?.let { return it }
        }
        return list.singleOrNull()
    }

    /** 等组建立并完成绑定。 */
    private fun awaitGroup(deadline: Long, isCancelled: () -> Boolean): WifiP2pInfo? {
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            val info = groupFormed.get()
            if (info != null && info.groupFormed) return info
            Thread.sleep(200)
        }
        return null
    }

    private fun finish(info: WifiP2pInfo): Result {
        if (info.isGroupOwner) {
            // 我们成了 GO：说明选错了角色（车机才是 GO），这个组对我们没用
            log("P2P: 本机成了 GO（不是加入方）——这不是期望的角色，断开后走站模式")
            return Result.Unavailable
        }
        val goIp = info.groupOwnerAddress?.hostAddress
        log("P2P: 已加入组，GO=$goIp")
        val network = bindP2pInterface()
        if (network == null) {
            // 组已成立但拿不到 Network 对象：清掉可能存在的错误绑定，靠子网路由直连
            connectivityManager.bindProcessToNetwork(null)
            log("P2P: 未找到 p2p 接口对应的网络对象——已解除绑定，改用子网路由直连")
        }
        return Result.Joined(network)
    }

    /**
     * 把进程绑定到 p2p 接口所在的网络。
     *
     * 必须按 `interfaceName` 前缀 `p2p` 精确匹配：直接 `requestNetwork(WIFI)` 在
     * 组刚建立、p2p 网络还没注册时，会绑到家里的普通 WiFi 上，等于把连接绑错网。
     */
    @SuppressLint("MissingPermission")
    private fun bindP2pInterface(): Network? {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            val found =
                connectivityManager.allNetworks.firstOrNull { network ->
                    runCatching {
                        connectivityManager.getLinkProperties(network)?.interfaceName?.startsWith("p2p") == true
                    }.getOrDefault(false)
                }
            if (found != null) {
                val bound = connectivityManager.bindProcessToNetwork(found)
                log("P2P: 已绑定 p2p 网络（$found bindProcessToNetwork=$bound）")
                return found
            }
            Thread.sleep(200)
        }
        return null
    }

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun isMacAddress(text: String): Boolean =
        runCatching { MacAddress.fromString(text) }.isSuccess

    /** 信道 → 频率（只处理 2.4G 1~13 与常见 5G 段；其它返回 null = 不指定）。 */
    private fun frequencyOf(channel: Int): Int? =
        when (channel) {
            in 1..13 -> 2407 + channel * 5
            in 36..165 -> 5000 + channel * 5
            else -> null
        }

    private fun reasonText(reason: Int): String =
        when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
            WifiP2pManager.BUSY -> "P2P 忙（上一轮未清理干净）"
            WifiP2pManager.ERROR -> "内部错误"
            -1 -> "10s 内无应答"
            else -> "reason=$reason"
        }
}
