package io.github.shaonialife.carptocar.carplus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.share.connect.WifiOwnerConfig
import com.ucar.vehiclesdk.ICarConnectListener
import com.ucar.vehiclesdk.ICarInitCallback
import com.ucar.vehiclesdk.UCarAdapter
import com.ucar.vehiclesdk.UCarCommon.WorkMode
import com.ucar.vehiclesdk.UCarConfig
import com.ucar.vehiclesdk.UCarConnectState
import io.github.shaonialife.carptocar.util.Bg
import io.github.shaonialife.carptocar.util.SysShell
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CarPlusSdk {
    private const val BLE_TRACE_TAG = "CarPlusBle"
    private const val BLE_CALLBACK_WAIT_MS = 5_000L
    private val advertisingStatus = Regex("""^onAdvertisingSetStarted status (\d+)\b""")

    private val main = Handler(Looper.getMainLooper())
    var report: ((status: String?, log: String) -> Unit)? = null

    private var adapter: UCarAdapter? = null
    private var discovery: CarPlusDiscovery? = null
    private var wanted = false
    private var initializing = false
    private var initialized = false
    private var advertising = false
    private var advertisingAttempt = 0
    private var connected = false
    private var casting = false
    private var surface: Surface? = null
    private var touchDown = false
    private var touchX = 0
    private var touchY = 0

    private val connection = object : ICarConnectListener {
        override fun onPinCode(code: String?, deviceName: String?) = onMain {
            if (wanted && !code.isNullOrBlank()) {
                report?.invoke("ICCOA 配对码 $code · 请在原生 Car+ 中输入", "配对码已显示")
            }
        }

        override fun onConnectStateChanged(id: String?, state: Int, parameter: Int) = onMain {
            if (wanted) {
                report?.invoke(null, "会话状态 $state ($parameter)")
                when (state) {
                    UCarConnectState.UCAR_DEVICE_DISCONNECT -> if (connected) {
                        connected = false
                        pauseCast()
                        advertise()
                    }
                    UCarConnectState.UCAR_DEVICE_CONNECTED -> {
                        connected = true
                        discovery?.advertiseReady(false)
                        Log.i(BLE_TRACE_TAG, "ICCOA BLE idle")
                        startCast()
                        if (surface == null) updateStatus("ICCOA 已连接 · 等待 CarPlay 视频通道")
                    }
                }
            }
        }

        override fun onConnectingProgress(id: String?, progress: Int) = Unit

        override fun onReconfigureWifiInfo(config: WifiOwnerConfig?) = Unit

        override fun onRethinkBluetoothConnect(rethink: Boolean) = Unit

        override fun onSelectWorkMode(id: String?, supported: WorkMode?): WorkMode =
            if (supported?.isSupportNormalCast == true) WorkMode.NORMAL_CAST else WorkMode.NONE
    }

    fun start(context: Context, refresh: Boolean) {
        if (connected || initializing || advertising && !refresh) return
        wanted = true
        try {
            val sdkContext = discovery ?: CarPlusDiscovery(context.applicationContext).also { discovery = it }
            sdkContext.watch()
            val sdk = adapter ?: UCarAdapter.getInstance().also { adapter = it }
            if (initialized) {
                advertise()
                return
            }
            context.assets.open("ccd.data").close()
            val config = config(context)
            sdk.registerCarConnectListener(connection)
            initializing = true
            updateStatus("正在初始化 ICCOA 接收端")
            val callback = object : ICarInitCallback {
                override fun onInitSuccess() = onMain {
                    initializing = false
                    initialized = true
                    if (wanted) advertise()
                }

                override fun onInitFailed(code: Int) = onMain {
                    initializing = false
                    if (wanted) updateStatus("ICCOA SDK 初始化失败 ($code) · 点此重试")
                }
            }
            if (!sdk.init(sdkContext, config, callback)) {
                initializing = false
                updateStatus("ICCOA SDK 未接受初始化 · 点此重试")
            }
        } catch (_: java.io.FileNotFoundException) {
            initializing = false
            updateStatus("ICCOA SDK 缺少 ccd.data")
        } catch (error: Exception) {
            initializing = false
            updateStatus("ICCOA 初始化失败 (${error.javaClass.simpleName}) · 点此重试")
        } catch (error: LinkageError) {
            initializing = false
            updateStatus("ICCOA SDK 运行时不兼容 (${error.javaClass.simpleName})")
        }
    }

    fun setSurface(newSurface: Surface?) {
        val previous = surface
        surface = null
        pauseCast()
        previous?.release()
        surface = newSurface?.takeIf { it.isValid }
        if (surface == null) {
            newSurface?.release()
            if (connected) updateStatus("ICCOA 已连接 · 等待 CarPlay 视频通道")
        } else {
            startCast()
        }
    }

    fun stop() {
        wanted = false
        Log.i(BLE_TRACE_TAG, "ICCOA BLE idle")
        discovery?.advertiseReady(false)
        discovery?.stopWatching()
        advertisingAttempt++
        connected = false
        pauseCast()
        surface?.release()
        surface = null
        runCatching { adapter?.stopAdvertise() }
        runCatching { adapter?.disconnect() }
        advertising = false
        updateStatus("ICCOA 接收端已停止 · 点此启动")
    }

    private fun advertise() {
        val sdk = adapter ?: return
        discovery?.advertiseReady(false)
        if (advertising) runCatching { sdk.stopAdvertise() }
        val attempt = ++advertisingAttempt
        val since = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val marker = "ICCOA BLE request $attempt"
        Log.i(BLE_TRACE_TAG, marker)
        advertising = runCatching { sdk.startAdvertise() }.getOrDefault(false)
        if (advertising) discovery?.advertiseReady(true)
        updateStatus(
            if (advertising) "ICCOA 广播请求已提交 · 请打开原生 Car+"
            else "ICCOA SDK 未接受广播请求 · 点此重试",
        )
        if (advertising) readAdvertisingCallback(attempt, since, marker)
        startCast()
    }

    /** The SDK exposes its Android advertising callback through its own process logs. */
    private fun readAdvertisingCallback(attempt: Int, since: String, marker: String) {
        main.postDelayed({
            if (attempt != advertisingAttempt) return@postDelayed
            Bg.run("iccoa-ble-callback") {
                val result = SysShell.exec(
                    "/system/bin/logcat -b main --pid=${Process.myPid()} -d -T '$since' -v raw -s " +
                        "$BLE_TRACE_TAG:I UCar_BLEService:I easylog_BLEService:I",
                    timeoutMs = 2_000,
                )
                val lines = result.output.lineSequence().dropWhile { it.trim() != marker }.toList()
                val status = lines.asReversed().firstNotNullOfOrNull {
                    advertisingStatus.find(it.trim())?.groupValues?.get(1)
                }
                val message = when {
                    !result.ok -> "读取 BLE 回调失败（logcat 退出码 ${result.exitCode}）"
                    lines.isEmpty() -> "未找到本轮 BLE 日志标记"
                    status != null -> {
                        val outcome = if (status == "0") "广播已启动" else "广播启动失败"
                        "BLE 实际回调 onAdvertisingSetStarted status=$status（$outcome）"
                    }
                    lines.any { it.contains("Opening gatt server failed.") } ->
                        "未收到 BLE 启动回调，SDK 报告 GATT 服务开启失败"
                    lines.any { it.contains("startAdvertising() e:") } ->
                        "未收到 BLE 启动回调，SDK 报告广播启动异常"
                    else -> "本轮 5 秒内未读到 BLE 启动回调"
                }
                onMain {
                    if (attempt == advertisingAttempt) report?.invoke(null, message)
                }
            }
        }, BLE_CALLBACK_WAIT_MS)
    }

    private fun startCast() {
        if (!wanted || !initialized || !connected) return
        val target = surface?.takeIf { it.isValid } ?: return
        if (!casting) {
            casting = runCatching {
                adapter?.startCast(target, CarPlusVideo.WIDTH, CarPlusVideo.HEIGHT) == true
            }.getOrDefault(false)
        }
        if (connected) {
            updateStatus(
                if (casting) "ICCOA 已接入编码器 · 等待画面"
                else "ICCOA SDK 未接受视频 Surface",
            )
        }
    }

    private fun pauseCast() {
        if (!casting) return
        if (touchDown) sendTouch(MotionEvent.ACTION_CANCEL)
        touchDown = false
        casting = false
        runCatching { adapter?.pauseCast() }
    }

    fun onTouch(down: Boolean, x: Int, y: Int) {
        if (!casting || !down && !touchDown) return
        val action = when {
            !down -> MotionEvent.ACTION_UP
            touchDown -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_DOWN
        }
        // Some receivers clear the coordinates on release. Keep the last position for that report.
        if (down || x != 0 || y != 0) {
            touchX = x
            touchY = y
        }
        if (action == MotionEvent.ACTION_MOVE) {
            sendTouch(action)
        } else {
            val accepted = sendTouch(action)
            touchDown = down && accepted
            Log.d("CarPlusTouch", "${MotionEvent.actionToString(action)} ($touchX,$touchY) accepted=$accepted")
        }
    }

    private fun sendTouch(action: Int): Boolean = runCatching {
        adapter?.sendTouchEvent(action, 1, intArrayOf(0), intArrayOf(touchX), intArrayOf(touchY)) == true
    }.getOrDefault(false)

    private fun config(context: Context): UCarConfig {
        val prefs = context.getSharedPreferences("iccoa", Context.MODE_PRIVATE)
        val identity = prefs.getString("receiver_id", null)?.let { saved ->
            runCatching { Base64.decode(saved, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 6 }
        } ?: ByteArray(6).also { address ->
            SecureRandom().nextBytes(address)
            address[0] = ((address[0].toInt() and 0xfc) or 0x02).toByte()
            prefs.edit().putString("receiver_id", Base64.encodeToString(address, Base64.NO_WRAP)).apply()
        }
        return UCarConfig.Builder()
            .setCarBrMac(identity)
            // A nonzero custom field identifies our receiver without the SDK's stock test-car marker.
            .setCarCustomField(byteArrayOf(0x43, 0x50))
            .setDpi(320)
            .setRealDpi(context.resources.displayMetrics.densityDpi.coerceIn(120, 640))
            .setFps(CarPlusVideo.FPS)
            .setScreenWidth(CarPlusVideo.WIDTH)
            .setScreenHeight(CarPlusVideo.HEIGHT)
            .setVideoDisplayWidth(CarPlusVideo.WIDTH)
            .setVideoDisplayHeight(CarPlusVideo.HEIGHT)
            .setSupportP2P(true)
            .setSupportSoftAP(true)
            .setSupportHevc(false)
            .setSupportMic(false)
            .setCcdFilePath("ccd.data")
            .setSupportedWorkModes(WorkMode.Builder().setSupportNormalCast(true).build())
            .build()
    }

    private fun updateStatus(message: String) {
        report?.invoke(message, message)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }
}
