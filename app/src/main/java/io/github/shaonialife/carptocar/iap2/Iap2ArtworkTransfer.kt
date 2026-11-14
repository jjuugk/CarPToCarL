package io.github.shaonialife.carptocar.iap2

/**
 * iAP2 文件传输（session 12）的数据报编解码 —— 目前只服务「正在播放封面」。
 *
 * ## 为什么要单独一条会话
 *
 * `0x5001 NowPlayingUpdate` 里的 `0x001A` 只是**单字节传输标识符**（`u8`），
 * 不是图片本身。真正要让车机屏幕上出现封面，必须在**文件传输会话**上把字节
 * 传过去，标识符只是「这次要取的那张图」的编号。两者必须成对：
 *
 * 1. 手机 `0x5001` 带上 `artworkId = N`；
 * 2. 手机在本会话发 `SETUP(N, 文件大小, 类型)`;
 * 3. 车机回 `START`（同意接收；若它已缓存过该 id 会直接回 `SUCCESS`）；
 * 4. 手机分块发数据；
 * 5. 车机回 `SUCCESS`。
 *
 * **第 3 步不能跳**：没等到 `START` 就抢发数据块，车机会判 `FAILURE`，这一轮封面作废。
 *
 * ## 数据报格式
 *
 * ```
 * [0]    transferId (u8)
 * [1]    command    (u8)
 * [2..]  payload
 * ```
 *
 * | 命令字 | 名称 | 方向 | 负载 |
 * | --- | --- | --- | --- |
 * | `0x04` | SETUP | 手机 → 车机 | `u64 文件大小` + `u16 文件类型` |
 * | `0x01` | START | 车机 → 手机 | 无 |
 * | `0x80` | FIRST_DATA | 手机 → 车机 | 首块 |
 * | `0x00` | DATA | 手机 → 车机 | 中间块 |
 * | `0x40` | LAST_DATA | 手机 → 车机 | 末块 |
 * | `0xC0` | FIRST_AND_ONLY | 手机 → 车机 | 只有一块时用它 |
 * | `0x05` | SUCCESS | 车机 → 手机 | 无（收完） |
 * | `0x06` | FAILURE | 车机 → 手机 | 无（拒收/超时） |
 * | `0x02` / `0x03` | CANCEL / PAUSE | 双向 | — |
 *
 * 文件类型 `0x0002` = Now Playing Artwork（车机据此决定把字节往哪个槽位落盘）。
 *
 * ## 分块大小的由来
 *
 * 车机侧声明 `maxReceivedPacketLength = 2048`，链路帧还有 9 字节头 + 1 字节校验，
 * 因此单帧负载上限约 2038B。这里取 [CHUNK_BYTES]，留出余量；
 * `SETUP` 里报的是**文件总大小**，与分块无关。
 */
object Iap2ArtworkTransfer {
    // ---- 命令字 ----
    const val CMD_SETUP = 0x04
    const val CMD_START = 0x01
    const val CMD_FIRST_DATA = 0x80
    const val CMD_DATA = 0x00
    const val CMD_LAST_DATA = 0x40
    const val CMD_FIRST_AND_ONLY = 0xC0
    const val CMD_CANCEL = 0x02
    const val CMD_PAUSE = 0x03
    const val CMD_SUCCESS = 0x05
    const val CMD_FAILURE = 0x06

    /** 文件类型：正在播放封面。 */
    const val FILE_TYPE_NOW_PLAYING_ARTWORK = 0x0002

    /** 单个数据报的原始负载上限（数据报总长 = 该值 + 2 字节头）。 */
    const val CHUNK_BYTES = 2_000

    /** 车机侧单文件上限 16 MB；超过直接放弃，别把链路堵死。 */
    const val MAX_ARTWORK_BYTES = 16 * 1_048_576

