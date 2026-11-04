package io.github.shaonialife.carptocar.buick

import android.content.Context

/**
 * 连接配置：把「车机协议族」做成显式档位，避免某一家的兼容改动波及别的车机。
 *
 * 背景（2026-10-29）：别克 E5（B233）的建链规则与通用线**相反**——
 *  - 不能拆 HFP/A2DP（拆了车机不开 iAP2 服务窗口）；
 *  - 不发 0x5702（能力清单未声明可收，发了会拖死握手）；
 *  - MFi 只认精确 32 字节，长度不符立即断链，不回落；
 *  - 0x4300 必须是 75 字节双 group（group0 设备标识、group1 真实蓝牙 MAC）；
 *  - 默认由**车机发起** RFCOMM，手机主动拨号是特例（最短间隔 15 秒）。
 *
 * 这些规则收进档位后，默认档「自动识别」对奥迪/宝马/凯迪拉克等既有车机
 * 保持完全原有的流程——只有认到别克 B233（或用户手动选别克档）才走专用分支。
 */
enum class ConnectionProfile(
    val id: String,
    val label: String,
    val summary: String,
    /** 该档位是否允许走 B233 专用流程。 */
    val allowsB233: Boolean = true,
    /** 该档位是否强制 B233（不看蓝牙名，只要连到目标设备就按 B233 处理）。 */
    val forcesB233: Boolean = false,
    /** B233 建链方向：false = 等车机发起（E5 实测默认），true = 手机主动拨号。 */
    val b233PhoneInitiates: Boolean = false,
    /**
     * 车机**没报** `maxFPS` 时的兜底帧率（null = 用本地偏好）。
     *
     * 正常路径是「车机报了就跟随」（见 [DisplayTuning.targetFps]）：接收端解码能力是硬约束，
     * 发得比它快只会丢帧、堆解码队列。这个兜底只处理 `/info` 没给 `maxFPS` 的情况——
     * 大屏车机普遍 30，宁可保守也不要按 60 硬发。
     */
    val carMaxFpsFallback: Int? = null,
    /**
     * 没指定目标车机时，是否**无条件**按 B233 处理。
     *
     * 「别克 E5」两档靠蓝牙名/历史记录兜底，是为了避免名字没认出来时悄悄退回通用流程
     * 却又不至于误连别的设备。但大屏档面对的车型连名字都不确定（很可能不是
     * `BUICK_[0-9]{4}`），若还要求「名字像别克」才能命中，用户选了档位等于白选。
     * 打开它以后：选了档位即认，语义从「猜」变成「用户明确指定」。
     */
    val forceWhenNoTarget: Boolean = false,
    /**
     * 该档位是否强制走「奥迪 MIB3 专版」连接参数（MFi `20→16`、`0x5703` 三秒五轮）。
     *
     * 奥迪本来是**自动判定**的（看 0x1D01 里有没有 `audi`，或有 `delphi` 且有 `mib`），
     * 不需要用户选。但身份字段是车厂自己写的：写私有串、或厂商字段不是 DELPHI 的固件
     * 判不出来，而那时用户手里没有任何开关。这一档就是那个人工兜底——**选中即认，
     * 不再看身份**，与「别克大连屏」档同一个思路（型号未知时只能靠人指定）。
     */
    val forcesAudiMib3: Boolean = false,
    /**
     * 识别阶段是否**补发我方自己的 `0x1D01`**（含本方会发/可收的消息位图）。
     *
     * 默认 false：本项目一直只「收车机的 0x1D01、回 0x1D02」，奥迪（Delphi）、
     * 别克（SAIC-GM/Cinemo）、老大众（探歌，MQB）都容忍这种做法并照常下发 `0x4301`。
     *
     * 打开它的理由：大众 MEB（ID 系列）的 ICAS3 用**苹果原厂栈**
     * （`AppleCarPlay_CommunicationPlugin` 320.17.7 + `libiAP2Service-3.0.so`），
     * 识别阶段是双向的——收不到手机的识别帧就不开 CarPlay 会话
     * （依据见 `docs/vw-id-icas3.md` 第 3 节）。默认档不动，只有显式选了这个档才发。
     */
    val sendsDeviceIdentification: Boolean = false,
) {
    AUTO(
        "auto",
        "自动识别",
        "识别到别克 B233 才走专用流程，其它车机保持原样（默认）",
    ),
    STANDARD(
        "standard",
        "通用（标准）",
        "只用通用 CarPlay 流程；连不上或想排除别克兼容改动时选它",
        // **必须显式关掉**：默认值 true 会让「通用档」也命中别克判定，
        // 结果就是用户选了「通用」却仍走 B233 分支——这一条由单测守着。
        allowsB233 = false,
    ),
    BUICK_B233(
        "buick_b233",
        "别克 E5（车机发起）",
        "强制 B233：只等车机接入，保留蓝牙免提，0x4300 用 75 字节双 group",
        forcesB233 = true,
    ),
    BUICK_B233_ACTIVE(
        "buick_b233_active",
        "别克 E5（手机拨号）",
        "强制 B233 且由手机主动拨号：车机不主动连入时用，两次尝试至少间隔 15 秒",
        forcesB233 = true,
        b233PhoneInitiates = true,
    ),

    /**
     * 别克「大连屏」档（2026-10-31 新增，实车待验证）。
     *
     * 面向的是**另一台别克**——30 吋级一体式宽屏车机（GL8 / 世纪 / 君越那一类），
     * 型号与身份文本尚未确认，因此：
     *  - 建链**先按 B233 处理**（同属 SAIC-GM 8155 / Cinemo 栈家族，75 字节双 group
     *    与 32 字节 MFi 是最可能的答案），但判定只认「用户显式选了这个档」，
     *    不做任何品牌/型号猜测——没有 0x1D01 日志前，自动识别命中即误伤；
     *  - 方向默认**等车机发起**（B233 家族的通例）。车机不主动连时，改选
     *    「别克 E5（手机拨号）」即可，两条档位共享同一套 B233 拨号闸门；
     *  - **帧率本身跟车机上报**（这条规则对所有档位生效，见 [DisplayTuning.targetFps]）；
     *    本档额外规定了「车机没报 maxFPS 时按 30 兜底」——大屏车机普遍 30，
     *    与其按 60 硬发，不如先保守。
     *
     * 实车跑通后要做的两件事写进 `docs/buick-bigscreen-profile.md`：
     * 补 0x1D01 身份标记（让它能被 AUTO 识别）、按实测调整缩放（默认仍 100%，
     * 参考项目在同尺寸屏上取 90%）。
     */
    BUICK_BIGSCREEN(
        "buick_bigscreen",
        "别克 大连屏（车机发起）",
        "强制 B233：只等车机接入，帧率跟随车机上限（未上报时按 30 兜底），实车待验证",
        forcesB233 = true,
        carMaxFpsFallback = DisplayTuning.FALLBACK_CAR_MAX_FPS,
        forceWhenNoTarget = true,
    ),

    /**
     * 奥迪 MIB3 强制档（2026-11-02 新增；2026-11-04 起标注覆盖整个 MIB3 家族）。
     *
     * **正常情况下不该用它**：奥迪走自动判定就够（`ReceiverProfile.variantOf` 看
     * 0x1D01 身份含 `audi`，或含 `delphi` 且含 `mib`）。这一档存在的唯一理由是
     * 自动判定有盲区——身份串由车厂填写，遇到写私有串的固件就判不出来，用户又没有
     * 别的手动开关。
     *
     * 适用家族（同一套 ESO iAP2 栈 + Apple 官方 AirPlay 接收端，连接时序一致）：
     *  - 3801 / 3874（MMX3 平台，`docs/audi-mib3-3801.md`、`docs/mib3-firmware-3874.md`）
     *  - C8-4580（CL3D 平台，`docs/mib3-firmware-c8-4580.md`）——该固件报告 §5.7
     *    证实 EA 会话经 CIAP2EAPProtocolViwi 桥进内部 VIWI TCP，承载 AirPlay 信令
     *    与车控数据；本分支的 EA 三协议解析 / 0xEA00 催问正是照这条管道做的
     *
     * 选中后强制启用专版三项：MFi 基线 `20→16`（去掉 32B）、20B 窗口压到 1.5 秒、
     * `0x5703` 交接 3 秒 × 5 轮。**不影响别克/宝马/大众档**，也不改任何画质参数。
     *
     * 风险提示（写进 summary 与日志）：它**忽略身份判定**，所以选错档也不会自己
     * 退回去。若连的是 MMI 0000（旧固件，只认 32B），专版基线里没有 32B 会让
     * MFi 多试一轮；不过 `MfiLengthMemory` 会把这台车成功过的档位强制排第一，
     * 连过一次之后就自动纠正，最坏只是冷启动首次多等几秒。
     */
    AUDI_MIB3(
        "audi_mib3",
        "奥迪 MIB3（强制专版）",
        "强制奥迪专版：MFi 20→16、0x5703 三秒五轮；适用 3801/3874/C8-4580 全家族，自动识别不到身份时用，选错不会自动退回",
        // 显式指定了奥迪就**排除**别克流程：两个档位的 0x4300 报文体与 MFi 硬要求
        // 正好相反（别克要 75 字节双 group + 32B，奥迪要单 group + 20B/16B）。
        // 不关的话，用户选了奥迪却连了台名字像别克的设备（换车测试、借车），
        // 会被 looksLikeBuick 拉进 B233，症状是「选了奥迪反而更连不上」。
        allowsB233 = false,
        forcesAudiMib3 = true,
    ),

    /**
     * 大众 MEB（ID 系列）档（2026-11-03 新增，**实车待验证**）。
     *
     * 目标车机：大众 MEB 平台的 ICAS3（`ICAS3GP_CN4_VW_R4220L` / MU 6422 / 1.422.4，
     * Samsung Exynos Auto V9，QNX + LGE Linux + Android VM）。与老 MQB 大众（探歌那类
     * Cinemo/自研栈）不是一套东西：MEB 上跑的是**苹果原厂 CarPlay 栈**
     * （插件 320.17.7 + iAP2 3.0 + `libAirPlay.so` 接收端）。
     *
     * 这一档只有**一处**行为改动：
     *  - [sendsDeviceIdentification] = true —— 识别阶段补发我方 `0x1D01`。
     *    苹果栈对识别的双向性要求，是静默分析与老大众实车差异之间最可能的那一条。
     *
     * 其余一律**跟随车机**，刻意不加任何硬编码：
     *  - 画布 1264×640、帧率上限、音频档位全部来自 `/info` 与 `0x1D01`（ICAS3 会报
     *    `widthPixels=1264 / heightPixels=640`），没有证据支持再压一个 30fps 兜底；
     *  - 视频载荷保持 AVCC（长度前缀）——报告明确「iPhone 发 AVCC，车机转 Annex-B」，
     *    与本仓库现有实现一致，**不能**改成 Annex-B；
     *  - AP 凭据仍走 `0x5702 → 0x5703`（ICAS3 声明了 `0x5703`，且它也会自己下发）；
     *    1 秒无应答后的并行兜底（同时发 `0x4300`）保留，老大众实测过这条救命。
     *
     * 风险与退路：
     *  - 补发识别帧的内容里**没有 EAP 协议名**（两份材料都没给字面量，见
     *    `DeviceIdentification` 的「已知缺口」）。若车机仍不回 `0x4301`，
     *    下一步是抓一次真 iPhone 的 `0x1D01` 补上，而不是继续加开关；
     *  - 万一这台车机因为多收了一帧识别反而更差，改选「通用（标准）」即可完全回到
     *    旧流程，不需要卸载重装。
     */
    VW_MEB(
        "vw_meb",
        "大众 ID（MEB ICAS3）",
        "补发我方识别帧（0x1D01 含消息位图）；其余跟随车机上报。实车待验证，连不上可退回「通用」",
        allowsB233 = false,
        sendsDeviceIdentification = true,
    ),
    ;

    companion object {
        fun byId(id: String?): ConnectionProfile = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** 连接配置偏好。改动在**下一次**连接开始时生效（蓝牙客户端 start 时读取）。 */
object ConnectionProfilePrefs {
    private const val FILE = "connection_profile"
    private const val KEY = "profile"

    fun get(context: Context): ConnectionProfile =
        ConnectionProfile.byId(
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, null),
        )

    fun set(
        context: Context,
        profile: ConnectionProfile,
    ) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, profile.id)
            .apply()
    }
}

