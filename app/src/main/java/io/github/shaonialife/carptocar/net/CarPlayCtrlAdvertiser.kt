package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.DeviceIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * 广播手机侧（iPhone 角色）的 _carplay-ctrl._tcp 控制服务并应答接收端探测。
 *
 * 无线 CarPlay 中接收端发现该服务后会反连此端口，先发
 * `GET /ctrl-int/1/connect`（携带 AirPlay-Receiver-Device-ID）探测手机是否在线，
 * 期待 HTTP 200（对照 xcertplay CarPlayBonjour.probeOnce：只读状态行）。
 * 探测通过后，iAP2 控制会话经 AirPlay 事件通道的 type-130 隧道接管（Phase 2 后续）。
 *
 * 本类同时把每个连接的请求行打进日志——DiPlay 后续发什么会直接可见。
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"

        /**
         * 发送端在 ctrl TXT 里自报的版本。
         *
         * **必须保持 950.7.1**：2026-10-19 奥迪 MMI 0000 实车对照定谳——改成 catplay 的
         * 566.25.21（发送端 SDK 版本）后，车机**连 ctrl-int 探测都不再发**、0x4301 永不到达；
         * 用 950.7.1 时同一台车 0.65 秒内就下发 0x4301。车机在 WiFi 上只看得到这个 TXT，
         * 它显然是按「接收端/系统广播常见版本」来判断对方形态的。
         */
        private const val SENDER_SRCVERS = "950.7.1"

        /**
         * ctrl 服务实例名。同理保持 0.1.6 的 `sender`：改名为 `iPhone` 与上一条同时生效，
         * 无法从单次日志区分归因，故一并回退到已验证可用的组合（2026-10-19）。
         */
        private const val SERVICE_NAME = "sender"

        /** 测试可见：这两个值经真车验证，改动需先复测（见 CarPlayCtrlAdvertiserTest）。 */
        internal fun advertisedSrcversForTest(): String = SENDER_SRCVERS

        internal fun advertisedServiceNameForTest(): String = SERVICE_NAME

        /** 测试可见：本类实际广播的服务类型全集（目前只有 ctrl；_device-info 已移除）。 */
        internal fun advertisedServiceTypesForTest(): List<String> = listOf(SERVICE_TYPE)

        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** 同一 SSID 的强制重注册冷却（见 ensureForNetwork）。 */
        private const val FORCE_COOLDOWN_MILLIS = 2_000L
    }

    private var serverSocket: ServerSocket? = null
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var registered = false

    /**
     * 车机 ctrl 邀请计数（每次 `GET /ctrl-int/1/connect` 加一）。
     *
     * catplay 发送端的连接触发点就是这条邀请：它代表「车机已发现我们、并打开了
     * AirPlay 窗口」，收到后立即连 AirPlay，不做固定 sleep。主控据此替代
     * 原先「交接端点后睡 1.6s」的时序猜测。
     */
    @Volatile
    var inviteCount: Int = 0
        private set

    /** 新一轮连接开始时清零（避免把上一轮的邀请当成本轮信号）。 */
    fun resetInviteCount() {
        inviteCount = 0
    }

    /**
     * 收到车机 ctrl 邀请时回调（每次邀请都回调，跑在 ctrl 服务线程池）。
     *
     * 邀请是「车机已发现手机、AirPlay 窗口已开」的**唯一权威信号**：空闲态收到它
     * 意味着对方正在等我们连。旧实现只 `inviteCount++` + 打一行日志，会话失败后
     * 车机一分钟一次地邀请、我们一次都不应（2026-11-02 日志：11 分钟 5 次邀请全丢）。
     * 回调里**必须快速返回**（会占用应答前的窗口），耗时动作自己丢到后台线程。
     */
    @Volatile
    var onInvite: ((receiverId: String?) -> Unit)? = null

    /** 注册时的 WiFi SSID：换网（2.4G→5G 或车机热点切换）后旧注册不可见，需重新注册。 */
    private var registeredSsid: String? = null

    /** 当前生效的注册监听器（每次 start 换新实例，避免 already-in-use）。 */
    private var activeListener: NsdManager.RegistrationListener? = null

    /** 本次注册时刻（force 冷却判定用）。 */
    @Volatile
    private var registeredAtMillis: Long = 0L

    /** 本次注册写入 TXT 的 MAC（占位值 = 车机不会邀请，需尽快重注册）。 */
    @Volatile
    private var registeredWithMac: String? = null

    /** 本次注册时进程所处的网络（用于判断「绑定后是否需要重注册」）。 */
    private var registeredNetwork: android.net.Network? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    /** 当前广播的 TXT id 是否是占位 MAC（车机据此匹配手机，占位则永不邀请）。 */
    val macPlaceholder: Boolean
        get() = registeredWithMac == DeviceIdentity.PLACEHOLDER_MAC

    /**
     * 确保广播在指定 SSID 的网络上可见（每次会话拿到 0x5703 时调用）。
     * 真车实测（2026-10-11）：手机从 2.4G 换到 5G 热点后，旧 mDNS 注册
     * 不再被车机看到（车机不再发 ctrl-int 探测 → 不开 AirPlay 窗口）。
     * SSID 变化时先注销再重新注册，让广播落到当前网络。
     *
     * @param force true = 无条件重新注册（用于「绑定进程网络后」的时机修正：
     *   mDNS 组播跟随 bindProcessToNetwork 的网络，注册必须发生在绑定之后）。
     * @param network 绑定的进程网络；与上次注册时的网络不同则必须重注册（不受冷却限制）——
     *   否则「刚注册完就切到车机热点」时那 2s 冷却会把关键的绑定后重注册吞掉。
     */
    @Synchronized
    fun ensureForNetwork(
        ssid: String,
        force: Boolean = false,
        network: android.net.Network? = null,
    ): Boolean {
        if (registered && registeredSsid == ssid && !force && !macPlaceholder) return true
        // force 冷却：绑定后的一连串「确保」调用（bindCarWifiAndRegister → 端点交接）
        // 常在同一秒内发生，逐次 unregister+register 会撞 NsdManager 的
        // listener-already-in-use 并吃 1.2s 重试，反而拖慢连接。
        // 例外：广播里的 MAC 是占位值、或进程网络已变都不能放过。
        val sameNetwork = network == null || network == registeredNetwork
        if (force &&
            registered &&
            registeredSsid == ssid &&
            !macPlaceholder &&
            sameNetwork &&
            SystemClock.uptimeMillis() - registeredAtMillis < FORCE_COOLDOWN_MILLIS
        ) {
            log("$TAG: 跳过重复的强制重注册（${SystemClock.uptimeMillis() - registeredAtMillis}ms 前刚注册）")
            return true
        }
        if (registered) {
            log("$TAG: 重新注册 mDNS 广播（${registeredSsid ?: "?"}${if (force) "，绑定后时机修正" else " → $ssid"}）")
            stop()
        }
        registeredSsid = ssid
        registeredNetwork = network ?: registeredNetwork
        var ok = start()
        if (!ok) {
            // 注销是异步的：极端情况下 registerService 会遇到 "listener already in use"，
            // 稍候重试一次（真车实测 10-11：300ms 不够，需要更长）
            try {
                Thread.sleep(1_200)
            } catch (_: InterruptedException) {
            }
            ok = start()
        }
        return ok
    }

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        return try {
            val socket = ServerSocket(0)
            serverSocket = socket
            // 占位 MAC 时强制重试一次（启动连接即发布广播时，蓝牙栈可能还没就绪；
            // 广播内容错了车机就永远不会邀请我们）
            var mac = DeviceIdentity.bluetoothMac(context)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
                log("$TAG: 蓝牙 MAC 解析为占位值，已强制重试${if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（仍未成功——车机可能不会邀请；Shizuku 已移除，请手动在车机上允许本机）" else "成功"}")
            }
            val info =
                NsdServiceInfo().apply {
                    serviceName = SERVICE_NAME
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // TXT id = 本机真实蓝牙 MAC（catplay CarPlayInviter 实证：车机按此
                    // 匹配待邀请的手机，占位值会导致车机永远不邀请 → 0x4301 永不到达）。
                    setAttribute("id", mac)
                    setAttribute("srcvers", SENDER_SRCVERS)
                }
            // 每次注册用全新 listener：unregisterService 是异步的，复用同一个 listener
            // 会撞上 "listener already in use"（真车实测 10-11 卡住 ctrl 广播）
            val freshListener = createListener()
            activeListener = freshListener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, freshListener)
            registered = true
            registeredAtMillis = SystemClock.uptimeMillis()
            registeredWithMac = mac
            // 车机在 WiFi 上唯一可见的手机身份就是这几个 TXT 字段：占位 MAC / 版本不符都会
            // 直接导致「不发 ctrl 探测、0x4301 永不到达」，而此前日志完全看不到它们
            // （2026-10-19 奥迪 0.1.7 排查时无法归因），故每次注册都打一行。
            log(
                "$TAG: 已监听 :${socket.localPort}，广播 $SERVICE_TYPE " +
                    "name=$SERVICE_NAME id=$mac srcvers=$SENDER_SRCVERS" +
                    if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（⚠ 占位 MAC：车机不会邀请）" else "",
            )
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                // 占位 id 车机不会邀请：后台等真实 MAC 就绪后立刻重注册，
                // 别让「广播提前到启动连接」反而把 TXT id 写错（2026-10-18）。
                Bg.run("ctrl-mac-repair") { repairPlaceholderMac() }
            }
            Thread(::acceptLoop, "ctrl-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            // 端口是刚 bind 的真实资源：注册失败/异常时必须关掉，否则每失败一次就
            // 漏一个监听端口 + 一条 accept 线程（registerService 之前就 throw，连
            // accept 线程都还没起，socket 谁也不会再管）。
            log("$TAG: 启动失败 - ${error.message}")
            runCatching { serverSocket?.close() }
            serverSocket = null
            registered = false
            false
        }
    }

    /** 占位 MAC 的自我修复：每 2s 重试解析，成功后按当前 SSID 重注册一次。 */
    private fun repairPlaceholderMac() {
        repeat(15) {
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
            if (!registered || !macPlaceholder) return
            val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) return@repeat
            log("$TAG: 蓝牙 MAC 已解析为 $mac——重新注册 ctrl 广播（修复占位 id）")
            ensureForNetwork(registeredSsid ?: "", force = true)
            return
        }
        log("$TAG: 30s 内未能解析真实蓝牙 MAC——ctrl 广播 id 仍是占位值，车机可能不会邀请（Shizuku 已移除，需车机侧手动允许本机）")
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (registered) {
            val client =
                try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
            Bg.run("ctrl-client") { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer 连入但无请求（EOF）")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers =
                head
                    .substringAfter("\r\n", "")
                    .lineSequence()
                    .filter { it.contains(':') }
                    .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")

            // 车机 ctrl 邀请：这是「车机已发现手机、AirPlay 窗口已开」的权威信号
            // （catplay CarPlayCtrlSession：收到即把邀请交给连接流程）。
            if (requestLine.startsWith("GET /ctrl-int/1/connect")) {
                val receiverId =
                    head
                        .lineSequence()
                        .firstOrNull {
                            it.substringBefore(':').trim().equals("AirPlay-Receiver-Device-ID", ignoreCase = true)
                        }?.substringAfter(':')
                        ?.trim()
                inviteCount++
                log("$TAG: ✓ 车机 ctrl 邀请（第 $inviteCount 次，receiver=${receiverId ?: "?"}）——AirPlay 窗口已开启")
                // 回调里可能再走一轮连接（会话失败后的恢复入口），异常不能打死应答
                runCatching { onInvite?.invoke(receiverId) }
                    ?.onFailure { log("$TAG: 邀请回调异常 - ${it.message ?: it.javaClass.simpleName}") }
            }

            val output = client.getOutputStream()
            val response =
                when {
                    requestLine.startsWith("GET /ctrl-int/") -> {
                        log("$TAG: ✓ 应答 ctrl-int 探测 200 OK")
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }

                    else -> {
                        log("$TAG: 未识别的请求，按 200 空应答处理（后续实现按需细化）")
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()

            // 对端 Connection: close 会自行断开；等待期间若还有字节（隧道/事件），转储出来
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // 读超时即停止转储
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: 应答后收到 ${rest.size()} 字节：" +
                        rest.toByteArray().take(64).joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                )
            }
            log("$TAG: $peer 连接结束")
        } catch (error: IOException) {
            log("$TAG: $peer 连接异常 - ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count =
                try {
                    input.read(chunk)
                } catch (_: IOException) {
                    break
                }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
    }

    @Synchronized
    fun stop() {
        registered = false
        registeredSsid = null
        registeredWithMac = null
        registeredNetwork = null
        activeListener?.let { runCatching { nsdManager.unregisterService(it) } }
        activeListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: 已停止")
    }

    /** 每次注册新建 listener（见 start() 注释；复用同一实例会触发 already in use）。 */
    private fun createListener(): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("$TAG: mDNS 注册成功 ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                // 注册是异步的：这里失败时 `start()` 已经把 registered 置 true 并起好了
                // accept 线程。只把标志打回 false 会留下「旧 ServerSocket + 旧 accept
                // 线程」无人可关（下一次 start() 用新 socket 覆盖字段，stop() 再也够不到
                // 旧的），每失败一次漏一个 fd 和一条常驻线程。
                registered = false
                runCatching { serverSocket?.close() }
                serverSocket = null
                activeListener = null
                log("$TAG: mDNS 注册失败 code=$errorCode（已释放端口，下次会话重试）")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) = Unit
        }
}
