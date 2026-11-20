package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.audio.AudioSink

/**
 * CarLife 音频桥接（手机 → 车机方向的**直通落地点**）。
 *
 * 早期实现是「本机播放 + 播放捕获」：把 CarLife 引擎的 PCM 用 `USAGE_MEDIA` 的
 * `AudioTrack` 在本机播出来，再让 [AudioSender] 的 `AudioPlaybackCapture` 采回去
 * 推给车机。代价是依赖 MediaProjection 录屏授权 + 一次系统内混音/重采样往返。
 *
 * 现在改为**直通**：直接把引擎的裸 PCM 喂给 [AudioSender.feedPcm]，由其内部完成
 * 采样率/声道对齐 + RTP/ChaCha20 封包发送。不依赖录屏授权、少一次中转。
 *
 * 两条音源各有一个实例、各绑一条 AirPlay 流：
 *  - 媒体（9240）→ [isNavigation] = false → `SessionController.audioSender`（协商档位）；
 *  - 导航播报（9241）→ [isNavigation] = true → `SessionController.navAudioSender`
 *    （固定 **101 AltAudio / default**，车机自己当提示音处理，不混音）。
 *
 * 前提：对应的 AirPlay 音频流已协商成功，否则直接丢弃（与旧实现的「未开启声音则丢弃」
 * 语义一致，由 [CarLifeHijack.routeAudio] 判断）。
 */
