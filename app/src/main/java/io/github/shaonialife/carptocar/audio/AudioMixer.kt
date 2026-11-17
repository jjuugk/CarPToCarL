package io.github.shaonialife.carptocar.audio

import kotlin.math.tanh

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
 * 1. 两路字节**只是首尾相接**，没有相加。一帧 1024 样本（旧帧长 21ms）里往往是前 10ms 音乐、
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
    /**
     * 媒体路的**目标水位**（毫秒）：环形缓冲里长期该保留多少音频，超了就丢最旧的压回去。
     *
     * 为什么必须有这个数（1.2.59）：容量 500ms 只是「最多能存多少」，它本身**不构成
     * 延迟上限**。CarLife 引擎在会话开局常常一次性推一大段（它自己的解码缓冲），水位
     * 直接顶到 500ms 并长期停在那儿——推多少读多少，水位不变，于是「积压」变成
     * **恒定 500ms 的音频延迟**：导航说「前方右转」永远比路口晚半秒，音画也差半拍，
     * 而且任何一次泵抖动都会在满水位上直接丢掉一段最旧的音乐。
     *
     * 压到 150ms 的取舍：7 个 21ms 块的余量，足够吸收引擎的写入突发；又比 500ms
     * 少 350ms 的固定延迟。裁的永远是**最旧的**媒体——已经过时的音频，丢了不影响
     * 可懂度，只是音乐可能轻微跳一下（引擎推得比实时快时这本来就不可避免，区别只是
     * 现在「小步常丢」而不是「顶满后一次丢一大段」）。
     *
     * **只作用于媒体路**：播报（TTS）是有语义的整段，裁头就是缺字，绝不能这么干。
     */
    private val mediaTargetMillis: Int = 150,
) {
    companion object {
        /** 增益定点基数（Q10）：1024 = 0dB。 */
        const val GAIN_UNITY_Q = 1024

        /**
         * 单拍最多追几帧（一帧 = `frameSamples` 样本：AAC 1024 → 23.2ms @44.1k）。
         *
         * 原先是 3（≈22ms），隐含假设「停拍只有几十毫秒」。2026-11-14 实车日志推翻了
         * 这个假设：泵线程单次停摆 1400ms，代码却只补 22ms、把剩下 1378ms **整段扔掉**，
         * 顺带把时间原点挪到眼前。听感就是每次停摆「断一下 + 音乐跳一截」，而且
         * 音频相对视频持续超前、越积越多（样本率 -0.09% → -4.8% → -14%）。
         *
         * 8 → **32**（2026-11-17）：8 帧在 1024 样本/帧下只有 186ms，而实车停拍是
         * 100~309ms 量级（`AUDIO: 停拍 309ms —— park 309`），超过上限的那部分当场
         * 永久丢掉。1.2.63 那份日志里表现得很干净：混音器每 30 秒产 1292 帧，车机只收到
         * 1273~1284 包，交付率 -0.16% → -1.49% 一路走低，累计到一分钟就是百分之几的
         * 欠账——车机缓冲只出不进，迟早哑掉。
         *
         * 32 帧 = 743ms/拍：一次 300ms 停摆一拍就追平。单拍突发 32 个 RTP 包（约 24KB）
         * 在车机热点直连的链路上是毫秒级的事，比「丢一段音频」划算得多。
         */
        const val MAX_CATCHUP_FRAMES = 32

        /**
         * 落后超过这么久才真的放弃追赶、重排时间原点。
         *
         * 2 秒以内都算「能被调度打断的停摆」，必须追回来。只有进程被冻结、系统休眠
         * 这种量级才会超过它——那时硬推两千多帧也没有意义。
         */
        const val RESYNC_AFTER_MILLIS = 2_000L

        /**
         * 软限幅的拐点：−0.9 dBFS（32767 × 10^(−0.9/20) ≈ 29491）。
         *
         * 低于它的样本**逐位原样通过**（不做任何增益、不引入量化误差），保证绝大多数
         * 正常电平的音乐听感与改动前完全一致。
         */
        const val SOFT_LIMIT_KNEE = 29_491

        /** 拐点到满幅的余量（3276）。 */
        private const val SOFT_LIMIT_ROOM = Short.MAX_VALUE - SOFT_LIMIT_KNEE

        /**
         * 软限幅：把超出 [SOFT_LIMIT_KNEE] 的部分沿 `tanh` 曲线压向满幅（**渐近，永不触顶**）。
         *
         * `f(d) = ROOM · tanh(d / ROOM)`，其中 `d = |x| − KNEE`。它满足：
         *  - `f(0) = 0`、`f'(0) = 1` → 拐点处斜率连续，没有折点；
         *  - `f(∞) → ROOM` 但**严格小于** ROOM → 输出永远 ≤ 32766，不会出现任何平顶。
         *
         * 为什么必须渐近而不能截断：第一版用的是「三次曲线 + 到 ROOM 为止」，结果输入
         * 一旦超过满幅（音乐 + 播报两路满幅相加很常见）就全部被压到 32767，反而造出一段
         * 15 个样本的平顶——和原来的硬 clamp 在听感上没区别。tanh 让越界的部分继续单调
         * 变化，波形被压扁但不被切平，谐波只来自「压缩」而不是「方波边沿」。
         *
         * 代价：满幅输入实际输出 31986（−0.21 dB），这点峰值损失不可闻，换掉的是削波破音。
         */
        fun softLimit(value: Int): Int {
            val magnitude = if (value < 0) -value else value
            if (magnitude <= SOFT_LIMIT_KNEE) return value
            val d = (magnitude - SOFT_LIMIT_KNEE).toDouble() / SOFT_LIMIT_ROOM
            val shaped = SOFT_LIMIT_KNEE + (SOFT_LIMIT_ROOM * tanh(d)).toInt()
            return if (value < 0) -shaped else shaped
        }
    }

    /** 一帧的 PCM 字节数（16bit 交织）。 */
    val frameBytes: Int = frameSamples * channels * 2

    /** [RESYNC_AFTER_MILLIS] 换算成帧数（跟着采样率与帧长走，不写死 272）。 */
    private val resyncThresholdFrames =
        (RESYNC_AFTER_MILLIS * sampleRate / 1000 / frameSamples).toInt()

    private val bytesPerSample = channels * 2

    /** 媒体路目标水位换算成字节（按 [bytesPerSample] 对齐，见 [Ring.trimTo]）。 */
    private val mediaTargetBytes =
        (sampleRate.toLong() * mediaTargetMillis / 1000).toInt() * bytesPerSample

    private val media = Ring((sampleRate.toLong() * capacityMillis / 1000).toInt() * bytesPerSample)
    private val nav = Ring((sampleRate.toLong() * capacityMillis / 1000).toInt() * bytesPerSample)

    private val duckHoldNanos = duckHoldMillis * 1_000_000L

    /** 泵的时间原点与已产帧数（定拍基准）。 */
    private var originNanos = 0L
    private var emittedFrames = 0L

    /**
     * 「外部已交付帧数」的基准（见 [dueFrames] 的 `sentFrames` 参数）。
     *
     * 只有收了外部计数才有意义：外部计数是**RTP 时间轴**（真正进了包的样本数），
     * 它和 [emittedFrames]（混出来多少帧）会分叉——编码器偶发不吐帧、或者一帧吐出
     * 两包，都会让两者差几帧。以 RTP 时间轴为准才不会把差额累积成「越放越少」。
     * 重排原点时同步刷新基准，免得重排后又追一笔已经放弃的旧账。
     */
    private var sentBaseline = 0L

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

    /**
     * 本次窗口内两次产出之间**最长的一次间隔**（纳秒）。
     *
     * 定拍泵正常应该每 7.33ms 醒一次（一帧 352 样本 @48k）；出现 50ms 以上就说明
     * 泵线程被调度/GC 拖住了
     * （或者发生了一次「落后太多直接重新对齐」）。接收端只能按到达时刻排播，
     * 泵晚到 100ms 就是它那边一个 100ms 的空洞——2026-11-14 对 WinPlay 的
     * `pumpGapMs` 就是看这个数。窗口内取最大值，供 `AUDIO: 混音体检` 打出。
     */
    @Volatile
    var windowMaxGapNanos = 0L
        private set

    /** 上一次产出帧的时刻（泵线程私有，用于算 [windowMaxGapNanos]）。 */
    private var lastFrameAtNanos = 0L

    /**
     * 本次窗口内输出样本的**峰值**（绝对值，0..32767）。
     *
     * 用途：判断「音质」问题的方向。峰值长期顶在 32767 说明送进来的音乐本身就是
     * 满幅（流行乐常见的响度战争母带），此时任何混音/编码链都在削波边缘工作，
     * 该做的是让用户把手机媒体音量降 2~3 格，而不是在链路上找补。
     */
    @Volatile
    var windowPeak = 0
        private set

    /**
     * 本次窗口内**被软限幅折回**的样本数（占总样本数的百分比见体检行）。
     *
     * 完全不触发 = 软限幅没有白白压电平；持续 5% 以上 = 两路相加确实在顶满幅，
     * 这时才需要用户降音量（或我们把 duck 做得更深）。
     */
    @Volatile
    var windowLimitedSamples = 0L
        private set

    /**
     * 本次窗口内 [dueFrames] 算出的**最大落后帧数**。
     *
     * 正常是 1（一帧 7.33ms）。停在 100 以上就是一次秒级的调度停摆——比
     * [windowMaxGapNanos] 更进一步：那个只说「晚了多久」，这个说「当时欠了多少帧、
     * 现在能不能追回来」。
     */
    @Volatile
    var windowMaxDueFrames = 0L
        private set

    /** 本窗口内因为落后超过 [RESYNC_AFTER_MILLIS] 而重排时间原点的次数。 */
    @Volatile
    var windowResyncs = 0L
        private set

    /**
     * 本窗口内媒体路的**峰值水位**（毫秒）——就是「音频延迟」的直接读数。
     *
     * 正常应该稳定在 [mediaTargetMillis] 附近；接近 [capacityMillis] 说明水位控制没
     * 起作用（或 target 被调得太大），此时用户听到的是「声音比画面慢半拍」。
     */
    @Volatile
    var windowMediaWaterMaxMillis = 0L
        private set

    /** 本窗口内播报路的峰值水位（毫秒）。顶到容量说明 TTS 在积压，播报会越说越晚。 */
    @Volatile
    var windowNavWaterMaxMillis = 0L
        private set

    /** 本窗口内**为了压延迟主动裁掉**的媒体字节数（不是溢出丢弃，是水位控制）。 */
    @Volatile
    var windowMediaTrimBytes = 0L
        private set

    /** 累计重排次数（跨窗口）。>0 说明真的丢掉过一段时间轴。 */
    @Volatile
    var resyncCount = 0L
        private set

    /** 累计「追赶补回来的帧数」= 原本会被扔掉、现在被补发出去的时间。 */
    @Volatile
    var catchUpFrames = 0L
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
     *
     * **落后时不丢时间轴**：只有超过 [RESYNC_AFTER_MILLIS] 才重排原点，其余情况一律
     * 用「多发几帧」把积压追回来（缺样本自动补零 = 静音）。改这个行为的原因见
     * [MAX_CATCHUP_FRAMES] 的注释。
     *
     * @param sentFrames 调用方**真正发出去的帧数**（RTP 时间戳 ÷ 每包样本数）。
     *   传了它就以它为准——这是 2026-11-17 的核心修正：混音器「混出来 1292 帧」不等于
     *   「发出去 1292 帧」，编码器偶尔不吐帧（`payloads.isEmpty()`）时那些样本根本没进
     *   RTP 时间轴，而内部计数器却已经往前走。两边差几帧看着无害，30 秒一窗口差 19 帧
     *   就是 -1.5%，一分钟车机缓冲就被掏空。缺口必须**由我们自己补回来**。
     *   传 -1（默认）= 退化成内部计数（单测与 PCM 采集通路用）。
     */
    fun dueFrames(
        nowNanos: Long,
        sentFrames: Long = -1L,
    ): Int {
        if (originNanos == 0L) {
            originNanos = nowNanos
            emittedFrames = 0L
            sentBaseline = if (sentFrames >= 0) sentFrames else 0L
            return 1
        }
        val elapsedNanos = nowNanos - originNanos
        if (elapsedNanos <= 0) return 0
        // 注意除以 frameSamples：这是「帧数」不是「样本数」，漏除会把 21ms 当成 1 秒，
        // 每拍都判定「落后太多」→ 重新对齐 → 实际只产出 1 帧/拍（速率崩掉）。
        val elapsedFrames = elapsedNanos * sampleRate / 1_000_000_000L / frameSamples
        val accounted = if (sentFrames >= 0) sentFrames - sentBaseline else emittedFrames
        val due = elapsedFrames - accounted
        if (due <= 0) return 0
        if (due > windowMaxDueFrames) windowMaxDueFrames = due
        if (due > resyncThresholdFrames) {
            // 落后超过 2 秒：追不回来了（进程冻结 / 系统休眠）。这是**唯一**允许丢掉
            // 一段时间轴的分支，RTP 时间戳从眼前继续走。
            originNanos = nowNanos
            emittedFrames = 0L
            if (sentFrames >= 0) sentBaseline = sentFrames
            resyncCount++
            windowResyncs++
            return 1
        }
        // 落后但追得回来：这一拍多发几帧消化积压，**不动时间原点**。
        // 积压里没样本的部分由 mixNextFrame 按 0 计（补静音），车机看到的是连续流，
        // 不会因为空洞把 jitter buffer 排空再重缓冲。
        val take = if (due > MAX_CATCHUP_FRAMES) MAX_CATCHUP_FRAMES else due.toInt()
        if (take > 1) catchUpFrames += take - 1
        return take
    }

    /**
     * 当前「时间轴落后」多少毫秒（正数 = 我们送得比墙钟慢，车机那边正在被掏空）。
     *
     * 体检行用它给出**瞬时**欠账；[windowMaxDueFrames] 给的是窗口内的峰值。
     */
    fun lagMillis(
        nowNanos: Long,
        sentFrames: Long = -1L,
    ): Long {
        if (originNanos == 0L) return 0L
        val elapsedNanos = nowNanos - originNanos
        if (elapsedNanos <= 0) return 0L
        val elapsedFrames = elapsedNanos * sampleRate / 1_000_000_000L / frameSamples
        val accounted = if (sentFrames >= 0) sentFrames - sentBaseline else emittedFrames
        return (elapsedFrames - accounted) * frameSamples * 1000L / sampleRate
    }

    /** 距离下一帧还有多久（纳秒，已钳到 [1ms, 20ms]，供泵线程 park）。 */
    fun nanosToNextFrame(
        nowNanos: Long,
        sentFrames: Long = -1L,
    ): Long {
        if (originNanos == 0L) return 0L
        val accounted = if (sentFrames >= 0) sentFrames - sentBaseline else emittedFrames
        val nextFrameAt =
            originNanos + (accounted + 1) * frameSamples * 1_000_000_000L / sampleRate
        return (nextFrameAt - nowNanos).coerceIn(1_000_000L, 20_000_000L)
    }

    /** 环形缓冲里的字节数换算成「多少毫秒的音频」（诊断用）。 */
    private fun waterMillisOf(bytes: Int): Long =
        if (bytes <= 0) {
            0L
        } else {
            bytes.toLong() * 1000L / (bytesPerSample.toLong() * sampleRate)
        }

    /**
     * 产出一帧到 [out]（长度必须 ≥ [frameBytes]）。两路相加，超出 −0.9dBFS 的部分软限幅。
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
        // 窗口统计用局部变量累加，帧末写一次（避免逐样本 volatile 写）
        var peak = windowPeak
        var limited = 0L
        // 锁两个环形缓冲（顺序固定：media → nav，写侧只锁自己那一个，不会成环）。
        // 锁粒度是「一整帧」（352 样本 ≈ 7.33ms，实际执行是几十微秒），争用可以忽略。
        synchronized(media) {
            synchronized(nav) {
                // 水位控制（1.2.59）：读样本**之前**把媒体积压裁到目标水位。
                // 放在这里而不是喂入侧，是因为「该不该丢」取决于消费端此刻的真实进度；
                // 放在读之前而不是之后，是因为这一帧马上要读的就是最旧的那批样本。
                val trimmed = media.trimTo(mediaTargetBytes, bytesPerSample)
                if (trimmed > 0) windowMediaTrimBytes += trimmed.toLong()
                val mediaWaterMs = waterMillisOf(media.size)
                if (mediaWaterMs > windowMediaWaterMaxMillis) windowMediaWaterMaxMillis = mediaWaterMs
                val navWaterMs = waterMillisOf(nav.size)
                if (navWaterMs > windowNavWaterMaxMillis) windowNavWaterMaxMillis = navWaterMs
                hasMedia = media.size >= bytesPerSample
                hasNav = nav.size >= bytesPerSample
                navActive =
                    hasNav || (lastNavFeedNanos != 0L && nowNanos - lastNavFeedNanos <= duckHoldNanos)
                val targetQ = if (navActive) duckGainQ else GAIN_UNITY_Q
                val startQ = gainQ
                val stepQ = targetQ - startQ
                var offset = 0
                for (frame in 0 until frameSamples) {
                    // 一帧之内线性走完增益变化（≈7.33ms 斜坡）：够平滑，又不会漏掉下一个字
                    val gain = if (stepQ == 0) startQ else startQ + stepQ * frame / frameSamples
                    for (channel in 0 until channels) {
                        val music = media.readShort()
                        val voice = nav.readShort()
                        val mixed = ((music * gain) shr 10) + voice
                        // 软限幅而不是硬 clamp（2026-11-16）：
                        // 音乐 0dBFS 的流行乐叠上导航播报，和值经常越过满幅。原来的
                        // `coerceIn(-32768, 32767)` 直接把波形切成平顶——平顶的垂直边沿
                        // 等价于往信号里注入大量奇次谐波，听感就是「播报一响，音乐破一下」。
                        // 现在 −0.9dBFS 以下逐位原样通过，超出部分沿三次曲线平滑折回满幅。
                        val clamped = softLimit(mixed)
                        if (clamped != mixed) limited++
                        val magnitude = if (clamped < 0) -clamped else clamped
                        if (magnitude > peak) peak = magnitude
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
        windowPeak = peak
        windowLimitedSamples += limited
        if (lastFrameAtNanos != 0L) {
            val gap = nowNanos - lastFrameAtNanos
            if (gap > windowMaxGapNanos) windowMaxGapNanos = gap
        }
        lastFrameAtNanos = nowNanos
        if (hasMedia) windowMediaFrames++
        if (hasNav) windowNavFrames++
        if (navActive) duckedFrames++
    }

    /** 清零窗口统计（30 秒体检读完就调）。 */
    fun resetWindow() {
        windowFrames = 0
        windowMediaFrames = 0
        windowNavFrames = 0
        windowMaxGapNanos = 0
        windowMaxDueFrames = 0
        windowResyncs = 0
        windowMediaWaterMaxMillis = 0
        windowNavWaterMaxMillis = 0
        windowMediaTrimBytes = 0
        windowPeak = 0
        windowLimitedSamples = 0
        // 不重置 lastFrameAtNanos：跨窗口的那一刻不该被算成「停拍」
    }

    /**
     * 只清空某一路尚未混出的样本（车机暂停/静音、格式切换时），另一路与定拍时钟不受影响。
     * 与 [mixNextFrame] 共用环形缓冲的锁，泵线程正在混的那一帧结束后才生效。
     */
    fun clear(source: AudioSource) {
        when (source) {
            AudioSource.MEDIA -> media.clear()
            AudioSource.NAV -> nav.clear()
        }
    }

    /** 新一轮会话开始时清干净：环形缓冲、相位、时间原点、增益。 */
    fun reset() {
        media.clear()
        nav.clear()
        originNanos = 0L
        emittedFrames = 0L
        sentBaseline = 0L
        gainQ = GAIN_UNITY_Q
        lastNavFeedNanos = 0L
        framesEmitted = 0L
        duckedFrames = 0L
        resyncCount = 0L
        catchUpFrames = 0L
        lastFrameAtNanos = 0L
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

        /**
         * 把水位压到 [keep] 字节以内，丢掉**最旧的**部分；返回实际丢掉的字节数。
         *
         * [align] 是「一个完整样本帧」的字节数（16bit 立体声 = 4）。必须按它对齐：
         * 从半个样本或半个声道开始读，会让左右声道互换、把波形错位成噪声。
         *
         * 与 [write] 的溢出丢弃分开计数（[dropped] vs 调用方的 trim 统计）：
         * 溢出是「写得太快兜不住」，裁剪是「我们主动压延迟」，两者的处理方向不一样。
         */
        @Synchronized
        fun trimTo(
            keep: Int,
            align: Int,
        ): Int {
            if (keep <= 0) return 0
            val excess = size - keep
            if (excess <= 0) return 0
            val drop = if (align > 1) (excess / align) * align else excess
            if (drop <= 0) return 0
            tail = (tail + drop) % capacity
            size -= drop
            return drop
        }
    }
}
