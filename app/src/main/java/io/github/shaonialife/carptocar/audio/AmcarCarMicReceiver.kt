package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车机麦克风输入流接收端：只收 UDP、只解密、只做 24k→16k PCM 转换，**不采集手机麦克风**。
 *
 * 按 AMcar 的 `CarMicReceiver` 原样移植（密钥工具换成我们的 [AirPlayCrypto]）。
 * 包形（xcertplay `MicrophonePacketizer` 字节级交叉验证）：
 * ```
 * 12B RTP 头 (0x80, PT=100, seq u16BE, timestamp u32BE, SSRC=0)
 * + ChaCha20-Poly1305 密文 + 16B tag
 * + 8B 尾部 = 小端 nonce 计数器
 * ```
 * AAD = `header[4..12)`，nonce = 4B 零 + 尾部 8B 小端计数。
 * 密钥 = `HKDF-SHA512(shared, "DataStream-Salt<connId>", "DataStream-Input-Encryption-Key")`；
 * 先试 Input key、失败再试 Output key（部分接收端会用错方向，宽容处理）。
 * 明文是**大端** PCM16 mono，换序成小端后降采样到 16kHz 交给 CarLife VR。
 *
 * 这是实验桥的私有实现：默认关，关闭时本类完全不会被构造。
 */
