package io.github.shaonialife.carptocar.audio

/**
 * 混音源：CarLife 的两条音频通路。
 *
 * 9240 = 媒体（音乐/视频伴音），9241 = TTS（导航播报）。两者是**独立读线程**推上来的，
 * 各自可能有不同采样率（媒体 44.1k、播报 16k 单声道都见过）。
 */
enum class AudioSource {
    MEDIA,
    NAV,
}

/**
 * 双路 PCM 混音器：把媒体与播报按**墙钟节拍**相加成一条 48k 立体声流。
 *
 * ## 为什么必须这么做（2026-11-14 定案）
 *
 * 旧实现是「两路都写进同一个 `ByteArrayOutputStream`，凑满一帧就发」：
 *
 * 1. 两路字节**只是首尾相接**，没有相加。一帧 1024 样本（21ms）里往往是前 10ms 音乐、
 *    后 11ms 播报，听感就是持续的碎、卡、糊。
 * 2. 两路同时推时一秒产出两秒样本，而封包侧 `timestamp += samplesPerPacket` 跟着产出走，
 *    RTP 时钟变成 2×。车机按 1× 消费，缓冲只增不减，几条播报之后溢出 → 丢包/重同步，
 *    表现正是「导航和音乐一起放，过一会儿开始卡」。用户 2026-11-14 报的就是这个。
 *
 * ## 设计要点
 *
 * - **定拍**：按 `nanoTime` 反算「现在应该产到第几帧」，不按到达量发。输入抖动、突发、
 *   空档都不会改变输出速率（严格 48000 样本/秒）。缺样本就补零（等价于原来那条
 *   「静音保活」线程，但同一条线程完成，不再有并发写时间戳的竞态）。
 * - **每源独立环形缓冲**：读线程只做「重采样 + 写入」，写满就丢最旧的，**永不阻塞**
 *   CarLife 引擎的 9240/9241 读线程（旧实现里编码+加密+UDP 发送全在它们身上，
 *   网络一抖就反压引擎）。
 * - **播报让路（duck）**：播报有数据时把音乐压到 −12dB，用 21ms 斜坡避免爆音；
 *   播报停 250ms 后恢复。对应 AIS 的 `duckAudio` 语义，只是由我们自己在手机侧做
 *   （CarLife 引擎不会替我们做，它把两路当成两条独立的流）。
 *
 * 线程模型：**只有泵线程**调用 [mixNextFrame] / [dueFrames]，其余线程只调 [feed]。
 * 因此编码器、RTP 序号/时间戳全部单线程，天然没有竞态。
 */
