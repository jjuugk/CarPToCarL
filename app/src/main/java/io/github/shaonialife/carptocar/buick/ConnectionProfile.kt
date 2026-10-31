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
