package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 车机麦克风上行（AirPlay **输入流**）：收包 → 解密 → 转小端 PCM → 可落一份 wav 供人耳确认。
 *
 * ## 机制（2026-11-08 修订，来自 shilapi/xcertplay 接收端实现与其注释）
 *
 * **不是**另开一路 `input=true` 的流，而是在 `type=100` 主音频 SETUP 的 stream dict 里带上
 * **我们自己的 UDP `dataPort`**——车机看到这个端口，就把麦克风音频推过来。
 * 它的原话：*"The input port, rather than the downlink category, signals that the phone
 * requests a mic."* 判据只有两条：`type == 100` 且 `dataPort` 在 1..65535。
 *
 * 所以本类的用法是「先 [open] 拿端口 → 把端口写进主音频 SETUP → 建流成功后 [start]」，
 * 端口的方向与下行**相反**：这一路是「车机 → 我们」。
 *
 * ## 线格式（与下行同族、方向相反）
 *
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密文 + 16B tag][8B 小端 nonce]
 * ```
 * - 密钥：`HKDF-SHA512(shared, "DataStream-Salt<connId>", "DataStream-Input-Encryption-Key", 32)`
 *   ——与 [AudioSender] 的 Output 密钥同一个函数，只有 label 后缀不同；
 * - AAD：RTP 头 `[4..12)`（timestamp + SSRC）；
 * - 负载：**LPCM 16bit 大端**（回车机采集侧是小端，所以这里要换序；不换听感是沙沙声）。
 *
 * ## 为什么要「探测」包格式
 *
 * 尾部 8 字节是 nonce 还是密文的一部分、8 字节是大端还是小端、AAD 到底算不算 —— 这三处
 * 在手里的几份实现里并不一致，而验一次要等上车。所以首包按 [WireFormat] 的固定顺序逐个
 * 试解，第一个成功（Poly1305 tag 校验通过）即锁定，后续包沿用。tag 校验是 128 位，
 * 试错撞上的概率可以忽略。日志会打 `MIC: 包格式=…`，验证完可以把它写死。
 *
 * @param channels   wav 头用的声道数（默认单声道——车机麦克风是单声道）
 * @param log        日志出口（SessionController.appendLog）
 * @param dumpWav    落盘回调：(pcm, sampleRate, channels) → 给人看的路径；失败返回 null
 */
class MicUplink(
    private val channels: Int = 1,
    private val log: (String) -> Unit,
    private val dumpWav: ((ByteArray, Int, Int) -> String?)? = null,
    maxDumpMillis: Long = 20_000,
) {
    /** 我们声明给车机的 UDP 端口（写进 SETUP 的 `dataPort`）。 */
    var localPort: Int = 0
        private set

    /** wav 头用的采样率：建流成功后才由 [start] 告知（取实际协商到的那一档）。 */
    var sampleRate: Int = 16_000
        private set

    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    @Volatile private var streamKey: ByteArray? = null

    @Volatile private var format: WireFormat? = null
    private var probeCounter = 0L

    private val dumpWindowMillis = maxDumpMillis
    private var dumpLimit = 64 * 1024
    private val pcm = ByteArrayOutputStream()

    @Volatile private var running = false

    private var packets = 0
    private var decoded = 0
    private var failed = 0
    private var totalPcm = 0L
    private var peak = 0
    private var lastTimestamp = -1L
    private var timestampStep = -1L
    private var firstHeadShown = false

    // ---- 生命周期 ----

    /** 绑定本地 UDP 端口（不发任何报文）。成功返回 true，之后 [localPort] 可用。 */
    fun open(): Boolean {
        if (socket != null) return true
        val bound =
            runCatching { DatagramSocket(0) }
                .onFailure { log("MIC: 本地 UDP 端口绑定失败 - ${it.message}") }
                .getOrNull() ?: return false
        bound.soTimeout = 500
        // 车机一上来就连续推，缓冲小会直接丢包（丢包只影响 wav 完整性，不影响判断）
        runCatching { bound.receiveBufferSize = 512 * 1024 }
        socket = bound
        localPort = bound.localPort
        return true
    }

    /**
     * 建流成功后开始收包。
     *
     * [connectionId] 必须与 SETUP 里的 `streamConnectionID` 一致——密钥的盐就是它，
     * 用错一个字节就永远解不开（症状是「车机在推、我们全解不开」）。
     * [sampleRate] 只影响 wav 头与缓冲上限，收包本身与它无关。
     */
    fun start(
        sharedSecret: ByteArray,
        connectionId: Long,
        sampleRate: Int,
    ): Boolean {
        if (running) return true
        if (!open()) return false
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        dumpLimit = ((dumpWindowMillis * this.sampleRate * channels * 2) / 1000L).toInt().coerceAtLeast(64 * 1024)
        streamKey =
            AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        running = true
        val thread =
            Thread({ loop() }, "mic-uplink").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        worker = thread
        thread.start()
        log("MIC: 上行监听已启动 dataPort=$localPort connId=$connectionId 目标采样率=$sampleRate")
        return true
    }

    /**
     * 音频流被**重建**（通话结束、看门狗重连）后跟换密钥。
     *
     * 端口不用换：UDP socket 一直绑着原端口，新 SETUP 里再声明同一个端口完全合法；
     * 变的只有密钥的盐（新流的 `streamConnectionID`）。包格式也重新探测——换流后
     * 对端有可能换写法，沿用旧判定会导致「新流全解不开」。
     */
    fun updateKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ) {
        streamKey =
            AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        format = null
        probeCounter = 0
        log("MIC: 密钥已跟随新音频流更新 connId=$connectionId（端口沿用 $localPort）")
    }

    /**
     * 停止收包并收尾：有数据就落一份 wav，最后打一行 `MIC: 结论——…`。
     *
     * 幂等；不依赖 [start]（上一轮没 [start] 过也能安全调）。
     */
    fun stop() {
        if (!running && socket == null) return
        running = false
        runCatching { socket?.close() }
        socket = null
        worker?.let { runCatching { it.join(1_200) } }
        worker = null
        val raw = pcm.toByteArray()
        val dumper = dumpWav
        val path =
            if (raw.isNotEmpty() && dumper != null) {
                runCatching { dumper.invoke(raw, sampleRate, channels) }.getOrNull()
            } else {
                null
            }
        val seconds = raw.size.toDouble() / (sampleRate * channels * 2).coerceAtLeast(1)
        when {
            decoded == 0 && packets == 0 ->
                log("MIC: 结论——$localPort 端口一包没收到（车机没把麦克风推过来）")

            decoded == 0 ->
                log("MIC: 结论——收到 $packets 包但一包都解不开（密钥/包格式不对，见上面的 head 与试验记录）")

            else ->
                log(
                    "MIC: 结论——✓ 收到 $packets 包、解开 $decoded 包、失败 $failed 包，" +
                        "累计 ${raw.size}B ≈ ${"%.1f".format(seconds)}s，峰值 $peak/32767" +
                        // ts 步长 = RTP 时间戳每包增量（样本帧数）：960@48k = 20ms/包，
                        // 与「包大小/2」对得上就说明负载是 16bit PCM 且没有丢包
                        (if (timestampStep > 0) "，每包 ${timestampStep} 样本" else "") +
                        (path?.let { "；wav 已落盘：$it" } ?: ""),
                )
        }
    }

    // ---- 收包 ----

    private fun loop() {
        val sock = socket ?: return
        val buf = ByteArray(8 * 1024)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (error: Exception) {
                if (running) log("MIC: 收包异常 - ${error.message}")
                break
            }
            onDatagram(buf, packet.length, packet.address?.hostAddress ?: "?", packet.port)
        }
    }

    private fun onDatagram(
        data: ByteArray,
        length: Int,
        host: String,
        port: Int,
    ) {
        packets += 1
        probeCounter += 1
        val key = streamKey ?: return
        val headerLen = rtpHeaderLength(data, length)
        val timestamp = if (headerLen != null) be32(data, 4) else -1L
        if (!firstHeadShown) {
            // 首包无论像不像 RTP 都要打：头长度判错时「每包解不开」，光看失败计数找不到原因
            firstHeadShown = true
            val head =
                data.copyOfRange(0, minOf(32, length)).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            log(
                "MIC: 首个数据报 ${length}B from $host:$port " +
                    "头长=${headerLen ?: "不是 RTP（version=${(data[0].toInt() and 0xFF) ushr 6}）"} ts=$timestamp head=$head",
            )
        }
        if (headerLen != null) {
            if (lastTimestamp >= 0 && timestamp > lastTimestamp && timestampStep < 0) {
                timestampStep = timestamp - lastTimestamp
            }
            lastTimestamp = timestamp
        }

        val current = format
        val plain =
            if (current != null) {
                decodePacket(key, data, length, probeCounter, current)
            } else {
                probeFormat(key, data, length, probeCounter)?.let { (found, pcmBytes) ->
                    format = found
                    log("MIC: 包格式=$found（首包试解命中，后续沿用）")
                    pcmBytes
                }
            }
        if (plain == null) {
            failed += 1
            if (failed <= 3) {
                log("MIC: 第 $packets 包解不开（$length B，头长=${headerLen ?: "非法"}）")
            }
            return
        }
        decoded += 1
        // 车机采集侧是**大端** s16；Android 播放/写 wav 要小端
        swapS16(plain)
        totalPcm += plain.size
        peak = maxOf(peak, peakOf(plain))
        if (pcm.size() < dumpLimit) {
            // 只留前 dumpLimit 字节：诊断够用，且不会把内存吃满（48k 立体声 20s ≈ 3.8MB）
            val room = dumpLimit - pcm.size()
            pcm.write(plain, 0, minOf(room, plain.size))
        }
    }

    // ---- 帧解码（纯逻辑，单测直测）----

    private companion object {
        const val TAG_MAC_BYTES = 16
        const val NONCE_BYTES = 8
        const val RTP_FIXED_HEADER = 12

        /** `[4..12)` 即 timestamp + SSRC —— 与下行 [AudioSender] 的 AAD 完全一致。 */
        const val AAD_FROM = 4
        const val AAD_TO = 12

        fun be32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)

        /**
         * RTP 头长度：固定 12 + 4×CSRC；置了扩展位再加 4 + 4×length（以 32bit 字为单位）。
         *
         * 这里不假设「一定 12 字节」——真机若带扩展头，按 12 切会把扩展字节当成密文，
         * 表现是「每包都解不开」，而这种错在日志里看不出原因。
         */
        fun rtpHeaderLength(
            packet: ByteArray,
            length: Int,
        ): Int? {
            if (length < RTP_FIXED_HEADER) return null
            if ((packet[0].toInt() and 0xC0) != 0x80) return null // version 必须是 2
            var header = RTP_FIXED_HEADER + 4 * (packet[0].toInt() and 0x0F)
            if ((packet[0].toInt() and 0x10) != 0) {
                if (length < header + 4) return null
                val words = ((packet[header + 2].toInt() and 0xFF) shl 8) or (packet[header + 3].toInt() and 0xFF)
                header += 4 + words * 4
            }
            return if (header in RTP_FIXED_HEADER..length) header else null
        }

        fun swapS16(bytes: ByteArray) {
            var index = 0
            while (index + 1 < bytes.size) {
                val head = bytes[index]
                bytes[index] = bytes[index + 1]
                bytes[index + 1] = head
                index += 2
            }
        }

        fun peakOf(bytes: ByteArray): Int {
            var maximum = 0
            var index = 0
            while (index + 1 < bytes.size) {
                // swapS16 之后是小端
                val sample = ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)).toShort().toInt()
                val magnitude = if (sample == Short.MIN_VALUE.toInt()) 32_768 else kotlin.math.abs(sample)
                if (magnitude > maximum) maximum = magnitude
                index += 2
            }
            return maximum
        }

        /** 试解全部候选格式，命中即返回（首包一次性判定）。 */
        fun probeFormat(
            key: ByteArray,
            packet: ByteArray,
            length: Int,
            counter: Long,
        ): Pair<WireFormat, ByteArray>? {
            for (candidate in WireFormat.PROBES) {
                val plain = decodePacket(key, packet, length, counter, candidate) ?: continue
                return candidate to plain
            }
            return null
        }

        /** 解一帧：RTP 头 + ChaCha20-Poly1305 密文(+tag) (+尾部 8B nonce)。失败返回 null。 */
        fun decodePacket(
            key: ByteArray,
            packet: ByteArray,
            length: Int,
            counter: Long,
            format: WireFormat,
        ): ByteArray? {
            val headerLen = rtpHeaderLength(packet, length) ?: return null
            val tail = if (format.nonceFromTail) NONCE_BYTES else 0
            if (length < headerLen + TAG_MAC_BYTES + tail) return null
            val aad = if (format.useAad) packet.copyOfRange(AAD_FROM, minOf(AAD_TO, headerLen)) else ByteArray(0)
            val nonce = if (format.nonceFromTail) nonceFromTail(packet, length, format.bigEndianNonce) else AirPlayCrypto.nonce64(counter)
            val body = packet.copyOfRange(headerLen, length - tail)
            return runCatching { AirPlayCrypto.chachaOpen(key, nonce, body, aad) }.getOrNull()
        }

        /**
         * 尾部 8 字节 → 12 字节 nonce（前 4 字节补零）。
         *
         * **注意 nonce 的后 8 字节是「小端」的**——[AirPlayCrypto.nonce64] 就是这么写的
         * （`nonce[i] = value.toByte(); value ushr= 8`），下行 [AudioSender] 的尾部也按同一
         * 顺序落盘。所以：
         *  - 小端（默认，与下行同族）：直接抄进 nonce[4..12)，**不能反转**；
         *  - 大端：反转后再抄。
         */
        fun nonceFromTail(
            packet: ByteArray,
            length: Int,
            bigEndian: Boolean,
        ): ByteArray {
            val nonce = ByteArray(12)
            val start = length - NONCE_BYTES
            if (bigEndian) {
                for (index in 0 until NONCE_BYTES) {
                    nonce[4 + index] = packet[start + NONCE_BYTES - 1 - index]
                }
            } else {
                packet.copyInto(nonce, 4, start, length)
            }
            return nonce
        }
    }

    /**
     * 一帧的线格式假设。
     *
     * [PROBES] 的顺序不是随意的：第一条是 xcertplay / 我们下行都用的写法
     * （尾部小端 nonce + AAD=头 4..12），命中率最高，正常情况下第一条就够；
     * 后面几条只是给「对端实现不一样」留的退路，日志会记下真正命中的是哪条。
     */
    internal data class WireFormat(
        val nonceFromTail: Boolean,
        val bigEndianNonce: Boolean,
        val useAad: Boolean,
    ) {
        override fun toString(): String =
            buildString {
                append(if (nonceFromTail) "nonce=尾部" else "nonce=序号计数器")
                if (nonceFromTail) append(if (bigEndianNonce) "大端" else "小端")
                append(if (useAad) " + AAD(头4..12)" else " + 无AAD")
            }

        internal companion object {
            /** 首包按此顺序试解，命中即锁定。 */
            val PROBES =
                listOf(
                    WireFormat(nonceFromTail = true, bigEndianNonce = false, useAad = true),
                    WireFormat(nonceFromTail = true, bigEndianNonce = false, useAad = false),
                    WireFormat(nonceFromTail = true, bigEndianNonce = true, useAad = true),
                    WireFormat(nonceFromTail = false, bigEndianNonce = false, useAad = true),
                    WireFormat(nonceFromTail = false, bigEndianNonce = false, useAad = false),
                )
        }
    }

    // 供测试直接验证解码（避免把 companion 私有函数暴露成 public API）
    internal fun decodeForTest(
        key: ByteArray,
        packet: ByteArray,
        length: Int,
        counter: Long,
        format: WireFormat,
    ): ByteArray? = decodePacket(key, packet, length, counter, format)

    internal fun headerLenForTest(
        packet: ByteArray,
        length: Int,
    ): Int? = rtpHeaderLength(packet, length)

    internal fun swapForTest(bytes: ByteArray) = swapS16(bytes)
}
