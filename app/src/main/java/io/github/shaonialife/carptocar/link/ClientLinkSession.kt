package io.github.shaonialife.carptocar.link

import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.airplay.AirPlayResourceState
import io.github.shaonialife.carptocar.airplay.AirPlaySpeechMode
import io.github.shaonialife.carptocar.airplay.PairingTrouble
import io.github.shaonialife.carptocar.audio.AudioProfile
import io.github.shaonialife.carptocar.audio.AudioTuning
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession

/**
 * 自研链路（[AirPlayClient]）的接口适配器。
 *
 * 为什么用适配器而不是让 [AirPlayClient] 直接 `: AirPlayLinkSession`：
 *  - 该类有 11 个 `private set` 属性（`sessionEstablished` / `pairingForbidden` 等），
 *    直接实现接口得把它们全部放开成公共可变——那是**扩大可变面**，会把「谁改了会话状态」
 *    这件事变得不可追；
 *  - 它有 20 多个带默认值的函数，Kotlin 不允许 override 声明默认值，只能把默认值删掉，
 *    而删掉会波及类内上百处调用点。
 *
 * 适配器把这些差异挡在外面：现有 4,700 行一行不动，接口面按需投影。
 * **纯转发，不含任何逻辑**——任何行为改动都应该落在 [AirPlayClient] 或 [ProtocolCoreSession] 里。
 */
