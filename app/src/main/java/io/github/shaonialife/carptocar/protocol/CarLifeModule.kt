package io.github.shaonialife.carptocar.protocol

/**
 * CarLife 协议门面。封装「本机 CarLife+ 引擎」这一路：它产出画面/音频/歌词，
 * 消费车机的媒体键、语音键、GPS、触摸。
 *
 * **纯委托，不持有协议状态**：所有真实动作由构造时注入的函数完成。
 * 阶段 4 接入时把 SessionController 里已有的 `CarLifeHijack.sendXxx`
 * / `dispatchMediaKey` 等函数传进来即可，门面代码不用改。这样：
 *  - 单测纯 JVM 可跑，不起蓝牙/AirPlay/CarLife 引擎；
 *  - 门面自身没有生命周期状态，重复 start/stop 不会累积副作用（幂等由委托方负责）。
 *
 * 现有真实入口：
 *  - `carlife/CarLifeHijack.prewarm()` / `.stop()` —— 守候与停守候
 *  - `CarLifeHijack.sendMediaKey/sendVoiceKey/sendCarGps` —— 控制下发
 */
class CarLifeModule(
    private val starter: () -> Boolean = { true },
    private val stopper: () -> Unit = {},
    private val onMediaKey: (keyCode: Int) -> Boolean = { false },
    private val onVoiceKey: (keyCode: Int, longPress: Boolean) -> Boolean = { _, _ -> false },
    private val onGps: (latitude: Double, longitude: Double, bearing: Float?, speed: Float?) -> Boolean = { _, _, _, _ -> false },
    private val onTouch: (down: Boolean, x: Int, y: Int, pointerId: Int) -> Boolean = { _, _, _, _ -> false },
) : ProtocolModule {
    override val id: ProtocolId = ProtocolId.CARLIFE

    override fun provides(): Set<Capability> =
        setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE, Capability.MEDIA_LYRICS)

    override fun consumes(): Set<Capability> =
        setOf(Capability.MEDIA_KEY, Capability.VOICE_KEY, Capability.GPS, Capability.TOUCH)

    override fun start(): Boolean = starter()

    override fun stop() = stopper()

    override fun onEvent(event: ProtocolEvent): Boolean =
        when (event) {
            is ProtocolEvent.MediaKey -> onMediaKey(event.keyCode)
            is ProtocolEvent.VoiceKey -> onVoiceKey(event.keyCode, event.longPress)
            is ProtocolEvent.Gps -> onGps(event.latitude, event.longitude, event.bearing, event.speed)
            is ProtocolEvent.Touch -> onTouch(event.down, event.x, event.y, event.pointerId)
            is ProtocolEvent.MediaInfo,
            is ProtocolEvent.Lyrics,
            -> false // CarLife 是这两类的产出方，不消费
        }
}
