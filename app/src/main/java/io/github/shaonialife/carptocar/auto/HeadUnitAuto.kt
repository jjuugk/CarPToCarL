package io.github.shaonialife.carptocar.auto

import android.content.Context

/**
 * 自动识别车机并选择连接档位（switch-pool-auto）。
 *
 * ## 为什么需要它
 *
 * 主线（1.2.24）靠 `ReceiverProfile` / `MfiLengthMemory` / `AvailabilityBodyMemory`
 * 做**能力自适应**，但等待窗口仍是全局常量。不同代际的车机时序差异很大：
 *
 * - CL35（3620）约 19 秒就拆链，等满 15 秒的入网窗口贴着它的放弃边缘；
 * - CL39（4420）不发 ctrl 邀请，0x4301 可能晚到 2~3 分钟；
 * - CL3D（4580）的 HMI 要等 iAP2 控制会话就绪才认 RECORD，且系统确认框常要 5~10 秒。
 *
 * 本对象的职责就是把「这是哪一代车机」翻译成一组**只调参数、不动协议**的连接档位。
 * 铁律与 AMCar 的 `HuPreset` 一致：档位不改变消息顺序、不改变收发内容、不跳过握手。
 * 因此识别错只会让等待窗口不合适并留下一条日志，不会让本来能连的车机连不上。
 *
 * ## 识别信号（按到达顺序）
 *
 * 1. iAP2 `0x1D01`：name / model / manufacturer / firmware（参 4，如 `CL3D_...`）；
 * 2. 蓝牙目标名（系统配对列表里的车机名）；
 * 3. AirPlay `/info`：`model` / `sourceVersion`（补刀；iAP2 缺失时仍能识别品牌）。
 *
 * 识别结果按「蓝牙目标地址 + 身份串」落盘，下次上车在 0x1D01 到达前就能先用上次的
 * 窗口；目标一换立刻回到通用参数，避免把上一台车的档位套到新车上。
 */
object HeadUnitAuto {

    /** 识别出的车机族。新增车型只需加枚举 + [policyOf] 一个分支。 */
    enum class Family(val label: String) {
        AUDI_CL37("奥迪 MIB3 CL37（3801 / 3874）"),
        AUDI_CL39("奥迪 MIB3 CL39（4420）"),
        AUDI_CL3D("奥迪 MIB3 CL3D（4580 / MIB3P / MIB3H）"),
        AUDI_CL35("奥迪 MIB3 CL35（3620 / 4910）"),
        AUDI_GENERIC("奥迪车机（未识别代际）"),
        BMW("宝马 MGU / iDrive"),
        BENCH("桌面接收端（WinPlay / DiPlay）"),
        GENERIC("通用车机（未识别）"),
    }

    /** VideoConfig 的尺寸声明策略（对齐主线 [io.github.shaonialife.carptocar.video.VideoCanvasMode]）。 */
    enum class VideoCanvas {
        /** 跟随用户/构建的 `VideoCanvasMode`，档位不干预。 */
        FOLLOW_DEFAULT,

        /** 解码尺寸=码流 SPS，视口=车机画布（main 1.2.24 默认）。 */
        STREAM,

        /** 解码尺寸=车机画布，不写视口（旧行为）。 */
        CAR,
    }

    /** 一次识别能拿到的全部信号；所有字段可空，缺信号不会导致误判为某代。 */
    data class Signals(
        val name: String? = null,
        val model: String? = null,
        val manufacturer: String? = null,
        val firmware: String? = null,
        val bluetoothName: String? = null,
        val sourceVersion: String? = null,
    ) {
        /** 用于日志与落盘的稳定身份串（不含易变的 sourceVersion 时也能区分车机）。 */
        fun identity(): String =
            listOfNotNull(
                name?.trim()?.takeIf { it.isNotEmpty() },
                model?.trim()?.takeIf { it.isNotEmpty() },
                manufacturer?.trim()?.takeIf { it.isNotEmpty() },
                firmware?.trim()?.takeIf { it.isNotEmpty() },
                bluetoothName?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" ").ifBlank { "unknown" }
    }