class AudioMixer(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameSamples: Int,
    /** 每个源的环形缓冲容量（毫秒）。500ms 足够吸收引擎的突发，又不至于把延迟堆起来。 */
    capacityMillis: Int = 500,
    /** 播报停了多久之后把音乐音量放回来。太短会在句与句之间反复抽动。 */
    private val duckHoldMillis: Long = 250L,
    /** duck 时的音乐增益（Q10，258 ≈ −12dB）。 */
    private val duckGainQ: Int = 258,
) {
    companion object {
        /** 增益定点基数（Q10）：1024 = 0dB。 */
        const val GAIN_UNITY_Q = 1024

        /**
         * 单拍最多追几帧。
         *
         * 进程被调度打断、系统短暂休眠之后，`dueFrames` 可能一次算出几十帧。全部补发会让
         * RTP 时间戳瞬间前跳一大截（车机侧表现为一段静音后突然快进），所以超过这个数就
         * **不追**，直接重新对齐——丢的那点时间没人能听出来。
         */
        const val MAX_CATCHUP_FRAMES = 3
    }

    /** 一帧的 PCM 字节数（16bit 交织）。 */
    val frameBytes: Int = frameSamples * channels * 2

    private val bytesPerSample = channels * 2

    private val media = Ring((sampleRate.toLong() * capacityMillis / 1000).toInt() * bytesPerSample)
    private val nav = Ring((sampleRate.toLong() * capacityMillis / 1000).toInt() * bytesPerSample)

    private val duckHoldNanos = duckHoldMillis * 1_000_000L

    /** 泵的时间原点与已产帧数（定拍基准）。 */
    private var originNanos = 0L
    private var emittedFrames = 0L

    /** 当前音乐增益（Q10），逐帧线性趋近目标值。 */
    private var gainQ = GAIN_UNITY_Q

    @Volatile
    private var lastNavFeedNanos = 0L

    // ---- 统计（30 秒体检用；全部由泵线程单线程更新，读侧允许脏读） ----

    /** 已产出的总帧数。 */
    @Volatile
    var framesEmitted = 0L
        private set

    /** 其中「播报有数据」的帧数。 */
    @Volatile
    var duckedFrames = 0L
        private set

    /** 环形缓冲因为写满而丢掉的字节数（丢最旧，不阻塞推送方）。 */
    val droppedBytes: Long get() = media.dropped + nav.dropped

    /** 本次统计窗口内的帧数与「该源有数据」的帧数（[resetWindow] 清零）。 */
    @Volatile
    var windowFrames = 0L
        private set

    @Volatile
    var windowMediaFrames = 0L
        private set

    @Volatile
    var windowNavFrames = 0L
        private set

    /** 读线程注入一段 PCM（目标采样率、16bit 交织）。**不做重采样**，由调用方负责。 */
    fun feed(
        source: AudioSource,
        pcm: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0) return
        when (source) {
            AudioSource.MEDIA -> media.write(pcm, offset, length)
            AudioSource.NAV -> {
                nav.write(pcm, offset, length)
                lastNavFeedNanos = System.nanoTime()
            }
        }
    }

    /**
     * 这一拍应该产几帧。
     *
     * 返回 0 = 还没到下一帧的时刻；返回 >0 = 立刻产这么多帧（≤ [MAX_CATCHUP_FRAMES]）。
     */
    fun dueFrames(nowNanos: Long): Int {
        if (originNanos == 0L) {
            originNanos = nowNanos
            emittedFrames = 0L
            return 1
        }
        val elapsedNanos = nowNanos - originNanos
        if (elapsedNanos <= 0) return 0
        // 注意除以 frameSamples：这是「帧数」不是「样本数」，漏除会把 21ms 当成 1 秒，
        // 每拍都判定「落后太多」→ 重新对齐 → 实际只产出 1 帧/拍（速率崩掉）。
        val elapsedFrames = elapsedNanos * sampleRate / 1_000_000_000L / frameSamples
        val due = elapsedFrames - emittedFrames
        if (due <= 0) return 0
        if (due > MAX_CATCHUP_FRAMES) {
            // 落后太多：不追赶，直接把时间原点挪到眼前，保证长期速率仍准确
            originNanos = nowNanos
            emittedFrames = 0L
            return 1
        }
        return due.toInt()
    }

    /** 距离下一帧还有多久（纳秒，已钳到 [1ms, 20ms]，供泵线程 park）。 */
    fun nanosToNextFrame(nowNanos: Long): Long {
        if (originNanos == 0L) return 0L
        val nextFrameAt =
            originNanos + (emittedFrames + 1) * frameSamples * 1_000_000_000L / sampleRate
        return (nextFrameAt - nowNanos).coerceIn(1_000_000L, 20_000_000L)
    }

    /**
     * 产出一帧到 [out]（长度必须 ≥ [frameBytes]）。两路相加，饱和限幅。
     *
     * 缺样本时按 0 计——等价于补静音，保证 RTP 时间戳连续（车机的 jitter buffer 不会排空）。
     */
    fun mixNextFrame(
        out: ByteArray,
        nowNanos: Long,
    ) {
        var hasMedia = false
        var hasNav = false
        var navActive = false
        // 锁两个环形缓冲（顺序固定：media → nav，写侧只锁自己那一个，不会成环）。
        // 锁粒度是「一整帧」（≈21ms 的样本量，实际执行是几十微秒），争用可以忽略。
        synchronized(media) {
            synchronized(nav) {
                hasMedia = media.size >= bytesPerSample
                hasNav = nav.size >= bytesPerSample
                navActive =
                    hasNav || (lastNavFeedNanos != 0L && nowNanos - lastNavFeedNanos <= duckHoldNanos)
                val targetQ = if (navActive) duckGainQ else GAIN_UNITY_Q
                val startQ = gainQ
                val stepQ = targetQ - startQ
                var offset = 0
                for (frame in 0 until frameSamples) {
                    // 一帧之内线性走完增益变化（≈21ms 斜坡）：够平滑，又不会漏掉下一个字
                    val gain = if (stepQ == 0) startQ else startQ + stepQ * frame / frameSamples
                    for (channel in 0 until channels) {
                        val music = media.readShort()
                        val voice = nav.readShort()
                        val mixed = ((music * gain) shr 10) + voice
                        val clamped =
                            when {
                                mixed > Short.MAX_VALUE -> Short.MAX_VALUE.toInt()
                                mixed < Short.MIN_VALUE -> Short.MIN_VALUE.toInt()
                                else -> mixed
                            }
                        out[offset++] = (clamped and 0xFF).toByte()
                        out[offset++] = ((clamped shr 8) and 0xFF).toByte()
                    }
                }
                gainQ = targetQ
            }
        }
        emittedFrames++
        framesEmitted++
        windowFrames++
        if (hasMedia) windowMediaFrames++
        if (hasNav) windowNavFrames++
        if (navActive) duckedFrames++
    }

    /** 清零窗口统计（30 秒体检读完就调）。 */
    fun resetWindow() {
        windowFrames = 0
        windowMediaFrames = 0
        windowNavFrames = 0
    }

    /** 新一轮会话开始时清干净：环形缓冲、相位、时间原点、增益。 */
    fun reset() {
        media.clear()
        nav.clear()
        originNanos = 0L
        emittedFrames = 0L
        gainQ = GAIN_UNITY_Q
        lastNavFeedNanos = 0L
        framesEmitted = 0L
        duckedFrames = 0L
        resetWindow()
    }

    /** 环形缓冲：写满丢最旧，读空返回 0（不阻塞、不抛异常）。 */
    private class Ring(
        private val capacity: Int,
    ) {
        private val data = ByteArray(capacity)
        private var head = 0
        private var tail = 0

        /** 可读字节数。 */
        var size = 0
            private set

        /** 因写满被丢弃的字节数（诊断：持续增长说明某个源推得比实时快）。 */
        @Volatile
        var dropped = 0L
            private set

        @Synchronized
        fun write(
            src: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (length <= 0 || capacity <= 0) return
            if (length >= capacity) {
                // 一段就超过整个容量：只留尾部（最新的音频才有意义）
                dropped += (size + length - capacity).toLong()
                System.arraycopy(src, offset + length - capacity, data, 0, capacity)
                head = 0
                tail = 0
                size = capacity
                return
            }
            if (size + length > capacity) {
                val overflow = size + length - capacity
                tail = (tail + overflow) % capacity
                size -= overflow
                dropped += overflow.toLong()
            }
            val first = minOf(length, capacity - head)
            System.arraycopy(src, offset, data, head, first)
            if (first < length) System.arraycopy(src, offset + first, data, 0, length - first)
            head = (head + length) % capacity
            size += length
        }

        /** 读一个 16bit 小端样本（带符号）；没有数据返回 0。 */
        fun readShort(): Int {
            if (size < 2) return 0
            val b0 = data[tail].toInt() and 0xFF
            var next = tail + 1
            if (next == capacity) next = 0
            val b1 = data[next].toInt() and 0xFF
            tail = next + 1
            if (tail == capacity) tail = 0
            size -= 2
            return (((b1 shl 8) or b0).toShort()).toInt()
        }

        @Synchronized
        fun clear() {
            head = 0
            tail = 0
            size = 0
        }
    }
}
