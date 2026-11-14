package io.github.shaonialife.carptocar.session

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.media.MediaListenerService
import io.github.shaonialife.carptocar.session.ConnectionPreflight.Requirement

/**
 * 「一键获取缺失权限」的可执行计划：把体检里**没通过**的项翻成一步步能真正走到的系统界面。
 *
 * 为什么需要它（2026-11-14 用户反馈）：体检只报「通知使用权未授权」，界面上却从来没有
 * 弹过任何框，用户以为是我们没申请。真相是**这项根本没有运行时权限弹窗**——
 * `NotificationListenerService` 的授权存在 `Settings.Secure.enabled_notification_listeners`
 * 里，只能由用户去「设置 → 通知使用权」亲手打开（或 adb `cmd notification allow_listener`）。
 * 旧实现靠 Shizuku 跑那条 adb 命令，Shizuku 移除后同一个调用只会静默失败，
 * 于是「检查一直红、永远没人提示怎么补」。
 *
 * 分工原则（不要混）：
 * - **能弹系统框的**（附近设备 / 通知 / 开蓝牙 / 忽略电池优化）→ 直接拉起，
 *   用户点一下就行；
 * - **只能跳设置页的**（WLAN 开关 / 位置服务 / 通知使用权）→ 打开对应页面并写清
 *   点哪里；其中通知使用权是唯一「既没弹窗、又必须在列表里找应用」的一项，
 *   API 30+ 用 `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` 直接把本应用那条顶到眼前。
 *
 * 本对象**只做规划与给 Intent**，不自己申请权限、不自己跳转（那部分要 Activity +
 * `registerForActivityResult`，见 `MainActivity.startPermissionRepair`）。
 */
object PermissionRepair {
    /**
     * 一步。
     *
     * @param title 体检/日志里的简称（与体检条目同名，便于对账）
     * @param hint 这一步会发生什么、用户要做什么（弹窗正文与日志共用）
     */
    enum class Step(
        val title: String,
        val hint: String,
    ) {
        /** 运行时权限：一次打包申请，系统按权限组弹框。 */
        PERMISSIONS(
            "系统权限（附近设备 / 通知）",
            "系统会依次弹「附近的设备」「通知」两个授权框，点「允许」",
        ),
        BLUETOOTH_ON("手机蓝牙开关", "系统会问「要开启蓝牙吗」，点「允许」"),
        WIFI_ON("手机 WLAN 开关", "Android 10+ 不允许应用自己开关 WLAN，请在打开的页面里手动打开"),
        LOCATION("位置服务", "开关在打开的页面顶部；Android 13+ 读 WiFi 名同样要它"),
        NOTIFICATION_ACCESS(
            "通知使用权（媒体会话）",
            "系统**没有**这项的授权弹窗（它不是运行时权限），" +
                "请在打开的页面里把 CarPToCarL 后面的开关打开",
        ),
        BATTERY("电池优化已忽略", "系统会问「是否允许应用始终在后台运行」，点「允许」"),

        /**
         * 兜底步，**不进 [stepsFor] 的计划**：Android 12/12L 没有
         * `POST_NOTIFICATIONS` 这个运行时权限，通知被关只能在系统页面里开。
         * 只有 `PERMISSIONS` 那一步发现「没有运行时权限可申请、但通知仍旧关着」时才用它。
         */
        NOTIFICATION_PAGE("应用通知设置", "在打开的页面里把允许通知打开"),
    }

    /** 需要运行时权限的那几项体检条目。 */
    private val runtimeRequirements =
        setOf(
            Requirement.BLUETOOTH_PERMISSION,
            Requirement.WIFI_PERMISSION,
            Requirement.NOTIFICATIONS,
        )

    /**
     * 体检里没通过的项 → 补齐顺序（纯函数，便于单测）。
     *
     * 顺序有讲究：先权限（不然连开关都读不到状态）→ 两个开关 → 位置 → 通知使用权 →
     * 电池优化。**「本机蓝牙地址 / 蓝牙名 / 目标车机已配对」永远不在计划里**：
     * 那三项是设置项不是权限，跳哪个页面都补不上，硬跳只会让用户白跑一趟。
     */
    internal fun stepsFor(missing: Set<Requirement>): List<Step> =
        buildList {
            if (missing.any { it in runtimeRequirements }) add(Step.PERMISSIONS)
            if (Requirement.BLUETOOTH_ENABLED in missing) add(Step.BLUETOOTH_ON)
            if (Requirement.WIFI_ENABLED in missing) add(Step.WIFI_ON)
            if (Requirement.LOCATION_SERVICE in missing) add(Step.LOCATION)
            if (Requirement.NOTIFICATION_ACCESS in missing) add(Step.NOTIFICATION_ACCESS)
            if (Requirement.BATTERY_OPTIMIZATION in missing) add(Step.BATTERY)
        }

    /** 现查一遍体检，给出「还要补哪几步」（已满足的不会出现）。 */
    fun plan(context: Context): List<Step> {
        val missing =
            ConnectionPreflight
                .inspect(context)
                .checks
                .filterNot { it.ready }
                .mapTo(mutableSetOf()) { it.requirement }
        return stepsFor(missing)
    }

    /**
     * 当前真正缺的运行时权限（缺哪个列哪个，一次交出去申请）。
     *
     * 与 `MainActivity.requiredConnectionPermissions()` 的差别：这里多带
     * `POST_NOTIFICATIONS`——它不属于「连不上」的硬前提，但既然用户点了「一键获取」，
     * 就一并问了（同一个按钮、一次操作，之后不再单独打扰）。
     */
    fun missingPermissions(context: Context): List<String> =
        buildList {
            if (!granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                if (!granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    /**
     * 这一步要打开的候选页（按优先级；调用方逐个试，第一个能拉起的就是它）。
     *
     * 给多个候选是因为国产 ROM 常把某个 Action 改成空实现或换名字
     * （荣耀 MagicOS 的「通知使用权」入口位置就与 AOSP 不同），
     * 兜底一律退到「应用详情」，用户至少能从那里往上翻。
     */
    fun candidates(
        context: Context,
        step: Step,
    ): List<Intent> =
        when (step) {
            // 运行时权限不跳页面，交给 ActivityResultContracts 弹框
            Step.PERMISSIONS -> emptyList()

            Step.BLUETOOTH_ON -> listOf(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

            Step.WIFI_ON -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))

            Step.LOCATION ->
                listOf(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    appDetails(context),
                )

            Step.NOTIFICATION_ACCESS ->
                listOf(
                    // API 30+：带组件名，系统直接展开本应用那一条，不必在几十个应用里翻
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            MediaListenerService.componentName(context).flattenToString(),
                        )
                    },
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    appDetails(context),
                )

            Step.BATTERY ->
                listOf(
                    // 直接弹「是否允许始终在后台运行」（清单里声明了 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    appDetails(context),
                )

            Step.NOTIFICATION_PAGE ->
                listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    },
                    appDetails(context),
                )
        }

    /** 应用详情页：所有 Action 都不认时的最后退路。 */
    private fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
