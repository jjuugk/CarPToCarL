package io.github.shaonialife.carptocar.carlife

/** CarLife 控制通道与媒体通道的可观测事件（不按静音时长或电平猜测播放意图）。 */
enum class CarLifeMediaActivity {
    INITIALIZED,
    PCM,
    STOP,
    PAUSE,
    RESUME,
    FOCUS_GAINED,
    FOCUS_RELEASED,
    TRANSPORT_CLOSED,
}

/**
 * 关联 CarLife 控制通道（模块焦点）与媒体通道（9240 包序列）为单一媒体播放状态机。
 *
 * CarLife+ 8.8.5 在建链期会主动下发 `INIT / STOP / INIT` 并在获得音乐焦点时报 `module 3 = 1`；
 * 重采样推流路径只发 `INIT` + `PCM`，未必伴随 `RESUME (0x30004)`。因此单凭 `INIT`、模块焦点或
 * 孤立的残留 `PCM` 都不能单独构成新的抢音源意图，三者同时就位才算一轮有效起播。
 *
 * 同一轮焦点周期只授权一次：当车机主动切走音源（如切到 FM 收音机）后，格式重建、残留 PCM 或
 * 通道重连不会把音源抢回来；只有焦点重新释放再获得、显式 `RESUME`、系统媒体会话由暂停转播放，
 * 或车机静音解除触发的显式恢复（[onCarResume]）才开启下一轮播放。
 */
internal class CarLifeMediaPlayback {
    private var focused: Boolean? = null
    private var eligible = true
    private var initialized = false
    private var pcmAfterInit = false
    private var wirePlaying = false
    private var sessionPlaying: Boolean? = null
    private var lastKnownSessionPlaying: Boolean? = null
    private var sourcePaused = false

    /**
     * 「暂停」是不是**车机侧**造成的（接管/静音借用主音频、音乐焦点被释放）。
     *
     * 必须和引擎侧暂停分开：车机把音源切走（例如切到 FM 收音机）后，CarLife 引擎仍在
     * 后台推 PCM，此时**不能**因为看到 INIT+PCM 就把音源抢回来；而引擎自己起播/重建编码器
     * 只发 `INIT + PCM`、从不补 `0x30004 RESUME`（2026-11-16 实测），这种必须能自动恢复。
     * 两者的区分信号就是这里：焦点释放 / 车机接管 → true，其余 → false。
     */
    private var pausedByCar = false

    val playing: Boolean get() = sessionPlaying ?: wirePlaying

    /**
     * PCM 门禁**只认引擎/车机信号**，不看手机媒体会话（2026-11-23 改）。
     *
     * 旧实现是 `sessionPlaying != false && !sourcePaused`，等于把「手机上的播放器状态」
     * 当成了「引擎音频该不该发」的判据。而 [MediaSessionsState] 的 `playing` 包含两类
     * 与音频无关的抖动：
     *  - **切歌**：旧会话结束、新会话建立之间必然有一段 `false`；
     *  - **监听器未绑定时的轮询兜底**：每秒枚举一次会话，状态更容易来回跳。
     *
     * 1.2.81 现场（BMW MGU-81，2026-11-23 07:58）就是被这两类抖动反复触发：
     * `一念之间 true → false` 相隔 217ms、`有理想 true`、再 `暂停 10.6 秒`……85 秒的
     * 音频流里约 50 秒在丢，车机只收到 37.5 秒音频（44%），听感就是全程无声。
     *
     * 现在只留 [sourcePaused]——它由引擎自己的 `0x30003 PAUSE` / `FOCUS_RELEASED` /
     * `STOP` 与车机侧 `onCarPause()` 驱动。手机真暂停时引擎要么发 PAUSE，要么干脆
     * 不推 PCM（没有残留可丢），两条路都不需要媒体会话参与。
     */
    val canForwardPcm: Boolean get() = !sourcePaused

    /**
     * 当前这次拦截是否「只能等车机还回来」（车机侧暂停）。
     *
     * 供 [CarLifeAudioSink] 决定要不要套用「15 秒后自动放行」的窗口：车机切走音源时
     * 必须一直拦住，不能到点自己把声音推回去。
     */
    val pauseHeldByCar: Boolean get() = pausedByCar && sourcePaused

    fun onSessionPlayback(playing: Boolean?) {
        // 只有从已知暂停态切换到播放态才代表用户恢复播放；重复上报的 PLAYING 快照不覆盖刚发生的 PAUSE。
        val resumed = playing == true && lastKnownSessionPlaying == false
        if (resumed) {
            sourcePaused = false
            pausedByCar = false
        }
        if (playing != null) lastKnownSessionPlaying = playing
        sessionPlaying = playing
        if (playing != null) {
            // 用户明确点播放（暂停→播放）是最强的起播意图，直接恢复本轮授权；
            // 引擎随后推的 PCM 不必再等一次 FOCUS_GAINED（2026-11-16：引擎起播时
            // 先发 PAUSE/STOP 再 INIT，焦点事件一次都没有）。
            eligible = resumed
            if (!playing) {
                // **不置 sourcePaused**（2026-11-23）：媒体会话的 false 只说明「手机上那个
                // 播放器当前不是播放态」，切歌、监听器回退轮询都会短暂报 false，而引擎
                // 仍在按实时推 PCM。旧实现把它当暂停，直接触发 15 秒丢包窗口并
                // clearPendingPcm()，实测吃掉了一半以上的音频（见 [canForwardPcm]）。
                // 真正的暂停由引擎的 PAUSE/FOCUS_RELEASED 与车机侧 onCarPause() 表达。
                wirePlaying = false
            }
        }
    }