/**
 * 别克 B233 的身份判定：**发现阶段**看蓝牙名，**确认阶段**看 0x1D01 文本。
 *
 * 蓝牙名只是线索（车主可以改），不作数；真正的车型身份由 0x1D01 的
 * name/model/manufacturer 三个字段共同判定，三个标记缺一不可，避免把
 * 别的「B233」字样（机身编号之类）误判成别克。
 */
object B233Identity {
    /** 别克车机的出厂蓝牙名形态：`BUICK_1234`。 */
    private val RECEIVER_NAME = Regex("^BUICK_[0-9]{4}$", RegexOption.IGNORE_CASE)

    /** 0x1D01 身份文本必须同时出现的两个标记（小写比对）。 */
    private val IDENTITY_MARKERS = listOf("buick", "saic general motors")

    /**
     * 车型代号的两种写法：模块型号 **B233**、平台代号 **E2LB**。
     *
     * 同一台 SAIC-GM 8155 车机（Cinemo 栈）在 0x1D01 里回的是 `model=E2LB`
     * （2026-10-29 BUICK_2874 真机日志），"B233" 只是我们给这条分支的命名。
     * 只认 b233 会让整条专用分支永远不命中——那台车于是走通用单 group 的 0x4300，
     * 被车机静默丢弃（日志里「发出后 8s 内车机零反馈」），而它要的正是 75B 双 group。
     */
    private val MODEL_MARKERS = listOf("b233", "e2lb")

