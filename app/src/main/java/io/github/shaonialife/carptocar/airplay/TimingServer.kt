package io.github.shaonialife.carptocar.airplay

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * AirPlay 计时服务（NTP over RTCP，对照 catplay clock/timing_server.rs + rtcp_timesync.rs）。
 *
 * 初始 SETUP 通告本端口（timingPort）；车机（媒体时钟主）向它发 32 字节 timesync 请求，
 * 我们按 NTP 语义应答：t1=回显请求 t3（客户端发送时刻）、t2=本地接收时刻、t3=本地发送时刻。
 * 缺失时序：此前通告 timingPort=0，车机无法与我们同步媒体时钟——屏幕流 SETUP 被 400 的
 * 头号嫌疑（catplay 真车实现始终绑定真实端口）。
 *
 * 线格式（大端）：[0x80][pt][length=7][rtp_ts=0][t1(8)][t2(8)][t3(8)]，
 * pt：0xD2 请求 / 0xD3 应答；时间 = NTP 64 位（秒<<32 | 2^32 分数），取自单调时钟。
 */
class TimingServer(
    private val log: (String) -> Unit = {},
) {
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** 收到的 timesync 请求计数（诊断：车机是否真的来同步媒体时钟）。 */
    @Volatile private var syncRequests = 0L
    private var lastSyncLogAt = 0L

    /** 实际发出去的应答数；请求数 - 应答数 = 还没回完/回失败的包。 */
    @Volatile private var syncReplies = 0L
    @Volatile private var lastReplyAtMs = 0L
    @Volatile private var sendErrors = 0L

    /** 非 timesync 包计数（诊断：有没有别的 UDP 报文打到这个端口）。 */
    private var unexpected = 0L

    /** 连接阶段卡片用的只读快照（读到的是某一瞬间，不保证请求/回包同帧一致）。 */
    data class Stats(
        val requests: Long,
        val replies: Long,
        val lastReplyAgeMs: Long?,
        val sendErrors: Long,
    )

    fun snapshot(): Stats = Stats(
        requests = syncRequests,
        replies = syncReplies,
        lastReplyAgeMs = if (lastReplyAtMs == 0L) null else SystemClock.elapsedRealtime() - lastReplyAtMs,
        sendErrors = sendErrors,
    )

    val port: Int get() = socket?.localPort ?: 0

    fun start(): Boolean {
        if (port != 0 && running) return true
        return try {
            val s = DatagramSocket()
            s.reuseAddress = true
            socket = s
            running = true
            Thread({ loop(s) }, "airplay-timing").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("TIMING: 启动失败 - ${error.message}")
            false
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                return // socket closed
            }
            val request = buffer.copyOfRange(0, packet.length)
            val response = buildResponse(request, System.nanoTime())
            if (response == null) {
                unexpected++
                if (unexpected <= 5) {
                    log("TIMING: 收到非 timesync 报文 ${request.size}B（累计 $unexpected）")
                }
                continue
            }
            syncRequests++
            val now = System.currentTimeMillis()
            if (syncRequests <= 3 || now - lastSyncLogAt >= 30_000L) {
                lastSyncLogAt = now
                log("TIMING: timesync 应答 #$syncRequests → ${packet.address.hostAddress}:${packet.port}")
            }
            try {
                s.send(DatagramPacket(response, response.size, packet.address, packet.port))
                syncReplies++
                lastReplyAtMs = SystemClock.elapsedRealtime()
            } catch (_: Exception) {
                sendErrors++
            }
        }
    }

    companion object {
        private const val PT_TIMESYNC_REQUEST: Byte = 0xD2.toByte()
        private const val PT_TIMESYNC_RESPONSE: Byte = 0xD3.toByte()
        private const val PACKET_BYTES = 32

        /**
         * 构造 timesync 应答；非 timesync 请求返回 null。
         * [nowNanos]：单调时钟当前值（System.nanoTime 语义，可为任意起点——NTP 差值计算只用相对量）。
         */
        fun buildResponse(
            request: ByteArray,
            nowNanos: Long,
        ): ByteArray? {
            if (request.size < PACKET_BYTES) return null
            val version = (request[0].toInt() and 0xFF) shr 6
            if (version != 2) return null
            if (request[1] != PT_TIMESYNC_REQUEST) return null
            val length = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
            if (length != PACKET_BYTES / 4 - 1) return null

            val response = ByteArray(PACKET_BYTES)
            response[0] = (2 shl 6).toByte()
            response[1] = PT_TIMESYNC_RESPONSE
            response[2] = 0
            response[3] = (PACKET_BYTES / 4 - 1).toByte()
            // rtp_ts(4..8) = 0
            System.arraycopy(request, 24, response, 8, 8) // t1 = 请求的 t3（ntp_xmit 回显）
            val now = ntpFromNanos(nowNanos)
            System.arraycopy(now, 0, response, 16, 8) // t2 = 接收时刻
            System.arraycopy(now, 0, response, 24, 8) // t3 = 发送时刻
            return response
        }

        /** 单调纳秒 → NTP 64 位（秒<<32 | (纳秒分数 * 2^32 / 1e9)），大端 8 字节。 */
        fun ntpFromNanos(nanos: Long): ByteArray {
            val secs = Math.floorDiv(nanos, 1_000_000_000L)
            val fracNanos = Math.floorMod(nanos, 1_000_000_000L)
            val frac = (fracNanos.toLong() shl 32) / 1_000_000_000L
            val packed = (secs shl 32) or (frac and 0xFFFF_FFFFL)
            return ByteArray(8) { index -> (packed ushr (8 * (7 - index))).toByte() }
        }
    }
}
