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

    val playing: Boolean get() = sessionPlaying ?: wirePlaying
    val canForwardPcm: Boolean get() = sessionPlaying != false && !sourcePaused

    fun onSessionPlayback(playing: Boolean?) {
        // 只有从已知暂停态切换到播放态才代表用户恢复播放；重复上报的 PLAYING 快照不覆盖刚发生的 PAUSE。
        if (playing == true && lastKnownSessionPlaying == false) sourcePaused = false
        if (playing != null) lastKnownSessionPlaying = playing
        sessionPlaying = playing
        if (playing != null) {
            eligible = false
            if (!playing) {
                sourcePaused = true
                wirePlaying = false
            }
        }
    }

    fun onActivity(activity: CarLifeMediaActivity) {
        when (activity) {
            CarLifeMediaActivity.INITIALIZED -> {
                initialized = true
                pcmAfterInit = false
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
                wirePlaying = false
                if (focused == true) eligible = false
                pcmAfterInit = false
            }

            CarLifeMediaActivity.RESUME -> {
                if (sessionPlaying != false) sourcePaused = false
                eligible = false
                wirePlaying = sessionPlaying != false
            }

            CarLifeMediaActivity.FOCUS_GAINED -> {
                if (focused != true) {
                    eligible = true
                    pcmAfterInit = false
                    if (sessionPlaying != false) sourcePaused = false
                }
                focused = true
            }

            CarLifeMediaActivity.FOCUS_RELEASED -> {
                sourcePaused = true
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
        wirePlaying = false
        eligible = false
        pcmAfterInit = false
    }

    /** 车机解除静音触发恢复：允许后续媒体 PCM 立即放行（即便第三方播放器不补发 0x30004）。 */
    fun onCarResume() {
        if (sessionPlaying == false) return
        sourcePaused = false
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
        wirePlaying = false
        transportChanged()
    }
}
