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

    /**
     * 语音会话控制：CarPlay 侧判定完手势后，要求引擎起/停语音识别。
     *
     * [keyCode] 是**要下发给引擎的硬键码**（`VR_START` 起识别 / `VR_STOP` 收尾），
     * 不是车机原始按键动作——原始「按下/松开」的边沿判定留在 `VoiceKeyRouter` 里，
     * 它属于输入侧状态机，不该跨模块。这里只表达「判定完了，请引擎执行」。
     *
     * [longPress] 仅作诊断标记（PTT 收尾还是短按），消费方可以忽略。
     */
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

    /**
     * 触摸输入。坐标以车机视图像素为单位，[pointerId] 是触摸槽位（本机 HID 最多两槽）。
     *
     * 只有按下/抬起两态：车机触控报文是**状态式**的（按住期间持续推 down=true），
     * 没有独立的 MOVE 动作，中间位移由同槽位连续的 down 帧携带。
     */
    data class Touch(
        val down: Boolean,
        val x: Int,
        val y: Int,
        val pointerId: Int = 0,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.TOUCH
    }

    /** 逐行歌词，来自 CarLife 引擎（iAP2 没有歌词字段，靠顶替 title 上车机）。 */
    data class Lyrics(
        val lines: List<String>,
        val lineIndex: Int = 0,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.MEDIA_LYRICS
    }

    /**
     * 源端播放状态。与 [Capability.AUDIO_SOURCE] 的区别是「要不要为它保持一条流」，
     * 而不是「有没有样本」——汇侧收到后做资源调和，不接收数据。
     *
     * [newMediaIntent] 表示这是**新发起**的一次媒体播放（而不是同一段声音的持续状态），
     * 汇侧据此决定要不要重新协商音频流而不是沿用现有那条。
     */
    data class PlaybackIntent(
        val media: Boolean,
        val navigation: Boolean,
        val newMediaIntent: Boolean = false,
        override val from: ProtocolId,
    ) : ProtocolEvent() {
        override val capability: Capability = Capability.PLAYBACK_INTENT
    }
}
