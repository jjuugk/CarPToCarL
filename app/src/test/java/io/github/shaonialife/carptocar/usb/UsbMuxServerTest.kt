package io.github.shaonialife.carptocar.usb

import io.github.shaonialife.carptocar.transport.DuplexStream
import io.github.shaonialife.carptocar.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 设备侧应答器的行为契约：**照着车机侧实现（DiPlay `Iap2UsbMuxHost`）当客户端来打**。
 * 覆盖三件事：版本握手要原样回、TCP 要按目标端口挂本地服务、双向数据要能过。
 */
class UsbMuxServerTest {
    @Test
    fun answersVersionHandshakeAndRelaysTcp() {
        val pair = InMemoryStreamPair()
        val socket = QueueSocket()
        val openedPorts = ArrayList<Int>()
        val server =
            UsbMuxServer(
                stream = pair.sideB,
                socketFactory = { port ->
                    openedPorts += port
                    socket
                },
            )
        val serveThread = thread(isDaemon = true) { server.serve() }
        val reader = FrameReader(pair.sideA)

        // 1) 车机发版本握手，必须收到同形回包（proto=0 / len=20 / ver=2）
        pair.sideA.write(UsbMux.versionFrame())
        val version = reader.next(3_000)
        assertEquals(UsbMux.PROTOCOL_VERSION, version.protocol)
        assertEquals(20, version.length)
        assertEquals(UsbMux.VERSION, UsbMux.readU32(version.payload, 0))

        // 2) 车机向 62078 发 SYN + 数据 → 本地服务拿到字节
        pair.sideA.write(
            UsbMux.tcpFrame(
                sourcePort = 40_000,
                destinationPort = UsbMux.LOCKDOWN_PORT,
                sequence = 1,
                acknowledgement = 0,
                flags = UsbMux.FLAG_SYN or UsbMux.FLAG_ACK,
                payload = "PING".toByteArray(),
            ),
        )
        assertEquals("PING", socket.takeFromHost(3_000))
        assertEquals(listOf(UsbMux.LOCKDOWN_PORT), openedPorts)

        // 3) 本地服务回数据 → 车机侧收到同一端口对的 PSH 帧
        socket.sendToHost("PONG".toByteArray())
        val dataFrame = reader.nextWithPayload(3_000)
        val segment = UsbMux.parseTcp(dataFrame.payload)
        assertEquals("PONG", String(segment.payload))
        assertEquals(UsbMux.LOCKDOWN_PORT, segment.sourcePort)
        assertEquals(40_000, segment.destinationPort)

        server.close()
        pair.sideA.close()
        serveThread.join(500)
    }

    @Test
    fun unknownPortGetsReset() {
        val pair = InMemoryStreamPair()
        val server = UsbMuxServer(stream = pair.sideB, socketFactory = { null })
        val serveThread = thread(isDaemon = true) { server.serve() }
        val reader = FrameReader(pair.sideA)

        pair.sideA.write(UsbMux.versionFrame())
        reader.next(3_000)

        pair.sideA.write(
            UsbMux.tcpFrame(
                sourcePort = 40_001,
                destinationPort = 9_999,
                sequence = 5,
                acknowledgement = 0,
                flags = UsbMux.FLAG_SYN,
            ),
        )
        // RST 帧不带载荷（TCP 头 20 字节即整段），所以按整帧取而不是等载荷帧
        val reset = reader.next(3_000)
        assertEquals(UsbMux.PROTOCOL_TCP, reset.protocol)
        val segment = UsbMux.parseTcp(reset.payload)
        assertTrue("应带 RST：flags=${segment.flags}", segment.flags and UsbMux.FLAG_RST != 0)
        assertEquals(9_999, segment.sourcePort)

        server.close()
        pair.sideA.close()
        serveThread.join(500)
    }

    /** 本地服务端点：从 host 收到的字节进队列，发往 host 的字节也进队列（测试里手动搬运）。 */
    private class QueueSocket : UsbMuxServer.MuxSocket {
        private val fromHost = LinkedBlockingQueue<ByteArray>()
        private val toHost = LinkedBlockingQueue<ByteArray>()
        private val closed = AtomicBoolean(false)
        private var leftover: ByteArray? = null

        override fun write(bytes: ByteArray) {
            if (bytes.isNotEmpty()) fromHost.offer(bytes)
        }

        override fun read(buffer: ByteArray): Int {
            while (true) {
                val pending = leftover
                if (pending != null && pending.isNotEmpty()) {
                    val count = minOf(pending.size, buffer.size)
                    pending.copyInto(buffer, 0, 0, count)
                    leftover = if (count == pending.size) null else pending.copyOfRange(count, pending.size)
                    return count
                }
                if (closed.get() && toHost.isEmpty()) return -1
                val chunk = toHost.poll(100, TimeUnit.MILLISECONDS) ?: continue
                leftover = chunk
            }
        }

        fun takeFromHost(timeoutMillis: Long): String {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val collected = StringBuilder()
            while (System.nanoTime() < deadline) {
                val chunk = fromHost.poll(50, TimeUnit.MILLISECONDS) ?: continue
                collected.append(String(chunk))
                if (collected.isNotEmpty()) return collected.toString()
            }
            return collected.toString()
        }

        fun sendToHost(bytes: ByteArray) {
            toHost.offer(bytes)
        }

        override fun close() {
            closed.set(true)
        }
    }

    /** 车机侧读帧器：后台线程把 `DuplexStream` 里的帧丢进队列。 */
    private class FrameReader(private val stream: DuplexStream) {
        private val frames = LinkedBlockingQueue<UsbMux.Frame>()
        private val framer = UsbMux.Framer()
        private val stopped = AtomicBoolean(false)
        private val worker =
            thread(isDaemon = true) {
                val buffer = ByteArray(8 * 1024)
                while (!stopped.get()) {
                    val read =
                        try {
                            stream.read(buffer)
                        } catch (_: Exception) {
                            break
                        }
                    if (read < 0) break
                    if (read == 0) continue
                    framer.append(buffer, 0, read)
                    while (true) {
                        val frame = framer.take() ?: break
                        frames.offer(frame)
                    }
                }
            }

        fun next(timeoutMillis: Long): UsbMux.Frame =
            frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                ?: error("等不到 USBMUX 帧（${timeoutMillis}ms）")

        fun nextWithPayload(timeoutMillis: Long): UsbMux.Frame {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                val frame = frames.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: continue
                if (frame.protocol == UsbMux.PROTOCOL_TCP && frame.payload.size > UsbMux.TCP_HEADER_BYTES) {
                    return frame
                }
            }
            error("等不到带载荷的 TCP 帧（${timeoutMillis}ms）")
        }
    }
}
