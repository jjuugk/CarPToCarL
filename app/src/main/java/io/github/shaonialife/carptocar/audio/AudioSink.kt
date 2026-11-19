package io.github.shaonialife.carptocar.audio

/**
 * 音频（PCM）的落点契约。与 [io.github.shaonialife.carptocar.video.VideoSink] 对称。
 *
 * **为什么需要它**：`carlife` 包原先直接持有 [AudioSender] 具体类。CarLife 需要做的
 * 只有「把引擎的裸 PCM 交出去」，采样率/声道对齐、混音、RTP 封包都是发送端的事。
 * 抽成接口后源模块不再感知 [AudioSender] 的内部结构（混音器、编码器、环形缓冲）。
 *
 * 与视频侧不同的是：音频有**两路独立源**（媒体 9240 / 播报 9241），
 * 由 [AudioSource] 区分——两路混成一堆字节就是「导航与音乐一起放时卡顿」的根因，
 * 所以这个参数必须显式传，接口不给默认值。
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
     * @param source 哪一路（媒体 / 播报），决定进哪个环形缓冲、用哪份重采样器
     */
    fun feedPcm(
        srcPcm: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
        source: AudioSource,
    )
}