internal class CarLifeAudioSink(
    private val log: (String) -> Unit,
    /**
     * 是否参与「音乐会话暂停 → 丢弃残留 PCM」的联动。
     *
     * 媒体通道（9240）用 true：音乐 App 暂停后引擎仍会把排队 PCM 推完，不丢会重复播放。
     * **导航通道（9241）必须用 false**：导航播报恰恰多发生在没放音乐 / 音乐被暂停的时候，
     * 一旦套用这条联动，整段播报会被当成「残留」吃掉——这是「开导航没声音」的另一半原因
     * （2026-10-27 日志：10:35:04 刚触发暂停丢弃，10:35:14 的导航 TTS 就来了）。
     */
    private val dropWhenPaused: Boolean = true,
    /**
     * 这条 sink 是导航播报（9241）还是媒体（9240）。
     *
     * 只用来决定投给哪条 AirPlay 流：导航走独立的 101 AltAudio/default，媒体走协商档。
     * 发送端本身不知道这件事——混音器删掉之后，它只需要「谁给的 PCM 就发出去」。
     */
    private val isNavigation: Boolean = false,
    /**
     * 注入时钟，供单测推进「自动放行」窗口；生产用 [android.os.SystemClock.uptimeMillis]。
     */
    private val nowMs: () -> Long = { android.os.SystemClock.uptimeMillis() },
    /**
     * 本轮车机侧暂停是否允许「超时自恢复」：仅 BORROWED_CONSTRAINED（临时借用）为 true，
     * TAKEN_PERMANENTLY（切到原车 FM，永久接管）必须为 false——否则放行 PCM 会与车机
     * 原生音源双声。默认读资源层当前是否持有主音频；单测可注入。
     */
    private val carAudioAcquired: () -> Boolean = {
        io.github.shaonialife.carptocar.SessionController.currentClient?.phoneOwnsAudio == true
    },
) {
    /** CarLife 引擎宣告的源采样率（CarlifeMusicInit；<=0 表示尚未宣告，按 48k 处理）。 */
    @Volatile
    private var srcRate = 0

    /** 引擎宣告的源采样率（只读，供 UI 显示是否发生 44.1↔48 重采样）；0 = 尚未宣告。 */
    val sourceSampleRate: Int get() = srcRate

    /** CarLife 引擎宣告的源声道数（1 或 2）。 */
    @Volatile
    private var srcChannels = 2

    private var packets = 0L
    private var bytes = 0L
    private var lastReportAt = 0L

    /** 因门禁被丢弃的包数（诊断用：导航播报被误判暂停时能一眼看出来）。 */
    private var droppedWhenPaused = 0L
    private var lastDropReportAt = 0L

    /**
     * 最近一次丢弃来自哪道门禁。
     *
     * 以前两条丢弃路径（调用方 `forwardingAllowed=false` / 本类暂停窗口）共用同一句日志，
     * 2026-11-16 排查时因此把「路由层恒丢」误读成「状态机误判暂停」，多花了一轮。
     */
    private var lastDropReason = ""

    /**
     * 电平统计（跟着 5 秒那条「音频直通」日志一起报）。
     *
     * 为什么需要（2026-10-26）：接过微信电话后，RTP 照发、车机 `/feedback` 的 sampleTime
     * 按 1.00 倍实时推进（= 它在正常消费），但喇叭全静音。这时只有两种可能：
     * ① 引擎推的就是静音（通话把媒体音频路由带走了）；② 引擎推的是真声音、车机静音位。
     * 看 ppm 峰值/非零占比就能一眼区分，不必再猜。
     */
    private var peakSinceReport = 0

    private var nonZeroSinceReport = 0L

    private var sampledSinceReport = 0L

    /**
     * 播放状态联动（车机按暂停 → 手机音乐 App PAUSED → 这里截断）。
     *
     * 根因（2026-10-24 实测）：音乐 App 收到暂停后，CarLife 引擎（`com.baidu.carlife.honor`）
     * 的音频推流管道**不跟随停止**，继续把暂停前排队的 PCM 往车机送，表现为「重复播放」。
     * 所以 CarPToCarL 侧必须自己兜底：检测到会话 PAUSED 时主动丢弃引擎仍在推的残留 PCM。
     *
     * **但截断必须有时限**（2026-10-26 修）：残留队列排空只需要几秒，而「暂停」状态可能
     * 维持很久——连上车机时手机里放着一条暂停的音乐会话是极常见的情况，此时导航播报、
     * 提示音全都会被当成「残留」丢掉，表现正是「刚连上开导航没声音，放音乐才有声」。
     * 现在只在前 [PAUSE_DROP_WINDOW_MS] 内截断，之后恢复放行。
     */
    @Volatile
    private var playbackPaused = false

    /** 本轮「暂停」的起点（0 = 没在暂停）。 */
    @Volatile
    private var pausedSince = 0L

    /**
     * 这次暂停是不是**车机侧**造成的（它接管/静音借用了主音频，或把音乐焦点切走）。
     *
     * 车机侧暂停不吃 [PAUSE_DROP_WINDOW_MS] 的「到点自动放行」——它必须一直拦到车机
     * 明确把音频还回来，否则引擎的残留 PCM 会把车机的原生音源（FM/蓝牙）抢回去。
     * 引擎自己发的 `0x30003 PAUSE` 不算，那条仍按 15 秒窗口恢复（2026-11-16：
     * 引擎起播时先发 PAUSE+STOP 再 INIT，从不补 RESUME，按车机侧处理就是永久静音）。
     */
    @Volatile
    private var pauseIndefinite = false

    /**
     * 本次车机侧暂停是否允许「超时自恢复」（只有 BORROWED_CONSTRAINED 临时借用为 true）。
     *
     * 2026-11-20 修：车机静音键/原车通话借用主音频后，85ms 就把主音频还了回来
     * （`AUDIO-INTENT acquired=true 主音频=手机`），但**没有**发 `UNBORROWED_TO_PHONE`，
     * [resumeMediaFromCar] 那条解除路径永远等不到 → [pauseIndefinite] 永久为 true →
     * 引擎 PCM 被永久丢弃，表现为「暂停后再播一直没声」。这里给临时借用开一个
     * 自恢复出口：超过 [CAR_BORROW_RELEASE_MS] 且资源层确认已重新持有主音频时自动放行。
     * 永久接管（切原车 FM）保持 indefinite 语义不变，绝不自动放行，避免双声。
     */
    @Volatile
    private var pauseAutoRelease = false

    /** 「超时放行」只记一次，避免每秒刷屏。 */
    private var pauseWindowExpiredLogged = false

    /**
     * 外部联动开关：音乐会话暂停时置 true（丢弃残留 PCM），恢复播放时置 false。
     *
     * @param indefinite true = 车机侧暂停，一直拦到它主动还回来；false = 引擎/应用侧暂停，
     *   只拦 [PAUSE_DROP_WINDOW_MS]，之后自动放行（宁可重复播放几秒，也不要永久没声音）。
     * @param autoRelease true = 这是「临时借用」导致的 indefinite 暂停，超时且主音频归还后
     *   允许自恢复；对 TAKEN_PERMANENTLY 必须为 false。只在 [indefinite] 为 true 时有意义。
     */
    fun setPlaybackPaused(
        paused: Boolean,
        indefinite: Boolean = false,
        autoRelease: Boolean = false,
    ) {
        val changed = playbackPaused != paused
        if (changed && paused && dropWhenPaused) clearPendingPcm()
        playbackPaused = paused
        pauseIndefinite = paused && indefinite
        pauseAutoRelease = paused && indefinite && autoRelease
        if (!paused) {
            pausedSince = 0L
            pauseWindowExpiredLogged = false
            if (changed) log("CARLIFE: 音乐会话恢复——停止丢弃引擎 PCM")
            return
        }
        if (changed || pausedSince == 0L) {
            pausedSince = nowMs()
            pauseWindowExpiredLogged = false
            val suffix =
                if (pauseAutoRelease) {
                    "车机侧临时借用，${CAR_BORROW_RELEASE_MS / 1000} 秒后主音频归还即自动恢复"
                } else if (pauseIndefinite) {
                    "车机侧暂停，等它归还音频后才恢复"
                } else {
                    "${PAUSE_DROP_WINDOW_MS / 1000} 秒窗口，之后到达的 PCM 即放行"
                }
            log("CARLIFE: 音乐会话暂停——丢弃引擎残留 PCM（避免重复播放，$suffix）")
        }
    }

    /**
     * 当前这包 PCM 是否该按「暂停残留」丢掉。
     *
     * 车机侧永久暂停默认无限拦；仅当本次暂停被标记为「临时借用」（[pauseAutoRelease]）时，
     * 超时且资源层确认主音频已归还才自动解除——给一个永远不来的归还通知留自恢复出口。
     */
    internal fun shouldDropPaused(now: Long): Boolean {
        if (!playbackPaused) return false
        if (pauseIndefinite) {
            if (pauseAutoRelease) {
                val since = pausedSince
                if (since > 0L && now - since >= CAR_BORROW_RELEASE_MS && carAudioAcquired()) {
                    playbackPaused = false
                    pauseIndefinite = false
                    pauseAutoRelease = false
                    pausedSince = 0L
                    log(
                        "CARLIFE: 车机临时借用超时且主音频已归还——自动解除暂停门禁，恢复放行 PCM" +
                            "（此前一直在丢弃，若此时有声即为自恢复生效）",
                    )
                    return false
                }
            }
            return true
        }
        val since = pausedSince
        if (since == 0L) return true
        if (now - since < PAUSE_DROP_WINDOW_MS) return true
        if (!pauseWindowExpiredLogged) {
            pauseWindowExpiredLogged = true
            log(
                "CARLIFE: 暂停已超过 ${PAUSE_DROP_WINDOW_MS / 1000} 秒——恢复放行 PCM" +
                    "（残留队列早已排空，避免误丢导航播报/提示音）",
            )
        }
        return false
    }

    /** 手机宣告的音频格式（CarlifeMusicInit）。重复调用无副作用。 */
    @Synchronized
    fun configure(
        sampleRate: Int,
        channels: Int,
    ) {
        if (srcRate > 0 && (srcRate != sampleRate || srcChannels != channels)) clearPendingPcm()
        srcRate = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        srcChannels = if (channels == 1) 1 else 2
        log("CARLIFE: 音频格式 ${srcRate}Hz ${if (srcChannels == 2) "立体声" else "单声道"} 16bit（直通）")
    }

    /** 收到一包裸 PCM（16bit 交织），直通给正在运行的 AirPlay 音频发送端。 */
    @Synchronized
    fun write(
        sampleRate: Int,
        channels: Int,
        pcm: ByteArray,
        size: Int,
        forwardingAllowed: Boolean = true,
    ) {
        if (size <= 0) return
        require(size <= pcm.size)
        sampleLevel(pcm, size)
        packets++
        bytes += size
        reportIfDue()
        if (!forwardingAllowed) {
            countDroppedWhenPaused("调用方禁止转发（forwardingAllowed=false）")
            return
        }
        // 音乐会话暂停后的一小段窗口内：丢弃引擎仍在推的残留 PCM（避免重复播放）
        val now = nowMs()
        if (dropWhenPaused && shouldDropPaused(now)) {
            countDroppedWhenPaused(
                if (pauseIndefinite) "车机侧暂停（无限期，等它归还音频）" else "引擎侧暂停（15 秒窗口内）",
            )
            return
        }
        if (srcRate <= 0) {
            configure(
                if (sampleRate > 0) sampleRate else DEFAULT_RATE,
                if (channels > 0) channels else 2,
            )
        }
        // 导航播报走独立的 101 AltAudio/default（见 CarLifeAudioSink 类注释）：
        // 那条流真的在跑时，9241 的 PCM 直接投给它；不在跑就丢掉——绝不回落到媒体流，
        // 否则导航声音会和音乐混在同一条 RTP 里，又变回被删掉的那套混音。
        val target: AudioSink? =
            if (isNavigation) {
                io.github.shaonialife.carptocar.SessionController.navAudioSender?.takeIf { it.isRunning }
            } else {
                io.github.shaonialife.carptocar.SessionController.audioSender?.takeIf { it.isRunning }
            }
        if (target == null) return
        target.feedPcm(pcm, size, srcRate, srcChannels)
    }

    /** 抽样统计电平（每 16 个采样点取 1 个，开销可忽略）。 */
    private fun sampleLevel(
        pcm: ByteArray,
        size: Int,
    ) {
        var index = 0
        while (index + 1 < size) {
            val sample = (((pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)).toShort()).toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peakSinceReport) peakSinceReport = magnitude
            if (magnitude >= SILENCE_FLOOR) nonZeroSinceReport++
            sampledSinceReport++
            index += 32
        }
    }

    /** 暂停或 STOP 时仅清空本路尚未发出的 PCM（两条流各自独立，互不影响）。 */
    fun clearPendingPcm() {
        val sender =
            if (isNavigation) {
                io.github.shaonialife.carptocar.SessionController.navAudioSender
            } else {
                io.github.shaonialife.carptocar.SessionController.audioSender
            }
        sender?.clearPcm()
    }

    @Synchronized
    fun stop() {
        srcRate = 0
        srcChannels = 2
        packets = 0
        bytes = 0
        lastReportAt = 0
        droppedWhenPaused = 0
        lastDropReportAt = 0
        lastDropReason = ""
        pauseWindowExpiredLogged = false
        pauseIndefinite = false
        pauseAutoRelease = false
        pausedSince = 0L
        // 必须一起复位：只清 pausedSince 而留着 playbackPaused = true，会让
        // shouldDropPaused() 命中「since == 0 → 丢弃」那条早返回——永久丢包，
        // 而且既不打印「15 秒后放行」也不打印「会话暂停」，日志上完全看不出来。
        playbackPaused = false
        peakSinceReport = 0
        nonZeroSinceReport = 0
        sampledSinceReport = 0
    }

    /**
     * 「暂停中丢弃」的诊断计数。
     *
     * 触发条件只看媒体会话状态（`nowPlaying.playing == false`），而导航播报、系统提示音
     * 很多时候**不产生** PLAYING 会话——一旦被误判成暂停，音频就会被静默丢掉，
     * 表现为「刚连上开导航没声音，开始放音乐才有声」。这里每 5 秒记一条，
     * 下次导出日志就能直接确认是不是这条路径吃掉了声音。
     */
    private fun countDroppedWhenPaused(reason: String) {
        droppedWhenPaused++
        lastDropReason = reason
        val now = android.os.SystemClock.uptimeMillis()
        if (lastDropReportAt == 0L) {
            lastDropReportAt = now
            return
        }
        if (now - lastDropReportAt < DROP_REPORT_INTERVAL_MS) return
        log(
            "CARLIFE: 丢弃 ${droppedWhenPaused} 包 PCM（原因：$lastDropReason）" +
                "——若此时本应有声音，说明这道门禁误判",
        )
        droppedWhenPaused = 0
        lastDropReportAt = now
    }

    private fun reportIfDue() {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastReportAt == 0L) {
            lastReportAt = now
            return
        }
        if (now - lastReportAt < REPORT_INTERVAL_MS) return
        val seconds = (now - lastReportAt) / 1000.0
        val kbps = if (seconds > 0) (bytes * 8 / 1000.0 / seconds).toInt() else 0
        val peakPercent = peakSinceReport * 100 / 32768
        val nonZeroPercent = if (sampledSinceReport > 0) (nonZeroSinceReport * 100 / sampledSinceReport).toInt() else 0
        log(
            "CARLIFE: 音频直通 ${packets} 包 / ${bytes / 1024}KB / ${kbps}kbps" +
                " · 电平 ${peakPercent}%（非零 ${nonZeroPercent}%）",
        )
        lastReportAt = now
        packets = 0
        bytes = 0
        peakSinceReport = 0
        nonZeroSinceReport = 0
        sampledSinceReport = 0
    }

    private companion object {
        const val DEFAULT_RATE = 48_000
        const val MIN_RATE = 8_000
        const val MAX_RATE = 48_000
        const val REPORT_INTERVAL_MS = 5_000L

        /** 「暂停中丢弃」日志节流（30 秒一条，避免长时间暂停刷屏）。 */
        const val DROP_REPORT_INTERVAL_MS = 30_000L

        /**
         * 暂停后**只在这段窗口内**丢弃 PCM。
         *
         * 引擎暂停后残留的排队 PCM 只够排几秒；窗口设成 15 秒既能把重复播放彻底压住，
         * 又不会因为「手机里常驻一条暂停的音乐会话」把导航播报/提示音长期吃掉。
         */
        const val PAUSE_DROP_WINDOW_MS = 15_000L

        /**
         * 车机「临时借用」自恢复窗口（2 秒）：超过它且资源层确认主音频已归还即可自动解除暂停。
         * 2 秒足够覆盖「静音键 → 85ms 就还回来」的实测场景，又不会短到在车机还在借用时
         * 就把 PCM 放出去（那样会与车机原生音源双声）。
         */
        const val CAR_BORROW_RELEASE_MS = 2_000L

        /** 电平统计的「非零」门限（约 -66dBFS，低于它的样本算静音底噪）。 */
        const val SILENCE_FLOOR = 16
    }
}

/** 音频包类型 → 可读名（日志用）。 */
internal fun carLifeAudioTypeName(type: Int): String =
    when (type) {
        CarLifeProtocol.MUSIC_PKG_INIT -> "INIT"
        CarLifeProtocol.MUSIC_PKG_STOP -> "停止"
        CarLifeProtocol.MUSIC_PKG_PAUSE -> "暂停"
        CarLifeProtocol.MUSIC_PKG_RESUME -> "恢复"
        CarLifeProtocol.MUSIC_PKG_NORMAL -> "PCM"
        CarLifeProtocol.TTS_PKG_INIT -> "TTS-INIT"
        CarLifeProtocol.TTS_PKG_STOP -> "TTS-停止"
        CarLifeProtocol.TTS_PKG_NORMAL -> "TTS-PCM"
        else -> "0x%X".format(type)
    }
