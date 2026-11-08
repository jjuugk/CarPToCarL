package io.github.shaonialife.carptocar.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 蓝牙「**只配对、不连接**」策略。
 *
 * 奥迪 MIB3 实测（2026-10-18，用户报告）：手机与车机处于蓝牙**已连接**状态
 * （HFP 通话 / A2DP 媒体配置文件在线）时，车机不开放无线 CarPlay 的 iAP2 服务
 * 窗口——表现为 `BT-C: 连接失败（车机未就绪或不在无线配对窗口）` 每 3 秒重试。
 * 真 iPhone 的无线 CarPlay 也不依赖这些配置文件：控制通道走 iAP2 RFCOMM（独立
 * SDP 服务），声音走 WiFi 上的 AirPlay。因此正确形态是：**系统级配对 + iAP2
 * RFCOMM，其余配置文件一律不连**。
 *
 * 实现说明：`BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)` 是
 * **@SystemApi**——编译期不可见，但运行时注解只要求 `BLUETOOTH_CONNECT`
 * （本应用已持有），因此反射可调。查询状态用公开的 `getProfileProxy` +
 * `connectedDevices`（需要主 Looper，应用进程有）。
 *
 * 曾试过把这段挪到 Shizuku UserService（shell uid）执行，实测不可行：
 * UserService 里 `BluetoothManager.getAdapter()` 返回 null（无主 Looper 的应用
 * 上下文），查询恒为「未连接」，反而掩盖真实状态。故固定在应用进程内做。
 */
object BtProfiles {
    private const val TAG = "BT-PROFILE"

    /** 守卫一直拿不到对端地址时，空转多久就收工（毫秒）。 */
    private const val NO_PEER_GIVE_UP_MILLIS = 60_000L

