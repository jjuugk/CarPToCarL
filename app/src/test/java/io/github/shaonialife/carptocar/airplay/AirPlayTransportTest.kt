package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** AirPlay 控制传输生命周期本地 TCP 回环集成测试。 */
class AirPlayTransportTest {
    @Test(timeout = 20_000)
    fun `对端 FIN 关闭仅触发一次会话终结且重连可建立新连接`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val closed = CountDownLatch(1)
            val failures = AtomicInteger()
            val client =
                client(
                    server.localPort,
                    onClosed = {
                        failures.incrementAndGet()
                        closed.countDown()
                    },
                )
            val receiver =
                Thread {
                    server.accept().use { peer ->
                        val input = peer.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) {
                        }
                        peer.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                client.fetchInfo(1_000)
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertFalse(client.controlSessionAlive)
                assertEquals(1, failures.get())
                assertTrue(client.reconnect(1_000))
                server.accept().use { assertTrue(client.controlSessionAlive) }
            } finally {
                client.close()
                receiver.join(2_000)
            }
            assertEquals(1, failures.get())
        }
    }

    @Test(timeout = 20_000)
    fun `本地主动关闭不会误报远端断链且不会污染新连接`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val failures = AtomicInteger()
            val client = client(server.localPort, onClosed = { failures.incrementAndGet() })
            val requested = CountDownLatch(1)
            val first =
                Thread { client.fetchInfo(5_000) }.apply {
                    isDaemon = true
                    start()
                }
            server.accept().use { peer ->
                val receiver =
                    Thread {
                        val input = peer.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) {
                        }
                        requested.countDown()
                    }.apply {
                        isDaemon = true
                        start()
                    }
                try {
                    assertTrue(requested.await(2, TimeUnit.SECONDS))
                    client.close()
                    assertTrue(client.reconnect(1_000))
                    server.accept().use {
                        first.join(2_000)
                        receiver.join(2_000)
                        assertFalse(first.isAlive)
                        assertTrue(client.controlSessionAlive)
                        assertEquals(0, failures.get())
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test(timeout = 20_000)
    fun `本地打断配对读取不会吞掉下一轮真实的 404 不支持配对响应`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val requested = CountDownLatch(1)
            val locallyClosed = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            val remoteEnds = AtomicInteger()
            val client = client(server.localPort) { remoteEnds.incrementAndGet() }

            fun head(peer: Socket): List<String> {
                val reader = peer.getInputStream().bufferedReader()
                val lines = ArrayList<String>()
                while (true) {
                    val line = reader.readLine() ?: throw java.io.EOFException("pairing request header")
                    if (line.isEmpty()) break
                    lines += line
                }
                assertTrue(lines.first().startsWith("POST /pair-setup "))
                return lines
            }

            val receiver =
                Thread {
                    try {
                        server.accept().use { peer ->
                            head(peer)
                            requested.countDown()
                            assertTrue(locallyClosed.await(5, TimeUnit.SECONDS))
                        }
                        server.accept().use { peer ->
                            val cseq = head(peer).first { it.startsWith("CSeq:") }.substringAfter(':').trim()
                            peer.getOutputStream().write(
                                "RTSP/1.0 404 Not Found\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                            )
                            assertTrue(finished.await(5, TimeUnit.SECONDS))
                        }
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            val worker =
                Thread {
                    try {
                        client.runPairing(5_000)
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    } finally {
                        finished.countDown()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                assertTrue(requested.await(5, TimeUnit.SECONDS))
                client.close()
                locallyClosed.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                receiver.join(2_000)
                assertEquals(null, error.get())
                assertTrue(client.pairingUnsupported)
                assertFalse(client.pairingForbidden)
                assertEquals(null, client.pairingTrouble)
                assertEquals(0, remoteEnds.get())
            } finally {
                client.cancelled = true
                client.close()
                locallyClosed.countDown()
                finished.countDown()
                server.close()
                worker.join(2_000)
                receiver.join(2_000)
            }
        }
    }

    @Test(timeout = 20_000)
    fun `无可用地址的空端点不会误触发端口拒绝唤醒钩子`() {
        var wakes = 0
        val client =
            AirPlayClient(
                endpoint(0, emptyList()),
                {},
                onPortRefused = { wakes++ },
            )
        try {
            client.runPairing(4_100)
            assertEquals(0, wakes)
            assertFalse(client.attemptedPairing)
        } finally {
            client.cancelled = true
            client.close()
        }
    }

    @Test(timeout = 20_000)
    fun `已建立会话被对端关闭时回调仍能观测到 wasSessionEstablished`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val closed = CountDownLatch(1)
            val allowPeerClose = CountDownLatch(1)
            val observedEstablished = AtomicReference<Boolean>()
            val observedCancelled = AtomicReference<Boolean>()
            val client =
                AirPlayClient(
                    endpoint(server.localPort, listOf(localHost())),
                    {},
                    onTransportClosed = { failed, _ ->
                        observedEstablished.set(failed.wasSessionEstablished)
                        observedCancelled.set(failed.cancelled)
                        closed.countDown()
                    },
                )
            val setupReplyBody = BinaryPlist.encode(linkedMapOf("eventPort" to 0L, "timingPort" to 0L))
            val receiver =
                Thread {
                    server.accept().use { peer ->
                        val input = peer.getInputStream()
                        // 依次应答 SETUP /setup 与 GET /info，使 runSetup() 完整返回后再由对端关闭触发 control EOF
                        repeat(2) {
                            val headerBuf = StringBuilder()
                            while (!headerBuf.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) return@use
                                headerBuf.append(b.toChar())
                            }
                            val headers = headerBuf.toString().lines()
                            val cseq = headers.first { it.startsWith("CSeq:", ignoreCase = true) }.substringAfter(':').trim()
                            val contentLen =
                                headers
                                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                    ?.substringAfter(':')
                                    ?.trim()
                                    ?.toIntOrNull() ?: 0
                            var remaining = contentLen
                            val scratch = ByteArray(512)
                            while (remaining > 0) {
                                val n = input.read(scratch, 0, minOf(remaining, scratch.size))
                                if (n <= 0) return@use
                                remaining -= n
                            }
                            val head =
                                "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n" +
                                    "Content-Type: application/x-apple-binary-plist\r\n" +
                                    "Content-Length: ${setupReplyBody.size}\r\n\r\n"
                            peer.getOutputStream().write(head.toByteArray())
                            peer.getOutputStream().write(setupReplyBody)
                            peer.getOutputStream().flush()
                        }
                        allowPeerClose.await(5, TimeUnit.SECONDS)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                assertTrue(client.reconnect(1_000))
                val setup = client.runSetup()
                assertTrue(setup != null)
                assertTrue(client.wasSessionEstablished)
                allowPeerClose.countDown()
                assertTrue(closed.await(3, TimeUnit.SECONDS))
                assertEquals(true, observedEstablished.get())
                assertEquals(true, observedCancelled.get())
                assertFalse(client.sessionEstablished)
            } finally {
                allowPeerClose.countDown()
                client.cancelled = true
                client.close()
                receiver.join(2_000)
            }
        }
    }

    private fun client(
        port: Int,
        onClosed: () -> Unit,
    ) = AirPlayClient(
        endpoint(port, listOf(localHost())),
        {},
        onTransportClosed = { _, _ -> onClosed() },
    )

    private fun localHost(): String =
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress ?: error("Local TCP integration requires a non-loopback IPv4 interface")

    private fun endpoint(
        port: Int,
        hosts: List<String>,
    ) = Iap2SenderSession.CarPlayEndpoint(
        ssid = "loopback-test",
        passphrase = "",
        channel = 0,
        securityType = 2,
        airPlayPort = port,
        deviceIdentifier = null,
        publicKey = null,
        sourceVersion = null,
        ipAddresses = hosts,
    )
}