    fun onActivity(activity: CarLifeMediaActivity) {
        when (activity) {
            CarLifeMediaActivity.INITIALIZED -> {
                initialized = true
                pcmAfterInit = false
                // 引擎起播/重建编码器的标准序列是 PAUSE(0x30003) → STOP → INIT → PCM，
                // **从不补 0x30004 RESUME**（2026-11-16 08:43:16 实测）。以前 PAUSE 在
                // focused=true 时把 eligible 锁成 false，后面这串 INIT+PCM 一次都授权不了，
                // 于是 mediaPlayback.canForwardPcm 永久为 false、引擎的 PCM 被全部丢弃
                // ——车机侧表现就是「放着放着突然没声音，之后再也不响」。
                // 车机侧真正接管走 onCarPause（资源层 transfer / 静音键），那种仍要等它还回来，
                // 不受这里影响。FOCUS_RELEASED 只是引擎放开了音乐焦点（切歌必然发生），
                // 语义上不等于「车机拿走了」：按 15 秒窗口常规自恢复，别把 PCM 门禁挂成无限期。
                if (focused == true && !pausedByCar) {
                    sourcePaused = false
                    eligible = true
                }
            }

            CarLifeMediaActivity.PCM ->
                if (initialized) {
                    pcmAfterInit = true
                }

            CarLifeMediaActivity.STOP -> {
                // STOP 也出现在切歌/换采样率边界：保持已授权焦点周期不断，但清空通道内缓冲计数。
                initialized = false
                pcmAfterInit = false
            }

            CarLifeMediaActivity.PAUSE -> {
                sourcePaused = true
                pausedByCar = false
                wirePlaying = false
                if (focused == true) eligible = false
                pcmAfterInit = false
            }

            CarLifeMediaActivity.RESUME -> {
                // 引擎显式 `0x30004` 是最高优先级的「我在播」信号：直接放行 PCM。
                // 旧实现加了 `if (sessionPlaying != false)` 前置条件——媒体会话恰好
                // 还没刷新（切歌/轮询兜底）时，RESUME 等于没生效。
                sourcePaused = false
                pausedByCar = false
                eligible = false
                wirePlaying = sessionPlaying != false
            }

            CarLifeMediaActivity.FOCUS_GAINED -> {
                if (focused != true) {
                    eligible = true
                    pcmAfterInit = false
                    // 焦点回到引擎手里就是「它可以出声」：不再要求媒体会话也报 playing。
                    sourcePaused = false
                }
                focused = true
                pausedByCar = false
            }

            CarLifeMediaActivity.FOCUS_RELEASED -> {
                sourcePaused = true
                // 切歌必然伴随一次 FOCUS_RELEASED。以前这里置 pausedByCar=true，
                // sink 门禁 indefinite → 只能等车机按播放键才解除；用户在手机上切歌时
                // 车机根本没参与，于是「切歌后永久静音」。改为只标记 sourcePaused，
                // 门禁按 15 秒窗口自动放行；真正的车机接管仍走 onCarPause()。
                pausedByCar = false
                if (focused != false) {
                    eligible = true
                    pcmAfterInit = false
                }
                focused = false
                wirePlaying = false
            }

            CarLifeMediaActivity.TRANSPORT_CLOSED -> transportChanged()
        }
        if (eligible && focused == true && initialized && pcmAfterInit) {
            eligible = false
            wirePlaying = sessionPlaying != false
        }
    }

    /** 车机侧抢占/静音触发暂停：立即阻断残留媒体 PCM 并退出播放态。 */
    fun onCarPause() {
        sourcePaused = true
        pausedByCar = true
        wirePlaying = false
        eligible = false
        pcmAfterInit = false
    }

    /** 车机解除静音触发恢复：允许后续媒体 PCM 立即放行（即便第三方播放器不补发 0x30004）。 */
    fun onCarResume() {
        // 车机把音频还回来，比任何媒体会话状态都权威。旧实现有
        // `if (sessionPlaying == false) return`：手机上那个播放器恰好报 false 时，
        // 车机解了静音、我们却不放行，表现为「静音键按回来还是没声」。
        sourcePaused = false
        pausedByCar = false
        eligible = false
        wirePlaying = true
    }

    /** 仅替换底层传输层证据，不重置已消费的焦点授权周期。 */
    fun transportChanged() {
        initialized = false
        pcmAfterInit = false
    }

    fun reset() {
        focused = null
        eligible = true
        sessionPlaying = null
        lastKnownSessionPlaying = null
        sourcePaused = false
        pausedByCar = false
        wirePlaying = false
        transportChanged()
    }
}
