package io.github.shaonialife.carptocar.audio

/**
 * 有状态的 PCM 重采样（44.1k ↔ 48k 这类小比值），16bit 小端交织。
 *
 * 为什么必须**有状态**（2026-11-13 实测日志）：
 * CarLife 引擎每 23.2ms 送来一块 1024 帧/44.1k 的 PCM，旧实现每块单独做
 * `outFrames = inFrames * outRate / inRate` 的整数截断——1024 × 48000/44100 = 1114.55，
 * 截成 1114，每块少 0.55 帧。43 块/秒 × 0.55 ≈ 24 帧/秒，即**每秒少 0.5ms 音频**：
 *
 *  - 车机按 RTP 时间戳以 48000 样本/秒消费，我们实际只送到 47975 样本/秒
 *    （日志里「已发 69239 包」与 `/feedback` 的 `sampleTime` 反推得 46.86 帧/秒，
 *    正是 47975，而不是理想 46875×1024/1024=48000）；
 *  - 抖动缓冲被慢慢抽干，长会话（20 分钟以上）迟早来不及而断一下；
 *  - 每块起点还把相位重置成 0，块边界处多一次子样本跳变，听感是「发毛发刺」。
 *
 * 现在把「相位」和「上一块最后一帧」带过块边界：位置连续、长期比值精确，
 * 边界处也能拿到下一帧做插值（不再是最近邻复制）。
 *
 * 插值核：**四点三次（Catmull-Rom）**，块首/块尾两帧退化线性。
 * 2026-11-16 从纯线性插值升级：线性的频响是 sinc²，大幅上采样（16k 的导航播报 →
 * 44.1k，比值 2.76）时 8k 以上的镜像只压到 −10dB 左右，听感是播报「发毛、发沙」；
 * 三次核在同样位置多压 ~15dB，且样本点上的值与原信号严格一致（不会改变音量/相位）。
 *
 * 线程模型：**非线程安全**，由调用方（[AudioSender.feedPcm]，本身 @Synchronized）串行使用。
 */
internal class PcmResampler {
    /** 下一个输出帧在「本工作块」里的位置（工作块 = [上一块末帧] + 本块）。 */
    private var phase = 0.0

    /** 上一块最后一帧（每声道一个），块边界插值用。 */
    private var hasPrev = false

    private var prev: ShortArray = ShortArray(0)

    /** 换流/改采样率时清掉相位，避免拿上一段的尾部去插值。 */
    fun reset() {
        phase = 0.0
        hasPrev = false
    }

    /**
     * 把 [src] 的前 [size] 字节从 [inRate] 重采样到 [outRate]。
     *
     * @return 新的 16bit 小端交织数组（长度 = 输出帧数 × 声道数 × 2）；无需转换时返回输入本身。
     */
    fun convert(
        src: ByteArray,
        size: Int,
        inRate: Int,
        outRate: Int,
        channels: Int = 2,
    ): ByteArray {
        if (size <= 0) return ByteArray(0)
        val ch = if (channels == 1) 1 else 2
        val frameBytes = ch * 2
        val inFrames = size / frameBytes
        if (inFrames <= 0) return ByteArray(0)
        if (inRate <= 0 || outRate <= 0 || inRate == outRate) {
            reset()
            return if (size == src.size) src else src.copyOf(size)
        }
        if (prev.size != ch) {
            prev = ShortArray(ch)
            hasPrev = false
        }

        // 工作块：index 0 = 上一块末帧，1..inFrames = 本块（末帧留作最后一段插值的右邻）
        val head = if (hasPrev) 1 else 0
        val work = ShortArray((inFrames + head) * ch)
        if (hasPrev) System.arraycopy(prev, 0, work, 0, ch)
        var i = 0
        var o = head * ch
        while (i < inFrames) {
            val base = i * frameBytes
            var c = 0
            while (c < ch) {
                val at = base + c * 2
                if (at + 1 < size) {
                    work[o] = (((src[at].toInt() and 0xFF) or (src[at + 1].toInt() shl 8)).toShort())
                }
                o++
                c++
            }
            i++
        }

        val step = inRate.toDouble() / outRate
        // 输出帧数上限：本块可覆盖的位置数（多留 2 帧，循环条件决定实际产量）
        val maxOut = ((inFrames + head - phase) / step).toInt() + 2
        val out = ShortArray(maxOut.coerceAtLeast(0) * ch)
        var pos = phase
        var produced = 0
        val lastIndex = inFrames + head - 1
        while (pos < inFrames + head - 1) {
            val i0 = pos.toInt()
            val frac = pos - i0
            val i1 = if (i0 + 1 <= lastIndex) i0 + 1 else i0
            val b0 = i0 * ch
            val b1 = i1 * ch
            // 四点（Catmull-Rom）还是两点（线性）
            val smooth = i0 >= 1 && i0 + 2 <= lastIndex
            val bm1 = if (smooth) (i0 - 1) * ch else 0
            val b2 = if (smooth) (i0 + 2) * ch else 0
            var c = 0
            while (c < ch) {
                val value =
                    if (smooth) {
                        // 三次插值：过 (i0, i1) 两点，用 i0-1 / i0+2 定两端斜率。
                        // 相比线性插值，16k→44.1k 这类**大幅上采样**的镜像（8k 以上的
                        // 「嘶」声）压制约 15dB，44.1k↔48k 的小比值也更平滑。
                        // 代价是陡峭瞬态可能轻微过冲，下面统一 clamp。
                        val p0 = work[bm1 + c].toDouble()
                        val p1 = work[b0 + c].toDouble()
                        val p2 = work[b1 + c].toDouble()
                        val p3 = work[b2 + c].toDouble()
                        val a = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3
                        val b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3
                        val cc = -0.5 * p0 + 0.5 * p2
                        ((a * frac + b) * frac + cc) * frac + p1
                    } else {
                        // 块首/块尾两帧没有完整四点，退化线性（每块最多 2 个点，不可闻）
                        val s0 = work[b0 + c].toInt()
                        val s1 = work[b1 + c].toInt()
                        s0 + (s1 - s0) * frac
                    }
                out[produced * ch + c] =
                    when {
                        value > 32767.0 -> Short.MAX_VALUE
                        value < -32768.0 -> Short.MIN_VALUE
                        else -> value.toInt().toShort()
                    }
                c++
            }
            produced++
            pos += step
        }
        // 相位带到下一块。基准是 **本块最后一个可用下标** [lastIndex]（它对应的绝对帧
        // 就是本块最后一帧，下一块会把它放在 index 0）——用 inFrames 或 inFrames-1 都会
        // 差一格，相位随之漂成负值/超出一个 step，长期就变成「每秒少几百个样本」。
        phase = pos - lastIndex
        if (phase < 0.0) phase = 0.0
        System.arraycopy(work, (head + inFrames - 1) * ch, prev, 0, ch)
        hasPrev = true

        val bytes = ByteArray(produced * frameBytes)
        var k = 0
        while (k < produced * ch) {
            val value = out[k].toInt()
            bytes[k * 2] = (value and 0xFF).toByte()
            bytes[k * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            k++
        }
        return bytes
    }
}