    /**
     * `SETUP` 数据报：`[id][0x04][u64 size][u16 type]`，共 12 字节。
     *
     * `size` 是文件总字节数（大端），车机据此判断「还有多少没到」并预留落盘空间。
     */
    fun setup(
        transferId: Int,
        size: Int,
    ): ByteArray {
        require(transferId in 1..0xFF) { "transferId must be 1..255" }
        val datagram = ByteArray(12)
        datagram[0] = transferId.toByte()
        datagram[1] = CMD_SETUP.toByte()
        for (index in 0 until 8) {
            datagram[2 + index] = (size.toLong() ushr (56 - index * 8)).toByte()
        }
        datagram[10] = (FILE_TYPE_NOW_PLAYING_ARTWORK ushr 8).toByte()
        datagram[11] = FILE_TYPE_NOW_PLAYING_ARTWORK.toByte()
        return datagram
    }

    /**
     * 把整张图切成数据报序列。
     *
     * - 只有一块 → `FIRST_AND_ONLY`
     * - 多块 → 首块 `FIRST_DATA`、中间 `DATA`、末块 `LAST_DATA`
     *
     * 空数据返回空列表（调用方应先判空，别让车机收到一个 0 字节的 SETUP）。
     */
    fun chunks(
        transferId: Int,
        data: ByteArray,
        chunkBytes: Int = CHUNK_BYTES,
    ): List<ByteArray> {
        require(transferId in 1..0xFF) { "transferId must be 1..255" }
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        if (data.isEmpty()) return emptyList()
        if (data.size <= chunkBytes) {
            return listOf(datagram(transferId, CMD_FIRST_AND_ONLY, data, 0, data.size))
        }
        val out = ArrayList<ByteArray>(data.size / chunkBytes + 2)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkBytes, data.size)
            val command =
                when {
                    offset == 0 -> CMD_FIRST_DATA
                    end == data.size -> CMD_LAST_DATA
                    else -> CMD_DATA
                }
            out += datagram(transferId, command, data, offset, end)
            offset = end
        }
        return out
    }

    /** 拼一个数据报：2 字节头 + `[start, end)` 之间的负载。 */
    private fun datagram(
        transferId: Int,
        command: Int,
        data: ByteArray,
        start: Int,
        end: Int,
    ): ByteArray {
        val out = ByteArray(2 + (end - start))
        out[0] = transferId.toByte()
        out[1] = command.toByte()
        data.copyInto(out, 2, start, end)
        return out
    }

    /**
     * 解析车机应答。数据报不足 2 字节、或 id 为 0 时返回 null（0 是保留值，
     * 车机不会用它，出现即说明字节流错位，交给上层忽略）。
     */
    fun parseResponse(datagram: ByteArray): Response? {
        if (datagram.size < 2) return null
        val transferId = datagram[0].toInt() and 0xFF
        if (transferId == 0) return null
        return Response(transferId, datagram[1].toInt() and 0xFF)
    }

    /** 车机应答：`(transferId, command)`。 */
    data class Response(
        val transferId: Int,
        val command: Int,
    ) {
        val isStart: Boolean get() = command == CMD_START
        val isSuccess: Boolean get() = command == CMD_SUCCESS
        val isFailure: Boolean get() = command == CMD_FAILURE

        /** 日志用：把命令字翻成人话，免得现场对着十六进制猜。 */
        fun describe(): String =
            when (command) {
                CMD_SETUP -> "SETUP"
                CMD_START -> "START"
                CMD_FIRST_DATA -> "FIRST_DATA"
                CMD_DATA -> "DATA"
                CMD_LAST_DATA -> "LAST_DATA"
                CMD_FIRST_AND_ONLY -> "FIRST_AND_ONLY"
                CMD_CANCEL -> "CANCEL"
                CMD_PAUSE -> "PAUSE"
                CMD_SUCCESS -> "SUCCESS"
                CMD_FAILURE -> "FAILURE"
                else -> "0x%02x".format(command)
            }
    }
}
