package io.github.shaonialife.carptocar.carlife

/**
 * H.264 SPS 解析：区分宏块对齐编码尺寸（coded）与裁剪后的真实画面尺寸（visible）。
 *
 * VideoConfig 的码流声明取 visible；coded 仅用于对齐填充诊断。CarLife 申请值与 65544 回包值
 * 可能因引擎白名单或用户配置与真实码流不同，不能替代 SPS 中的实际画面尺寸。
 *
 * 只实现 AnnexB + 常见 profile（Baseline/Main/High 及其变体），足够覆盖 CarLife 的 H.264。
 */
internal object H264Sps {
    data class Dimensions(
        val codedWidth: Int,
        val codedHeight: Int,
        val visibleWidth: Int,
        val visibleHeight: Int,
    ) {
        val visible: Pair<Int, Int> get() = visibleWidth to visibleHeight
    }

    /** 从单个 SPS NAL（含 NAL 头 0x67、不含起始码）解析编码尺寸与裁剪后尺寸。 */
    fun parseNal(nal: ByteArray): Dimensions? {
        if (nal.size < 8) return null
        return runCatching {
            // 跳过 NAL 头，并剔除 emulation prevention bytes（00 00 03 → 00 00）。
            // 漏这一步会让 bit 流整体错位，解出 272x112 之类的垃圾值。
            val rbsp = stripEmulation(nal, 1, nal.size)
            parseRbsp(rbsp)
        }.getOrNull()
    }

    /** 从 AnnexB 字节流里找 SPS 并返回裁剪后尺寸。返回 null = 没找到或解析失败。 */
    fun parseAnnexB(data: ByteArray): Pair<Int, Int>? {
        var index = 0
        while (index + 5 < data.size) {
            // 找 00 00 01（三字节起始码）或 00 00 00 01
            if (data[index] != 0.toByte() || data[index + 1] != 0.toByte()) {
                index++
                continue
            }
            val scLength =
                when {
                    data[index + 2] == 1.toByte() -> 3
                    index + 3 < data.size &&
                        data[index + 2] == 0.toByte() &&
                        data[index + 3] == 1.toByte() -> 4
                    else -> {
                        index++
                        continue
                    }
                }
            val nalStart = index + scLength
            if (nalStart >= data.size) return null
            val nalType = data[nalStart].toInt() and 0x1F
            if (nalType == 7) {
                // SPS 到下一个起始码为止
                var end = nalStart + 1
                while (end + 2 < data.size) {
                    if (data[end] == 0.toByte() && data[end + 1] == 0.toByte() &&
                        (data[end + 2] == 1.toByte() || data[end + 2] == 0.toByte())
                    ) {
                        break
                    }
                    end++
                }
                return parseNal(data.copyOfRange(nalStart, end))?.visible
            }
            index = nalStart + 1
        }
        return null
    }

    /** 去掉 00 00 03 → 00 00 的防竞争字节。 */
    private fun stripEmulation(
        src: ByteArray,
        from: Int,
        to: Int,
    ): ByteArray {
        val out = ByteArray(to - from)
        var o = 0
        var zeros = 0
        for (i in from until to) {
            val b = src[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[o++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun u1(): Int {
            val byteIndex = bitPos ushr 3
            if (byteIndex >= data.size) throw IndexOutOfBoundsException()
            val bit = 7 - (bitPos and 7)
            bitPos++
            return (data[byteIndex].toInt() ushr bit) and 1
        }

        fun u(n: Int): Int {
            var value = 0
            repeat(n) { value = (value shl 1) or u1() }
            return value
        }

        /** 无符号指数哥伦布。 */
        fun ue(): Int {
            var leadingZeros = 0
            while (u1() == 0) {
                leadingZeros++
                if (leadingZeros > 32) throw IllegalStateException("ue overflow")
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + u(leadingZeros)
        }

        /** 有符号指数哥伦布。 */
        fun se(): Int {
            val v = ue()
            return if (v % 2 == 0) -(v / 2) else (v + 1) / 2
        }
    }

    private fun parseRbsp(rbsp: ByteArray): Dimensions {
        val r = BitReader(rbsp)
        val profileIdc = r.u(8)
        r.u(8) // constraint_set0..5 + reserved（8 位，多读一位会让后面全部错位）
        r.u(8) // level_idc
        r.ue() // seq_parameter_set_id

        var chromaFormatIdc = 1
        var separateColourPlane = false
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) separateColourPlane = r.u1() == 1
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.u1() // qpprime_y_zero_transform_bypass_flag
            if (r.u1() == 1) { // seq_scaling_matrix_present_flag
                val count = if (chromaFormatIdc == 3) 12 else 8
                for (i in 0 until count) {
                    if (r.u1() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }

        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                r.u1() // delta_pic_order_always_zero_flag
                r.se() // offset_for_non_ref_pic
                r.se() // offset_for_top_to_bottom_field
                val cycle = r.ue()
                repeat(cycle) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.u1() // gaps_in_frame_num_value_allowed_flag

        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.u1()
        if (frameMbsOnly == 0) r.u1() // mb_adaptive_frame_field_flag
        r.u1() // direct_8x8_inference_flag

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (r.u1() == 1) { // frame_cropping_flag
            cropLeft = r.ue()
            cropRight = r.ue()
            cropTop = r.ue()
            cropBottom = r.ue()
        }

        val subWidthC = if (chromaFormatIdc == 3 || separateColourPlane) 1 else 2
        val subHeightC =
            when {
                chromaFormatIdc == 1 -> 2
                chromaFormatIdc == 3 || separateColourPlane -> 1
                else -> 1 // 4:2:2 已经是 1，其它按 1 处理
            }
        val codedWidth = widthMbs * 16
        val codedHeight = (2 - frameMbsOnly) * heightMapUnits * 16
        val width = codedWidth - (cropLeft + cropRight) * subWidthC
        val height = codedHeight - (cropTop + cropBottom) * subHeightC * (2 - frameMbsOnly)
        if (width <= 0 || height <= 0) throw IllegalStateException("bad sps size")
        return Dimensions(codedWidth, codedHeight, width, height)
    }

    private fun skipScalingList(
        r: BitReader,
        size: Int,
    ) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val delta = r.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private val HIGH_PROFILES = setOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135,
    )
}
