package io.github.shaonialife.carptocar.airplay

/**
 * AirPlay 资源归属（`modesChanged` / `changeModes`）状态机。
 *
 * ## 为什么要有这个文件
 *
 * 旧实现里 `modesChanged` 是一段**写死的常量**：主屏（resourceID=1）与主音频（2）都是
 * `entity=1, permanentEntity=1`，即「手机持有、且永久持有」。车机发来 `changeModes`
 * 时（真车会发——奥迪 2026-11-09 日志 09:38/09:42/09:43 共 7 次，另有一次 `requestUI`）
 * 我们**无条件**再发一遍这段常量，等于对每一句「我要接管」「我要归还」「我借一下」
 * 都回答「屏永远是我的」。
 *
 * 这不是「奥迪能忍就没事」：modes 是车机与手机之间的**仲裁契约**，答非所问的车机会
 * 判定接收端不配合。link_plus（同源 cardeck 分支，别克可连）在这里是完整状态机，
 * 它的注释点得很直接——「车机接管或归还资源时只应答实际归属，**不把所有请求解释成
 * 手机接管**」。
 *
 * ## 语义来源
 *
 * - 初始归属：`/info.modes.initialPermanentEntity[].permanentEntity`。**车机说了算**，
 *   我们不再假设自己是主人；
 * - 主屏/主音频：`resourceID` 1 / 2；
 * - 归属方：`permanentEntity` 1=手机、2=车机；`entity` = 当前归属，有借用者时是 2；
 * - 车机侧转移（`changeModes.params.resources[].transferType`）：
 *   `1` 接管、`2` 归还、`3` 开始借用（带 `borrowID`）、`4` 结束借用。
 *   **未知取值不改变任何状态**——猜错方向比不动更糟。
 *
 * ## 保守兜底（重要）
 *
 * `/info` 没给 `modes` 时（奥迪 3232 的日志里就没看到该字段），[initialize] 保持
 * 「手机持有主屏 + 主音频」，与改动前的常量**逐字节一致**。也就是说：这条改动只会在
 * 「车机明确表达过归属」的车机上生效，对奥迪现有行为零影响。
 */
class AirPlayResourceState {
    private class Resource(
        var permanentEntity: Long = ENTITY_PHONE,
        var takeConstraint: Int? = null,
        val borrowers: MutableSet<String> = linkedSetOf(),
    ) {
        /** 有借用者时当前归属是车机（2），否则跟随永久归属。 */
        val entity: Long get() = if (borrowers.isEmpty()) permanentEntity else ENTITY_CAR
    }

    private val lock = Any()

    private val resources = linkedMapOf(RESOURCE_SCREEN to Resource(), RESOURCE_AUDIO to Resource())

    /** appStateID → entity。1 是带 `speechMode` 的那一条（语音/电话），2、3 是其余应用态。 */
    private val appStates = linkedMapOf(2 to 0L, 3 to 0L, 1 to 0L)

    private var speechMode = -1L

    /** 主屏当前是否归手机。旧行为（车机没声明）下恒为 true。 */
    val phoneOwnsScreen: Boolean get() = synchronized(lock) { resources.getValue(RESOURCE_SCREEN).entity == ENTITY_PHONE }

    /** 主音频当前是否归手机。 */
    val phoneOwnsAudio: Boolean get() = synchronized(lock) { resources.getValue(RESOURCE_AUDIO).entity == ENTITY_PHONE }

    /**
     * 每条控制连接独立初始化：先把状态复位成「手机持有」，再采纳 `/info` 的初始归属。
     *
     * 复位是必须的——上一轮会话里车机借走的屏如果不复位，重连后会一直认为「屏在车机手里」，
     * 而新会话的车机其实什么都没借。
     */
    fun initialize(modes: Map<*, *>?) {
        synchronized(lock) {
            resources.values.forEach {
                it.permanentEntity = ENTITY_PHONE
                it.takeConstraint = null
                it.borrowers.clear()
            }
            appStates.keys.forEach { appStates[it] = 0L }
            speechMode = -1L
            // `modes` 本身就是一份 initialPermanentEntity 形态的快照（字段名与事件里的
            // `params` 同构），所以直接复用同一套解析。
            applyCarChangeLocked(modes)
        }
    }

    /**
     * 启动阶段只申请**主音频**。
     *
     * 主屏不在这里抢：真 iPhone 是先按车机给的归属发布，再单独用 [requestPhoneScreen]
     * 把屏要回来。一上来就宣称「永久持有主屏」，在不打算让出屏的车机上就是硬抢。
     */
    fun claimAudio() {
        synchronized(lock) {
            resources.getValue(RESOURCE_AUDIO).permanentEntity = ENTITY_PHONE
        }
    }

    /**
     * 请求把主屏交回手机（对应车机的 `requestUI`，以及启动阶段的第二步）。
     *
     * 只在「没有借用者、且接管约束允许」时才改永久归属——车机正借着的屏，
     * 我们声明成自己的会让它下次归还时报错。
     */
    fun requestPhoneScreen() {
        synchronized(lock) {
            val screen = resources.getValue(RESOURCE_SCREEN)
            val allowed = screen.takeConstraint.let { it == null || it == 100 || it == 500 }
            if (screen.borrowers.isEmpty() && allowed) {
                screen.permanentEntity = ENTITY_PHONE
            }
        }
    }

