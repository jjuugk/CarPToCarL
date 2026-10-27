package io.github.shaonialife.carptocar.usb

import io.github.shaonialife.carptocar.transport.DuplexStream
import java.io.Closeable
import java.io.IOException

/**
 * USBMUX v2 **设备侧**（发送端/手机侧）应答器。
 *
 * 角色对照（2026-10-27 依据 DiPlay `Iap2UsbMuxHost.kt` 反推）：
 *
 * | 动作 | 车机（USB 主机，DiPlay） | 我们（USB 设备，本类） |
 * |---|---|---|
 * | 版本握手 | 发 proto=0/len=20/ver=2，等回包 | **收下并原样回一帧**（不能等 proto=1） |
 * | setup | 发 proto=2 + `0x07` | 只记日志（苹果侧无需回应） |
 * | TCP | 用车机侧自己的源端口做会话键，向手机端口发 SYN/数据 | 按 `sourcePort` 建会话，按 `destinationPort` 找本地服务 |
 *
 * 典型首个连接就是 `62078`（lockdown）：车机靠它拿设备信息、触发 CarPlay 会话。
 * 这一步在真机上必须是「苹果 plist 协议」的服务端，本类只负责把 TCP 字节流递上去，
 * 具体服务由 [socketFactory] 决定。
 */