    /**
     * 一组连接参数。
     *
     * 默认值必须与 `SessionController` 里的原常量逐字段一致——未识别车机时行为
     * 与 1.2.24 完全相同。
     */
    data class Policy(
        val family: Family,
        val reason: String,
        /** `WifiNetworkSpecifier` 入网窗口。 */
        val specifierJoinTimeoutMs: Long,
        /** 等车机 ctrl 邀请的窗口。 */
        val ctrlInviteWaitMs: Long,
        /** 等 0x4301 / 热点信息的窗口。 */
        val pendingStartWaitMs: Long,
        /** 绑当前 WiFi 的窗口。 */
        val bindWaitSeconds: Long,
        /** RECORD 后等 iAP2 控制会话就绪的窗口。 */
        val reactivateWaitMs: Long,
        /** true = 即使控制会话已就绪，也重声明主屏 + 补发 RECORD。 */
        val reactivateAlways: Boolean,
        /** VideoConfig 尺寸声明策略。 */
        val videoCanvas: VideoCanvas,
        /** true = 不协商音频，主音频交还车机。 */
        val videoOnly: Boolean,
        /**
         * 失败后是否断开该车机的 HFP/A2DP 再试。
         *
         * 默认为 true（与 1.2.24 相同：奥迪等车机在音频配置文件在线时不开放 iAP2 窗口）。
         * BMW = false：车机自己会在 ACL 连上后主动连入，断音频配置反而可能把 ACL 拽掉。
         */
        val dropAudioProfiles: Boolean = true,
        /**
         * 是否允许主动出站拨号（…caff）。
         *
         * 默认为 true（主线行为）。BMW = false：只保留 …cafe 被动监听，等车机发起。
         */
        val outboundIap2: Boolean = true,
        /**
         * 是否要求**本轮**的 ctrl 邀请或 iAP2 端点才算激活。
         *
         * 默认 false。BMW = true：端口开着 / 记住过网络都不算激活；没有激活信号时
         * 不拿网关或 mDNS 猜端点，保持蓝牙与广播等车机下一轮邀请。
         */
        val requireAirplayActivation: Boolean = false,
        /** 只有 ctrl 邀请、没有 0x4301 时使用的 AirPlay 端口（BMW 实测 7000）。 */
        val inviteFallbackPort: Int = 7000,
        /**
         * 既没有 0x5703 也没有 0x4301 时是否保持链路（不抛异常拆链）。
         *
         * 默认 false（主线只在车机声明会发 0x4301 时才保活）。BMW 档案 A 只有
         * iPod/BMW Apps，不会给 CarPlay 凭据；保活让车机自己切回档案 B 重连。
         */
        val stayAliveWithoutEndpoint: Boolean = false,
    ) {
        /** 排障用：一眼看全本轮实际生效的参数。 */
        fun describe(): String =
            "入网${specifierJoinTimeoutMs / 1_000}s · ctrl邀请${ctrlInviteWaitMs / 1_000}s · " +
                "等0x4301 ${pendingStartWaitMs / 60_000}min · 绑网${bindWaitSeconds}s · " +
                "重激活${reactivateWaitMs / 1_000}s" +
                (if (reactivateAlways) "(无条件)" else "") +
                " · 画面=${videoCanvas.name.lowercase()}" +
                (if (videoOnly) " · 仅视频" else "") +
                (if (!dropAudioProfiles) " · 保留HFP/A2DP" else "") +
                (if (!outboundIap2) " · 仅被动iAP2" else "") +
                (if (requireAirplayActivation) " · 等本轮激活" else "")

        /** 识别结果没变时不用重复落盘/刷日志。 */
        internal fun cacheKey(): String = "${family.name}|$reason|${describe()}"
    }

    /**
     * 通用参数 = 主线 1.2.24 的常量。
     *
     * 常量来源 = 主线 1.2.24 的 `SessionController` 等待窗口。本对象是**唯一真源**：
     * `HeadUnitAutoTest` 会逐字段锁定这些值，改动等于改变「未识别车机」的行为。
     * 入网 15s、ctrl 邀请 4s、等 0x4301 5min、绑网 15s、重激活等待 2.5s。
     */
    val DEFAULT: Policy =
        Policy(
            family = Family.GENERIC,
            reason = "未识别（通用参数）",
            specifierJoinTimeoutMs = 15_000L,
            ctrlInviteWaitMs = 4_000L,
            pendingStartWaitMs = 5 * 60_000L,
            bindWaitSeconds = 15L,
            reactivateWaitMs = 2_500L,
            reactivateAlways = false,
            videoCanvas = VideoCanvas.FOLLOW_DEFAULT,
            videoOnly = false,
        )

