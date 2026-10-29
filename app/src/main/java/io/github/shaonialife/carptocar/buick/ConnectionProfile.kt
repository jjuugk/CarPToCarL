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

    /** 0x1D01 身份文本必须同时出现的三个标记（小写比对）。 */
    private val IDENTITY_MARKERS = listOf("buick", "b233", "saic general motors")

    /** 蓝牙名是否像别克车机（仅用于发现阶段的优先尝试，不构成身份认证）。 */
    fun looksLikeBuick(name: String?): Boolean = name != null && RECEIVER_NAME.matches(name.trim())

    /** 0x1D01 身份文本是否确认为别克 B233（三个标记全含）。 */
    fun confirmsB233(identityText: String?): Boolean {
        if (identityText.isNullOrBlank()) return false
        val text = identityText.lowercase()
        return IDENTITY_MARKERS.all { text.contains(it) }
    }
}

/**
 * 单台设备本轮是否按 B233 处理。三层判定，只影响蓝牙传输与握手分支：
 * 1. 档位「通用」→ 永不命中；
 * 2. 档位「别克」→ 已选目标设备、或蓝牙名像别克、或本机曾在该地址上走通 0x4301；
 * 3. 档位「自动」→ 蓝牙名 `BUICK_[0-9]{4}`，或该地址被记录为 B233。
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
                    B233Identity.looksLikeBuick(peerName) || observedB233
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
