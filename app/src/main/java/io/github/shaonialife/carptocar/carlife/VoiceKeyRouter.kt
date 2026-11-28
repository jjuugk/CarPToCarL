package io.github.shaonialife.carptocar.carlife

/**
 * 方控语音键状态机（2026-11-24 按 AMcar 语义全盘移植）。
 *
 * 车机发 `requestSiri` 三档，这里按 AMcar 的解释处理：**1=按下（预热）/ 2=激活 / 3=松开**。
 * 必须按住 ≥ [HOLD_MILLIS] 才真的唤起语音，短按只当误触；松手**不补结束键**，
 * 收尾交给引擎自己或 [LEASE_MILLIS] 租约到期后补一条 `VR_STOP`。
 *
 * 起/停都走 `0x68008` 硬键（`VR_START=0x21` / `VR_STOP=0x22`），与 AMcar 一致。
 * 本类只做纯状态判定，不碰 socket；下发由外部 `send` 回调决定在哪个线程执行。
 *
 * 注意：1.2.78 曾因这台车机的 `0x21` 无 `语音=1` 而改走 `0x10066`，本次按用户
 * 「全盘照抄」决定退回；判据仍是引擎自己的 `65574 … 语音=1`。
 *
 * 2026-11-28 定谳：`0x21` 当时「死的」根因不是协议而是通道——0x68008 全系硬键
 * 必须走 touch 通道（9340），此前误写 command 通道（7240）socket 成功但引擎不理。
 * 现已随旋钮一起迁移，本类的状态机逻辑不变。
 */
class VoiceKeyRouter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val send: (Int) -> Unit,
    private val log: (String) -> Unit = {},
    /** 长按松开（识别结束）后的通知，上层据此等助手回答播完再处理音频。 */
    private val onVoiceEnd: () -> Unit = {},
    /** 可注入的延时调度；JVM 单测传假实现即可，绝不触碰 Android Looper。 */
    scheduleDelay: ((Long, () -> Unit) -> Unit)? = null,
) {
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val scheduler: (Long, () -> Unit) -> Unit =
        scheduleDelay ?: { delay, action -> handler.postDelayed(action, delay) }

    companion object {
        /** 车机先把 1 当作按下/预热，不是 Apple 头文件里的 ButtonDown。 */
        const val SIRI_ACTION_PRESS = 1
        const val SIRI_ACTION_ACTIVATE = 2
        const val SIRI_ACTION_RELEASE = 3

        /** 短于这个时长算误触，与真车 iPhone 的 500ms 一致。 */
        const val HOLD_MILLIS = 500L

        /** 没有引擎状态回报时靠租约收尾：宁可早收，别让麦克风一直被占。 */
        const val LEASE_MILLIS = 60_000L
    }

    /** 本次按下的时刻（0 = 没有按下状态）。 */
    private var pressedAt = 0L

    /** 当前是否处于「已唤起、等结束」状态。 */
    @Volatile
    private var active = false

    /** 租约令牌：每次唤起自增，旧租约到点后不再补结束键。 */
    private var leaseToken = 0L

    /** 等待补足长按的令牌：松手即作废。 */
    private var pendingToken = 0L

    @Synchronized
    fun onSiriAction(action: Int?) {
        val now = nowMillis()
        when (action) {
            SIRI_ACTION_PRESS -> {
                pressedAt = now
                log("VOICE-BUTTON: 方控语音键按下（等待长按确认）")
            }

            SIRI_ACTION_ACTIVATE -> {
                val downAt = if (pressedAt != 0L) pressedAt else now
                val held = now - downAt
                if (held >= HOLD_MILLIS) {
                    startVoice("已按住 ${held}ms")
                } else {
                    val token = downAt
                    pendingToken = token
                    val remaining = HOLD_MILLIS - held
                    scheduler(remaining) {
                        synchronized(this) {
                            if (pendingToken == token && pressedAt == token) startVoice("补足长按")
                        }
                    }
                    log("VOICE-BUTTON: 激活已到，补足长按还差 ${remaining}ms")
                }
            }

            SIRI_ACTION_RELEASE -> {
                val downAt = pressedAt
                pressedAt = 0L
                pendingToken = 0L
                val held = if (downAt != 0L) now - downAt else -1L
                log("VOICE-BUTTON: 方控语音键松开" + if (held >= 0) "（按住 ${held}ms）" else "")
                // 长按松开 = 用户说完了；不补 34，只把「识别结束」交给上层观察。
                if (held >= HOLD_MILLIS) onVoiceEnd()
            }

            else -> log("VOICE-BUTTON: 未识别的 siriAction=$action（忽略）")
        }
    }

    /** HID Consumer `0xCF` 兜底：按下等价于 1+2，松开等价于 3。 */
    @Synchronized
    fun onHidDown(source: String = "0xCF") {
        log("VOICE-BUTTON: HID $source 按下")
        onSiriAction(SIRI_ACTION_PRESS)
        onSiriAction(SIRI_ACTION_ACTIVATE)
    }

    @Synchronized
    fun onHidUp(source: String = "0xCF") {
        log("VOICE-BUTTON: HID $source 松开")
        onSiriAction(SIRI_ACTION_RELEASE)
    }

    /** 新一轮会话：别把上一轮的按下/租约带过来。 */
    @Synchronized
    fun reset() {
        pressedAt = 0L
        pendingToken = 0L
        active = false
        leaseToken++
    }

    @Synchronized
    private fun startVoice(why: String) {
        if (active) {
            send(CarLifeProtocol.HardKey.VR_STOP)
            active = false
        }
        send(CarLifeProtocol.HardKey.VR_START)
        active = true
        val token = ++leaseToken
        scheduler(LEASE_MILLIS) {
            synchronized(this) {
                if (active && leaseToken == token) {
                    active = false
                    send(CarLifeProtocol.HardKey.VR_STOP)
                    log("VOICE-BUTTON: 语音键 ${LEASE_MILLIS / 1000}s 租约到期——补发结束键（34）")
                }
            }
        }
        log("VOICE-BUTTON: $why → 唤起车机语音（硬按键 33，等引擎「语音=1」确认）")
    }
}
