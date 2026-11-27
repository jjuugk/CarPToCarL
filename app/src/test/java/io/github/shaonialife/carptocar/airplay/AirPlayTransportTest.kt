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

    @Test(timeout = 20_000)
    fun `已配对优先直通 pair-verify 失败时清标记并换新连接回退 pair-setup`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val pathsSeen = java.util.Collections.synchronizedList(ArrayList<String>())
            val fastRejectedCount = AtomicInteger()
            val error = AtomicReference<Throwable?>()
            val client = client(server.localPort) {}

            fun readRequestLineAndCSeq(peer: Socket): Pair<String, String> {
                val input = peer.getInputStream()
                val head = java.io.ByteArrayOutputStream()
                var prev = 0
                while (true) {
                    val b = input.read()
                    if (b < 0) throw java.io.EOFException("eof reading request head")
                    head.write(b)
                    val bytes = head.toByteArray()
                    if (bytes.size >= 4 &&
                        bytes[bytes.size - 4] == '\r'.code.toByte() &&
                        bytes[bytes.size - 3] == '\n'.code.toByte() &&
                        bytes[bytes.size - 2] == '\r'.code.toByte() &&
                        bytes[bytes.size - 1] == '\n'.code.toByte()
                    ) {
                        break
                    }
                    prev = b
                }
                val lines = head.toString("ISO-8859-1").split("\r\n")
                val cseq = lines.first { it.startsWith("CSeq:") }.substringAfter(':').trim()
                val contentLen =
                    lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                var left = contentLen
                while (left > 0) {
                    val skipped = input.read(ByteArray(left))
                    if (skipped <= 0) break
                    left -= skipped
                }
                return lines.first() to cseq
            }

            val receiver =
                Thread {
                    try {
                        // 第 1 条连接：收到 /pair-verify，返回 {Error:2, State:4}（模拟车机已清除配对记录）
                        server.accept().use { peer ->
                            val (firstLine, cseq) = readRequestLineAndCSeq(peer)
                            pathsSeen += firstLine
                            val body =
                                Tlv8.encode(
                                    listOf(
                                        Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4)),
                                        Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(2)),
                                    ),
                                )
                            peer.getOutputStream().write(
                                ("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body,
                            )
                            peer.getOutputStream().flush()
                        }
                        // 第 2 条全新 TCP 连接：客户端必须回退到 /pair-setup；回 404 干净终止循环
                        server.accept().use { peer ->
                            val (firstLine, cseq) = readRequestLineAndCSeq(peer)
                            pathsSeen += firstLine
                            peer.getOutputStream().write(
                                "RTSP/1.0 404 Not Found\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                            )
                            peer.getOutputStream().flush()
                        }
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                client.runPairing(
                    windowMillis = 5_000,
                    preferVerifyFirst = true,
                    onFastVerifyRejected = { fastRejectedCount.incrementAndGet() },
                )
            } finally {
                client.cancelled = true
                client.close()
                receiver.join(3_000)
            }
            error.get()?.let { throw it }
            assertEquals(1, fastRejectedCount.get())
            assertEquals(2, pathsSeen.size)
            assertTrue("首条连接必须直通 /pair-verify: $pathsSeen", pathsSeen[0].startsWith("POST /pair-verify "))
            assertTrue("回退后必须换新连接发 /pair-setup: $pathsSeen", pathsSeen[1].startsWith("POST /pair-setup "))
            assertFalse("快路径拒绝不得误判为确定性终止", client.pairingTerminal)
            assertTrue(client.pairingUnsupported)
        }
    }

    @Test(timeout = 20_000)
    fun `已配对直通 pair-verify 成功后跳过 pair-setup 且加密 auth-setup 兼容 200 与 404`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val pathsSeen = java.util.Collections.synchronizedList(ArrayList<String>())
            val logs = java.util.Collections.synchronizedList(ArrayList<String>())
            val error = AtomicReference<Throwable?>()
            val allowPeerClose = CountDownLatch(1)
            val receiverKey = AirPlayCrypto.ed25519Generate()
            val client =
                AirPlayClient(
                    endpoint(server.localPort, listOf(localHost())),
                    { logs += it },
                )

            fun readExact(
                input: java.io.InputStream,
                count: Int,
            ): ByteArray {
                val out = ByteArray(count)
                var off = 0
                while (off < count) {
                    val n = input.read(out, off, count - off)
                    if (n < 0) throw java.io.EOFException("eof reading $count bytes")
                    off += n
                }
                return out
            }

            fun readPlainRtsp(input: java.io.InputStream): Triple<String, String, ByteArray> {
                val head = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) throw java.io.EOFException("eof reading rtsp head")
                    head.write(b)
                    val bytes = head.toByteArray()
                    if (bytes.size >= 4 &&
                        bytes[bytes.size - 4] == '\r'.code.toByte() &&
                        bytes[bytes.size - 3] == '\n'.code.toByte() &&
                        bytes[bytes.size - 2] == '\r'.code.toByte() &&
                        bytes[bytes.size - 1] == '\n'.code.toByte()
                    ) {
                        break
                    }
                }
                val lines = head.toString("ISO-8859-1").split("\r\n")
                val cseq = lines.first { it.startsWith("CSeq:") }.substringAfter(':').trim()
                val len =
                    lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                val body = if (len > 0) readExact(input, len) else ByteArray(0)
                return Triple(lines.first(), cseq, body)
            }

            fun readEncryptedRtsp(
                input: java.io.InputStream,
                cipher: ControlCipher,
            ): Triple<String, String, ByteArray> {
                var plain = ByteArray(0)
                var cipherRest = ByteArray(0)
                val buf = ByteArray(4096)
                while (true) {
                    val sep =
                        (0..plain.size - 4).firstOrNull {
                            plain[it] == '\r'.code.toByte() &&
                                plain[it + 1] == '\n'.code.toByte() &&
                                plain[it + 2] == '\r'.code.toByte() &&
                                plain[it + 3] == '\n'.code.toByte()
                        }
                    if (sep != null) {
                        val headerEnd = sep + 4
                        val lines = String(plain, 0, sep, Charsets.ISO_8859_1).split("\r\n")
                        val cseq = lines.first { it.startsWith("CSeq:") }.substringAfter(':').trim()
                        val len =
                            lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.trim()
                                ?.toIntOrNull()
                                ?: 0
                        if (plain.size >= headerEnd + len) {
                            return Triple(lines.first(), cseq, plain.copyOfRange(headerEnd, headerEnd + len))
                        }
                    }
                    val n = input.read(buf)
                    if (n < 0) throw java.io.EOFException("eof reading encrypted rtsp")
                    val decrypted = cipher.decrypt(cipherRest + buf.copyOf(n))
                    plain += decrypted.data
                    cipherRest = decrypted.rest
                }
            }

            val receiver =
                Thread {
                    try {
                        server.accept().use { peer ->
                            val input = peer.getInputStream()
                            val output = peer.getOutputStream()

                            // M1 -> M2
                            val (line1, cseq1, m1Body) = readPlainRtsp(input)
                            pathsSeen += line1
                            val m1Tlv = Tlv8.decode(m1Body)
                            val clientPub = m1Tlv[Tlv8.TYPE_PUBLIC_KEY]!!
                            val serverEph = AirPlayCrypto.x25519Generate()
                            val shared = AirPlayCrypto.x25519Shared(serverEph.privateKey, clientPub)
                            val pvKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
                            val idBytes = "mock-receiver".toByteArray()
                            val sig = AirPlayCrypto.ed25519Sign(receiverKey.privateKey, serverEph.publicKey + idBytes + clientPub)
                            val sub =
                                Tlv8.encode(
                                    listOf(
                                        Tlv8.Item(Tlv8.TYPE_IDENTIFIER, idBytes),
                                        Tlv8.Item(Tlv8.TYPE_SIGNATURE, sig),
                                    ),
                                )
                            val m2Body =
                                Tlv8.encode(
                                    listOf(
                                        Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                                        Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, serverEph.publicKey),
                                        Tlv8.Item(
                                            Tlv8.TYPE_ENCRYPTED_DATA,
                                            AirPlayCrypto.chachaSeal(pvKey, AirPlayCrypto.nonceLabel("PV-Msg02"), sub),
                                        ),
                                    ),
                                )
                            output.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq1\r\nContent-Length: ${m2Body.size}\r\n\r\n").toByteArray() + m2Body)
                            output.flush()

                            // M3 -> M4
                            val (line2, cseq2, m3Body) = readPlainRtsp(input)
                            pathsSeen += line2
                            val m3Tlv = Tlv8.decode(m3Body)
                            AirPlayCrypto.chachaOpen(pvKey, AirPlayCrypto.nonceLabel("PV-Msg03"), m3Tlv[Tlv8.TYPE_ENCRYPTED_DATA]!!)
                            val m4Body = Tlv8.encode(listOf(Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4))))
                            output.write(("RTSP/1.0 200 OK\r\nCSeq: $cseq2\r\nContent-Length: ${m4Body.size}\r\n\r\n").toByteArray() + m4Body)
                            output.flush()

                            // 切入与客户端镜像对称的 ControlCipher
                            val serverReadKey = AirPlayCrypto.hkdfSha512(shared, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray())
                            val serverWriteKey = AirPlayCrypto.hkdfSha512(shared, "Control-Salt".toByteArray(), "Control-Read-Encryption-Key".toByteArray())
                            val serverCipher = ControlCipher(readKey = serverReadKey, writeKey = serverWriteKey)

                            // 第 1 次加密 POST /auth-setup -> 200 OK + 合法 MFi-SAP 信封
                            val (line3, cseq3, sapReq1) = readEncryptedRtsp(input, serverCipher)
                            pathsSeen += line3
                            assertEquals(33, sapReq1.size)
                            assertEquals(1.toByte(), sapReq1[0])
                            val sapServer = AirPlayCrypto.x25519Generate()
                            val sapShared = AirPlayCrypto.x25519Shared(sapServer.privateKey, sapReq1.copyOfRange(1, 33))
                            fun sha1Key(salt: String) =
                                java.security.MessageDigest.getInstance("SHA-1").run {
                                    update(salt.toByteArray(Charsets.US_ASCII))
                                    update(sapShared)
                                    digest().copyOf(16)
                                }
                            val encSig = ByteArray(64) { 0x5A }.also {
                                AirPlayCrypto.Aes128CtrStream(sha1Key("AES-KEY"), sha1Key("AES-IV")).applyKeystream(it)
                            }
                            val sapResp =
                                sapServer.publicKey +
                                    byteArrayOf(0, 0, 0, 4) +
                                    byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00) +
                                    byteArrayOf(0, 0, 0, 64) +
                                    encSig
                            output.write(
                                serverCipher.encrypt(
                                    ("RTSP/1.0 200 OK\r\nCSeq: $cseq3\r\nContent-Length: ${sapResp.size}\r\n\r\n").toByteArray() + sapResp,
                                ),
                            )
                            output.flush()

                            // 第 2 次加密 POST /auth-setup -> 404 Not Found（验证不支持车机容错且会话不断）
                            val (line4, cseq4, _) = readEncryptedRtsp(input, serverCipher)
                            pathsSeen += line4
                            output.write(
                                serverCipher.encrypt(
                                    "RTSP/1.0 404 Not Found\r\nCSeq: $cseq4\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                                ),
                            )
                            output.flush()
                            allowPeerClose.await(5, TimeUnit.SECONDS)
                        }
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                val keys = client.runPairing(windowMillis = 5_000, preferVerifyFirst = true)
                assertTrue("已配对直通 pair-verify 必须成功返回密钥", keys != null)
                assertTrue("加密 /auth-setup 200 OK 必须返回 true", client.runAuthSetup())
                assertTrue("加密 /auth-setup 404 Not Found 必须容错返回 true", client.runAuthSetup())
                assertTrue("控制通道在 /auth-setup 后仍须存活", client.controlSessionAlive)
            } finally {
                allowPeerClose.countDown()
                client.cancelled = true
                client.close()
                receiver.join(3_000)
            }
            error.get()?.let { throw it }
            assertEquals(4, pathsSeen.size)
            assertTrue("绝不能触发 /pair-setup: $pathsSeen", pathsSeen.none { it.contains("/pair-setup") })
            assertTrue(pathsSeen[0].startsWith("POST /pair-verify "))
            assertTrue(pathsSeen[1].startsWith("POST /pair-verify "))
            assertTrue(pathsSeen[2].startsWith("POST /auth-setup "))
            assertTrue(pathsSeen[3].startsWith("POST /auth-setup "))
            assertTrue("应记录 /auth-setup 完成日志: $logs", logs.any { it.contains("✓ /auth-setup 完成 cert=4B sig=64B") })
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
