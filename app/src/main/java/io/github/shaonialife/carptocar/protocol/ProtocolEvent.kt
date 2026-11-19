package io.github.shaonialife.carptocar.protocol

/**
 * 跨模块事件。所有事件都带 [from]（产出方）与 [capability]（路由键），
 * [ProtocolRegistry] 只靠这两项决定投递给谁——不认识任何具体协议类。
 *
 * 事件体刻意只放**可序列化的值类型**，不放 AudioSender/VideoSender 之类的活对象：
 * 这样单测能在纯 JVM 上路由事件，不用起蓝牙/AirPlay。
 */
sealed class ProtocolEvent {
    abstract val from: ProtocolId
    abstract val capability: Capability

    /** 车机媒体键：下一曲/上一曲/播放暂停。 */
    data class MediaKey(
        val keyCode: Int,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.MEDIA_KEY
    }

    /** 车机语音键。longPress 为真时表示长按收尾。 */
    data class VoiceKey(
        val keyCode: Int,
        val longPress: Boolean = false,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.VOICE_KEY
    }

    /** 车机 GPS 定位，转发给 CarLife 0x18010。 */
    data class Gps(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float? = null,
        val speed: Float? = null,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.GPS
    }

    /** 歌名/歌手/封面/播放状态，来自系统媒体会话或 CarLife。 */
    data class MediaInfo(
        val title: String?,
        val artist: String?,
        val album: String? = null,
        val playing: Boolean = false,
        val progressMs: Long? = null,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.MEDIA_INFO
    }

    /** 触摸/旋钮输入。坐标以车机视图像素为单位。 */
    data class Touch(
        val action: Int,
        val x: Int,
        val y: Int,
        val pointerId: Int = 0,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.TOUCH
    }
}