class AmcarCarMicReceiver private constructor(
    val dataSocket: DatagramSocket,
    private val controlSocket: DatagramSocket,
    private val inputKey: ByteArray,
    private val outputKey: ByteArray,
    private val output: (ByteArray) -> Boolean,
    private val log: (String) -> Unit,
    private val onForwarding: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val clock: () -> Long,
    private val firstPcmTimeoutMs: Long,
    private val outputStallTimeoutMs: Long,
) : AutoCloseable {
    val dataPort: Int get() = dataSocket.localPort
    val controlPort: Int get() = controlSocket.localPort

    /** SETUP 应答里的 streamID；只用于 TEARDOWN，不参与解密。 */
    @Volatile
    var streamId: Long? = null

    private val closed = AtomicBoolean(false)
    private val publicationLock = Any()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    var authenticatedPackets: Long = 0L
        private set

    @Volatile
    var forwardedPackets: Long = 0L
        private set

    /** 重复/超出重放窗口的包（nonce 已见过或落后 64 个以上）。 */
    @Volatile
    var stalePackets: Long = 0L
        private set

    /** 头部/长度不符合麦克风 RTP 包形的报文。 */
    @Volatile
    var malformedPackets: Long = 0L
        private set

    /** 包形正确但认证失败（密钥方向或篡改）。 */
    @Volatile
    var unauthenticatedPackets: Long = 0L
        private set

    /** 已认证且解密成功、但整帧为 0 的静音包。 */
    @Volatile
    var silentPackets: Long = 0L
        private set

    val ready: Boolean get() = !closed.get() && !dataSocket.isClosed

    @Synchronized
    fun start() {
        check(!closed.get() && thread == null)
        thread =
            Thread(::receive, "amcar-microphone").apply {
                isDaemon = true
                start()
            }
    }

    private fun receive() {
        val input = RtpDecoder(inputKey, outputKey)
        val buffer = ByteArray(4096)
        var packets = 0L
        var rejected = 0L
        var firstLogged = false
        val startedAt = clock()
        var lastAcceptedAt = startedAt
        var outputRejectedAt: Long? = null
        var nextSummary = System.nanoTime() + 5_000_000_000L
        while (!closed.get()) {
            val expired =
                when {
                    forwardedPackets == 0L && clock() - startedAt >= firstPcmTimeoutMs -> "等待首个有效 PCM 超时"
                    outputRejectedAt != null && clock() - outputRejectedAt!! >= outputStallTimeoutMs -> "语音下游持续拒收 PCM"
                    forwardedPackets > 0L && clock() - lastAcceptedAt >= firstPcmTimeoutMs -> "车机麦克风有效 PCM 中断"
                    else -> null
                }
            if (expired != null) {
                fail(expired)
                break
            }
            val datagram = DatagramPacket(buffer, buffer.size)
            val got =
                try {
                    dataSocket.receive(datagram)
                    true
                } catch (_: SocketTimeoutException) {
                    false
                } catch (error: Exception) {
                    if (!closed.get()) fail("麦克风接收失败：${error.javaClass.simpleName}")
                    break
                }
            if (!got || closed.get()) continue
            packets++
            val wire = buffer.copyOf(datagram.length)
            val pcm24 =
                try {
                    input.open(wire)
                } catch (_: StalePacket) {
                    stalePackets++
                    rejected++
                    continue
                } catch (_: IllegalArgumentException) {
                    malformedPackets++
                    rejected++
                    continue
                } catch (_: IllegalStateException) {
                    unauthenticatedPackets++
                    rejected++
                    continue
                } catch (_: Exception) {
                    malformedPackets++
                    rejected++
                    continue
                }
            authenticatedPackets++
            if (!firstLogged) {
                firstLogged = true
                log("CAR-MIC: ✓ first authenticated RTP; ${pcm24.size}B → 16000Hz mono PCM16")
            }
            val pcm16 = resample24To16(pcm24)
            if (pcm16.isNotEmpty() && isSilent(pcm16)) silentPackets++
            if (pcm16.isNotEmpty()) {
                val accepted =
                    try {
                        output(pcm16)
                    } catch (error: Exception) {
                        fail("语音下游写入失败：${error.javaClass.simpleName}")
                        break
                    }
                synchronized(publicationLock) {
                    if (!closed.get()) {
                        if (accepted) {
                            lastAcceptedAt = clock()
                            outputRejectedAt = null
                            forwardedPackets++
                            if (forwardedPackets == 1L) {
                                log("CAR-MIC: ✓ 下游已接受首帧 ${pcm16.size}B（16kHz mono PCM16 → 9242/0x58001）")
                                runCatching(onForwarding).onFailure { fail("语音状态发布失败") }
                            }
                        } else if (outputRejectedAt == null) {
                            outputRejectedAt = clock()
                        }
                    }
                }
            }
            val now = System.nanoTime()
            if (now >= nextSummary) {
                nextSummary = now + 5_000_000_000L
                log(
                    "CAR-MIC: packets=$packets authenticated=$authenticatedPackets " +
                        "forwarded=$forwardedPackets silent=$silentPackets rejected=$rejected " +
                        "（stale=$stalePackets malformed=$malformedPackets auth=$unauthenticatedPackets）",
                )
            }
        }
        log(
            "CAR-MIC: 接收结束 packets=$packets authenticated=$authenticatedPackets " +
                "forwarded=$forwardedPackets silent=$silentPackets rejected=$rejected " +
                "（stale=$stalePackets malformed=$malformedPackets auth=$unauthenticatedPackets）",
        )
    }

    private fun fail(reason: String) {
        synchronized(publicationLock) {
            if (!closed.compareAndSet(false, true)) return
            release()
            log("CAR-MIC: $reason")
            runCatching { onFailure(reason) }
        }
    }

    override fun close() {
        synchronized(publicationLock) {
            if (!closed.compareAndSet(false, true)) return
            release()
        }
    }

    private fun release() {
        runCatching { dataSocket.close() }
        runCatching { controlSocket.close() }
        inputKey.fill(0)
        outputKey.fill(0)
        thread?.interrupt()
    }

    private class StalePacket : Exception()

    private fun isSilent(pcm16: ByteArray): Boolean {
        for (byte in pcm16) {
            if (byte != 0.toByte()) return false
        }
        return true
    }

    /** 有界窗口的 nonce 去重：迟到的重复包不能回退序号，也不能重复喂给引擎。 */
    private class RtpDecoder(
        private val inputKey: ByteArray,
        private val outputKey: ByteArray,
    ) {
        private var highest = -1L
        private var seen = 0L
        private var useOutputKey: Boolean? = null

        fun open(packet: ByteArray): ByteArray {
            require(packet.size >= 36 && packet[0] == 0x80.toByte()) { "Unexpected microphone RTP header" }
            require(packet[1].toInt() and 127 == RTP_PAYLOAD_TYPE) { "Unexpected microphone payload type" }
            val nonce =
                ByteBuffer
                    .wrap(packet.copyOfRange(packet.size - 8, packet.size))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .long
            require(nonce >= 0) { "Nonce outside supported counter lifetime" }
            if (highest >= 0 && nonce <= highest) {
                val delta = highest - nonce
                if (delta >= 64 || (seen and (1L shl delta.toInt())) != 0L) throw StalePacket()
            }
            val cipherText = packet.copyOfRange(RTP_HEADER_SIZE, packet.size - 8)
            val aad = packet.copyOfRange(4, 12)
            val clear =
                decrypt(cipherText, aad, nonce)
                    ?: throw IllegalStateException("Microphone packet could not be authenticated")
            require(clear.size % 2 == 0) { "Unaligned microphone PCM" }
            for (i in clear.indices step 2) {
                val hi = clear[i]
                clear[i] = clear[i + 1]
                clear[i + 1] = hi
            }
            if (nonce > highest) {
                val delta = nonce - highest
                seen = if (highest < 0 || delta >= 64) 1L else (seen shl delta.toInt()) or 1L
                highest = nonce
            } else {
                seen = seen or (1L shl (highest - nonce).toInt())
            }
            return clear
        }

        private fun decrypt(
            cipherText: ByteArray,
            aad: ByteArray,
            nonce: Long,
        ): ByteArray? {
            val order = useOutputKey?.let { listOf(it) } ?: listOf(false, true)
            for (useOutput in order) {
                val key = if (useOutput) outputKey else inputKey
                val plain =
                    runCatching {
                        AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(nonce), cipherText, aad)
                    }.getOrNull()
                if (plain != null) {
                    useOutputKey = useOutput
                    return plain
                }
            }
            return null
        }
    }

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_PAYLOAD_TYPE = 100

        fun prepare(
            connectionId: Long,
            secret: ByteArray,
            output: (ByteArray) -> Boolean,
            log: (String) -> Unit,
            onForwarding: () -> Unit = {},
            onFailure: (String) -> Unit = {},
            clock: () -> Long = { System.nanoTime() / 1_000_000L },
            firstPcmTimeoutMs: Long = 5_000L,
            outputStallTimeoutMs: Long = 1_500L,
        ): AmcarCarMicReceiver {
            require(firstPcmTimeoutMs > 0 && outputStallTimeoutMs > 0)
            val data = DatagramSocket(null)
            val control = DatagramSocket(null)
            try {
                // 先 bind 再设缓冲/超时：未绑定 socket 上设 SO_RCVBUF 不一定落到实际队列。
                data.bind(InetSocketAddress(0))
                control.bind(InetSocketAddress(0))
                data.receiveBufferSize = 256 * 1024
                data.soTimeout = 1_000
                control.soTimeout = 1_000

                fun key(direction: String) =
                    AirPlayCrypto.hkdfSha512(
                        secret,
                        "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                        "DataStream-$direction-Encryption-Key".toByteArray(Charsets.US_ASCII),
                    )
                return AmcarCarMicReceiver(
                    dataSocket = data,
                    controlSocket = control,
                    inputKey = key("Input"),
                    outputKey = key("Output"),
                    output = output,
                    log = log,
                    onForwarding = onForwarding,
                    onFailure = onFailure,
                    clock = clock,
                    firstPcmTimeoutMs = firstPcmTimeoutMs,
                    outputStallTimeoutMs = outputStallTimeoutMs,
                )
            } catch (error: Exception) {
                data.close()
                control.close()
                throw error
            }
        }

        /** 供单测直接验证 RTP 解密与端序，不走 UDP/socket。 */
        internal fun decodeForTest(
            inputKey: ByteArray,
            outputKey: ByteArray,
            packet: ByteArray,
        ): ByteArray = RtpDecoder(inputKey, outputKey).open(packet)

        /** 24kHz mono PCM16LE → 16kHz mono PCM16LE 的 box filter。 */
        fun resample24To16(pcm24: ByteArray): ByteArray {
            require(pcm24.size % 2 == 0)
            val out = java.io.ByteArrayOutputStream()
            var filled = 0
            var weighted = 0L
            for (offset in pcm24.indices step 2) {
                val sample =
                    ((pcm24[offset].toInt() and 255) or (pcm24[offset + 1].toInt() shl 8)).toShort().toInt()
                var left = 16_000
                while (left > 0) {
                    val width = minOf(left, 24_000 - filled)
                    weighted += sample.toLong() * width
                    filled += width
                    left -= width
                    if (filled == 24_000) {
                        val value = (weighted / 24_000).toInt()
                        out.write(value and 255)
                        out.write((value shr 8) and 255)
                        filled = 0
                        weighted = 0L
                    }
                }
            }
            return out.toByteArray()
        }
    }
}
