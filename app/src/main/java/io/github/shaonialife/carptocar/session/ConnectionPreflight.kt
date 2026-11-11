package io.github.shaonialife.carptocar.session

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.shaonialife.carptocar.bt.TargetDevicePrefs
import io.github.shaonialife.carptocar.util.DeviceIdentity

/**
 * 连接前体检：**只读**当前状态，不申请权限、不弹框、不改任何设置。
 *
 * 为什么要有它（2026-11-11 三份别克日志的教训）：那一晚连着七八轮失败，日志里
 * 全靠事后猜——「位置服务到底开没开」「本机蓝牙地址是不是占位」「目标车机还在不在
 * 已配对列表里」这些**一次性就能查清**的东西，每次都要翻几千行去凑。
 * 更糟的是 Android 13+ 的位置服务：`NEARBY_WIFI_DEVICES` 给了也没用，位置关着照样
 * 读不到 SSID，而旧的前置检查只在 Android 12/12L 看位置——整个盲区没有任何记录。
 *
 * 参考实现（link_plus `session/ConnectionPreflight`）的做法值得抄：**UI 与日志共用
 * 同一份结论**，而不是「用到哪个权限才去申请哪个、失败再说」。
 *
 * 与它的差别：那边把「全绿」当作连接前提（含 Shizuku、触控服务）；我们这里只分
 * [Severity.BLOCKING] / [Severity.WARNING] / [Severity.INFO] 三档——**只读体检绝不
 * 阻断连接**，缺项写清后果，由既有流程去引导，避免又多出几个自家弹窗。
 *
 * 11 项：蓝牙权限 / 蓝牙开关 / WLAN 权限 / WLAN 开关 / 本机地址 / 位置服务 /
 * 目标车机已配对 / 本机蓝牙名 / 通知权限 / 通知使用权（媒体会话）/ 电池优化。UI 与日志
 * 取的是同一份 [Report]（[Report.headline] 给界面一行摘要，[Report.describe] 给日志与
 * 详情弹窗），因此界面上看到的那句话，导出的日志里一定有完全相同的一份。
 *
 * **不含「投屏授权」**：本版本画面走双屏虚拟屏、声音走 CarLife 引擎 PCM 直通，
 * 都不经 `MediaProjection`（2026-11-11 核对：`AudioSender.start(projection)` 无调用方，
 * `CarPlayService.pendingProjectionResult` 从未被写入）。把它列进体检只会让用户
 * 白去找一个根本不存在的授权框。
 */
object ConnectionPreflight {
    /** 一项检查的严重度。 */
    enum class Severity {
        /** 不满足就一定连不上（权限 / 开关 / 本机身份）。 */
        BLOCKING,

        /** 不满足会降级：能连但某条路走不通，或车机那边显示不对。 */
        WARNING,

        /** 只影响后台存活与可见性，与协议无关。 */
        INFO,
    }

    enum class Requirement(
        val title: String,
        val severity: Severity,
    ) {
        BLUETOOTH_PERMISSION("「附近的蓝牙设备」权限", Severity.BLOCKING),
        BLUETOOTH_ENABLED("手机蓝牙开关", Severity.BLOCKING),
        WIFI_PERMISSION("「附近的 WLAN 设备」权限", Severity.BLOCKING),
        WIFI_ENABLED("手机 WLAN 开关", Severity.BLOCKING),
        IDENTITY("本机蓝牙地址", Severity.BLOCKING),
        LOCATION_SERVICE("位置服务", Severity.WARNING),
        TARGET_PAIRED("目标车机已配对", Severity.WARNING),
        IDENTITY_NAME("本机蓝牙名", Severity.WARNING),
        NOTIFICATIONS("通知权限", Severity.INFO),
        NOTIFICATION_ACCESS("通知使用权（媒体会话）", Severity.INFO),
        BATTERY_OPTIMIZATION("电池优化已忽略", Severity.INFO),
    }

    /**
     * 单项结论。
     *
     * @param detail 缺项时的一句「会怎样」，就绪时为补充说明（可为 null）
     */
    data class Check(
        val requirement: Requirement,
        val ready: Boolean,
        val detail: String? = null,
    )

