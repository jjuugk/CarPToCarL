package io.github.shaonialife.carptocar.protocol

/**
 * CarPlay 协议门面。封装「手机冒充 iPhone 连车机」这一路（iAP2 + AirPlay + 蓝牙/WiFi），
 * 它产出车机侧的控制事件（媒体键、语音键、GPS、触摸、媒体信息），
 * 消费画面/音频/歌词并把它们推上车机。
 *
 * **纯委托设计同 [CarLifeModule]**：不 import 任何 iap2/airplay/bt/net 类型，
 * 真实动作全部由构造时注入。阶段 4 接入时把 SessionController 里已有的
 * 端点/串流处理函数接进来即可。
 *
 * 现有真实入口（接线时参考，不在本类出现）：
 *  - `bt/Iap2BluetoothServer` + `Iap2BluetoothClient` —— 建 iAP2 链路
 *  - `airplay/AirPlayClient` —— AirPlay 会话
 *  - `video/VideoSender` + `audio/AudioSender` —— 媒体面 sink
 */
class CarPlayModule(
    private val starter: () -> Boolean = { true },
    private val stopper: () -> Unit = {},
    private val onVideoSource: (Boolean) -> Boolean = { false },
    private val onAudioSource: (Boolean) -> Boolean = { false },
    private val onLyrics: (List<String>, Int) -> Boolean = { _, _ -> false },
) : ProtocolModule {
    override val id: ProtocolId = ProtocolId.CARPLAY

    override fun provides(): Set<Capability> =
        setOf(
            Capability.MEDIA_KEY,
            Capability.VOICE_KEY,
            Capability.GPS,
            Capability.TOUCH,
            Capability.MEDIA_INFO,
        )

    override fun consumes(): Set<Capability> =
        setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE, Capability.MEDIA_LYRICS)

    override fun start(): Boolean = starter()

    override fun stop() = stopper()

    override fun onEvent(event: ProtocolEvent): Boolean =
        when (event) {
            is ProtocolEvent.Lyrics -> onLyrics(event.lines, event.lineIndex)
            is ProtocolEvent.MediaInfo -> false // 车机卡是产出/显示方，CarPlay 不回头消费
            is ProtocolEvent.MediaKey,
            is ProtocolEvent.VoiceKey,
            is ProtocolEvent.Gps,
            is ProtocolEvent.Touch,
            -> false // CarPlay 是这些的产出方，不消费
        }

    /**
     * 数据面 sink 绑定：阶段 4 由 SessionController 把 VideoSender/AudioSender
     * 挂进来时调用。这里只把「有源/无源」的信号转给注入的回调，不碰任何发送端实例。
     */
    fun onSourceAvailable(
        capability: Capability,
        available: Boolean,
    ): Boolean =
        when (capability) {
            Capability.VIDEO_SOURCE -> onVideoSource(available)
            Capability.AUDIO_SOURCE -> onAudioSource(available)
            else -> false
        }
}