    /**
     * 退回改动前的行为：主屏与主音频都声明为手机永久持有。
     *
     * 只在一个地方用——车机**拒绝**我们按它给的归属复述时。那时说明这套仲裁在它身上
     * 不成立，与其继续按状态机推演，不如回到 1.2.34 那种「硬声明」，保证最坏情况不退化。
     */
    fun claimAll() {
        synchronized(lock) {
            resources.values.forEach {
                it.permanentEntity = ENTITY_PHONE
                it.takeConstraint = null
                it.borrowers.clear()
            }
        }
    }

    /**
     * 采纳车机的转移语义（`changeModes.params`，以及 `/info.modes` 的初始快照）。
     *
     * 未知 `transferType` 一律忽略：宁可维持旧归属，也不要猜错方向。
     */
    fun applyCarChange(params: Map<*, *>?) {
        synchronized(lock) { applyCarChangeLocked(params) }
    }

    /**
     * 组装一条 `modesChanged` 请求体。
     *
     * 字段与顺序都沿用改动前的常量（appStates 在前、appStateID 升序固定为 2/3/1），
     * 只有取值来自状态机——这样车机侧看到的报文形态没有变化，变的只是内容。
     */
    fun command(): Map<String, Any?> =
        synchronized(lock) {
            linkedMapOf(
                "type" to "modesChanged",
                "params" to
                    linkedMapOf(
                        "appStates" to
                            appStates.map { (id, entity) ->
                                linkedMapOf<String, Any?>("appStateID" to id.toLong(), "entity" to entity).apply {
                                    if (id == 1) put("speechMode", speechMode)
                                }
                            },
                        "resources" to
                            resources.map { (id, resource) ->
                                linkedMapOf<String, Any?>(
                                    "resourceID" to id.toLong(),
                                    "entity" to resource.entity,
                                    "permanentEntity" to resource.permanentEntity,
                                )
                            },
                    ),
            )
        }

    /** 一行诊断：车机到底把哪块资源拿走了，日志里要能一眼看出来。 */
    fun describe(): String =
        synchronized(lock) {
            val screen = resources.getValue(RESOURCE_SCREEN)
            val audio = resources.getValue(RESOURCE_AUDIO)
            "主屏=${ownerName(screen)} 主音频=${ownerName(audio)}" +
                " speechMode=$speechMode appStates=${appStates.entries.joinToString(",") { "${it.key}:${it.value}" }}"
        }

    private fun ownerName(resource: Resource): String {
        val base = if (resource.entity == ENTITY_PHONE) "手机" else "车机"
        val borrow = if (resource.borrowers.isEmpty()) "" else "（借用 ${resource.borrowers.size} 个）"
        val permanent = if (resource.permanentEntity == ENTITY_PHONE) "永久=手机" else "永久=车机"
        return "$base$borrow/$permanent"
    }

    private fun applyCarChangeLocked(params: Map<*, *>?) {
        (params?.get("resources") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = (item["resourceID"] as? Number)?.toInt() ?: return@forEach
            val resource = resources[id] ?: return@forEach
            val transfer = (item["transferType"] as? Number)?.toInt()
            when (transfer) {
                // 1 接管：车机成为永久归属方，并可能带一个接管约束
                1 -> {
                    resource.permanentEntity = ENTITY_CAR
                    resource.takeConstraint = (item["takeConstraint"] as? Number)?.toInt()
                }
                // 2 归还：永久归属还给手机
                2 -> {
                    resource.permanentEntity = ENTITY_PHONE
                    resource.takeConstraint = null
                }
                // 3 开始借用：只是临时占用，永久归属不变
                3 -> resource.borrowers.add(item["borrowID"]?.toString().orEmpty())
                // 4 结束借用
                4 -> resource.borrowers.remove(item["borrowID"]?.toString().orEmpty())
                // 没有 transferType = `/info` 的初始快照形态，直接读声明字段。
                // 有 transferType 时不看这些字段：两套语义混在一起时以转移指令为准。
                null -> {
                    (item["permanentEntity"] as? Number)?.toLong()?.takeIf { it in ENTITY_PHONE..ENTITY_CAR }?.let {
                        resource.permanentEntity = it
                    }
                    (item["takeConstraint"] as? Number)?.toInt()?.let { resource.takeConstraint = it }
                }
                // 未知 transferType：什么也不改（猜错方向比不动更糟）
            }
        }
        applyAppStatesLocked(params)
    }

    private fun applyAppStatesLocked(params: Map<*, *>?) {
        (params?.get("appStates") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = (item["appStateID"] as? Number)?.toInt() ?: return@forEach
            if (id !in appStates) return@forEach
            if (id == 1) {
                // 带 speechMode 的那条：-1 = 无语音，其余按「正在语音」记为车机侧（2）
                (item["speechMode"] as? Number)?.toLong()?.let {
                    speechMode = it
                    appStates[id] = if (it == -1L) 0L else ENTITY_CAR
                }
            } else {
                (item["state"] as? Boolean)?.let { appStates[id] = if (it) ENTITY_CAR else 0L }
            }
        }
    }

    companion object {
        /** resourceID：1 = 主屏，2 = 主音频。 */
        const val RESOURCE_SCREEN = 1
        const val RESOURCE_AUDIO = 2

        /** permanentEntity：1 = 手机，2 = 车机。 */
        const val ENTITY_PHONE = 1L
        const val ENTITY_CAR = 2L
    }
}
