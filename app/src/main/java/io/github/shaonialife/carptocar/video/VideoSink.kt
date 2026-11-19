package io.github.shaonialife.carptocar.video

/**
 * 画面码流的落点契约。
 *
 * **为什么需要它**：`carlife` 包原先直接持有 [VideoSender] 具体类，于是「CarLife 劫持」
 * 这条链在编译期就绑死了 AirPlay 的视频发送实现——而 CarLife 真正需要的只有「把 H.264
 * 帧交出去」这一件事。抽成接口后：
 *
 *  - 源模块（carlife）只依赖能力，不依赖实现，也不感知 [VideoSender] 构造参数里的
 *    加密方式 / Timing / VideoConfig 声明；
 *  - 将来第三种协议要接管画面，实现本接口即可，不必改 carlife 一行；
 *  - 单测可以用假 sink 验证帧泵，不必起 socket。
 *
 * **方法刻意保持极小**：只保留源真正调用的四个。加方法前先分辨「这是源的职责、
 * 还是发送端的内部实现」——后者不该出现在这里，否则接口会退化成另一个 VideoSender。
 */
interface VideoSink {
    /**
     * 发送端是否在运行。源据此决定「缓存还是直送」：false 时缓存等重连，
     * 直接丢弃会让车机重连后拿不到参考帧。
     */
    val isRunning: Boolean

    /**
     * 预置 SPS/PPS。在首帧之前调用，兜底「首帧不带参数集」的情况；
     * 当期帧自带 SPS 时会被覆盖，所以先塞不会污染。
     */
    fun seedCsd(annexB: ByteArray)

    /**
     * 投递一帧 Annex-B 字节流。
     *
     * @param data 一帧（可含多 NAL）
     * @param keyFrame 是否关键帧（引擎标记，可能与实际 NAL 类型不一致，发送端会自行校验）
     * @param sourceTimestampMs 帧头里的毫秒时间戳，仅用于算排队延迟；取不到传 null。
     *   **缓存回放不要传值**——那批帧本来就旧，会被误判成已经在排队。
     * @return true = 已写出；false = 未运行 / 端口不可用 / 等关键帧中丢弃
     */
    fun submitH264(
        data: ByteArray,
        keyFrame: Boolean,
        sourceTimestampMs: Long?,
    ): Boolean

    /**
     * 源重建（引擎重连 / 换分辨率）时通知汇：清配置态、丢旧 IDR、在新 IDR 到来前拒收 P 帧。
     *
     * 语义是「同一条流、同一个 key，只是内容换了代」，所以实现**不应该**重置帧计数器——
     * 归零等于 nonce 复用，车机侧 AEAD 校验必然失败。细节见实现类同名方法。
     */
    fun onSourceRestart(reason: String)

    /** 帧写入阻塞时的观测上报（只记录，不处置）。由停摆看门狗周期性调用。 */
    fun noteWriteStall()
}