    /** 蓝牙名是否像别克车机（仅用于发现阶段的优先尝试，不构成身份认证）。 */
    fun looksLikeBuick(name: String?): Boolean = name != null && RECEIVER_NAME.matches(name.trim())

    /** 0x1D01 身份文本是否确认为别克 B233 / E2LB（两个身份标记 + 一个车型代号）。 */
    fun confirmsB233(identityText: String?): Boolean {
        if (identityText.isNullOrBlank()) return false
        val text = identityText.lowercase()
        return IDENTITY_MARKERS.all { text.contains(it) } && MODEL_MARKERS.any { text.contains(it) }
    }
}

/**
 * 单台设备本轮是否按 B233 处理。三层判定，只影响蓝牙传输与握手分支：
 * 1. 档位「通用」→ 永不命中；
 * 2. 档位「别克」→ 已选目标设备、或蓝牙名像别克、或本机曾在该地址上走通 0x4301；
 * 3. 档位「自动」→ 蓝牙名 `BUICK_[0-9]{4}`，或该地址被记录为 B233。
 *
 * 例外：[ConnectionProfile.forceWhenNoTarget] 的档位（大屏档）在没选目标设备时
 * 直接认——车型身份未知，靠名字猜会等于白选。
 */
object B233Selection {
    fun matches(
        profile: ConnectionProfile,
        selectedAddress: String?,
        peerAddress: String?,
        peerName: String?,
        observedB233: Boolean,
    ): Boolean =
        when {
            !profile.allowsB233 -> false
            profile.forcesB233 -> {
                if (!selectedAddress.isNullOrBlank()) {
                    peerAddress != null && peerAddress.equals(selectedAddress, ignoreCase = true)
                } else {
                    profile.forceWhenNoTarget ||
                        B233Identity.looksLikeBuick(peerName) ||
                        observedB233
                }
            }
            else -> B233Identity.looksLikeBuick(peerName) || observedB233
        }
}

