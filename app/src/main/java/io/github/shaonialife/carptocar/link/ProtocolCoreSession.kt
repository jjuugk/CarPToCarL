package io.github.shaonialife.carptocar.link

import android.content.Context
import io.github.shaonialife.carptocar.airplay.AirPlayResourceState
import io.github.shaonialife.carptocar.airplay.AirPlaySpeechMode
import io.github.shaonialife.carptocar.airplay.PairingTrouble
import io.github.shaonialife.carptocar.audio.AudioProfile
import io.github.shaonialife.carptocar.audio.AudioTuning
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.protocolcore.AirPlayLink

/**
 * **协议核链路**：v2 里 [AirPlayLinkSession] 的**唯一实现**，协议决策全部交给
 * `libcardeck_protocol.so`（Rust `fw::airplay`），Kotlin 只剩运输与动作执行。
 *
 * v2 没有链路开关：`SessionController` 只构造本类。自研链路（`airplay/AirPlayClient`）
 * 保留在源码树里作参照与单测载体，但没有任何运行路径能走到它。
 *
 * ## 与自研链路的形态差异（重要）
 *
 * Rust 的决策机是**整会话托管**的：`runFlow` 一次跑完 connect → pairing → setup →
 * RECORD 全链，中间不回调 Kotlin。而 [SessionController] 的调用形态是分步的
 * （`runPairing()` → `runSetup()` → `record()`）。本类用**结果缓存**把两者对上：
 * 第一次 `runPairing()` 就触发整条 flow，`runSetup()`/`record()` 只是回放它的产物。
 * 因此会出现日志里三行连续打印但只有一次真实连接——那是设计，不是重复连接。
 *
 * ## 能力落差（日志里一律打 `LINK: 协议核链路不支持 …`）
 *
 * 下面这些成员在 [AirPlayLink] 侧没有等价物，本类给**安全默认值 + 一次性降级日志**，
 * 不是静默 no-op：
 *  - 单流 TEARDOWN（Rust 只有整会话 teardown）→ 返回 false，不断开会话（断开更糟）
 *  - 通话/麦克风音频档候选 → 空表（上层会退回主音频档）
 *  - 麦克风探针 → 0
 *  - NowPlaying / CallState 推送、导航 ducking → 无动作
 *  - 事件通道 iAP 字节透传、iAP 隧道句柄 → 无（隧道由 flow 内部的 SetupPlan 自建）
 *
 * 这些落差**不影响"能不能连上、能不能出画面"**，只影响通话、媒体信息与导航闪避。
 * 定位是：先验证协议核在真车上能把会话连起来，再逐项补这些能力。
 */
