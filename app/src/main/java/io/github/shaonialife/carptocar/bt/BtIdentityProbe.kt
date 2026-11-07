package io.github.shaonialife.carptocar.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 车机「只认苹果设备」时的两条占位 SDP 记录（见 [BtCompatPrefs]）。
 *
 * 记录本身**不做任何协议**：连进来就记一行日志并立刻关掉。它存在的意义只是让车机
 * 浏览 SDP 时能看到 CarPlay 相关的 UUID——凯迪拉克那款同类 APK 也是这么占位的。
 *
 * 为什么是这两条：
 *  - `2d8d2466-…`：iPhone 在 **EIR** 里广播的无线 CarPlay UUID，也是车机判
 *    「这台是不是 CarPlay 设备」最常引用的那一个。Android 应用层写不了 EIR，
 *    退而求其次把它注册成一条 SDP 记录（唯一能做的「声明」）。
 *  - `02030302-…`：catplay 在 iPhone 角色下真正注册的 SDP 记录，服务名
 *    `Wireless iAP v2`（`bluetooth/catplay_iap2_bt/src/lib.rs`）。
 */
object BtIdentityProbe {
    val CARPLAY_WIRELESS_UUID: UUID =
        UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")

    val WIRELESS_IAP2_UUID: UUID =
        UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77")

    /** 一条占位记录：SDP 服务名 + UUID + 为什么有它（日志里直接打出来，便于对照车机）。 */
    data class Record(
        val name: String,
        val uuid: UUID,
        val why: String,
    )

    /** 开关打开时要注册的全部记录；关闭时一条都不注册（纯函数，便于单测）。 */
    val ALL: List<Record> =
        listOf(
            Record(
                name = "CarPlay",
                uuid = CARPLAY_WIRELESS_UUID,
                why = "iPhone 在 EIR 广播的无线 CarPlay UUID，车机据此判「是不是 CarPlay 设备」",
            ),
            Record(
                name = "Wireless iAP v2",
                uuid = WIRELESS_IAP2_UUID,
                why = "iPhone 角色下真正注册的 SDP 记录（catplay 同名同 UUID）",
            ),
        )

    fun recordsFor(enabled: Boolean): List<Record> = if (enabled) ALL else emptyList()
}

/**
 * 占位 SDP 记录的 socket 持有者。
 *
 * 只负责「让记录在线」：accept 到连接说明**车机确实在看这个 UUID**——这是本项目
 * 目前唯一能拿到的直接证据，所以连入/断开都要打日志。不解析、不回应、不迁移链路。
 */
class BtIdentityProbeSockets(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val sockets = CopyOnWriteArrayList<BluetoothServerSocket>()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 注册全部记录，返回成功条数（0 = 一条都没成，调用方据此决定要不要提示）。 */
    @SuppressLint("MissingPermission")
    fun start(): Int {
        if (running) return sockets.size
        val adapter: BluetoothAdapter? =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-PROBE: 蓝牙不可用或未开启，跳过身份探针")
            return 0
        }
        var ok = 0
        BtIdentityProbe.ALL.forEach { record ->
            try {
                val socket = adapter.listenUsingRfcommWithServiceRecord(record.name, record.uuid)
                sockets += socket
                ok++
                log("BT-PROBE: 已注册 SDP「${record.name}」${record.uuid} —— ${record.why}")
                Thread({ acceptLoop(socket, record) }, "bt-probe-${record.uuid}").apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                // 常见的两种失败：系统把同一个 UUID 已经注册过（其它 App 占着）、
                // 或蓝牙刚被关掉。都只是「这条占位没挂上」，不影响 iAP2 主链路。
                log("BT-PROBE: 注册「${record.name}」失败 - ${error.message}")
            }
        }
        running = ok > 0
        return ok
    }

    private fun acceptLoop(
        socket: BluetoothServerSocket,
        record: BtIdentityProbe.Record,
    ) {
        while (running) {
            val incoming =
                try {
                    socket.accept()
                } catch (error: IOException) {
                    if (running) log("BT-PROBE: 「${record.name}」accept 结束 - ${error.message}")
                    break
                }
            val peer =
                try {
                    incoming.remoteDevice?.let { "${it.name ?: "unknown"}(${it.address})" }
                } catch (_: SecurityException) {
                    null
                } ?: "未知设备"
            log(
                "BT-PROBE: $peer 连入了占位记录「${record.name}」——" +
                    "车机确实在看这个 UUID；探针不做协议，立即关闭",
            )
            runCatching { incoming.close() }
        }
    }

    fun stop() {
        running = false
        sockets.forEach { socket -> runCatching { socket.close() } }
        sockets.clear()
    }
}
