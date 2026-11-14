package io.github.shaonialife.carptocar.airplay

import java.io.ByteArrayOutputStream

/**
 * Apple 二进制 plist（bplist00）最小解析器：覆盖 /info 能力清单用到的类型
 * （null/bool/int/real/data/ASCII/UTF-16 字符串/UID/数组/字典）。
 * 返回值映射：Map<String,Any?> / List<Any?> / String / Long / Double / Boolean / ByteArray / null。
 */
object BinaryPlist {
    // ---- 编码：Map<String,Any?>/List/String/Long/Int/Boolean/Double/ByteArray ----

    fun encode(value: Any?): ByteArray {
        val objects = ArrayList<ByteArray>()
        val totalObjects = countObjects(value)
        val rootRef = encodeObject(value, objects, totalObjects)
        var offset = 8
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = offset
            offset += bytes.size
        }
        val offsetIntSize = intBytes(offset)
        val refSize = intBytes(totalObjects)
        val tableSize = objects.size * offsetIntSize
        val tableOffset = offset

        val out = ByteArrayOutputStream(offset + tableSize + 32)
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        objects.forEach(out::write)
        offsets.forEach { writeUint(out, it.toLong(), offsetIntSize) }
        // trailer: unused(6) offsetIntSize(1) refSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        out.write(ByteArray(6))
        out.write(offsetIntSize)
        out.write(refSize)
        writeUint(out, objects.size.toLong(), 8)
        writeUint(out, rootRef.toLong(), 8)
        writeUint(out, tableOffset.toLong(), 8)
        // 根对象必须位于字节 8：本编码器先递归编码子对象、最后才追加根容器，
        // 默认根在末尾。CarPlay 210.81 一类的老车机**不查 trailer 的 topObject，
        // 直接读偏移 8**（catplay 上游 9ad3a7e「fix recent encoder regression with
        // CarPlay 210.81 that hardcodes expected offset of the root object」）。
        // Apple 的编码器本来也是根在前，这里按同一布局重排以匹配真实 iPhone。
        return makeRootFirst(out.toByteArray())
    }

    /**
     * 把根对象整体移到偏移 8，并修正偏移表（catplay `make_root_first` 同算法）。
     * 输入必须是本类刚生成的合法 bplist（根是最后一个对象），异常布局原样返回。
     */
    private fun makeRootFirst(data: ByteArray): ByteArray {
        if (data.size < 41 || !data.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            return data
        }
        val trailer = data.size - 32
        val width = data[trailer + 6].toInt() and 0xFF
        if (width != 1 && width != 2 && width != 4 && width != 8) return data
        val count = readUint(data, trailer + 8, 8).toInt()
        val root = readUint(data, trailer + 16, 8).toInt()
        val table = readUint(data, trailer + 24, 8).toInt()
        if (root >= count ||
            table < 9 ||
            table.toLong() + count.toLong() * width > trailer
        ) {
            return data
        }

        fun entry(index: Int): Int = table + index * width
        val rootStart = readUint(data, entry(root), width).toInt()
        if (rootStart !in 8 until table) return data
        if (rootStart == 8) return data
        // 根之后（到偏移表之前）属于根对象的字节块；其余对象都排在它前面。
        var rootEnd = table
        for (index in 0 until count) {
            val offset = readUint(data, entry(index), width).toInt()
            if (offset !in 8 until table) return data
            if (offset > rootStart) rootEnd = minOf(rootEnd, offset)
        }
        val rootLen = rootEnd - rootStart
        if (rootLen <= 0) return data
        val out = data.copyOf()
        // data[8..rootEnd) 右旋 rootLen：等价于把根块搬到最前、其余对象顺延。
        val tailLen = rootEnd - 8 - rootLen
        System.arraycopy(data, rootStart, out, 8, rootLen)
        System.arraycopy(data, 8, out, 8 + rootLen, tailLen)
        for (index in 0 until count) {
            val at = entry(index)
            val offset = readUint(data, at, width).toInt()
            val adjusted =
                when {
                    index == root -> 8
                    offset < rootStart -> offset + rootLen
                    else -> offset
                }
            writeUint(out, adjusted.toLong(), width, at)
        }
        return out
    }

    private fun writeUint(
        target: ByteArray,
        value: Long,
        size: Int,
        offset: Int,
    ) {
        for (index in 0 until size) {
            target[offset + index] = (value ushr (8 * (size - 1 - index))).toByte()
        }
    }

    /** 预遍历：引用宽度必须按最终对象总数（含容器自身）确定。 */
    private fun countObjects(value: Any?): Int =
        when (value) {
            is List<*> -> 1 + value.sumOf { countObjects(it) }
            is Map<*, *> -> 1 + value.keys.sumOf { countObjects(it) } + value.values.sumOf { countObjects(it) }
            else -> 1
        }

    private fun encodeObject(
        value: Any?,
        objects: ArrayList<ByteArray>,
        total: Int,
    ): Int {
        val bytes =
            when (value) {
                null -> byteArrayOf(0x00)
                is Boolean -> byteArrayOf(if (value) 0x09 else 0x08)
                is Int -> encodeInt(value.toLong())
                is Long -> encodeInt(value)
                is Double -> byteArrayOf(0x23) + value.toRawBits().toBytes(8)
                is ByteArray -> withLength(0x40, value)
                is String -> encodeString(value)
                is List<*> -> {
                    val refs = value.map { encodeObject(it, objects, total) }
                    withLength(0xA0, refsToBytes(refs, total), refs.size)
                }

                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = value as Map<String, Any?>
                    val keyRefs = map.keys.map { encodeObject(it, objects, total) }
                    val valueRefs = map.values.map { encodeObject(it, objects, total) }
                    withLength(0xD0, refsToBytes(keyRefs + valueRefs, total), map.size)
                }

                else -> throw IllegalArgumentException("不支持的 plist 类型 ${value.javaClass.simpleName}")
            }
        objects += bytes
        return objects.size - 1
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.toByteArray(Charsets.US_ASCII)
        return if (value.all { it.code < 0x80 } && ascii.decodeToString() == value) {
            withLength(0x50, ascii)
        } else {
            withLength(0x60, value.toByteArray(Charsets.UTF_16BE), value.length)
        }
    }

    /**
     * 整数按 **有符号** 范围选宽度（bplist 规范：整数是有符号的，读取端按符号扩展）。
     *
     * 曾按无符号阈值选宽度：35449（0x8A79）被写成 2 字节，Apple 侧按 int16 读成
     * -30087。整型端口（timingPort/eventPort/dataPort）一旦落到 32768..65535 就会
     * 变成负值——接收端可能直接拒绝或绑定到错误端口。单测 `SessionSetupBodyTest`
     * 即由此发现。
     *
     * 负数一律写满 8 字节补码：短宽度载荷按无符号解释（单字节 `FF` 读成 255 而非 -1，如 `speechMode = -1`）。
     */
    private fun encodeInt(value: Long): ByteArray {
        val size =
            when {
                value < 0 -> 8
                value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE -> 1
                value >= Short.MIN_VALUE && value <= Short.MAX_VALUE -> 2
                value >= Int.MIN_VALUE && value <= Int.MAX_VALUE -> 4
                else -> 8
            }
        return byteArrayOf((0x10 or (log2(size))).toByte()) + value.toBytes(size)
    }

    private fun withLength(
        marker: Int,
        payload: ByteArray,
        count: Int = payload.size,
    ): ByteArray {
        if (count < 15) return byteArrayOf((marker or count).toByte()) + payload
        val lengthBytes = encodeInt(count.toLong())
        return byteArrayOf((marker or 0x0F).toByte()) + lengthBytes + payload
    }

    private fun refsToBytes(
        refs: List<Int>,
        objectCount: Int,
    ): ByteArray {
        val width = intBytes(objectCount)
        val out = ByteArray(refs.size * width)
        refs.forEachIndexed { index, ref ->
            val value = ref.toLong()
            for (byte in 0 until width) {
                out[index * width + byte] = (value ushr (8 * (width - 1 - byte))).toByte()
            }
        }
        return out
    }

    private fun Long.toBytes(size: Int): ByteArray = writeBytes(this, size)

    private fun writeBytes(
        value: Long,
        size: Int,
    ): ByteArray =
        ByteArray(size) { index ->
            (value ushr (8 * (size - 1 - index))).toByte()
        }

    private fun writeUint(
        out: ByteArrayOutputStream,
        value: Long,
        size: Int,
    ) {
        out.write(writeBytes(value, size))
    }

    private fun intBytes(value: Int): Int =
        when {
            value < 0x100 -> 1
            value < 0x10000 -> 2
            value < 0x100000000 -> 4
            else -> 8
        }

    private fun log2(size: Int): Int =
        when (size) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }

    // ---- 解析 ----

    /** 解析结果 + 恢复说明（发生过裁剪时 note 非空，正常解析为 null）。 */
    class ParseResult(
        val value: Any?,
        val note: String?,
    )

    /**
     * 容错解析：先按标准布局解析；失败时依次裁掉尾部 1~8 字节重试。
     *
     * 车机的 RTSP 报文有时在 `Content-Length` 之外还带尾随字节（CRLF / 填充），
     * bplist 的 trailer 是**从末尾倒数 32 字节**定位的，多一个字节就整体错位——
     * 表现为「body 明明是合法 bplist00 却解析失败」。裁尾重试能把这种报文救回来，
     * 且失败时抛出的是原始异常的详细信息（trailer 摘要已带在 message 里）。
     */
    fun parseRecovered(bytes: ByteArray): ParseResult {
        val first = runCatching { parse(bytes) }
        first.getOrNull()?.let { return ParseResult(it, null) }
        if (first.isSuccess) return ParseResult(null, null)
        val failure = first.exceptionOrNull() ?: IllegalArgumentException("解析失败")
        if (bytes.size >= 48) {
            for (trim in 1..8) {
                if (bytes.size - trim < 40) break
                val attempt = runCatching { parse(bytes.copyOfRange(0, bytes.size - trim)) }
                if (attempt.isSuccess) {
                    return ParseResult(attempt.getOrThrow(), "尾部多 $trim 字节（裁掉后解析成功）")
                }
            }
        }
        throw failure as? IllegalArgumentException
            ?: IllegalArgumentException("${failure.javaClass.simpleName}: ${failure.message}")
    }

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size < 40) throw IllegalArgumentException("plist 太短（${bytes.size} 字节）")
        val magic = "bplist00".toByteArray(Charsets.US_ASCII)
        if (!bytes.copyOfRange(0, 8).contentEquals(magic)) {
            throw IllegalArgumentException(
                "不是 bplist00（首 8 字节 " + bytes.take(8).joinToString(" ") { "%02x".format(it) } + "）",
            )
        }

        // 尾部 32 字节 trailer：unused(6) offsetIntSize(1) objectRefSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        val offsetIntSize = u8(bytes[bytes.size - 26])
        val objectRefSize = u8(bytes[bytes.size - 25])
        val numObjects = readUint(bytes, bytes.size - 24, 8)
        val topObject = readUint(bytes, bytes.size - 16, 8)
        val offsetTableOffset = readUint(bytes, bytes.size - 8, 8)
        val trailer =
            "trailer{offsetIntSize=$offsetIntSize objectRefSize=$objectRefSize numObjects=$numObjects " +
                "topObject=$topObject offsetTableOffset=$offsetTableOffset body=${bytes.size}B}"
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
            throw IllegalArgumentException("trailer 字段不合法 $trailer")
        }
        if (numObjects <= 0 || numObjects > MAX_OBJECTS) {
            throw IllegalArgumentException("对象数非法 $trailer")
        }
        if (topObject >= numObjects) throw IllegalArgumentException("根对象下标越界 $trailer")
        if (offsetTableOffset < 9 ||
            offsetTableOffset + numObjects * offsetIntSize > bytes.size - 32
        ) {
            throw IllegalArgumentException("偏移表越界 $trailer")
        }

        val count = numObjects.toInt()
        val tableOffset = offsetTableOffset.toInt()
        val offsets =
            IntArray(count) { index ->
                readUint(bytes, tableOffset + index * offsetIntSize, offsetIntSize).toInt()
            }
        return try {
            parseObject(bytes, offsets[topObject.toInt()], objectRefSize, offsets)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: IndexOutOfBoundsException) {
            throw IllegalArgumentException("读取越界（$trailer，${error.message}）")
        } catch (error: NegativeArraySizeException) {
            throw IllegalArgumentException("长度字段为负（$trailer）")
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("长度字段过大（$trailer）")
        }
    }

    private fun parseObject(
        bytes: ByteArray,
        offset: Int,
        refSize: Int,
        offsets: IntArray,
    ): Any? {
        val marker = u8(bytes[offset])
        val type = marker ushr 4
        val info = marker and 0xF
        return when (type) {
            0x0 ->
                when (info) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }

            // 整数一律按**无符号**解读：接收端（车机）会把位图/微秒/采样率写进
            // 2 字节 int，值超过 32767 时按有符号读会变成负数（实测 44100 → -21436、
            // 35000us → -30536、0x8854 → -30636）。位图字段一旦被符号扩展，高 16 位
            // 全部变成 1，任何 >bit15 的格式位都会「看起来被声明」——协商就会挑到
            // 车机根本不支持的格式。
            0x1 -> readUint(bytes, offset + 1, 1 shl info)

            0x2 ->
                when (info) {
                    0x2 -> Float.fromBits(readUint(bytes, offset + 1, 4).toInt()).toDouble()
                    0x3 -> Double.fromBits(readUint(bytes, offset + 1, 8))
                    else -> null
                }

            0x3 -> // 日期：CFAbsoluteTime（2001-01-01 起算的秒数），按 Double 原样返回
                Double.fromBits(readUint(bytes, offset + 1, 8))

            0x4 -> { // data
                val (count, start) = elementCount(bytes, offset, info)
                bytes.copyOfRange(start, start + count)
            }

            0x5 -> { // ASCII 字符串
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.US_ASCII)
            }

            0x6 -> { // UTF-16BE 字符串
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count * 2, Charsets.UTF_16BE)
            }

            0x7 -> { // UTF-8 字符串（bplist 0x7 号类型，部分编码器会写）
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.UTF_8)
            }

            // UID：长度由 info 决定（info+1 字节），**不是** refSize——按 refSize 读会在
            // refSize>1 时多吃掉后续字节，把整条 plist 的对象指针读歪。
            0x8 -> readUint(bytes, offset + 1, info + 1)

            0xB -> { // UUID（16 字节定长）
                val size = info + 1
                bytes.copyOfRange(offset + 1, minOf(offset + 1 + size, bytes.size))
            }

            0xA, 0xC -> { // array / set
                val (count, start) = elementCount(bytes, offset, info)
                List(count) { index ->
                    parseObject(
                        bytes,
                        offsets[readUint(bytes, start + index * refSize, refSize).toInt()],
                        refSize,
                        offsets,
                    )
                }
            }

            0xD -> { // dict
                val (count, start) = elementCount(bytes, offset, info)
                val map = LinkedHashMap<String, Any?>(count)
                for (index in 0 until count) {
                    val keyRef = readUint(bytes, start + index * refSize, refSize).toInt()
                    val valueRef = readUint(bytes, start + (count + index) * refSize, refSize).toInt()
                    val key = parseObject(bytes, offsets[keyRef], refSize, offsets)
                    map[key?.toString() ?: "#$index"] = parseObject(bytes, offsets[valueRef], refSize, offsets)
                }
                map
            }

            else -> null
        }
    }

    /** 集合/字符串/数据长度：info<0xF 时长度就是 info；否则长度存在随后的 int 对象里。 */
    private fun elementCount(
        bytes: ByteArray,
        offset: Int,
        info: Int,
    ): Pair<Int, Int> {
        if (info != 0xF) return info to offset + 1
        if (offset + 1 >= bytes.size) {
            throw IllegalArgumentException("长度字节缺失（offset=$offset body=${bytes.size}B）")
        }
        val intMarker = u8(bytes[offset + 1])
        if (intMarker ushr 4 != 0x1) {
            throw IllegalArgumentException(
                "期望长度 int 对象，得到 0x${intMarker.toString(16)}（offset=$offset " +
                    "上下文=" +
                    bytes
                        .copyOfRange(offset, minOf(offset + 8, bytes.size))
                        .joinToString(" ") { "%02x".format(it) } + "）",
            )
        }
        val intSize = 1 shl (intMarker and 0xF)
        // 扩展长度必须按**无符号**读：DiPlay 发 182B 的 data 时写 0xB6，按有符号读成
        // -74，copyOfRange(51, -23) 直接抛 IAE——整条 iAPSendMessage 命令会被当
        // 「无法识别」丢弃（2026-10-20 实测定位，与整数负号扩展是同一族 bug）
        val count = readUint(bytes, offset + 2, intSize)
        if (count < 0 || count > MAX_OBJECTS) {
            throw IllegalArgumentException("长度字段非法（count=$count offset=$offset）")
        }
        return count.toInt() to offset + 2 + intSize
    }

    private const val MAX_OBJECTS = 1 shl 20

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        if (size < 0 || size > 8) throw IllegalArgumentException("读取宽度非法（size=$size offset=$offset）")
        if (offset < 0 || offset + size > bytes.size) {
            throw IllegalArgumentException(
                "读取越界（offset=$offset size=$size body=${bytes.size}B）——" +
                    "trailer 与报文实际长度不一致时会出现",
            )
        }
        var value = 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
}
