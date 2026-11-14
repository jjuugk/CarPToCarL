package io.github.shaonialife.carptocar.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.transport.DuplexStream
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后监听这个 SDP 服务等待车机回连；
 * 测试台上 DiPlay/xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * 连入后：链路协商（marker/同步/ACK）→ 完整发送端 bring-up（Phase 1），
 * 成功时回调 [onEndpoint] 交出热点 SSID/密码与 AirPlay 端点。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 「正在播放」信息提供者（歌名/歌手/专辑，见 [Iap2SenderSession]）。 */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /** 保活泵退出（BT 链路断开）时回调：主控据此释放「会话处理中」（僵尸轮次堵门修复）。 */
    private val onLinkClosed: (() -> Boolean) -> Unit = {},
    /**
     * 有设备真正连进来（通过目标校验之后）时回调。
     *
     * 「兼容模式」下监听是常开的（App 一启动就发布服务，不等「启动连接」），
     * 主控据此把「只是挂着等」和「真的在跑会话」区分开——否则 `isSessionRunning`
     * 会一直为真，自动连接会被自己挡掉。
     */
    private val onPeerConnected: () -> Unit = {},
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L

        /** 额度不足时 accept 的退让间隔（与 link_plus 一致）。 */
        private const val BUDGET_POLL_MILLIS = 500L

        /**
         * 进程级共享：物理关闭额度是**整个进程**的，换一个 server 实例不该重新发一份。
         * 见 [BtSocketClosePool] 的类注释。
         */
        private val closePool = BtSocketClosePool()
    }

    private var serverSocket: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    /**
     * 「车机只认苹果设备」兼容模式的占位 SDP 记录（默认关，见 [BtCompatPrefs]）。
     * 生命周期跟着 [start]/[stop] 走——监听在，占位就在。
     */
    private val identityProbe = BtIdentityProbeSockets(context) { line -> log(line) }

    val isProbeRunning: Boolean get() = identityProbe.isRunning

    /**
     * 立刻撤掉占位记录（用户关掉开关时用），**不影响** iAP2 监听。
     *
     * 不能顺手把监听也停掉：这一刀可能落在正在跑的会话上（车机全靠这条 RFCOMM
     * 做控制通道），开关的语义只是「别再声明苹果 UUID」，不是「断开」。
     */
    fun stopProbe() {
        if (!identityProbe.isRunning) return
        identityProbe.stop()
        log("BT-PROBE: 占位 SDP 记录已撤下（iAP2 监听保持）")
    }

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    private val channelLock: Any = this
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    private fun ownsChannel(channel: Iap2LinkChannel): Boolean =
        activeChannel === channel && BtSessionGate.ownsControl(channel) && !channel.closedLocally

    /** 当前保活阶段的发送端会话（bring-up 完成后由 runSenderSession 回填）。 */
    @Volatile
    private var senderSession: Iap2SenderSession? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 歌名变化时主动补推 0x5001（转发给当前保活会话）。 */
    fun pushNowPlaying() {
        senderSession?.pushNowPlaying()
    }

    /**
     * 车机 AirPlay 端口一直不开时，让 iAP2 会话重发一次 `0x4e0e + 0x4300` 叫醒它。
     *
     * 返回 false = 当前没有可用的发送端会话（链路已断/还没起来），调用方只能退而重公告 ctrl。
     */
    fun nudgeCarPlayAvailability(reason: String): Boolean =
        senderSession?.nudgeCarPlayAvailability(reason) ?: false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(): Boolean {
        if (running) {
            // 已在监听：只把占位记录对齐到当前开关状态（运行期拨开关就靠这条生效）
            syncIdentityProbe()
            return true
        }
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        return try {
            val listener = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID)
            serverSocket.set(listener)
            running = true
            val myGeneration = generation.incrementAndGet()
            log("BT: 监听 $SDP_NAME，等待接收端回连…")
            syncIdentityProbe()
            Thread({ acceptLoop(listener, myGeneration) }, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            false
        }
    }

    /** 占位记录跟随开关：开着补挂、关掉撤下。失败不影响 iAP2 主链路。 */
    private fun syncIdentityProbe() {
        if (BtCompatPrefs.enabled(context)) {
            if (!identityProbe.isRunning) identityProbe.start()
        } else {
            stopProbe()
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(
        listener: BluetoothServerSocket,
        myGeneration: Long,
    ) {
        var waitingForBudget = false
        while (running && generation.get() == myGeneration) {
            // 额度优先于 accept：close 在协议栈里是阻塞的，车机反复回连时若继续 accept，
            // 只会把没关掉的套接字越堆越多，进程级配额吃满后 connect 直接失败。
            if (!closePool.tryAcquire()) {
                if (!waitingForBudget) {
                    log("BT: 上一条套接字尚未关闭完成，暂停接受新连接（并发上限 ${closePool.budget}）")
                }
                waitingForBudget = true
                try {
                    Thread.sleep(BUDGET_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    synchronized(channelLock) {
                        if (generation.get() == myGeneration) running = false
                    }
                    break
                }
                continue
            }
            waitingForBudget = false
            if (!running || generation.get() != myGeneration) {
                closePool.release()
                break
            }
            val socket: BluetoothSocket =
                try {
                    listener.accept()
                } catch (error: IOException) {
                    closePool.release()
                    synchronized(channelLock) {
                        if (generation.get() == myGeneration) {
                            if (running) log("BT: accept 失败 - ${error.message}")
                            // 必须把 running 改回 false：accept 循环是唯一的接收方，循环一退出
                            // 就再没人接连接了。留着 true 会让 isRunning 一直骗人说「在监听」，
                            // 而 start() 又因为 running 已是 true 直接返回——监听永久假死。
                            // 「监听常开」（见 BtCompatPrefs）依赖下一次 start() 能把循环重新拉起来。
                            running = false
                        }
                    }
                    break
                }
            if (!running || generation.get() != myGeneration) {
                closePool.closeAsync(socket) { socket.close() }
                return
            }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null &&
                !BtSessionGate.targetSoftFallback &&
                !TargetDevicePrefs.matches(target, peerAddress)
            ) {
                log(
                    "BT: 忽略非目标设备连入 name=$peerName addr=${peerAddress ?: "?"}" +
                        "（当前目标：「${target.name}」）",
                )
                runCatching { closePool.closeAsync(socket) { socket.close() } }
                continue
            }
            log("BT: 接收端已连入 name=$peerName，开始 iAP2 发送端会话")
            // 登记对端：音频配置守卫（BtProfiles.guardAudioProfiles）据此知道盯哪台设备
            BtSessionGate.notePeerActive(peerAddress)
            onPeerConnected()
            Thread({ runSession(socket, myGeneration) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(
        socket: BluetoothSocket,
        myGeneration: Long,
    ) {
        val stream =
            try {
                BtSocketStream(socket.inputStream, socket.outputStream) {
                    closePool.closeAsync(socket) { socket.close() }
                }
            } catch (error: Exception) {
                // 建链途中对端就断了（inputStream/outputStream 抛 IOException）：额度必须还，
                // 否则每断一次 accept 的并发上限就少一格，最后整个监听停滞。
                log("SESSION 失败: 无法打开蓝牙流 - ${error.message}")
                closePool.closeAsync(socket) { socket.close() }
                return
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }
        val previous =
            synchronized(channelLock) {
                if (!running || generation.get() != myGeneration) {
                    channel.close()
                    return
                }
                activeChannel = channel
                senderSession = null
                BtSessionGate.claimControl(channel, inbound = true)
            }
        previous?.close()
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint = { if (ownsChannel(channel)) onEndpoint(it) },
                onWifiCredentials = { if (ownsChannel(channel)) onWifiCredentials(it) },
                onEndpointUpdate = { if (ownsChannel(channel)) onEndpointUpdate(it) },
                nowPlayingProvider = nowPlayingProvider,
                onSessionReady = { session ->
                    synchronized(channelLock) {
                        if (ownsChannel(channel)) {
                            senderSession = session
                            BtSessionGate.markControlReady(channel)
                        }
                    }
                },
                onLinkClosed = {
                    onLinkClosed { running && ownsChannel(channel) }
                },
            )
        } catch (error: IOException) {
            log("SESSION 失败: ${error.message}")
        } finally {
            synchronized(channelLock) {
                if (activeChannel === channel) {
                    activeChannel = null
                    senderSession = null
                    BtSessionGate.releaseControl(channel)
                }
            }
            channel.close()
            // 两条完全不同的撤退路径，日志必须能分辨（否则用户以为「蓝牙连上就断 = App 有 bug」）：
            //  - sessionActive=true：AirPlay 已在 WiFi 上跑起来，接收端把 iAP2 迁到控制通道
            //    隧道（VirtualIap2Link）后**主动关掉 RFCOMM**。WinPlay 1.2.4 实测：
            //    20:07:31 连入 → 20:07:32.4 隧道建立 → 32.52 对端 close（read -1）。
            //    这是无线 CarPlay 的正常交接，画面/音频/触摸全程不受影响。
            //  - sessionActive=false：会话在拿到端点前就断了，才是真故障，等重连。
            log(
                if (BtSessionGate.sessionActive) {
                    "SESSION 结束——接收端已断开 RFCOMM，iAP2 已迁到 WiFi 隧道" +
                        "（无线 CarPlay 正常交接，继续推流；BT 监听仍在，需要时会再连）"
                } else {
                    "SESSION 结束，等待接收端重连（BT 监听仍在运行）"
                },
            )
        }
    }

    /** 主动结束当前 BT 控制会话（不影响监听）。用于 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        synchronized(channelLock) {
            activeChannel?.also { channel ->
                activeChannel = null
                senderSession = null
                BtSessionGate.releaseControl(channel)
            }
        }?.let { channel ->
            channel.close()
            log("BT: 已主动断开当前控制会话（模拟 tunnel handoff 后的退役）")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        generation.incrementAndGet()
        detachCurrentSession()
        identityProbe.stop()
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        log("BT: 监听已停止")
    }
}

/**
 * 蓝牙 RFCOMM 专用流：`close()` 只**提交**物理关闭，不在调用线程上阻塞。
 *
 * 不能直接用 StreamDuplexStream：它的 `close()` 会先 `input.close()` / `output.close()`，
 * 而 Android 的 `BluetoothInputStream.close()` 内部就是 `mSocket.close()`——慢的那一步
 * 依然发生在调用线程上，额度池等于白建（而且会把 socket 关两次）。
 *
 * read/write 不能共享一把锁：读线程会阻塞在 `read()` 里持锁等数据，写线程将永久拿不到锁
 * （真机蓝牙上实际触发过）。写入的并发安全由 [Iap2LinkChannel] 的通道锁保证。
 */
private class BtSocketStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
) : DuplexStream {
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = onClose()
}