class ProtocolCoreSession(
    initialEndpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    context: Context? = null,
    endpointRefresher: ((Iap2SenderSession.CarPlayEndpoint) -> Iap2SenderSession.CarPlayEndpoint?)? = null,
    retryTick: ((attempt: Int) -> Unit)? = null,
    /**
     * 控制口连接失败回调（每轮 ensureConnected 全候选失败时一次）。
     *
     * 主控用它做「车机没起 AirPlay 服务」的叫醒：重发 0x4e0e+0x4300 并重公告 ctrl。
     * 不限流——节流在宿主侧（3 次 / 10s），因为「该不该再叫一次」是策略不是链路细节。
     */
    onConnectFailed: ((failures: List<String>) -> Unit)? = null,
) : AirPlayLinkSession {
    private val link =
        AirPlayLink(
            initialEndpoint = initialEndpoint,
            log = log,
            context = context,
            endpointRefresher = endpointRefresher,
            retryTick = retryTick,
        ).also { it.onConnectFailed = onConnectFailed }

    // 资源归属由 Rust 侧维护，Kotlin 这份只用于「本端声明」与日志——两边不同步时
    // 以 Rust 为准（它才是真正发 modesChanged 的一方）。
    private val resourceState = AirPlayResourceState()

    @Volatile private var everEstablished = false
    @Volatile private var attempted = false
    @Volatile private var noPairingHint = false

    /** flow 的产物缓存：分步调用靠它回放。 */
    @Volatile private var lastSetup: SetupInfo? = null

    @Volatile private var lastKeys: ControlKeys? = null

    private val activeStreams = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    @Volatile private var screenStreamUp = false

    /** 降级日志只打一次，避免每帧/每次调用刷屏。 */
    private val degradations = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override val linkIdentity: Any get() = this

    // ---------------- 生命周期 ----------------

    override var cancelled: Boolean
        get() = link.cancelled
        set(value) {
            link.cancelled = value
        }

    override val sessionEstablished: Boolean get() = everEstablished

    override val wasSessionEstablished: Boolean get() = everEstablished

    override val controlSessionAlive: Boolean get() = link.controlAlive

    override val controlSessionReady: Boolean get() = link.controlReady

    override val activeHost: String? get() = link.activeHost

    override val pairingInProgress: Boolean get() = link.pairingInProgress

    override val pairingForbidden: Boolean get() = link.pairingForbidden

    // 以下六项在自研链路里是「配对过程走到了哪一步」的中间态。Rust 决策机不把它们
    // 吐回 Kotlin，所以这里恒为初值——上层的分支只会走"未发生"那一支，
    // 不会误判成"已失败"。若将来需要精确到步，得让 Rust 事件带上阶段名。
    override val pairSetupRejected: Boolean = false

    override val attemptedPairing: Boolean get() = attempted

    override val pairingTrouble: PairingTrouble? = null

    override val pairingTerminal: Boolean = false

    override val pairingIntegrityFailed: Boolean = false

    override val pairingUnsupported: Boolean = false

    /** Rust 内部自驱 RECORD，Kotlin 侧不需要也没法限制单次 RECORD。 */
    override var restrictSingleRecord: Boolean = false

    // ---------------- 端点 ----------------

    override val currentEndpoint: Iap2SenderSession.CarPlayEndpoint get() = link.currentEndpoint

    override fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean = link.updateEndpoint(newEndpoint, reason)

    // ---------------- 连接 ----------------

    override fun reconnect(timeoutMillis: Int): Boolean = link.reconnectControl(timeoutMillis)

    override fun skipPairing(reason: String) {
        noPairingHint = true
        log("LINK: 跳过配对（$reason）——下一轮 flow 以已配对快路径启动")
    }

    override fun runPairing(windowMillis: Long): ControlKeys? {
        if (lastKeys != null) return lastKeys
        return runFlow(windowMillis)
    }

    override fun retryWithPairing(windowMillis: Long): ControlKeys? = runFlow(windowMillis)

    override fun retryWithoutEncryption(reason: String) {
        // 明文降级（et=NONE）由 Rust 决策机按车机应答自行判定，Kotlin 不能也不应插手。
        log("LINK: 明文降级由协议核决策机自决，忽略外部请求（$reason）")
    }

    override fun runSetup(): SetupInfo? = lastSetup ?: runFlow(DEFAULT_FLOW_WINDOW_MS)?.let { lastSetup }

    override fun record(): Boolean {
        // RECORD 已在 flow 的 setup 动作里由 Rust 发出。这里**必须不能**再发一次：
        // 重复 RECORD 会把宝马车机的整轮会话弄死（2026-11 实测）。
        val done = lastSetup != null
        logOnce("record", "LINK: RECORD 由协议核在 setup 链内完成，本次不再重发（结果=$done）")
        return done
    }

    override fun awaitControlSession(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (link.controlReady) return true
            if (cancelled) return false
            Thread.sleep(50)
        }
        return link.controlReady
    }

    override fun close() {
        link.close()
        lastSetup = null
        lastKeys = null
        screenStreamUp = false
        activeStreams.clear()
    }

    override fun closeGracefully(budgetMillis: Long) = close()

    override fun teardownQuietly(): Boolean {
        close()
        return true
    }

    override fun teardownSession(): Boolean {
        close()
        return true
    }

    // ---------------- 流 ----------------

    override fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String?,
    ): ScreenStreamInfo? {
        val plan = link.setupPlanJson(displayUuid = displayUuid, audio = null, includeScreen = true)
        val result = link.runFirmwareSetupPlan(plan) ?: return null
        val event = result.streams[STREAM_TYPE_SCREEN] ?: return null
        val info = link.screenStreamInfoFromEvent(event) ?: return null
        screenStreamUp = true
        activeStreams.add(STREAM_TYPE_SCREEN.toLong())
        return info
    }

    override fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning,
        micPort: Int,
        fixVolume: Boolean,
        setupTimeoutMillis: Int,
    ): AudioStreamInfo? {
        if (micPort > 0) {
            logOnce("mic-uplink", "LINK: 协议核链路暂不支持麦克风上行端口，本次按纯下行建流")
        }
        // 主屏已建好时**不能再带 110**：重复 SETUP 主屏会把已开的数据口作废（视频黑屏）。
        val plan =
            link.setupPlanJson(
                displayUuid = null,
                audio = profile,
                includeScreen = !screenStreamUp,
            )
        val result = link.runFirmwareSetupPlan(plan, setupTimeoutMillis.toLong()) ?: return null
        val event = result.streams[profile.streamType.toInt()] ?: return null
        val info = link.audioStreamInfoFromEvent(event, profile) ?: return null
        activeStreams.add(profile.streamType)
        return info
    }

    override fun acquireAudioResource(
        priority: Int,
        temporary: Boolean,
    ): Boolean =
        resourceState.acquireAudio(priority, temporary) { command ->
            publishModes("申请主音频", command)
        }

    override fun teardownStream(
        type: Long,
        displayUuid: String?,
    ): Boolean {
        // Rust 只暴露整会话 teardown，没有单流 TEARDOWN。这里**不**退化成"关整个会话"——
        // 上层拆音频流时关会话会把画面一起弄黑，比留着一条空音频流更糟。
        logOnce("teardown-$type", "LINK: 协议核链路不支持单流 TEARDOWN（type=$type），本次忽略")
        activeStreams.remove(type)
        if (type == STREAM_TYPE_SCREEN.toLong()) screenStreamUp = false
        return false
    }

    // ---------------- 资源仲裁 ----------------

    override val hasActiveStreams: Boolean get() = activeStreams.isNotEmpty()

    override val phoneOwnsScreen: Boolean get() = resourceState.phoneOwnsScreen

    override val phoneOwnsAudio: Boolean get() = resourceState.phoneOwnsAudio

    override val phoneBorrowsAudio: Boolean get() = resourceState.phoneBorrowsAudio

    override val resourceDescription: String get() = resourceState.describe()

    override var onCarAudioTransfer: ((AirPlayResourceState.CarAudioTransfer) -> Unit)? = null

    override fun acquireScreenResource(
        fromRequestUi: Boolean,
        respectBorrow: Boolean,
    ): Boolean =
        resourceState.acquireScreen(fromRequestUi, respectBorrow) { command ->
            publishModes("申请主屏", command)
        }

    override fun releaseAudioBorrow(): Boolean =
        resourceState.releaseAudioBorrow { command -> publishModes("归还主音频借用", command) }

    override fun republishModes(): Boolean = link.assertModes()

    override fun assertModes(): Boolean = link.assertModes()

    override fun setOwnAppState(
        speech: AirPlaySpeechMode,
        phoneCall: Boolean,
    ): Boolean {
        val speechChanged = resourceState.setOwnSpeech(speech)
        val callChanged = resourceState.setOwnPhoneCall(phoneCall)
        if (!speechChanged && !callChanged) return false
        return link.assertModes()
    }

    /**
     * 把本地资源状态推给车机。
     *
     * 命令体由 Rust 按它自己维护的会话状态构造（`nativeAirplayAssertModesControl`），
     * 这里传进去的 [command] 只用于日志——**这是已知落差**：Rust 不知道我们在
     * Kotlin 侧算出的借用/优先级，所以极端场景（导航借用与主音频并存）下
     * 车机看到的状态可能比实际保守。先按这个跑，等实车日志确认后再决定要不要
     * 把 command 透传给 Rust。
     */
    private fun publishModes(
        reason: String,
        command: Map<String, Any?>,
    ): Boolean {
        val accepted = link.assertModes()
        log("LINK: modesChanged（$reason）${resourceState.describe()} 接受=$accepted")
        return accepted
    }

    // ---------------- HID ----------------

    override fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int,
    ): Boolean = link.sendHidSetInputMode(deviceUuidHex, mode)

    // ---------------- 事件通道 / iAP2 ----------------

    override fun connectEventChannel(
        sessionKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit,
    ): Boolean {
        // 协议核要的是 Events 双钥（它自己派生），不是 shared secret；双钥来自 flow 的 Done 事件。
        // 无配对（明文会话 / flow 未交付密钥）时传 null：AirPlayLink 按明文建通道
        // （参考实现同样把 null 当 unencrypted）。**不能**因此跳过后面的通道——
        // 触摸 hidSendReport、changeModes、keepAlive 全在事件通道上，跳过等于没有触摸。
        val keys = lastKeys
        if (keys == null) {
            logOnce("event-plaintext", "LINK: 事件通道无 Events 双钥（明文会话），按未加密连接")
        }
        link.connectEventChannel(keys?.eventsWriteKey, keys?.eventsReadKey, setup, onCommand)
        return true
    }

    override fun sendIap(data: ByteArray): Boolean = link.sendIap(data)

    override fun onIapEventBytes(data: ByteArray) {
        logOnce("iap-event-bytes", "LINK: 事件通道 iAP 字节透传由协议核内部处理，宿主入口为空实现")
    }

    override fun postControlIap(
        frame: ByteArray,
        isCurrentEpoch: () -> Boolean,
    ) {
        // 协议核链路里"控制通道 iAP2 写"与 sendIap 是同一条路径（Rust 决定走控制还是
        // 事件通道），所以直接复用 sendIap，不再另开一条。
        if (!isCurrentEpoch()) return
        link.sendIap(frame)
    }

    override fun runIapTunnel(sessionKey: ByteArray?): IapTunnel? {
        // 隧道数据面由 flow 内部的 SetupPlan（streamType=130）自建（startTunnelDataPlane），
        // 不向宿主交付句柄。上层的"隧道为空则降级为事件通道"分支会自然生效。
        logOnce("iap-tunnel", "LINK: iAP 隧道由协议核在 SetupPlan 内自建，不向宿主交付句柄")
        return null
    }

    // ---------------- 事件回调 ----------------

    override var onMediaKey: ((Int) -> Unit)?
        get() = link.onMediaKey
        set(value) {
            link.onMediaKey = value
        }

    override var onHidKeysReleased: ((Set<Int>) -> Unit)? = null

    override var onLocationSamples: ((List<String>) -> Unit)? = null

    override var nowPlayingSource: (() -> CarPlayNowPlaying?)? = null

    override var artworkSource: ((Int) -> ByteArray?)? = null

    override var callStateSource: (() -> Int)? = null

    override fun pushNowPlaying(quiet: Boolean) {
        logOnce("now-playing", "LINK: 协议核链路暂无 NowPlaying 推送（0x5001），车机媒体信息不会更新")
    }

    override fun pushCallState() {
        logOnce("call-state", "LINK: 协议核链路暂无 CallState 推送（0x4155）")
    }

    override fun postNavigationDucking(duck: Boolean): Boolean {
        logOnce("nav-ducking", "LINK: 协议核链路暂无导航闪避命令，本次忽略")
        return false
    }

    // ---------------- 音频档位 ----------------

    override fun audioCandidates(
        preferCompat: Boolean,
        preferRate: Int?,
    ): List<AudioProfile> {
        val all = link.audioCandidates(preferCompat)
        return if (preferRate == null) all else all.filter { it.sampleRate == preferRate }.ifEmpty { all }
    }

    override fun audioProfileForType(audioType: String): AudioProfile? =
        audioCandidates(preferCompat = false, preferRate = null).firstOrNull { it.audioType == audioType }

    override fun callCandidates(): List<AudioProfile> {
        logOnce("call-candidates", "LINK: 协议核链路暂无通话音频档，通话将退回主音频档")
        return emptyList()
    }

    override fun microphoneCandidates(): List<AudioProfile> {
        logOnce("mic-candidates", "LINK: 协议核链路暂无麦克风音频档")
        return emptyList()
    }

    override fun probeMicrophoneInput(
        audioType: String,
        formatBit: Long,
        sampleRate: Int,
        listenMillis: Long,
    ): Int {
        logOnce("mic-probe", "LINK: 协议核链路暂无麦克风探针，返回 0（探针仅用于诊断）")
        return 0
    }

    override fun refreshVolume(reason: String) {
        log("LINK: 重新核对接收端音量（$reason）")
        link.fetchVolume()
    }

    // ---------------- 保活与统计 ----------------

    override fun startKeepAlive(
        host: String,
        port: Int,
    ) = link.startKeepAlive(host, port)

    override fun startFeedbackLoop() = link.startFeedbackLoop()

    override val screenReceiveFps: Double? = null

    override fun screenReceiveSummary(): String? = null

    override fun receiverIdentity(): String = link.receiverIdentityText

    // ---------------- 内部 ----------------

    /** 跑一次整链 flow（connect → pairing → setup → RECORD），缓存产物供分步调用回放。 */
    private fun runFlow(windowMillis: Long): ControlKeys? {
        attempted = true
        val setup = link.runFlow(windowMillis, noPairingHint)
        val keys = link.lastKeys
        lastSetup = setup
        lastKeys = keys
        if (setup != null) {
            everEstablished = true
            log(
                "LINK: 协议核连接序完成——SETUP 产物已缓存" +
                    "（eventPort=${setup.eventPort} display=${setup.displayWidth}x${setup.displayHeight}）",
            )
        } else {
            log("LINK: 协议核连接序未完成（窗口 ${windowMillis}ms）")
        }
        return keys
    }

    private fun logOnce(
        key: String,
        message: String,
    ) {
        if (degradations.add(key)) log(message)
    }

    private companion object {
        const val STREAM_TYPE_SCREEN = 110

        /** 与自研链路 `PAIRING_WINDOW_MILLIS` 同量级：Rust flow 在窗口内自决重试节拍。 */
        const val DEFAULT_FLOW_WINDOW_MS = 120_000L
    }
}