    /** 一次体检的完整结论。 */
    data class Report(
        val checks: List<Check>,
    ) {
        /** 阻塞项里没通过的（空 = 连接的必要条件齐了）。 */
        val blockingMissing: List<Check>
            get() = checks.filter { !it.ready && it.requirement.severity == Severity.BLOCKING }

        /** 会降级但不禁连的缺项。 */
        val warnings: List<Check>
            get() = checks.filter { !it.ready && it.requirement.severity == Severity.WARNING }

        val readyCount: Int get() = checks.count { it.ready }

        val ready: Boolean get() = blockingMissing.isEmpty()

        /** 除「全绿」以外的缺项（阻塞 + 降级），供 UI 决定要不要标黄。 */
        val missing: List<Check>
            get() =
                checks.filter {
                    !it.ready && it.requirement.severity != Severity.INFO
                }

        /**
         * 最该先处理的那一项：先阻塞、再降级；同类里按枚举顺序（越靠前越基础）。
         * UI 上「体检」那一行只放得下一句提示，就放它。
         */
        fun topIssue(): Check? = blockingMissing.firstOrNull() ?: warnings.firstOrNull()

        /** 一行摘要（UI 提示 / 节流日志用）。 */
        fun headline(): String {
            val head = "$readyCount/${checks.size} 项就绪"
            return when {
                blockingMissing.isNotEmpty() ->
                    "$head，缺 ${blockingMissing.joinToString("、") { it.requirement.title }}"
                warnings.isNotEmpty() ->
                    "$head，阻塞项全过（注意 ${warnings.joinToString("、") { it.requirement.title }}）"
                else -> "$head，全部通过"
            }
        }

        /**
         * 详情弹窗正文：与 [describe] **同一份逐行结论**，只是去掉 `PREFLIGHT:` 前缀、
         * 换成更适合人读的符号。UI 与日志必须能逐行对上，否则「界面说 A、日志说 B」
         * 又回到要靠猜的老路上。
         */
        fun dialogText(): String = describe().drop(1).joinToString("\n") { it.removePrefix("PREFLIGHT:   ") }

        /** 逐行日志：✓/○/· 分别表示通过 / 阻塞缺项 / 降级或缺项提示。 */
        fun describe(): List<String> {
            val lines = mutableListOf("PREFLIGHT: 连接前体检——${headline()}")
            checks.forEach { check ->
                val mark =
                    when {
                        check.ready -> "✓"
                        check.requirement.severity == Severity.BLOCKING -> "○"
                        else -> "·"
                    }
                val detail = check.detail?.let { "——$it" }.orEmpty()
                lines += "PREFLIGHT:   $mark ${check.requirement.title}$detail"
            }
            if (!ready) {
                lines +=
                    "PREFLIGHT: ⚠ 阻塞项缺失（${blockingMissing.joinToString("、") { it.requirement.title }}）" +
                        "——现在的状态连接必然失败，请先补齐"
            }
            return lines
        }

        /** 节流用的指纹：只看「哪几项未通过」，不看具体文案。 */
        fun signature(): String =
            checks
                .filterNot { it.ready }
                .joinToString(",") { "${it.requirement.name}:${it.requirement.severity}" }
    }

    /** 同样的结论在这段时间内重复出现时只打一行摘要。 */
    private const val REPEAT_QUIET_MILLIS = 20_000L

    @Volatile
    private var lastSignature: String? = null

    @Volatile
    private var lastLoggedAtMillis = 0L

    /**
     * 体检并落日志（同类结论 20 秒内只打一行摘要，避免重连循环刷屏）。
     *
     * 调用点：`MainActivity.ensureBluetoothReady`（用户点连接的前置检查，也是权限
     * 被拦时唯一还会走到的位置）与 `SessionController.startConnection`（自动补连接）。
     */
    fun log(
        context: Context,
        sink: (String) -> Unit,
    ): Report {
        val report = inspect(context)
        val signature = report.signature()
        val now = SystemClock.uptimeMillis()
        if (signature == lastSignature && now - lastLoggedAtMillis < REPEAT_QUIET_MILLIS) {
            sink("PREFLIGHT: 与上次相同——${report.headline()}")
            return report
        }
        lastSignature = signature
        lastLoggedAtMillis = now
        report.describe().forEach(sink)
        return report
    }

