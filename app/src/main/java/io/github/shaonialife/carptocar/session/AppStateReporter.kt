package io.github.shaonialife.carptocar.session

import io.github.shaonialife.carptocar.airplay.AirPlaySpeechMode

/**
 * 「本机正在干什么」→ AirPlay `modesChanged.appStates`（AIS 23.3.7.5 / Table 23-56）。
 *
 * ## 为什么要有这个文件
 *
 * 规范把 `Speech` 的语义写得很直白（`Table 23-56` 原文）：
 *
 * > An entity is performing a speech operation. **All audio output must be disabled
 * > while speech is being recorded** to prevent interference with recognition algorithms.
 *
 * 也就是说：**我们一开始收音，车机就该把它自己的音源闭嘴**。此前我们从不报自己的
 * 状态——`appStates` 只是把车机 `changeModes` 里的值抄回来再回显——于是车机永远不知道
 * 「手机在听你说话」，车内音响照放，声音直接灌进手机麦克风，识别率自然好不了。
 *
 * ## 三个信号从哪来
 *
 * 1. **引擎语音会话**（主判据）：CarLife 的 `0x1001x 模块状态`里 `语音(4)` 从 0 变非 0
 *    —— 这正是「0x68008 `0x21` 到底有没有被引擎接住」的同一份证据（见
 *    [io.github.shaonialife.carptocar.carlife.VoiceKeyRouter] 的注释）。
 * 2. **TTS 音频**（区分「在听」还是「在说」）：9241 有 PCM 进来 = 助手正在对用户说话。
 *    只认**语音会话期间**的 TTS——导航播报也走 9241，但那是 `TurnByTurn` 语义，
 *    把它算成「手机在说话」会让车机在放音乐时莫名其妙地闭嘴。
 * 3. **通话状态**：沿用 `AudioManager.mode` 那条既有的判据（与 iAP2 `CallState` 同源），
 *    不另造一套。
 *
 * ## 为什么不直接在每个事件里发报文
 *
 * TTS 是每包都来的高频事件，语音会话里状态还会「收音 → 说话 → 收音」来回跳。所以这里
 * 只维护**期望状态**，由 [tick] 收敛（500ms 一次）后只在**真的变了**才发一次
 * `modesChanged`：报文不会因为一个 200ms 的音节就抖两回。
 */
