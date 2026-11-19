package io.github.shaonialife.carptocar.protocol

/**
 * 数据面契约：让「谁产出画面/音频」和「谁把它送上车机」解耦。
 *
 * 现在的直接依赖是 `CarLifeHijack → VideoSender/AudioSender`（carlife 包 import video/audio），
 * 以及 `AirPlayClient → VideoSender/AudioSender`（airplay 包 import video/audio）——
 * 两个协议模块都抓着同一批发送端实例，这是环的主要来源。
 *
 * 抽象后：发送端实现 [SinkSession]，源模块只持有接口。同一个 sink 可以被任一协议挂载，
 * 新增协议不再需要改 video/audio 的代码。
 */
interface SinkSession<in T> {
    /** 投递一帧/一块样本。返回 true = 已接收；false = 当前不可接收（如发送端未启动）。 */
    fun accept(sample: T): Boolean
}

/** 画面码流源。典型实现是 CarLife 劫持的 H.264 帧泵。 */
interface MediaSource<T> {
    fun attachSink(sink: SinkSession<T>)

    fun detachSink(sink: SinkSession<T>)
}

/**
 * 一个空实现，供阶段 1 的单测与后续门面占位使用。
 * 真链路接入后由 CarLifeModule / CarPlayModule 提供各自实现。
 */
class NoopMediaSource<T> : MediaSource<T> {
    override fun attachSink(sink: SinkSession<T>) = Unit

    override fun detachSink(sink: SinkSession<T>) = Unit
}