    /** 只读当前状态。任何一项读取失败都按「未就绪」处理，不让体检本身抛异常。 */
    @SuppressLint("MissingPermission")
    fun inspect(context: Context): Report {
        val app = context.applicationContext
        val hasBluetoothPermission = granted(app, Manifest.permission.BLUETOOTH_CONNECT)
        // 没有 BLUETOOTH_CONNECT 时连 adapter 都别碰：真机上取 adapter 本身不抛，
        // 但后续任何一次 isEnabled/bondedDevices 都会抛 SecurityException。
        val adapter =
            if (hasBluetoothPermission) {
                runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            } else {
                null
            }
        val bluetoothEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

        val wifiPermissionGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                granted(app, Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                granted(app, Manifest.permission.ACCESS_FINE_LOCATION)
            }
        val wifiEnabled =
            runCatching {
                app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            }.getOrDefault(false)
        val locationEnabled =
            runCatching {
                val manager = app.getSystemService(LocationManager::class.java)
                manager != null && LocationManagerCompat.isLocationEnabled(manager)
            }.getOrDefault(false)

        val target = TargetDevicePrefs.get(app)
        val targetPaired =
            target != null &&
                runCatching {
                    adapter?.bondedDevices?.any { TargetDevicePrefs.matches(target, it.address) } == true
                }.getOrDefault(false)

        // 只用缓存值：这里绝不能触发一次可能要跑 4 秒 shell 的解析（连接流程就在眼前）
        val mac = DeviceIdentity.cachedBluetoothMac()
        val identityReady = !mac.isNullOrEmpty() && mac != DeviceIdentity.PLACEHOLDER_MAC
        val rawName = runCatching { adapter?.name }.getOrNull()
        val nameReady = DeviceIdentity.displayName(rawName) != null
        // 通知使用权 = 音乐卡（NowPlaying / 0x5001）的数据源。**不是 MediaProjection**：
        // 本版本的画面走双屏虚拟屏、声音走 CarLife 引擎 PCM 直通，都不需要录屏授权
        // （MediaProjection 相关代码只剩 AudioSender.start 这个没有调用方的旧入口，
        // 2026-11-11 核对）。缺它只影响车机上显示的歌名/封面，故只算 INFO。
        val notificationAccess =
            runCatching {
                androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(app)
                    .contains(app.packageName)
            }.getOrDefault(false)

        val checks =
            buildList {
                add(
                    Check(
                        Requirement.BLUETOOTH_PERMISSION,
                        hasBluetoothPermission,
                        if (hasBluetoothPermission) null else "未授权——系统不让本应用与车机建立 iAP2 会话",
                    ),
                )
                add(
                    Check(
                        Requirement.BLUETOOTH_ENABLED,
                        bluetoothEnabled,
                        if (bluetoothEnabled) null else "已关闭——无线 CarPlay 的配对通道全在这里",
                    ),
                )
                add(
                    Check(
                        Requirement.WIFI_PERMISSION,
                        wifiPermissionGranted,
                        if (wifiPermissionGranted) null else "未授权——连不上车机热点（应用专属网络）",
                    ),
                )
                add(
                    Check(
                        Requirement.WIFI_ENABLED,
                        wifiEnabled,
                        if (wifiEnabled) null else "已关闭——AirPlay 走 WiFi，视频/音频都发不出去",
                    ),
                )
                add(
                    Check(
                        Requirement.IDENTITY,
                        identityReady,
                        when {
                            identityReady -> "→ $mac"
                            DeviceIdentity.hasOverride(app) -> "手填地址格式异常，请重新填写"
                            mac.isNullOrEmpty() -> "尚未解析（连接时会再解析一次；占位值会让车机丢弃 0x4300）"
                            else -> "仍是占位值 $mac——车机会丢弃 0x4300 的无线组，请在设置里手填真实地址"
                        },
                    ),
                )
                add(
                    Check(
                        Requirement.LOCATION_SERVICE,
                        locationEnabled,
                        if (locationEnabled) {
                            null
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "已关闭——Android 13+ 读当前 WiFi 名（SSID）同样要它，" +
                                "关着只能退到「按当前 WiFi 探测」，无法按 SSID 精确绑定车机热点"
                        } else {
                            "已关闭——Android 12/12L 加入车机热点（应用专属网络）必须先开它"
                        },
                    ),
                )
                add(
                    Check(
                        Requirement.TARGET_PAIRED,
                        targetPaired,
                        when {
                            target == null -> "未选定（自动模式）：出站会尝试所有已配对设备，可能连到别的车机"
                            targetPaired -> "→ ${target.name}（${target.address}）"
                            else -> "「${target.name}」已不在已配对列表——请重新配对或改选目标"
                        },
                    ),
                )
                add(
                    Check(
                        Requirement.IDENTITY_NAME,
                        nameReady,
                        if (nameReady) {
                            "→ ${DeviceIdentity.deviceName(app)}"
                        } else {
                            "系统里是「${rawName.orEmpty()}」，车机蓝牙列表会显示成「未知设备」" +
                                "（iAP2 0x4E09 仍固定发 iPhone，不影响识别）"
                        },
                    ),
                )
                add(
                    Check(
                        Requirement.NOTIFICATIONS,
                        runCatching { NotificationManagerCompat.from(app).areNotificationsEnabled() }
                            .getOrDefault(false),
                        "不授权也能连，只是后台自动连接时看不到状态通知",
                    ),
                )
                add(
                    Check(
                        Requirement.NOTIFICATION_ACCESS,
                        notificationAccess,
                        if (notificationAccess) {
                            "已授权——车机上的歌名/封面可用"
                        } else {
                            "未授权——车机音乐卡与 0x5001 NowPlaying 拿不到歌名（不影响连接与推流）"
                        },
                    ),
                )
                add(
                    Check(
                        Requirement.BATTERY_OPTIMIZATION,
                        runCatching {
                            app.getSystemService(PowerManager::class.java)
                                ?.isIgnoringBatteryOptimizations(app.packageName) == true
                        }.getOrDefault(false),
                        "未忽略会限制后台存活：车机回连时本机可能已被系统冻结",
                    ),
                )
            }
        return Report(checks)
    }

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
