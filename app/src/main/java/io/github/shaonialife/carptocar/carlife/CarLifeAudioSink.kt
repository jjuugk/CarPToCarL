package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.audio.AudioSender
import io.github.shaonialife.carptocar.audio.AudioSource

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
 * 前提：AirPlay 音频通道已协商成功（[SessionController.audioSender] 正在直通模式运行），
 * 否则直接丢弃（与旧实现的「未开启声音则丢弃」语义一致，由 [CarLifeHijack.routeAudio] 判断）。
 */
internal class CarLifeAudioSink(
    private val log: (String) -> Unit,
    /**
     * 是否参与「音乐会话暂停 → 丢弃残留 PCM」的联动。
     *
     * 媒体通道（9240）用 true：音乐 App 暂停后引擎仍会把排队 PCM 推完，不丢会重复播放。
     * **TTS 通道（9241）必须用 false**：导航播报恰恰多发生在没放音乐 / 音乐被暂停的时候，
     * 一旦套用这条联动，整段播报会被当成「残留」吃掉——这是「开导航没声音」的另一半原因
     * （2026-10-27 日志：10:35:04 刚触发暂停丢弃，10:35:14 的导航 TTS 就来了）。
     */
    private val dropWhenPaused: Boolean = true,
    /**
     * 这条 sink 对应哪一路：媒体（9240）还是播报（9241）。
     *
     * 发送端按它把 PCM 写进各自的环形缓冲、各自重采样，再定拍混音。**必须区分**：
     * 两路混成一堆字节首尾相接（旧实现）就是「导航与音乐一起放时卡顿」的根因。
     */
    private val source: AudioSource = AudioSource.MEDIA,
) {
    /** CarLife 引擎宣告的源采样率（CarlifeMusicInit；<=0 表示尚未宣告，按 48k 处理）。 */
    @Volatile
    private var srcRate = 0

    /** CarLife 引擎宣告的源声道数（1 或 2）。 */
    @Volatile
    private var srcChannels = 2

    private var packets = 0L
    private var bytes = 0L
    private var lastReportAt = 0L

    /** 因「音乐会话暂停」被丢弃的包数（诊断用：导航播报被误判暂停时能一眼看出来）。 */
    private var droppedWhenPaused = 0L
    private var lastDropReportAt = 0L

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

    /** 「超时放行」只记一次，避免每秒刷屏。 */
    private var pauseWindowExpiredLogged = false

    /**
     * 外部联动开关：音乐会话暂停时置 true（丢弃残留 PCM），恢复播放时置 false。
     */
    fun setPlaybackPaused(paused: Boolean) {
        val changed = playbackPaused != paused
        playbackPaused = paused
        if (!paused) {
            pausedSince = 0L
            pauseWindowExpiredLogged = false
            if (changed) log("CARLIFE: 音乐会话恢复——停止丢弃引擎 PCM")
            return
        }
        if (changed || pausedSince == 0L) {
            pausedSince = android.os.SystemClock.uptimeMillis()
            pauseWindowExpiredLogged = false
            log(
                "CARLIFE: 音乐会话暂停——丢弃引擎残留 PCM（避免重复播放，" +
                    "${PAUSE_DROP_WINDOW_MS / 1000} 秒后自动恢复放行）",
            )
        }
    }

    /** 当前这包 PCM 是否该按「暂停残留」丢掉（只在暂停后的一小段窗口内）。 */
    private fun shouldDropPaused(now: Long): Boolean {
        if (!playbackPaused) return false
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
    ) {
        if (size <= 0) return
        // 音乐会话暂停后的一小段窗口内：丢弃引擎仍在推的残留 PCM（避免重复播放）
        val now = android.os.SystemClock.uptimeMillis()
        if (dropWhenPaused && shouldDropPaused(now)) {
            countDroppedWhenPaused()
            return
        }
        if (srcRate <= 0) {
            configure(
                if (sampleRate > 0) sampleRate else DEFAULT_RATE,
                if (channels > 0) channels else 2,
            )
        }
        val sender = io.github.shaonialife.carptocar.SessionController.audioSender
        if (sender == null || !sender.isRunning) return
        // 直通：交给 AudioSender 重采样 + 写入该源的环形缓冲（混音与发送在泵线程）
        sender.feedPcm(pcm, size, srcRate, srcChannels, source)
        sampleLevel(pcm, size)
        packets++
        bytes += size
        reportIfDue()
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

    @Synchronized
    fun stop() {
        srcRate = 0
        srcChannels = 2
        packets = 0
        bytes = 0
        lastReportAt = 0
        droppedWhenPaused = 0
        lastDropReportAt = 0
        pauseWindowExpiredLogged = false
        pausedSince = 0L
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
    private fun countDroppedWhenPaused() {
        droppedWhenPaused++
        val now = android.os.SystemClock.uptimeMillis()
        if (lastDropReportAt == 0L) {
            lastDropReportAt = now
            return
        }
        if (now - lastDropReportAt < DROP_REPORT_INTERVAL_MS) return
        log("CARLIFE: 暂停期间丢弃 ${droppedWhenPaused} 包 PCM（若此时本应有导航/媒体声音，说明会话状态被误判）")
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

        /** 电平统计的「非零」门限（约 -66dBFS，低于它的样本算静音底噪）。 */
        const val SILENCE_FLOOR = 16
    }
}

/** 音频包类型 → 可读名（日志用）。 */
internal fun carLifeAudioTypeName(type: Int): String =
    when (type) {
        CarLifeProtocol.MUSIC_PKG_INIT -> "INIT"
        CarLifeProtocol.MUSIC_PKG_START -> "开始"
        CarLifeProtocol.MUSIC_PKG_STOP -> "停止"
        CarLifeProtocol.MUSIC_PKG_NORMAL -> "PCM"
        CarLifeProtocol.TTS_PKG_INIT -> "TTS-INIT"
        0x40002 -> "TTS-开始"
        0x40003 -> "TTS-PCM"
        else -> "0x%X".format(type)
    }
