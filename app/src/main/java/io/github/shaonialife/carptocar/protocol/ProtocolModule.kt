package io.github.shaonialife.carptocar.protocol

/**
 * 一条协议链路的门面。CarPlayModule / CarLifeModule 各自实现它，
 * [ProtocolRegistry] 只持有这个接口——新增协议不需要改 Registry 以外的地方。
 *
 * 阶段 1 只定义契约、不接真链路：`start/stop` 在门面实现里再落到具体的
 * Bluetooth/AirPlay/CarLifeHijack 实例。这里刻意不 import 任何 android.* 类型，
 * 让 Registry 能在纯 JVM 单测中验证路由逻辑。
 */
interface ProtocolModule {
    val id: ProtocolId

    /** 本模块产出哪些能力（数据流的源、或控制事件的发起方）。 */
    fun provides(): Set<Capability>

    /** 本模块消费哪些能力（数据流的汇、或控制事件的执行方）。 */
    fun consumes(): Set<Capability>

    /** 启动链路。返回 false 表示前置条件不满足（权限/蓝牙/目标未就绪）。 */
    fun start(): Boolean

    /** 停止链路并释放本模块持有的资源。必须可重入。 */
    fun stop()

    /**
     * 处理一个路由过来的事件。
     *
     * @return true = 已消费，[ProtocolRegistry] 对 FIRST_MATCH 投递立即短路；
     *         false = 没处理（例如 CarLife 劫持未启动），继续找下一个消费者或走兜底。
     */
    fun onEvent(event: ProtocolEvent): Boolean
}