    /** 关心的配置文件（按断开优先级排列）。 */
    private val profiles: List<Int> =
        listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.LE_AUDIO,
        )

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "通话(HFP)"
            BluetoothProfile.A2DP -> "音频(A2DP)"
            BluetoothProfile.LE_AUDIO -> "低功耗音频(LE)"
            else -> "profile#$profile"
        }

    /** profile id → 已获取的代理（进程内复用；代理本身无会话状态）。 */
    private val proxies = HashMap<Int, BluetoothProfile>()

    /** 该地址当前在线（已连接）的配置文件名称。 */
    fun connectedNames(
        context: Context,
        address: String,
    ): List<String> =
        profiles.mapNotNull { id ->
            val p = proxy(context, id) ?: return@mapNotNull null
            val connected =
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                } catch (_: Throwable) {
                    false
                }
            if (connected) profileName(id) else null
        }

    /**
     * 尽力断开该设备的全部配置文件连接。
     *
     * @return 断开前在线的配置文件（空 = 本来就没连接，无需任何动作）
     */
    fun disconnect(
        context: Context,
        device: BluetoothDevice,
    ): List<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return emptyList()
            }
        return disconnectAddress(context, address, device)
    }

    /** 同 [disconnect]，只给地址（守卫按地址轮询时用）。 */
    fun disconnectAddress(
        context: Context,
        address: String,
        deviceHint: BluetoothDevice? = null,
    ): List<String> {
        val before = connectedNames(context, address)
        if (before.isEmpty()) return emptyList()
        val device = deviceHint ?: remoteDevice(context, address)
        for (id in profiles) {
            if (profileName(id) !in before) continue
            val proxy = proxy(context, id)
            if (proxy != null && device != null) disconnectProfile(proxy, device)
        }
        if (!awaitDisconnected(context, address, 1_500L) && device != null) {
            // 配置级断开未生效（个别 ROM 限流）：设备级断开兜底（@hide，同样只要求
            // BLUETOOTH_CONNECT）。车载/耳机当前连接的设备可能被一并断开，属预期。
            runCatching { BluetoothDevice::class.java.getMethod("disconnect").invoke(device) }
            awaitDisconnected(context, address, 500L)
        }
        return before
    }

    /**
     * 音频配置守卫：**会话期间持续**把该设备的 HFP/A2DP 断开（2026-11-08 按宝马 fork 移植）。
     *
     * 为什么一次性 [disconnect] 不够：车机（以及它那一侧的蓝牙栈）会在我们断开后
     * 自己把配置文件再拉回来——宝马 NBT EVO + 荣耀实测，握手期会有 2~4 分钟的
     * 「连上又断」循环，期间车机只要看到 HFP/A2DP 在线就不开放 iAP2 窗口。所以要在
     * 整段建链期反复确认它没被拉起来，iAP2 会话一建立（或用户断开）就收工。
     *
     * 跑在独立后台线程上；本方法阻塞到退出为止，返回「实际断开了几次」。
     */
    fun guardAudioProfiles(
        context: Context,
        addressProvider: () -> String?,
        shouldStop: () -> Boolean,
        onEvent: (String) -> Unit,
    ): Int {
        val started = SystemClock.uptimeMillis()
        var drops = 0
        var idlePolls = 0
        onEvent(
            "BT-GUARD: 开始盯音频配置文件（每 ${AudioProfileGuardPolicy.POLL_MILLIS}ms 一次，" +
                "上限 ${AudioProfileGuardPolicy.MAX_MILLIS / 1000}s，iAP2 会话建立即收工）",
        )
        while (!shouldStop()) {
            val elapsed = SystemClock.uptimeMillis() - started
            if (!AudioProfileGuardPolicy.shouldKeepGuarding(elapsed)) {
                onEvent(
                    "BT-GUARD: 已盯满 ${AudioProfileGuardPolicy.MAX_MILLIS / 1000}s，" +
                        "停止（断开 $drops 次）——仍未建立 iAP2 会话",
                )
                return drops
            }
            val address = addressProvider()
            if (address == null) {
                // 还没有对端（没指定目标、也还没建链）：空转 60 秒就收工，别留一条
                // 白睡的线程跑到 5 分钟上限。
                idlePolls++
                if (idlePolls * AudioProfileGuardPolicy.POLL_MILLIS >= NO_PEER_GIVE_UP_MILLIS) {
                    onEvent("BT-GUARD: 一直没有对端可盯（未指定目标、也没建链）→ 收工")
                    return drops
                }
            } else {
                idlePolls = 0
                val before = connectedNames(context, address)
                if (before.isNotEmpty()) {
                    val dropped = disconnectAddress(context, address)
                    if (dropped.isNotEmpty()) {
                        drops++
                        onEvent(
                            "BT-GUARD: 车机把 ${dropped.joinToString("/")} 拉回来了 → " +
                                "再次断开（第 $drops 次）",
                        )
                    }
                }
            }
            try {
                Thread.sleep(AudioProfileGuardPolicy.POLL_MILLIS)
            } catch (_: InterruptedException) {
                return drops
            }
        }
        return drops
    }

    /** 断开后仍在线的配置文件（用于日志判断是否真的断开）。 */
    fun stillConnected(
        context: Context,
        address: String,
    ): List<String> = connectedNames(context, address)

    private fun awaitDisconnected(
        context: Context,
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (connectedNames(context, address).isEmpty()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return connectedNames(context, address).isEmpty()
    }

    @SuppressLint("MissingPermission")
    private fun remoteDevice(
        context: Context,
        address: String,
    ): BluetoothDevice? =
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?.getRemoteDevice(address)
        } catch (_: Throwable) {
            null
        }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        synchronized(proxies) { proxies[profile]?.let { return it } }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val requested =
            try {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            id: Int,
                            service: BluetoothProfile,
                        ) {
                            ref.set(service)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(id: Int) {
                            // 蓝牙服务重启后旧代理失效：清缓存，下次重新获取
                            synchronized(proxies) { proxies.remove(id) }
                            latch.countDown()
                        }
                    },
                    profile,
                )
            } catch (_: Throwable) {
                false
            }
        if (!requested) return null
        // `getProfileProxy` 的回调经**主线程 Handler** 投递：若本方法跑在主线程，
        // 回调永远无法执行 → 必然白等到超时（1.2s）并返回 null。宁可显式失败也不
        // 静默卡顿——调用方必须转到后台线程（本对象所有入口都是后台调用）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "proxy() 在主线程调用——getProfileProxy 回调会被阻塞，返回 null")
            return null
        }
        if (!latch.await(1_200, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { synchronized(proxies) { proxies[profile] = it } }
    }

    /**
     * 断开某配置文件的连接。`disconnect(BluetoothDevice)` 是 @SystemApi（编译期
     * 不可见），反射调用；失败不抛，由调用方按最终状态判定。
     */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean =
        try {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
                ?.let { it as? Boolean }
                ?: true
        } catch (_: Throwable) {
            false
        }
}

/**
 * [BtProfiles.guardAudioProfiles] 的节奏与上限（纯逻辑，便于单测）。
 *
 * 轮询取 1.5 秒：车机把 HFP 拉回来到它真正听到「在线」之间通常只有几百毫秒，
 * 间隔再长就会漏掉；再短则每次都要走一遍 `getProfileProxy` 查询，白耗电。
 *
 * 上限 5 分钟：宝马 fork 实测的抖动窗口是 2~4 分钟，留 1 分钟余量。到点还在抖
 * 说明这条路走不通（车机根本不打算开 iAP2 窗口），继续断只会让用户看着蓝牙
 * 反复连断，不如停下来把控制权交回去。
 */
object AudioProfileGuardPolicy {
    const val POLL_MILLIS = 1_500L
    const val MAX_MILLIS = 300_000L

    /** 守卫是否还该继续盯：从开始计时起，未到上限就一直盯。 */
    fun shouldKeepGuarding(
        elapsedMillis: Long,
        maxMillis: Long = MAX_MILLIS,
    ): Boolean = elapsedMillis < maxMillis
}