    /** 车机族 → 参数（唯一需要维护的映射表）。 */
    fun policyOf(family: Family): Policy =
        when (family) {
            // 已验证基线：与 1.2.24 完全一致。3801/3874 同属 CL37，共用一组参数。
            Family.AUDI_CL37 -> DEFAULT.copy(family = family, reason = "CL37（3801/3874 已验证基线）")

            // 4420：不发 ctrl 邀请，只认 0x4301，实测可晚到 2~3 分钟；邀请窗口缩短，
            // 让连接更快落到「已发现 _airplay._tcp」或超时后的旧发现路径。
            Family.AUDI_CL39 ->
                DEFAULT.copy(
                    family = family,
                    reason = "CL39（4420：不发 ctrl 邀请、0x4301 可能晚到）",
                    ctrlInviteWaitMs = 1_000L,
                    pendingStartWaitMs = 6 * 60_000L,
                    reactivateAlways = true,
                    videoCanvas = VideoCanvas.STREAM,
                )

            // 4580：系统确认框常要 5~10 秒（入网 25s），HMI 要等控制会话就绪才认 RECORD
            //（重激活 5s），0x4301 也可能晚到 6 分钟。
            Family.AUDI_CL3D ->
                DEFAULT.copy(
                    family = family,
                    reason = "CL3D（4580：入网慢、要等控制会话就绪）",
                    specifierJoinTimeoutMs = 25_000L,
                    pendingStartWaitMs = 6 * 60_000L,
                    reactivateWaitMs = 5_000L,
                    reactivateAlways = true,
                    videoCanvas = VideoCanvas.STREAM,
                )

            // 3620：约 19 秒拆链，入网窗口缩短到 12 秒，让失败在车机放弃前暴露。
            Family.AUDI_CL35 ->
                DEFAULT.copy(
                    family = family,
                    reason = "CL35（3620：约 19s 拆链）",
                    specifierJoinTimeoutMs = 12_000L,
                    reactivateAlways = true,
                    videoCanvas = VideoCanvas.STREAM,
                )

            Family.AUDI_GENERIC -> DEFAULT.copy(family = family, reason = "奥迪车机（固件未给出 CLxx 代际）")

            // BMW / MGU：与 hypercar 的 bmw_id85 档案对齐——**车机主导**建链。
            //  - 车机主动连入手机的 …cafe，手机不做出站拨号；
            //  - 保留系统 HFP/A2DP（断开反而会拽掉 ACL，2026-11-03 hypercar 实测）；
            //  - 必须有本轮 ctrl 邀请或 iAP2 端点才算激活，开放端口/记住的网络不算；
            //  - 车机不发 0x4301 时，用 ctrl 邀请的对端 IP + 7000 当端点；
            //  - 档案 A（只有 iPod/BMW Apps）不拆链，让车机自己切回档案 B。
            Family.BMW ->
                DEFAULT.copy(
                    family = family,
                    reason = "BMW / MGU / iDrive（车机主导：被动监听、保留 HFP/A2DP、等本轮激活）",
                    dropAudioProfiles = false,
                    outboundIap2 = false,
                    requireAirplayActivation = true,
                    inviteFallbackPort = 7000,
                    stayAliveWithoutEndpoint = true,
                )

            Family.BENCH -> DEFAULT.copy(family = family, reason = "桌面测试接收端（窗口较短、无车机时序约束）")
            Family.GENERIC -> DEFAULT
        }

    /**
     * 纯函数识别：给定信号 → 档位。与 Android 无关，单测直接覆盖。
     *
     * 判定顺序刻意是「固件代际 → 品牌 → 测试台 → 未知」：固件串里的 `CLxx` 是
     * 车机自己报的最强信号，优先于名称里的品牌词。
     */
    internal fun classify(signals: Signals): Policy {
        val text =
            listOfNotNull(
                signals.name,
                signals.model,
                signals.manufacturer,
                signals.firmware,
                signals.bluetoothName,
                signals.sourceVersion,
            ).joinToString(" ").uppercase()

        val cl = Regex("CL([0-9A-F]{2})").find(text)?.groupValues?.getOrNull(1)
        val family =
            when {
                cl == "3D" -> Family.AUDI_CL3D
                cl == "39" -> Family.AUDI_CL39
                cl == "37" -> Family.AUDI_CL37
                cl == "35" -> Family.AUDI_CL35
                cl != null -> Family.AUDI_GENERIC
                text.contains("BMW") || text.contains("MGU") || text.contains("NBT") ||
                    Regex("\\bID[89]\\b").containsMatchIn(text) -> Family.BMW
                text.contains("WINPLAY") || text.contains("DIPLAY") ||
                    text.contains("XCERTPLAY") || text.contains("DESKTOP-") -> Family.BENCH
                text.contains("AUDI") || text.contains("DELPHI") || text.contains("MIB3") -> Family.AUDI_GENERIC
                else -> Family.GENERIC
            }
        val detail =
            when {
                cl != null -> "固件代际 CL$cl"
                signals.bluetoothName != null -> "蓝牙名「${signals.bluetoothName}」"
                signals.model != null || signals.name != null -> "0x1D01 身份"
                else -> "仅 AirPlay /info"
            }
        return policyOf(family).copy(reason = "${policyOf(family).reason} · $detail")
    }

