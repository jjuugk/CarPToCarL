package io.github.shaonialife.carptocar.protocol

/**
 * 模块间可交换的能力。每个模块声明自己 provides（产出）与 consumes（消费）哪些能力，
 * [ProtocolRegistry] 据此接线——模块之间不直接 import 对方。
 *
 * 能力命名刻意用「功能」而不是「协议消息号」：CarLife 的语音键是 0x68008、
 * CarPlay 的语音键走 iAP2 事件通道，两条链路报文完全不同，但对聚合层来说都是
 * [VOICE_KEY] 这一种交换。后续接第三种协议时只需声明它能提供/消费哪些能力。
 */
enum class Capability {
    /** H.264 画面码流（CarLife 产出，CarPlay 推流消费）。 */
    VIDEO_SOURCE,

    /** PCM 音频（CarLife/系统媒体产出，CarPlay 音频通道消费）。 */
    AUDIO_SOURCE,

    /** 逐行歌词（CarLife 产出，iAP2 NowPlaying 消费）。 */
    MEDIA_LYRICS,

    /** 歌名/歌手/封面/播放状态（系统媒体会话产出，车机卡消费）。 */
    MEDIA_INFO,

    /** 媒体键：下一曲/上一曲/播放暂停（车机产出，CarLife 消费）。 */
    MEDIA_KEY,

    /** 语音键（车机事件通道产出，CarLife 消费）。 */
    VOICE_KEY,

    /** 车机 GPS 定位（车机产出，CarLife 0x18010 消费）。 */
    GPS,

    /**
     * 源端播放状态（媒体/播报是否在响）。
     *
     * 与 [AUDIO_SOURCE] 的区别：后者是「有声音频数据」，前者是「要不要为它保持一条流」。
     * 汇侧收到后做的是资源调和（开/关音频流、切换双流），不是收样本。
     */
    PLAYBACK_INTENT,

    /** 触摸/旋钮（车机 HID 产出，CarLife 9340 消费）。 */
    TOUCH,
}