class ClientLinkSession(
    val client: AirPlayClient,
) : AirPlayLinkSession {
    override val linkIdentity: Any get() = client

    // ---------------- 生命周期 ----------------

    override var cancelled: Boolean
        get() = client.cancelled
        set(value) {
            client.cancelled = value
        }

    override val sessionEstablished: Boolean get() = client.sessionEstablished

    override val wasSessionEstablished: Boolean get() = client.wasSessionEstablished

    override val controlSessionAlive: Boolean get() = client.controlSessionAlive

    override val controlSessionReady: Boolean get() = client.controlSessionReady

    override val activeHost: String? get() = client.activeHost

    override val pairingInProgress: Boolean get() = client.pairingInProgress

    override val pairingForbidden: Boolean get() = client.pairingForbidden

    override val pairSetupRejected: Boolean get() = client.pairSetupRejected

    override val attemptedPairing: Boolean get() = client.attemptedPairing

    override val pairingTrouble: PairingTrouble? get() = client.pairingTrouble

    override val pairingTerminal: Boolean get() = client.pairingTerminal

    override val pairingIntegrityFailed: Boolean get() = client.pairingIntegrityFailed

    override val pairingUnsupported: Boolean get() = client.pairingUnsupported

    override var restrictSingleRecord: Boolean
        get() = client.restrictSingleRecord
        set(value) {
            client.restrictSingleRecord = value
        }

    // ---------------- 端点 ----------------

    override val currentEndpoint: Iap2SenderSession.CarPlayEndpoint get() = client.currentEndpoint

    override fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean = client.updateEndpoint(newEndpoint, reason)

    // ---------------- 连接 ----------------

    override fun reconnect(timeoutMillis: Int): Boolean = client.reconnect(timeoutMillis)

    override fun skipPairing(reason: String) = client.skipPairing(reason)

    override fun runPairing(windowMillis: Long): ControlKeys? = client.runPairing(windowMillis)

    override fun retryWithPairing(windowMillis: Long): ControlKeys? = client.retryWithPairing(windowMillis)

    override fun retryWithoutEncryption(reason: String) = client.retryWithoutEncryption(reason)

    override fun runSetup(): SetupInfo? = client.runSetup()

    override fun record(): Boolean = client.record()

    override fun awaitControlSession(timeoutMs: Long): Boolean = client.awaitControlSession(timeoutMs)

    override fun close() = client.close()

    override fun closeGracefully(budgetMillis: Long) = client.closeGracefully(budgetMillis)

    override fun teardownQuietly(): Boolean = client.teardownQuietly()

    override fun teardownSession(): Boolean = client.teardownSession()

    // ---------------- 流 ----------------

    override fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String?,
    ): ScreenStreamInfo? = client.runScreenStream(sessionKey, displayUuid)

    override fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning,
        micPort: Int,
        fixVolume: Boolean,
        setupTimeoutMillis: Int,
    ): AudioStreamInfo? =
        client.runAudioStream(
            sharedSecret = sharedSecret,
            profile = profile,
            tuning = tuning,
            micPort = micPort,
            fixVolume = fixVolume,
            setupTimeoutMillis = setupTimeoutMillis,
        )

    override fun teardownStream(
        type: Long,
        displayUuid: String?,
    ): Boolean = client.teardownStream(type, displayUuid)

    // ---------------- 资源仲裁 ----------------

    override val hasActiveStreams: Boolean get() = client.hasActiveStreams

    override val phoneOwnsScreen: Boolean get() = client.phoneOwnsScreen

    override val phoneOwnsAudio: Boolean get() = client.phoneOwnsAudio

    override val phoneBorrowsAudio: Boolean get() = client.phoneBorrowsAudio

    override val resourceDescription: String get() = client.resourceDescription

    override var onCarAudioTransfer: ((AirPlayResourceState.CarAudioTransfer) -> Unit)?
        get() = client.onCarAudioTransfer
        set(value) {
            client.onCarAudioTransfer = value
        }

    override fun acquireScreenResource(
        fromRequestUi: Boolean,
        respectBorrow: Boolean,
    ): Boolean = client.acquireScreenResource(fromRequestUi, respectBorrow)

    override fun acquireAudioResource(
        priority: Int,
        temporary: Boolean,
    ): Boolean = client.acquireAudioResource(priority, temporary)

    override fun releaseAudioBorrow(): Boolean = client.releaseAudioBorrow()

    override fun republishModes(): Boolean = client.republishModes()

    override fun assertModes(): Boolean = client.assertModes()

    override fun setOwnAppState(
        speech: AirPlaySpeechMode,
        phoneCall: Boolean,
    ): Boolean = client.setOwnAppState(speech, phoneCall)

    // ---------------- HID ----------------

    override fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int,
    ): Boolean = client.sendHidSetInputMode(deviceUuidHex, mode)

    // ---------------- 事件通道 / iAP2 ----------------

    override fun connectEventChannel(
        sessionKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit,
    ): Boolean = client.connectEventChannel(sessionKey, setup, onCommand)

    override fun sendIap(data: ByteArray): Boolean = client.sendIap(data)

    override fun onIapEventBytes(data: ByteArray) = client.onIapEventBytes(data)

    override fun postControlIap(
        frame: ByteArray,
        isCurrentEpoch: () -> Boolean,
    ) = client.postControlIap(frame, isCurrentEpoch)

    override fun runIapTunnel(sessionKey: ByteArray?): IapTunnel? = client.runIapTunnel(sessionKey)

    // ---------------- 事件回调 ----------------

    override var onMediaKey: ((Int) -> Unit)?
        get() = client.onMediaKey
        set(value) {
            client.onMediaKey = value
        }

    override var onHidKeysReleased: ((Set<Int>) -> Unit)?
        get() = client.onHidKeysReleased
        set(value) {
            client.onHidKeysReleased = value
        }

    override var onLocationSamples: ((List<String>) -> Unit)?
        get() = client.onLocationSamples
        set(value) {
            client.onLocationSamples = value
        }

    override var nowPlayingSource: (() -> CarPlayNowPlaying?)?
        get() = client.nowPlayingSource
        set(value) {
            client.nowPlayingSource = value
        }

    override var artworkSource: ((Int) -> ByteArray?)?
        get() = client.artworkSource
        set(value) {
            client.artworkSource = value
        }

    override var callStateSource: (() -> Int)?
        get() = client.callStateSource
        set(value) {
            client.callStateSource = value
        }

    override fun pushNowPlaying(quiet: Boolean) = client.pushNowPlaying(quiet)

    override fun pushCallState() = client.pushCallState()

    override fun postNavigationDucking(duck: Boolean): Boolean = client.postNavigationDucking(duck)

    // ---------------- 音频档位 ----------------

    override fun audioCandidates(
        preferCompat: Boolean,
        preferRate: Int?,
    ): List<AudioProfile> = client.audioCandidates(preferCompat, preferRate)

    override fun audioProfileForType(audioType: String): AudioProfile? = client.audioProfileForType(audioType)

    override fun callCandidates(): List<AudioProfile> = client.callCandidates()

    override fun microphoneCandidates(): List<AudioProfile> = client.microphoneCandidates()

    override fun probeMicrophoneInput(
        audioType: String,
        formatBit: Long,
        sampleRate: Int,
        listenMillis: Long,
    ): Int = client.probeMicrophoneInput(audioType, formatBit, sampleRate, listenMillis)

    override fun refreshVolume(reason: String) = client.refreshVolume(reason)

    // ---------------- 保活与统计 ----------------

    override fun startKeepAlive(
        host: String,
        port: Int,
    ) = client.startKeepAlive(host, port)

    override fun startFeedbackLoop() = client.startFeedbackLoop()

    override val screenReceiveFps: Double? get() = client.screenReceiveFps

    override fun screenReceiveSummary(): String? = client.screenReceiveSummary()

    override fun receiverIdentity(): String = client.receiverIdentity()
}