    // ---- 进程内当前值 + 轻量持久化 ----

    private const val PREFS = "headunit_auto_v1"
    private const val KEY_FAMILY = "family"
    private const val KEY_IDENTITY = "identity"
    private const val KEY_TARGET = "target"

    @Volatile
    private var active: Policy = DEFAULT

    @Volatile
    private var identity: String? = null

    @Volatile
    private var targetKey: String? = null

    @Volatile
    private var loaded: Boolean = false

    /** Application 启动时调用：恢复上次识别结果，供 0x1D01 到达前的首条日志使用。 */
    fun load(context: Context?) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            if (context != null) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val family = prefs.getString(KEY_FAMILY, null)?.let { raw ->
                    Family.entries.firstOrNull { it.name == raw }
                }
                identity = prefs.getString(KEY_IDENTITY, null)
                targetKey = prefs.getString(KEY_TARGET, null)
                if (family != null) active = policyOf(family)
            }
            loaded = true
        }
    }

    /**
     * 每次「启动连接」调用一次。
     *
     * 目标车机换了就丢弃上一台的识别结果——否则新车的入网/等待窗口会先按旧车跑，
     * 直到 0x1D01 到达才纠正（这段时间正好是 WiFi 入网窗口，影响最大）。
     */
    fun beginAttempt(
        context: Context?,
        targetAddress: String?,
        targetName: String?,
    ) {
        load(context)
        val key =
            targetAddress?.trim()?.takeIf { it.isNotEmpty() }
                ?: targetName?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(this) {
            if (key != null && targetKey != null && key != targetKey) {
                active = DEFAULT
                identity = null
            }
            targetKey = key
        }
        // 蓝牙名是 0x1D01 之前唯一能拿到的信号：能识别品牌/代际就先用上，
        // 但仍受 observe 的强度保护（弱信号不会冲掉已识别的固件代际）。
        if (!targetName.isNullOrBlank()) {
            observe(context, Signals(bluetoothName = targetName))
        }
        persist(context)
    }

    /**
     * 收到 0x1D01 / /info 后调用：重新识别并立即生效（入网发生在 0x1D01 之后，
     * 因此新的等待窗口赶得上本轮连接）。
     */
    fun observe(
        context: Context?,
        signals: Signals,
    ): Policy {
        load(context)
        val next = classify(signals)
        val nextIdentity = signals.identity()
        synchronized(this) {
            // 不允许弱信号覆盖强信号：`/info` 只有 model/sourceVersion，会把已经由
            // 固件串识别的 CL3D 降级成「通用」。只有同级或更强才更新。
            if (strengthOf(next.family) < strengthOf(active.family)) return active
            if (active.cacheKey() != next.cacheKey() || identity != nextIdentity) {
                active = next
                identity = nextIdentity
                persist(context)
            }
        }
        return active
    }

    /** 识别强度：固件代际 > 品牌/测试台 > 未知。用于阻止弱信号降级强结论。 */
    internal fun strengthOf(family: Family): Int =
        when (family) {
            Family.AUDI_CL3D,
            Family.AUDI_CL39,
            Family.AUDI_CL37,
            Family.AUDI_CL35,
            -> 3

            Family.AUDI_GENERIC,
            Family.BMW,
            Family.BENCH,
            -> 2

            Family.GENERIC -> 0
        }

    /** 仅供 `/info` 补刀：身份信号不全时也能识别品牌。 */
    fun observeInfo(
        context: Context?,
        model: String?,
        sourceVersion: String?,
    ): Policy = observe(context, Signals(model = model, sourceVersion = sourceVersion))

    fun current(): Policy = active

    fun currentIdentity(): String? = identity

    /** 一句话日志：档位 + 触发依据 + 实际参数。 */
    fun describe(): String = "${active.family.label}（${active.reason}）——${active.describe()}"

    internal fun resetForTest() {
        active = DEFAULT
        identity = null
        targetKey = null
        loaded = false
    }

    private fun persist(context: Context?) {
        if (context == null) return
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAMILY, active.family.name)
            .putString(KEY_IDENTITY, identity)
            .putString(KEY_TARGET, targetKey)
            .apply()
    }
}
