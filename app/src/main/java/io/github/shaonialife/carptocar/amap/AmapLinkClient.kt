package io.github.shaonialife.carptocar.amap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 手机高德地图 linksdk 的 Binder 客户端：绑定 → 认证 → 注册 → 收回调。
 *
 * 协议细节全部来自 2026-11-24 的实机验证（探针 `.tmp/amap-link-probe/`），
 * 与参考实现（`fw867/hypercar` 的 `AmapLinkProbe` / `SangLuoCN/OneStep4`）一致。
 *
 * ## 三条实现铁律（都是实测踩出来的）
 *
 * 1. **`onTransact` 里必须先 `data.enforceInterface(CALLBACK)`**。Binder 在业务字段
 *    前面写了一段接口描述符，不先读掉会让第一个 `int` 读到描述符长度，
 *    `readString()` 返回 null 并把游标推到末尾（现象：`avail=312 flag=-1 meta=null`），
 *    整条数据流一个字都解不出来。
 * 2. **大包不需要分块协议**。参考实现里那一套 `begin_transfer`/`chunk_data`/`end_transfer`
 *    在实测中从未出现，`code=2` 的 FD 直接读到 EOF 就是完整 JSON（单帧约 3KB）。
 *    这里只保留 4MiB 上限做防护。
 * 3. **明文是主路径**。实测回调体就是 `{"bizType":113,"datas":"[...]"}`，没有加密信封。
 *    AES-GCM 解密只作兜底留着——真遇到了也不至于束手无策，但它不是主路。
 *
 * 本类**只负责取数与解包**，不判断导航状态、不接触 iAP2。状态判定在
 * [AmapNaviStateMachine]，发送在 `SessionController`。
 */