class AppStateReporter(
    private val publish: (AirPlaySpeechMode, Boolean) -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val speakingHoldMillis: Long = SPEAKING_HOLD_MILLIS,
) {
    private val lock = Any()

    /**
     * 引擎是否在语音会话里（CarLife `语音(4)` 模块非 0）。
     *
     * `@Volatile`：写入来自 CarLife 控制通道读线程，读取来自 9241 音频读线程与收敛线程。
     */
    @Volatile
    private var engineVoice = false

    /** 最近一次收到 TTS PCM 的时刻（0 = 本轮语音会话还没有说过话）。同样跨线程读。 */
    @Volatile
    private var lastSpeechAudioAt = 0L

    /** 通话中（写入来自通话看门狗，读取来自收敛线程）。 */
    @Volatile
    private var callActive = false

    private var publishedSpeech = AirPlaySpeechMode.NONE
    private var publishedCall = false

    private var ticker: Thread? = null

    /**
     * 引擎语音会话开始/结束。
     *
     * 只在**边沿**上触发一次发送：模块状态是 1Hz 周期上报的，不认边沿会把同一句话
     * 每 200ms 发一遍。
     */
    fun onEngineVoice(active: Boolean) {
        synchronized(lock) {
            if (engineVoice == active) return
            engineVoice = active
            // 新一轮语音会话：上一轮的 TTS 时刻必须作废，否则会把「刚刚说过话」
            // 误当成「这一轮正在说话」。
            lastSpeechAudioAt = 0L
        }
        log(
            if (active) {
                "APPSTATE: 引擎进入语音会话 → 报 Speech=RecognizingSpeech" +
                    "（车机应收起自己的音源，避免灌进手机麦克风）"
            } else {
                "APPSTATE: 引擎语音会话结束 → 报 Speech=None（车机可恢复自己的音源）"
            },
        )
        emit("引擎语音会话${if (active) "开始" else "结束"}")
    }

    /** 语音会话期间收到一包 TTS PCM（9241）——说明助手正在对用户说话。 */
    fun onSpeechAudio() {
        if (!engineVoice) return
        lastSpeechAudioAt = nowMillis()
    }

    /** 通话状态变化（`AudioManager.mode` 判据，与 iAP2 `CallState` 同源）。 */
    fun onCallActive(active: Boolean) {
        synchronized(lock) {
            if (callActive == active) return
            callActive = active
        }
        log("APPSTATE: 通话${if (active) "开始 → 报 PhoneCall=true" else "结束 → 报 PhoneCall=false"}")
        emit("通话状态变化")
    }

    /** 收敛一次：把「收音/说话」的漂移算成稳定的语音态，只在变化时发报文。 */
    fun tick() = emit("周期收敛")

    /**
     * 起收敛线程（默认 500ms 一次）。会话开始时调用，会话收尾时 [stop]。
     *
     * **只换线程、不动状态**：CarLife 的语音会话常常比 AirPlay 会话早开（用户先点了
     * 「无线连接」，车机稍后才连上），这时 `engineVoice` 已经是 true；在这里复位会把
     * 那条状态抹掉，而模块状态是周期上报的，不变化就不会再有回调——等于整轮都报不出
     * 「在收音」。重复 start 也只是换一根线程，状态照旧。
     */
    fun start(intervalMillis: Long = TICK_INTERVAL_MILLIS) {
        ticker?.interrupt()
        ticker = null
        val worker =
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(intervalMillis)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    runCatching { tick() }
                }
            }
        worker.isDaemon = true
        worker.name = "appstate-tick"
        ticker = worker
        worker.start()
    }

    /**
     * 停掉收敛线程并复位状态。
     *
     * @param publishFinal true = 复位后再报一次 `Speech=None / PhoneCall=false`
     *   （通话结束但会话还在时用：让车机把音源放回来）；
     *   false = 静默复位（正在拆会话时用：那一刻报文已经没人收，发只会留下一条失败日志）。
     */
    fun stop(publishFinal: Boolean = true) {
        ticker?.interrupt()
        ticker = null
        synchronized(lock) {
            engineVoice = false
            callActive = false
            lastSpeechAudioAt = 0L
        }
        if (publishFinal) {
            emit("会话收尾")
        } else {
            synchronized(lock) {
                publishedSpeech = AirPlaySpeechMode.NONE
                publishedCall = false
            }
        }
    }

    /** 一行诊断（会话启动/断开时进日志）。 */
    fun describe(): String =
        synchronized(lock) {
            val speech =
                when {
                    !engineVoice -> "无语音会话"
                    lastSpeechAudioAt > 0 && nowMillis() - lastSpeechAudioAt <= speakingHoldMillis ->
                        "语音会话（正在说话）"
                    else -> "语音会话（收音中）"
                }
            "$speech，通话=${if (callActive) "是" else "否"}，已发布=$publishedSpeech/$publishedCall"
        }

    private fun emit(reason: String) {
        val speech: AirPlaySpeechMode
        val call: Boolean
        synchronized(lock) {
            speech =
                when {
                    !engineVoice -> AirPlaySpeechMode.NONE
                    lastSpeechAudioAt > 0 && nowMillis() - lastSpeechAudioAt <= speakingHoldMillis ->
                        AirPlaySpeechMode.SPEAKING
                    else -> AirPlaySpeechMode.RECOGNIZING
                }
            call = callActive
            if (speech == publishedSpeech && call == publishedCall) return
            publishedSpeech = speech
            publishedCall = call
            log("APPSTATE: $reason → Speech=${speech.name} PhoneCall=$call（发 modesChanged）")
        }
        runCatching { publish(speech, call) }
            .onFailure { log("APPSTATE: 上报失败 - ${it.message}") }
    }

    companion object {
        /** 收敛周期：语音会话里的状态抖动在 0.5s 内基本能稳定下来。 */
        const val TICK_INTERVAL_MILLIS = 500L

        /**
         * 最后一包 TTS 之后多久才认为「说完了」。
         *
         * 取 700ms：助手朗读是连续 PCM，包间隔通常 20~60ms；700ms 足以跨过句间停顿，
         * 又短到「说完 → 等用户回答」这段收音期能及时切成 `RecognizingSpeech`。
         */
        const val SPEAKING_HOLD_MILLIS = 700L
    }
}