/**
 * B233 主动拨号的闸门（纯函数，便于单测）。
 *
 * 被动档位下永远返回 false——「等车机接入」这条路必须靠得住，不能被自动重试
 * 悄悄改成主动：E5 对密集拨号很敏感，连拨会把车机侧的 iAP2 窗口拖死。
 */
object B233DialPolicy {
    /** 服务启动后先给车机反向接入留的优先窗口。 */
    const val INBOUND_PRIORITY_MS = 1_500L

    /** 两次主动拨号的最短间隔。 */
    const val MIN_ACTIVE_RETRY_INTERVAL_MS = 15_000L

    fun mayDial(
        active: Boolean,
        sessionBusy: Boolean,
        runningForMs: Long,
        sinceLastAttemptMs: Long,
        cooldownRemainingMs: Long,
    ): Boolean =
        active && !sessionBusy &&
            runningForMs >= INBOUND_PRIORITY_MS &&
            sinceLastAttemptMs >= MIN_ACTIVE_RETRY_INTERVAL_MS &&
            cooldownRemainingMs <= 0L
}

/**
 * 档位 → 显示参数的耦合规则（纯函数，便于单测）。
 *
 * 帧率规则对所有档位一致：**车机报了就跟随**（见 [targetFps]）。档位只在「车机没报」
 * 时表现出差异——声明了 [ConnectionProfile.carMaxFpsFallback] 的档位（大屏档）退回兜底值，
 * 其余档位保持本地偏好，等价于旧行为。
 */