internal class AmapLinkClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onSnapshot: (AmapNaviSnapshot) -> Unit,
    private val onNavigateStop: () -> Unit,
) {
    enum class State { IDLE, BINDING, AUTHENTICATING, REGISTERED, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var frameCount: Int = 0
        private set

    @Volatile
    var lastFrameAt: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 高德版本名，自检面板要显示——它决定了字段/事务号是否仍然可信。 */
    @Volatile
    var amapVersion: String? = null
        private set

    /** 已绑定上并且注册成功。 */
    val isRegistered: Boolean get() = state == State.REGISTERED

    /** 懒创建，理由同 [AmapNaviSource] 的 ticker：JVM 单测里主 Looper 是 null。 */
    private var handlerRef: Handler? = null

    private fun handler(): Handler =
        handlerRef ?: Handler(Looper.getMainLooper()).also { handlerRef = it }

    private var bound = false
    private var bindRequested = false
    private var attempts = 0
    private var remote: IBinder? = null
    private var callback: LinkCallback? = null
    private var stopping = false
    private var lastNavLogAt = 0L
    private var lastLoggedManeuver = -1

    /** 认证拿到的 token，只留在内存，**不落盘、不进日志**。 */
    private var token: String = ""

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                if (stopping || service == null) return
                remote = service
                bound = true
                state = State.AUTHENTICATING
                log("AMAP: 已连上高德链接服务，开始认证")
                handshake(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onLinkLost("服务断开")
            }

            override fun onBindingDied(name: ComponentName?) {
                onLinkLost("绑定失效")
            }

            override fun onNullBinding(name: ComponentName?) {
                onLinkLost("返回空绑定（服务未导出或厂商拦截）")
            }
        }

    /** 启动：读版本 → 绑定。已绑定则空操作。 */
    fun start() {
        if (bindRequested && !stopping) return
        stopping = false
        lastError = null
        val version = readAmapVersion()
        if (version == null) {
            state = State.FAILED
            lastError = "未安装高德地图"
            return
        }
        amapVersion = version
        bind()
    }

    /** 停止：退订 + 解绑。不产出清除帧（由上层决定）。 */
    fun stop() {
        stopping = true
        bindRequested = false
        attempts = 0
        handlerRef?.removeCallbacksAndMessages(null)
        callback?.let { cb ->
            remote?.let { r ->
                runCatching { callBoolWithBinder(r, TX_UNREGISTER, cb) }
            }
        }
        if (bound) {
            runCatching { context.unbindService(connection) }
        }
        bound = false
        bindRequested = false
        remote = null
        callback = null
        token = ""
        state = State.IDLE
    }

    /** 供自检：模拟一次「等 10 秒看有没有帧」的结论行。 */
    fun diagnose(): String =
        when {
            state == State.FAILED -> "不可用：${lastError ?: "未知原因"}"
            state != State.REGISTERED -> "未就绪：${state.name}（${lastError ?: "无错误"}）"
            frameCount == 0 -> "已注册但未收到数据：请在高德地图里开始导航"
            else -> "正常：已收 $frameCount 帧，最后 ${if (lastFrameAt == 0L) "-" else "刚刚"}"
        }

    // ---------- 绑定与握手 ----------

    private fun readAmapVersion(): String? =
        try {
            val info = context.packageManager.getPackageInfo(PKG, 0)
            info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun bind() {
        if (bindRequested) return
        val intent = Intent(ACTION).setComponent(ComponentName(PKG, SERVICE))
        val ok =
            runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.onFailure { recordFailure("bindService 异常：${it.javaClass.simpleName}") }
                .getOrDefault(false)
        bindRequested = ok
        if (ok) {
            state = State.BINDING
            log("AMAP: 绑定高德导航服务（第 ${attempts + 1} 次）")
        } else {
            recordFailure("绑定被拒绝：请把高德加入系统「关联启动/自启动」白名单")
            scheduleRetry()
        }
    }

    private fun handshake(service: IBinder) {
        Thread({
            try {
                val auth = callString(service, TX_AUTHENTICATE, null)
                val authJson = JSONObject(auth ?: "{}")
                if (authJson.optString("status") != "success") {
                    recordFailure("认证未通过：${authJson.optString("status", "?")}")
                    return@Thread
                }
                token = authJson.optJSONObject("data")?.optString("token").orEmpty()
                val cb = LinkCallback()
                callback = cb
                val accepted = callBoolWithBinder(service, TX_REGISTER, cb)
                if (!accepted) {
                    recordFailure("注册监听被拒绝")
                    return@Thread
                }
                state = State.REGISTERED
                attempts = 0
                log("AMAP: 注册成功（${amapVersion ?: "?"}）；等到导航数据前不会有帧")
                // cmd 7/5 各请求一次即可：高德之后自己按 1Hz 主动推，不需要轮询。
                for (cmd in intArrayOf(7, 5)) {
                    val req =
                        JSONObject()
                            .put("cmd", cmd)
                            .put("requestId", (System.nanoTime() % 900_000 + 100_000).toInt())
                            .put("token", token)
                            .put("sessionId", "cptc-$cmd-${System.nanoTime()}")
                    val rsp = callString(service, TX_EXECUTE, req.toString())
                    val code = runCatching { JSONObject(rsp ?: "{}").optInt("errorCode", -1) }.getOrDefault(-1)
                    if (code != 0) log("AMAP: execute cmd=$cmd 返回 errorCode=$code")
                }
            } catch (t: Throwable) {
                recordFailure("握手失败：${t.javaClass.simpleName} ${t.message?.take(80) ?: ""}")
            }
        }, "amap-handshake").start()
    }

    private fun onLinkLost(reason: String) {
        if (stopping) return
        bound = false
        bindRequested = false
        remote = null
        callback = null
        token = ""
        state = State.FAILED
        lastError = reason
        log("AMAP: 链路断开（$reason）")
        scheduleRetry()
    }

    private fun recordFailure(message: String) {
        lastError = message
        state = State.FAILED
        log("AMAP: $message")
    }

    /** 3s / 6s / 12s 三次快速重试，之后每分钟一次；全程静默，只留一行日志。 */
    private fun scheduleRetry() {
        if (stopping) return
        attempts++
        val delay =
            when (attempts) {
                1 -> 3_000L
                2 -> 6_000L
                3 -> 12_000L
                else -> 60_000L
            }
        handler().postDelayed({
            if (!stopping) {
                bindRequested = false
                bind()
            }
        }, delay)
    }

    // ---------- Binder 调用 ----------

    private fun callString(
        service: IBinder,
        code: Int,
        arg: String?,
    ): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CONTROL)
            if (arg != null) data.writeString(arg)
            service.transact(code, data, reply, 0)
            reply.readException()
            reply.readString()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun callBoolWithBinder(
        service: IBinder,
        code: Int,
        cb: IBinder,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CONTROL)
            data.writeStrongBinder(cb)
            service.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ---------- 回调 ----------

    private inner class LinkCallback :
        Binder(),
        IInterface {
        init {
            attachInterface(this, CALLBACK)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK)
                return true
            }
            // 高德在注册瞬间会拿不是我们接口的 code 探一下（实测 1079135572），
            // 这里直接放行检查，别把它当异常刷日志。
            if (code != 1 && code != 2) return false
            try {
                data.enforceInterface(CALLBACK)
                when (code) {
                    1 -> data.readString()?.let { onPayload(it) }
                    else -> {
                        val meta = data.readString()
                        val hasFd = data.readInt() != 0
                        val fd = if (hasFd) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
                        try {
                            val payload = if (fd != null) readFd(fd) else meta
                            payload?.let { onPayload(it) }
                        } finally {
                            runCatching { fd?.close() }
                        }
                    }
                }
            } catch (t: Throwable) {
                // 单帧解析失败绝不能冒泡：Binder 线程异常会走到系统日志，用户只看到"没反应"。
                if (frameCount < 3) log("AMAP: 回调处理失败 code=$code ${t.javaClass.simpleName}")
            }
            return true
        }
    }

    private fun readFd(fd: ParcelFileDescriptor): String? {
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input: InputStream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) {
                    log("AMAP: 单帧超过 ${MAX_BYTES / 1024}KB，丢弃")
                    return null
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray().toString(StandardCharsets.UTF_8)
        }
    }

    /** 回调体处理：先解信封（兜底），再按 `bizType`/`type` 分发。 */
    private fun onPayload(raw: String) {
        val plain =
            try {
                decodeEnvelope(raw)
            } catch (t: Throwable) {
                if (frameCount < 3) log("AMAP: 信封解密失败 ${t.javaClass.simpleName}")
                return
            }
        val root =
            try {
                JSONObject(plain)
            } catch (t: Throwable) {
                return
            }
        if (root.optInt("bizType", -1) != BIZ_NAVIGATION) return
        val items = parseItems(root.opt("datas")) ?: return
        for (item in items) {
            when (item.optInt("type", -1)) {
                TYPE_NAVIGATION_STATE -> handleNavigation(item.opt("data"))
                // 导航停止 / 到达或手动退出：这两个才是清帧依据，`eventType` 不是。
                TYPE_NAV_STOP, TYPE_NAV_ARRIVED -> onNavigateStop()
                else -> Unit
            }
        }
    }

    private fun parseItems(datas: Any?): List<JSONObject>? {
        // 不能写 `val array: JSONArray? = ... ?: return null`：显式可空类型会让
        // 下面的智能转换失效（编译期报 nullable receiver）。
        val array =
            when (datas) {
                is JSONArray -> datas
                is String -> runCatching { JSONArray(datas) }.getOrNull()
                else -> null
            } ?: return null
        val out = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun handleNavigation(dataRaw: Any?) {
        val data = asObject(dataRaw) ?: return
        val maneuver = data.optInt("curManeuverID", -1)
        if (maneuver < 0) return
        val snapshot =
            AmapNaviSnapshot(
                maneuverId = maneuver,
                currentRoad = AmapNaviMapper.sanitizeRoad(data.optString("curRouteName")),
                nextRoad = AmapNaviMapper.sanitizeRoad(data.optString("nextRouteName")),
                segmentRemainMeters = data.optInt("segmentRemainDist", -1),
                routeRemainMeters = data.optInt("routeRemainDist", 0),
                routeRemainSeconds = data.optInt("routeRemainTime", 0),
            )
        frameCount++
        lastFrameAt = System.currentTimeMillis()
        // 每秒一帧，全量打日志会把会话日志挤爆：只在转向变化或每 5 秒打一行。
        val now = lastFrameAt
        if (maneuver != lastLoggedManeuver || now - lastNavLogAt >= 5_000L) {
            lastLoggedManeuver = maneuver
            lastNavLogAt = now
            log(
                "AMAP: 导航帧 #$frameCount 转向=${snapshot.maneuverId} " +
                    "${snapshot.currentRoad} → ${snapshot.nextRoad} · " +
                    "${snapshot.segmentRemainMeters}m / ${snapshot.routeRemainMeters}m",
            )
        }
        onSnapshot(snapshot)
    }

    private fun asObject(raw: Any?): JSONObject? =
        when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }

    /**
     * 信封兜底：`{"encrypted":true,"content":base64}` → AES/GCM。
     * 实测从未出现，但高德升级后真要加密了，至少有路可走。
     */
    private fun decodeEnvelope(raw: String): String {
        if (!raw.startsWith("{")) return raw
        val envelope = JSONObject(raw)
        if (!envelope.optBoolean("encrypted", false)) return raw
        val bytes = Base64.decode(envelope.getString("content"), Base64.DEFAULT)
        val buffer = ByteBuffer.wrap(bytes)
        val aadLen = buffer.int
        if (aadLen < 0 || aadLen > 1024 || buffer.remaining() < aadLen + 4) {
            throw IllegalArgumentException("AAD 长度非法")
        }
        val aad = ByteArray(aadLen).also { buffer.get(it) }
        val keyLen = buffer.int
        if ((keyLen != 16 && keyLen != 24 && keyLen != 32) || buffer.remaining() < keyLen + 28) {
            throw IllegalArgumentException("密钥长度非法")
        }
        val key = ByteArray(keyLen).also { buffer.get(it) }
        val nonce = ByteArray(12).also { buffer.get(it) }
        val cipherText = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PKG = "com.autonavi.minimap"
        const val SERVICE = "com.amap.bundle.wearable.connect.glass.AmapLinkService"
        const val ACTION = "com.autonavi.minimap.service.NAVIGATION_SERVICE"
        const val CONTROL = "com.amap.linksdk.aidldefine.ILinkSdkControl"
        const val CALLBACK = "com.amap.linksdk.aidldefine.ILinkSdkCallback"

        // ILinkSdkControl 事务号（实测）
        const val TX_AUTHENTICATE = 1
        const val TX_EXECUTE = 2
        const val TX_REGISTER = 3
        const val TX_UNREGISTER = 4

        const val BIZ_NAVIGATION = 113
        const val TYPE_NAVIGATION_STATE = 4
        const val TYPE_NAV_STOP = 100
        const val TYPE_NAV_ARRIVED = 200

        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
