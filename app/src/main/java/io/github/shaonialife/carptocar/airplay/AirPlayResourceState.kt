package io.github.shaonialife.carptocar.airplay

/**
 * 手机自己的**语音态**（AIS `Table 23-57 Speech mode enum values`）。
 *
 * 三个取值都要原样出现在 `modesChanged.appStates[appStateID=1].speechMode` 里：
 * `-1` = 没有语音相关活动、`1` = 正在对用户说话、`2` = 正在收音做语音识别。
 */
enum class AirPlaySpeechMode(
    val wireValue: Long,
) {
    NONE(-1L),
    SPEAKING(1L),
    RECOGNIZING(2L),
}

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

    /**
     * appStateID → entity（AIS `Table 23-56`）。
     *
     * 顺序沿用旧常量固定为 **2 / 3 / 1**（电话、导航、语音）：报文顺序不变，老车机就不必
     * 重新适应；1 是唯一带 `speechMode` 的那一条。
     */
    private val appStates =
        linkedMapOf(APP_STATE_PHONE_CALL to 0L, APP_STATE_TURN_BY_TURN to 0L, APP_STATE_SPEECH to 0L)

    private var speechMode = -1L

    /**
     * **我们自己**的语音态（`appStateID=1`）与通话态（`appStateID=2`）。
     *
     * 此前这两个值只从车机的 `changeModes` 抄来再回显，等于全程只报「车机在干什么」，
     * 从不报「手机在干什么」。AIS 23.3.4 明确要求**两边都要如实上报自己的状态**：
     * `Speech` 的语义是「某实体正在做语音操作，**收音期间所有音频输出必须关闭**，
     * 以免干扰识别算法」。车机只能按这条来决定「是不是该把自己的音源闭嘴」——
     * 我们不报，它就永远不会闭嘴，车内音源直接灌进手机麦克风。
     */
    private var ownSpeech = AirPlaySpeechMode.NONE

    /** 我们是否正在通话（`appStateID=2`，entity 报 Controller）。 */
    private var ownPhoneCall = false

    /**
     * 车机自己报的 `Speech` / `PhoneCall` 归属（0 = None、2 = Accessory）。
     *
     * 单独存一份是为了「我们说完/挂断之后还原成车机那条」：我们有状态时把
     * `appStates` 顶成 Controller，撤掉时得记得车机原来报的是什么，不能一律写 0。
     */
    private var carSpeechEntity = 0L
    private var carCallEntity = 0L

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
            // 自己这边的状态也复位：`initialize` 每条控制连接只跑一次（AirPlayClient 每轮
            // 新建），上一轮留下的「正在识别语音」若带过来，新车机会一直等我们闭嘴。
            ownSpeech = AirPlaySpeechMode.NONE
            ownPhoneCall = false
            carSpeechEntity = 0L
            carCallEntity = 0L
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
     * 报告**本机**语音态（`Speech` / `speechMode`）。
     *
     * @return 是否真的变了（没变就别再发一遍 `modesChanged`）
     */
    fun setOwnSpeech(mode: AirPlaySpeechMode): Boolean =
        synchronized(lock) {
            if (ownSpeech == mode) return false
            ownSpeech = mode
            // `Speech` 是「单实体」状态：我们报了自己，就不能再同时报车机。
            // 撤掉时还原成车机自己报的那条（不是一律 0——车机可能确实在语音态里）。
            appStates[APP_STATE_SPEECH] =
                if (mode == AirPlaySpeechMode.NONE) carSpeechEntity else ENTITY_PHONE
            true
        }

    /** 报告本机是否在通话（`appStateID=2`）。@return 是否真的变了 */
    fun setOwnPhoneCall(active: Boolean): Boolean =
        synchronized(lock) {
            if (ownPhoneCall == active) return false
            ownPhoneCall = active
            appStates[APP_STATE_PHONE_CALL] = if (active) ENTITY_PHONE else carCallEntity
            true
        }

    /** 本机语音态（诊断/断言用）。 */
    val ownSpeechMode: AirPlaySpeechMode get() = synchronized(lock) { ownSpeech }

    /** 本机是否在通话（诊断/断言用）。 */
    val ownPhoneCallActive: Boolean get() = synchronized(lock) { ownPhoneCall }

    /**
     * 组装一条 `modesChanged` 请求体。
     *
     * 字段与顺序都沿用改动前的常量（appStates 在前、appStateID 升序固定为 2/3/1），
     * 只有取值来自状态机——这样车机侧看到的报文形态没有变化，变的只是内容。
     *
     * **本机状态优先**：`Speech`（1）与 `PhoneCall`（2）在「我们自己有话说」时报
     * `entity=Controller`，否则才回显车机自己的那条。默认（本机既不说话也不通话）时
     * 报文与改动前逐字节一致，因此对现有车机零影响。
     */
    fun command(): Map<String, Any?> =
        synchronized(lock) {
            linkedMapOf(
                "type" to "modesChanged",
                "params" to
                    linkedMapOf(
                        "appStates" to
                            appStates.map { (id, entity) ->
                                val ownSpeechActive = ownSpeech != AirPlaySpeechMode.NONE
                                val claimed =
                                    when (id) {
                                        APP_STATE_SPEECH -> if (ownSpeechActive) ENTITY_PHONE else entity
                                        APP_STATE_PHONE_CALL -> if (ownPhoneCall) ENTITY_PHONE else entity
                                        else -> entity
                                    }
                                linkedMapOf<String, Any?>("appStateID" to id.toLong(), "entity" to claimed).apply {
                                    // Table 23-72：Speech / PhoneCall 必须带 speechMode。
                                    // 只在**本机主动报告**时补 2 那条的 speechMode（None），
                                    // 平时不多写字段，保持既有报文逐字节不变。
                                    when {
                                        id == APP_STATE_SPEECH ->
                                            put(
                                                "speechMode",
                                                if (ownSpeechActive) ownSpeech.wireValue else speechMode,
                                            )
                                        id == APP_STATE_PHONE_CALL && ownPhoneCall -> put("speechMode", -1L)
                                    }
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

    /** 一行诊断：车机到底把哪块资源拿走了、我们自己在报什么，日志里要能一眼看出来。 */
    fun describe(): String =
        synchronized(lock) {
            val screen = resources.getValue(RESOURCE_SCREEN)
            val audio = resources.getValue(RESOURCE_AUDIO)
            val own =
                "本机语音=${ownSpeech.name}" + if (ownPhoneCall) "/通话中" else ""
            "主屏=${ownerName(screen)} 主音频=${ownerName(audio)}" +
                " $own speechMode=$speechMode" +
                " appStates=${appStates.entries.joinToString(",") { "${it.key}:${it.value}" }}"
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
            if (id == APP_STATE_SPEECH) {
                // 带 speechMode 的那条：-1 = 无语音，其余按「正在语音」记为车机侧（2）
                (item["speechMode"] as? Number)?.toLong()?.let {
                    speechMode = it
                    carSpeechEntity = if (it == -1L) 0L else ENTITY_CAR
                    appStates[id] = if (ownSpeech == AirPlaySpeechMode.NONE) carSpeechEntity else ENTITY_PHONE
                }
            } else {
                (item["state"] as? Boolean)?.let {
                    val value = if (it) ENTITY_CAR else 0L
                    if (id == APP_STATE_PHONE_CALL) carCallEntity = value
                    appStates[id] = if (id == APP_STATE_PHONE_CALL && ownPhoneCall) ENTITY_PHONE else value
                }
            }
        }
    }

    companion object {
        /** resourceID（`Table 23-52`）：1 = 主屏，2 = 主音频。 */
        const val RESOURCE_SCREEN = 1
        const val RESOURCE_AUDIO = 2

        /** appStateID（`Table 23-56`）：1 = Speech、2 = PhoneCall、3 = TurnByTurn。 */
        const val APP_STATE_SPEECH = 1
        const val APP_STATE_PHONE_CALL = 2
        const val APP_STATE_TURN_BY_TURN = 3

        /** permanentEntity：1 = 手机，2 = 车机。 */
        const val ENTITY_PHONE = 1L
        const val ENTITY_CAR = 2L
    }
}