object DisplayTuning {
    /** 车机没报 maxFPS（或报了荒谬值）时的兜底：大屏车机普遍 30，取它比取 60 安全。 */
    const val FALLBACK_CAR_MAX_FPS = 30

    /** 帧率下限：再低就没有滑动感，宁可略超车机也不要降到不可用。 */
    const val MIN_TARGET_FPS = 24

    /**
     * 大屏建议缩放。参考项目（CarDeck 0.1.28 别克适配版）在同一块 1972×520 屏上
     * 取 90%：曾出现 160% + 60fps 的组合让流变成 3154×832@60 / 15Mbps、
     * 关键帧 632KB，远超车机解码能力。这里只作为**建议值**展示，不自动写用户设置。
     */
    const val BIGSCREEN_SUGGESTED_SCALE_PERCENT = 90

    /**
     * 本次会话的目标帧率：**车机报了就跟随**。
     *
     * 接收端 `/info displays[0].maxFPS` 是它解码能力的自述，发得比它快只会丢帧、堆解码
     * 队列——别克 30 吋一体屏（1972×520@30）就是典型：按 60 硬发的结果是画面卡顿甚至拆链。
     * 车机报得比本地偏好高（如 120）时**不抬高**，帧率上限仍由本地偏好决定。
     *
     * 车机**没报**（<=0，例如 `/info` 没取到 displays）时，只有显式声明了
     * [ConnectionProfile.carMaxFpsFallback] 的档位会退回兜底值，其余档位保持本地偏好
     * ——这条保证「没有车机信息时行为与 1.2.7 一致」。
     *
     * @param preferredFps 本地偏好（当前固定 60，见 DisplayPrefs.FPS）
     * @param carMaxFps 车机 `/info displays[0].maxFPS`；<=0 视为未声明
     */
    fun targetFps(
        profile: ConnectionProfile,
        preferredFps: Int,
        carMaxFps: Int,
    ): Int =
        if (carMaxFps > 0) {
            minOf(preferredFps, carMaxFps.coerceAtLeast(MIN_TARGET_FPS))
        } else {
            profile.carMaxFpsFallback ?: preferredFps
        }

    /** 该档位是否需要提示用户调整缩放（不需要则返回 null）。 */
    fun suggestedScalePercent(profile: ConnectionProfile): Int? =
        if (profile.carMaxFpsFallback != null) BIGSCREEN_SUGGESTED_SCALE_PERCENT else null
}
