package io.github.shaonialife.carptocar.audio

/**
 * 音频（PCM）的落点契约。与 [io.github.shaonialife.carptocar.video.VideoSink] 对称。
 *
 * **为什么需要它**：`carlife` 包原先直接持有 [AudioSender] 具体类。CarLife 需要做的
 * 只有「把引擎的裸 PCM 交出去」，采样率/声道对齐、RTP 封包都是发送端的事。
 * 抽成接口后源模块不再感知 [AudioSender] 的内部结构。
 *
 * 1.2.72 起不再区分「媒体 / 播报」：两条音源各有一个 sink、各绑一条独立的 AirPlay
 * 音频流（媒体 = 协商出的档位，导航 = **101 AltAudio / default**），发送端不需要知道
 * 手里这一路是谁——混音器删掉之后，`source` 参数没有意义了。
 */
interface AudioSink {
    /** 发送端是否在运行。false 时源应丢弃（与「未开启声音则丢弃」的既有语义一致）。 */
    val isRunning: Boolean

    /**
     * 投递一块 PCM。
     *
     * @param srcPcm 16bit 小端交织的裸 PCM
     * @param size 有效字节数
     * @param srcRate 输入采样率（<=0 表示未宣告，发送端按目标率处理、不重采样）
     * @param srcChannels 输入声道数（1 或 2，其它按 2 处理）
     */
    fun feedPcm(
        srcPcm: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    )
}
