package io.github.shaonialife.carptocar.protocol

/**
 * 事件投递方式。三种方式对应项目里真实存在的三类交换：
 *
 * - [BIND]：数据流配对（一个源 → 一个汇）。CarLife 的画面/音频固定投给 CarPlay 推流，
 *   不存在「多个消费者竞争」的问题；声明了多个消费者视为配置错误，只取第一个。
 * - [FIRST_MATCH]：控制竞争。车机媒体键先问 CarLife 能不能吃，吃不下再落系统媒体键兜底，
 *   任何一个消费者返回 true 就短路。
 * - [ALL]：状态广播。媒体信息变化要让车机卡、歌词、进度各自刷新，互不短路。
 */
enum class Delivery {
    BIND,
    FIRST_MATCH,
    ALL,
}

/** 一次路由的结果。[handled] 为是否有人消费；FIRST_MATCH 下 [consumedBy] 至多一项。 */
data class RouteResult(
    val consumedBy: List<ProtocolModule>,
) {
    val handled: Boolean get() = consumedBy.isNotEmpty()
}

/**
 * 协议聚合层。全项目**唯一** import 具体协议实现的地方（构造 modules 列表时），
 * 运行期只按 [Capability] 接线，不认识任何协议内部类型。
 *
 * 线程安全：注册表用 synchronized 保护；事件投递先取消费者快照再在锁外调用，
 * 避免消费者的 onEvent 回调里再调 register/stopAll 时重入死锁。
 */
object ProtocolRegistry {
    private val lock = Any()
    private val modules = LinkedHashMap<ProtocolId, ProtocolModule>()
    private val startedIds = LinkedHashSet<ProtocolId>()

    /** 注册模块。同 id 重复注册直接覆盖（重启链路时门面会重建实例）。 */
    fun register(module: ProtocolModule) {
        synchronized(lock) { modules[module.id] = module }
    }

    fun unregister(id: ProtocolId) {
        synchronized(lock) { modules.remove(id) }
    }

    fun get(id: ProtocolId): ProtocolModule? = synchronized(lock) { modules[id] }

    fun all(): List<ProtocolModule> = synchronized(lock) { modules.values.toList() }

    /** 能产出该能力的模块（数据流源头）。 */
    fun providers(capability: Capability): List<ProtocolModule> =
        synchronized(lock) { modules.values.filter { capability in it.provides() } }

    /** 能消费该能力的模块（数据流汇、控制执行方）。按注册顺序，[FIRST_MATCH] 依赖此顺序。 */
    fun consumers(capability: Capability): List<ProtocolModule> =
        synchronized(lock) { modules.values.filter { capability in it.consumes() } }

    /**
     * 是否已有模块声明消费该能力。
     *
     * 调用方用它区分「聚合层已装配」与「尚未装配」两种运行态：
     * 已装配就完全交给 [route]，未装配才走本地原路径。两条路必须互斥，
     * 否则同一次控制事件会被消费两次。
     */
    fun hasConsumer(capability: Capability): Boolean =
        synchronized(lock) { modules.values.any { capability in it.consumes() } }

    /**
     * 按 [Delivery] 策略投递事件，返回真正消费它的模块列表。
     *
     * 永远跳过事件的产出方自己——避免 CarLife 发的事件又被 CarLife 自己吃回去形成回环。
     */
    fun route(
        event: ProtocolEvent,
        delivery: Delivery = Delivery.FIRST_MATCH,
    ): RouteResult {
        val candidates =
            consumers(event.capability)
                .filter { it.id != event.from }
        if (candidates.isEmpty()) return RouteResult(emptyList())

        return when (delivery) {
            Delivery.BIND -> {
                // 一个源对一个汇：只投第一个声明消费的模块，且把「已投递」算作消费
                // （数据流没有「返回 false 表示没处理」的语义，只有 sink 在不在）。
                val target = candidates.first()
                target.onEvent(event)
                RouteResult(listOf(target))
            }
            Delivery.FIRST_MATCH -> {
                val first = candidates.firstOrNull { it.onEvent(event) }
                RouteResult(listOfNotNull(first))
            }
            Delivery.ALL -> {
                // 广播：所有模块都收到，返回值只表示「是否成功更新」，
                // 不影响「已投递」这一事实——否则返回 false 的模块会被错误漏掉。
                candidates.forEach { it.onEvent(event) }
                RouteResult(candidates)
            }
        }
    }

    /** 依次启动全部已注册模块，返回成功启动的 id（失败的模块不阻断其它模块）。 */
    fun startAll(): List<ProtocolId> {
        val started =
            all().mapNotNull { module -> module.id.takeIf { module.start() } }
        synchronized(lock) { startedIds.addAll(started) }
        return started
    }

    /**
     * 逆序停止全部模块（后启动的先停，符合依赖方向）。
     *
     * 只 stop 上一次 [startAll] 中真正启动成功的模块：失败启动的模块没占用资源，
     * 重复 stop 会让门面的计数器虚高，也会让单测里的断言失真。可重入。
     */
    fun stopAll() {
        val running = synchronized(lock) { startedIds.toSet() }
        all()
            .asReversed()
            .filter { it.id in running }
            .forEach { it.stop() }
        synchronized(lock) { startedIds.clear() }
    }

    /** 仅单测用：清空注册表与启动记录，避免测试间互相污染。 */
    fun reset() {
        synchronized(lock) {
            modules.clear()
            startedIds.clear()
        }
    }
}
