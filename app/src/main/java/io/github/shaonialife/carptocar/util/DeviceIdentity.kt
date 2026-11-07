package io.github.shaonialife.carptocar.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.shaonialife.carptocar.util.SysShell

/**
 * 本机身份解析：蓝牙 MAC 与设备名（无线 CarPlay 协议必需）。
 *
 * 背景（Audi MIB3 真车实测，2026-10-09）：Android 11+ 应用层调
 * `BluetoothAdapter.getAddress()` 只会拿到占位 `02:00:00:00:00:00`；
 * 而车机在解析 0x4300 的无线组（bluetoothTransportIdentifier）与后续
 * 0x4E0E 时会校验其是否与实际 RFCOMM 对端一致——占位值导致车机静默丢弃
 * （现象：链路层照常 ACK，CSM 层不再回应，永远等不到 0x4301）。
 *
 * 解析顺序（2026-10-24 重构）：
 *  0. **用户手填**（`settings` 偏好里的 `bt_mac_override`）——最高优先。
 *     Honor / MagicOS 之类机型把 MAC 在所有接口上都脱敏成 `XX:XX:XX:XX:xx:xx`
 *     （连 `dumpsys bluetooth_manager` 都脱敏，adb shell 也读不到），
 *     软件侧**没有任何合法途径**拿到真实地址，只能由用户填一次。
 *  1. sysfs 直读 `/sys/class/bluetooth/hci0/address`（多数机型 world-readable）
 *  2. `Settings.Secure` 直读（普通应用多数机型读不到，兜底）
 *  3. `BluetoothAdapter.getAddress()` 反射（旧 ROM 不脱敏）
 * 成功结果永久缓存；失败 10 秒内不重试（避免每次连接阻塞 4 秒）。
 *
 * 为什么必须真实地址：WinPlay（UxPlay 系接收端）在 `automaticPhone=false`
 * 时按「用户选中的那台 iPhone 的蓝牙地址」过滤，`SETUP session` 命中不符
 * 即回 `403`（2026-10-24 在接收端本机 `diagnostics.json` 实证：配对全 200、
 * `authenticated:true`，唯独 SETUP 403）。
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    /** 与主界面共用同一个偏好文件（MainActivity 用 getSharedPreferences("settings")）。 */
    private const val PREFS = "settings"
    private const val KEY_MAC_OVERRIDE = "bt_mac_override"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    /**
     * 各 ROM 在「本地蓝牙名未设置」时返回的占位串（小写比较）。
     *
     * `未知设备` 是 MagicOS/Cinemo 车机上都会原样显示的元凶：它既不是名字也不是空值，
     * 所以旧实现 `isNotBlank()` 放行了它，车机屏幕上就出现「未知设备」。
     */
    private val PLACEHOLDER_NAMES =
        setOf(
            "未知设备",
            "未知",
            "未知的设备",
            "未命名",
            "unknown",
            "unknown device",
            "null",
            "n/a",
            "na",
        )

    /** 手填地址（进程内缓存，避免每次 SETUP 都读偏好）。 */
    @Volatile
    private var overrideMac: String? = null

    @Volatile
    private var overrideLoaded = false

    @Volatile
    private var cachedMac: String? = null

    @Volatile
    private var lastAttemptAtMillis = 0L

    /**
     * 真实蓝牙 MAC（大写冒号格式）；解析失败返回占位。
     *
     * @param forceRetry 忽略静默窗口立即重试。ctrl 广播提前到「启动连接」后，
     *   首次注册可能赶上 Shizuku 尚未就绪、只能拿到占位值；而车机按 TXT id
     *   （=真实蓝牙 MAC）匹配是否邀请——占位值等于永远不被邀请。广播侧检测到
     *   占位时必须能立刻重试，而不是等满 10 秒静默窗口。
     */
    fun bluetoothMac(
        context: Context,
        forceRetry: Boolean = false,
    ): String {
        // 手填地址优先：它是唯一能突破厂商脱敏的来源，且必须能覆盖任何自动解析结果
        readOverride(context)?.let { return it }
        cachedMac?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return PLACEHOLDER_MAC
        synchronized(this) {
            readOverride(context)?.let { return it }
            cachedMac?.let { return it }
            lastAttemptAtMillis = now
            // 顺序按「免特权可行性」排：Shizuku 移除后 shell 只等同 adb shell 的普通
            // 应用身份，`content query settings/secure` 多数机型直接拒绝，所以必须
            // 先试不依赖任何特权的来源（sysfs / 适配器反射）。
            val resolved =
                resolveViaSysfs() ?: resolveViaSettings(context) ?: resolveViaAdapter(context) ?: resolveViaShell() ?: PLACEHOLDER_MAC
            if (resolved != PLACEHOLDER_MAC) cachedMac = resolved
            return resolved
        }
    }

    /**
     * 已解析并缓存的本机蓝牙 MAC；尚未解析成功返回 null（不触发 shell 查询）。
     *
     * 供 AirPlay 会话级 SETUP 这类「不能阻塞在 shell 上」的位置使用。
     */
    fun cachedBluetoothMac(): String? = overrideMac ?: cachedMac

    /** 当前是否在用「手填地址」（UI 上用来显示来源）。 */
    fun hasOverride(context: Context): Boolean = readOverride(context) != null

    /**
     * 保存手填的本机蓝牙地址（大小写/分隔符随意，内部规范化）。
     *
     * @return 规范化后的地址；`raw` 为空表示清除；无法解析时返回 null（调用方提示格式错误）
     */
    fun setOverride(
        context: Context,
        raw: String?,
    ): String? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MAC_OVERRIDE).apply()
            overrideMac = null
            overrideLoaded = true
            return PLACEHOLDER_MAC
        }
        val normalized = normalize(text) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAC_OVERRIDE, normalized).apply()
        overrideMac = normalized
        overrideLoaded = true
        return normalized
    }

    /** 手填地址；未设置或格式非法返回 null。 */
    fun overrideBluetoothMac(context: Context): String? = readOverride(context)

    private fun readOverride(context: Context): String? {
        if (overrideLoaded) return overrideMac
        synchronized(this) {
            if (overrideLoaded) return overrideMac
            val stored =
                context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MAC_OVERRIDE, null)
            overrideMac = stored?.let { normalize(it) }
            overrideLoaded = true
            return overrideMac
        }
    }

    /** 把 `aabbccddeeff` / `AA-BB-CC-DD-EE-FF` / `AA:BB:CC:DD:EE:FF` 统一成大写冒号格式。 */
    fun normalize(raw: String): String? {
        val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hex.length != 12) return null
        val mac =
            hex
                .chunked(2)
                .joinToString(":")
                .uppercase()
        if (!MAC_REGEX.matches(mac)) return null
        if (mac == PLACEHOLDER_MAC) return null
        return mac
    }

    /**
     * 车机蓝牙列表里要显示的**蓝牙名**（占位串一律换掉）。
     *
     * **不要把它塞进 iAP2 的 0x4E09**：那一层是真机身份，固定 `iPhone`
     * （见 `Iap2SenderSession.IAP2_DEVICE_NAME`）。2026-11-07 别克 E2LB 现场
     * 把两者混用，车机把我们归类成普通蓝牙设备。
     *
     * 取名顺序：适配器名 → `Settings.Secure.bluetooth_name` → 营销名 → MODEL。
     */
    fun deviceName(context: Context): String =
        displayName(adapterName(context))
            ?: displayName(storedBluetoothName(context))
            ?: fallbackDeviceName()

    /**
     * 把一个候选名净化成可用名：空白与各 ROM 的占位串（`未知设备` 等）返回 null。
     *
     * 抽成纯函数是为了让单测能直接咬住「未知设备不能发给车机」这条约定
     * （真机上 `BluetoothAdapter.getName()` 返回的就是这个串）。
     */
    fun displayName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) return null
        return name.takeIf { it.lowercase() !in PLACEHOLDER_NAMES }
    }

    /** 本机蓝牙名原始值（可能为空或占位）。 */
    private fun adapterName(context: Context): String? =
        try {
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.name
        } catch (_: Exception) {
            null
        }

    /** `Settings.Secure.bluetooth_name`（部分 ROM 上适配器名是占位、这里才是真名）。 */
    private fun storedBluetoothName(context: Context): String? =
        try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        } catch (_: Exception) {
            null
        }

    /** 占位/无意义名：车机会原样显示，用户看到的就是「未知设备」。 */
    private fun isUsableName(raw: String?): Boolean = displayName(raw) != null

    /** 拿不到可用蓝牙名时的兜底：营销名 → Build.MODEL → 应用名。 */
    private fun fallbackDeviceName(): String =
        marketingName()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "CarPToCarL"

    /** 营销名（`ro.product.marketname`），取不到返回 null。 */
    fun marketingName(): String? =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    @Volatile
    private var nameEnsureAttempted = false

    /**
     * 本机蓝牙名是占位串时改成可用名（进程内只试一次）。
     *
     * 为什么必须改系统名而不只是改 0x4E09：车机蓝牙列表显示的是**配对时的蓝牙名**，
     * 0x4E09 只影响 iAP2 会话内的称呼。名字叫「未知设备」时车机 HMI 认不出这台手机
     * （2026-11-07 别克 E2LB：屏幕上就是「未知设备 / 已连接」）。
     */
    fun ensureUsableBluetoothName(
        context: Context,
        log: (String) -> Unit = {},
    ) {
        if (nameEnsureAttempted) return
        nameEnsureAttempted = true
        val current = adapterName(context)
        if (isUsableName(current)) return
        val target = fallbackDeviceName()
        val renamed =
            try {
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.setName(target) ?: false
            } catch (_: Exception) {
                false
            }
        if (renamed) {
            log("BT: 本机蓝牙名是占位「${current.orEmpty()}」——已改名为「$target」（只影响车机蓝牙列表；iAP2 0x4E09 固定发 iPhone）")
        } else {
            log(
                "BT: 本机蓝牙名是占位「${current.orEmpty()}」——改名失败（ROM 拦了 setName），" +
                    "车机列表里仍可能显示旧名；iAP2 0x4E09 不受影响（固定发 iPhone）",
            )
        }
    }

    private fun resolveViaShell(): String? =
        try {
            val result =
                SysShell.exec(
                    "content query --uri content://settings/secure/bluetooth_address",
                    timeoutMs = 4_000,
                )
            MAC_REGEX.find(result.output)?.value?.uppercase()
        } catch (_: Exception) {
            null
        }

    /**
     * sysfs 直读：多数机型 `/sys/class/bluetooth/hci0/address` 是 world-readable
     * （不需要任何权限，纯文件读），这是 Shizuku 移除后**最可靠**的真实地址来源。
     * 有些 ROM 的 SELinux 会拦，读失败就继续往下走。
     */
    private fun resolveViaSysfs(): String? =
        SYSFS_MAC_PATHS.firstNotNullOfOrNull { path ->
            runCatching {
                val file = java.io.File(path)
                if (!file.canRead()) return@runCatching null
                file
                    .readText()
                    .trim()
                    .takeIf { MAC_REGEX.matches(it) && it != PLACEHOLDER_MAC }
                    ?.uppercase()
            }.getOrNull()
        }

    /**
     * `BluetoothAdapter.getAddress()` 反射（部分旧 ROM / 定制 ROM 不脱敏）。
     * Android 11+ 官方实现返回占位值，这里只是穷尽来源，失败无副作用。
     */
    private fun resolveViaAdapter(context: Context): String? =
        try {
            val adapter =
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?: return null
            val raw =
                adapter.javaClass
                    .getMethod("getAddress")
                    .invoke(adapter) as? String
            raw
                ?.takeIf { MAC_REGEX.matches(it) && it != PLACEHOLDER_MAC }
                ?.uppercase()
        } catch (_: Exception) {
            null
        }

    private fun resolveViaSettings(context: Context): String? =
        try {
            Settings.Secure
                .getString(context.contentResolver, "bluetooth_address")
                ?.takeIf { MAC_REGEX.matches(it) }
                ?.uppercase()
        } catch (_: Exception) {
            null
        }

    private const val RETRY_QUIET_MILLIS = 10_000L

    /** sysfs 蓝牙地址候选（按命中概率排序）。 */
    private val SYSFS_MAC_PATHS =
        listOf(
            "/sys/class/bluetooth/hci0/address",
            "/sys/devices/virtual/bluetooth/hci0/address",
            "/sys/module/bluetooth/parameters/bdaddr",
        )
}