class UsbMuxServer(
    private val stream: DuplexStream,
    /** 按目标端口取一个本地服务端点；返回 null 表示该端口无人监听（回 RST）。 */
    private val socketFactory: (destinationPort: Int) -> MuxSocket?,
    private val log: (String) -> Unit = {},
) : Closeable {
    /** 一端虚拟 TCP socket：阻塞读、整段写。实现方通常桥到本地回环 TCP 或内存管道。 */
    interface MuxSocket : Closeable {
        /** 阻塞读；返回 -1 表示对端关闭。 */
        fun read(buffer: ByteArray): Int

        /** 写全部字节或抛异常。 */
        fun write(bytes: ByteArray)
    }

    private class Connection(
        val hostPort: Int,
        val devicePort: Int,
        val socket: MuxSocket,
    ) {
        @Volatile var deviceSequence: Long = 0
        @Volatile var hostAcknowledgement: Long = 0
        @Volatile var closed: Boolean = false
        var pump: Thread? = null
    }

    private val writeLock = Any()
    private val connections = HashMap<Int, Connection>()
    private val framer = UsbMux.Framer()
    private var closed = false
    private var versionReplied = false
    private var tcpFrames = 0

    /** 阻塞服务循环；流结束或对端出错时正常返回（不抛给调用方）。 */
    fun serve() {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                if (closed) return
                val read = stream.read(buffer)
                if (read < 0) {
                    log("USBMUX: 链路 EOF，设备侧应答器退出")
                    return
                }
                if (read == 0) continue
                framer.append(buffer, 0, read)
                while (true) {
                    val frame = framer.take() ?: break
                    dispatch(frame)
                }
            }
        } catch (error: IOException) {
            log("USBMUX: 读取失败 —— ${error.message}")
        } catch (error: RuntimeException) {
            log("USBMUX: 帧处理异常 —— ${error.message}")
        } finally {
            closeQuietly()
        }
    }

    private fun dispatch(frame: UsbMux.Frame) {
        when (frame.protocol) {
            UsbMux.PROTOCOL_VERSION -> {
                // 车机侧只认「proto=0 / len=20 / ver=2」的回包，且回包必须与请求同形。
                versionReplied = true
                log("USBMUX: 版本握手 mux=${UsbMux.readU32(frame.payload, 0)} → 原样回帧")
                send(UsbMux.versionFrame(frame.sequence, frame.acknowledgement))
            }

            UsbMux.PROTOCOL_SETUP -> {
                val value = frame.payload.firstOrNull()?.toInt()?.and(0xFF)
                log("USBMUX: setup 帧 value=0x${value?.toString(16) ?: "??"}")
            }

            UsbMux.PROTOCOL_TCP -> handleTcp(frame)

            else -> log("USBMUX: 未知协议帧 proto=${frame.protocol} len=${frame.length}")
        }
    }

    private fun handleTcp(frame: UsbMux.Frame) {
        val segment =
            try {
                UsbMux.parseTcp(frame.payload)
            } catch (error: IllegalArgumentException) {
                log("USBMUX: TCP 段解析失败 —— ${error.message}")
                return
            }
        if (++tcpFrames <= 20 || segment.payload.isNotEmpty()) {
            log(
                "USBMUX: TCP ${segment.sourcePort}→${segment.destinationPort} " +
                    "flags=0x${segment.flags.toString(16)} payload=${segment.payload.size}B",
            )
        }

        val key = segment.sourcePort
        var connection = synchronized(connections) { connections[key] }

        if (connection == null) {
            if (segment.flags and UsbMux.FLAG_RST != 0) return
            val socket = socketFactory(segment.destinationPort)
            if (socket == null) {
                log("USBMUX: 端口 ${segment.destinationPort} 无监听 → RST")
                send(
                    UsbMux.tcpFrame(
                        sourcePort = segment.destinationPort,
                        destinationPort = segment.sourcePort,
                        sequence = 0,
                        acknowledgement = segment.sequence,
                        flags = UsbMux.FLAG_RST or UsbMux.FLAG_ACK,
                    ),
                )
                return
            }
            connection =
                Connection(
                    hostPort = segment.sourcePort,
                    devicePort = segment.destinationPort,
                    socket = socket,
                ).also { created ->
                synchronized(connections) { connections[key] = created }
                log("USBMUX: 接受连接 ${created.hostPort}→${created.devicePort}（本地服务已挂上）")
                startPump(created)
            }
        }

        val target = connection
        if (segment.payload.isNotEmpty()) {
            try {
                target.socket.write(segment.payload)
            } catch (error: IOException) {
                log("USBMUX: 本地服务写入失败 —— ${error.message}")
                closeConnection(target, notifyHost = true)
                return
            }
            target.hostAcknowledgement = segment.sequence + segment.payload.size
            send(
                UsbMux.tcpFrame(
                    sourcePort = target.devicePort,
                    destinationPort = target.hostPort,
                    sequence = target.deviceSequence,
                    acknowledgement = target.hostAcknowledgement,
                    flags = UsbMux.FLAG_ACK,
                ),
            )
        } else {
            // 纯 ACK/握手段：对端没带数据，确认号就是它的当前序号（不回退）。
            target.hostAcknowledgement = maxOf(target.hostAcknowledgement, segment.sequence)
        }

        if (segment.flags and (UsbMux.FLAG_FIN or UsbMux.FLAG_RST) != 0) {
            log("USBMUX: 车机关闭连接 ${target.hostPort}→${target.devicePort}")
            closeConnection(target, notifyHost = segment.flags and UsbMux.FLAG_FIN != 0)
        }
    }

    private fun startPump(connection: Connection) {
        val thread =
            Thread({
                val buffer = ByteArray(16 * 1024)
                try {
                    while (!connection.closed) {
                        val read = connection.socket.read(buffer)
                        if (read < 0) {
                            send(
                                UsbMux.tcpFrame(
                                    sourcePort = connection.devicePort,
                                    destinationPort = connection.hostPort,
                                    sequence = connection.deviceSequence,
                                    acknowledgement = connection.hostAcknowledgement,
                                    flags = UsbMux.FLAG_FIN or UsbMux.FLAG_ACK,
                                ),
                            )
                            break
                        }
                        if (read == 0) continue
                        val payload = buffer.copyOf(read)
                        connection.deviceSequence += read
                        send(
                            UsbMux.tcpFrame(
                                sourcePort = connection.devicePort,
                                destinationPort = connection.hostPort,
                                sequence = connection.deviceSequence - read,
                                acknowledgement = connection.hostAcknowledgement,
                                flags = UsbMux.FLAG_PSH or UsbMux.FLAG_ACK,
                                payload = payload,
                            ),
                        )
                    }
                } catch (error: IOException) {
                    if (!connection.closed) log("USBMUX: 本地服务读取失败 —— ${error.message}")
                } finally {
                    closeConnection(connection, notifyHost = false)
                }
            }, "usbmux-pump-${connection.devicePort}")
        thread.isDaemon = true
        connection.pump = thread
        thread.start()
    }

    private fun closeConnection(connection: Connection, notifyHost: Boolean) {
        connection.closed = true
        synchronized(connections) { connections.remove(connection.hostPort) }
        runCatching { connection.socket.close() }
        if (notifyHost) {
            runCatching {
                send(
                    UsbMux.tcpFrame(
                        sourcePort = connection.devicePort,
                        destinationPort = connection.hostPort,
                        sequence = connection.deviceSequence,
                        acknowledgement = connection.hostAcknowledgement,
                        flags = UsbMux.FLAG_FIN or UsbMux.FLAG_ACK,
                    ),
                )
            }
        }
    }

    private fun send(bytes: ByteArray) {
        synchronized(writeLock) {
            if (closed) return
            runCatching { stream.write(bytes) }
                .onFailure { log("USBMUX: 写入失败 —— ${it.message}") }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(connections) { connections.values.toList() }.forEach { connection ->
            connection.closed = true
            runCatching { connection.socket.close() }
            runCatching { connection.pump?.join(200) }
        }
        synchronized(connections) { connections.clear() }
        runCatching { stream.close() }
    }

    private fun closeQuietly() {
        if (closed) return
        closed = true
        synchronized(connections) { connections.values.toList() }.forEach { connection ->
            connection.closed = true
            runCatching { connection.socket.close() }
        }
        synchronized(connections) { connections.clear() }
    }

    /** 诊断：握手是否已回应、当前连接数。 */
    fun diagnostics(): String =
        "version=$versionReplied tcpFrames=$tcpFrames connections=${connections.size}"
}
