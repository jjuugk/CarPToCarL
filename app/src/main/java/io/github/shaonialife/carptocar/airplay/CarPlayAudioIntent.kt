package io.github.shaonialife.carptocar.airplay

/**
 * 源端播放意图（跨会话保留）：决定何时向车机申请主音频，以及车机接管/静音后是否暂停、恢复本机媒体。
 *
 * 车机接管或静音借走主音频后，这次媒体播放即作废：只有新的播放指令才会再去申请主音频，
 * 源端状态重放不会被当成新的播放而反抢车机音源。
 */
internal class CarPlayAudioIntent {
    enum class MediaCommand {
        NONE,
        PAUSE,
        RESUME,
    }

    @get:Synchronized
    var media = false
        private set

    @get:Synchronized
    var navigation = false
        private set

    private var pausedByCarAudioBorrow = false

    /** [newMediaIntent] = 显式播放指令：布尔值没翻转也算一次新的播放。 */
    @Synchronized
    fun setPlayback(
        media: Boolean,
        navigation: Boolean,
        newMediaIntent: Boolean = false,
    ) {
        if (media != this.media || newMediaIntent) {
            this.media = media
            if (media) pausedByCarAudioBorrow = false
        }
        this.navigation = navigation
    }

    /** 新一轮会话：上一轮的静音记忆不能带过来（否则新会话里一次归还就误启动音乐）。 */
    @Synchronized
    fun reset(mediaPlaying: Boolean) {
        pausedByCarAudioBorrow = false
        media = mediaPlaying
        navigation = false
    }

    /**
     * - 永久接管（切到原车 FM）：暂停媒体，之后解除静音也不自动恢复；
     * - 受限借用（静音键/原车通话）：记住借用前在播，暂停媒体；重复的借用通知不抹掉这条记忆；
     * - 归还手机：仅当借用前确由本机在播时恢复。
     */
    @Synchronized
    fun onCarAudioTransfer(
        transfer: AirPlayResourceState.CarAudioTransfer,
        currentlyPlaying: Boolean = false,
    ): MediaCommand {
        val wasPlaying = media || currentlyPlaying
        return when (transfer) {
            AirPlayResourceState.CarAudioTransfer.TAKEN_PERMANENTLY,
            AirPlayResourceState.CarAudioTransfer.BORROWED_CONSTRAINED,
            -> {
                pausedByCarAudioBorrow =
                    transfer == AirPlayResourceState.CarAudioTransfer.BORROWED_CONSTRAINED &&
                    (wasPlaying || pausedByCarAudioBorrow)
                if (!wasPlaying) return MediaCommand.NONE
                media = false
                MediaCommand.PAUSE
            }

            AirPlayResourceState.CarAudioTransfer.UNBORROWED_TO_PHONE -> {
                if (!pausedByCarAudioBorrow) return MediaCommand.NONE
                pausedByCarAudioBorrow = false
                media = true
                MediaCommand.RESUME
            }
        }
    }
}
