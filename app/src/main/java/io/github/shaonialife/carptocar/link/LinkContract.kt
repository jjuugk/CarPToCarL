package io.github.shaonialife.carptocar.link

import io.github.shaonialife.carptocar.airplay.AirPlayResourceState
import io.github.shaonialife.carptocar.airplay.AirPlaySpeechMode
import io.github.shaonialife.carptocar.airplay.PairingTrouble
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.audio.AudioProfile
import io.github.shaonialife.carptocar.audio.AudioTuning
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession

/**
 * CarPlay 链路的**中立契约**：两种实现共用的协商产物 + 会话接口。
 *
 * 为什么要有这一层：工程里同时存在两套链路
 *  - [io.github.shaonialife.carptocar.airplay.AirPlayClient] —— 自研，4,700+ 行，
 *    报文构造/解析/加密全在 Kotlin，已在多台真车验证；
 *  - [io.github.shaonialife.carptocar.protocolcore.AirPlayLink] —— 协议核版，
 *    全部协议决策移交 Rust（`libcardeck_protocol.so`），Kotlin 只剩运输与动作执行。
 *
 * 两者的协商产物**本来是同名嵌套类**（同一祖先各自演化），字段已经长得不一样：
 * `AirPlayClient.SetupInfo` 多 `displayFromCar`/`extendedFeatures`/`keepAliveLowPower`/
 * `bluetoothIds`，`AirPlayLink.SetupInfo` 早期少这些。于是「把链路换掉」这件事没法只换一个
 * 类——调用方拿到的是两个不兼容的类型。
 *
 * 这里把三者提成顶层类型并取**并集**（缺的给默认值），`ScreenStreamInfo`/`AudioStreamInfo`
 * 两边原本就逐字节一致，直接归一。此后 `SessionController` 面向 [AirPlayLinkSession] 编程，
 * 换实现不再牵动类型。
 *
 * 迁移原则：**只挪类型、不改语义**。所有新增字段都带默认值，构造点用命名参数，行为与
 * 迁移前逐字节一致（单测锁定）。
 */

/**
 * 会话建立（SETUP）返回的接收端参数。
 *
 * 字段语义详见各处行内注释；这里只强调两个**会造成误判**的默认值：
 *  - [displayFromCar] = false 表示宽高是手机兜底，不是车机参数，UI 必须照实标注；
 *  - [maxFps] = 0 表示车机**没有声明**，不是「0 fps」，目标帧率另由 `video/StreamFps.kt` 判定。
 */
class SetupInfo(
    val eventPort: Int,
    val timingPort: Int,
    val keepAlivePort: Int?,
    val enabledFeatures: List<String>,
    /** 主显示屏参数（`/info displays[]`，跟随接收端当前屏幕方向）。 */
    val displayWidth: Int,
    val displayHeight: Int,
    /** 宽高是否真的来自车机；false = 按手机横屏兜底，不能当车机参数展示。 */
    val displayFromCar: Boolean = false,
    val displayUuid: String? = null,
    /** 车机声明的解码帧率上限（`/info displays[].maxFPS`）。**0 = 未声明**。 */
    val maxFps: Int = 0,
    /** 车机有没有能承载方向盘媒体键的 HID 设备；false 时媒体键只能走 iAP2 `0x6800`。 */
    val hasMediaKeyHid: Boolean = false,
    /** `/info hidDevices[].uuid` 列表（0=触摸、1=旋钮）。 */
    val hidDeviceUuids: List<String> = emptyList(),
    /** uuid → HID 描述符（媒体键 Array 报告字节是 Usage 下标，必须解析描述符）。 */
    val hidDescriptors: Map<String, ByteArray> = emptyMap(),
    /** `/info.keepAliveSendStatsAsBody`：屏幕流 keepAlive 帧是否携带统计体。 */
    val keepAliveSendStatsAsBody: Boolean = false,
    /** `/info.extendedFeatures`；**null = 车机没声明这个键**，不是「什么都不支持」。 */
    val extendedFeatures: List<String>? = null,
    /** 车机是否声明 `keepAliveLowPower`（无媒体流时只发 UDP 保活）。 */
    val keepAliveLowPower: Boolean = false,
    /** 车机蓝牙模块 MAC 清单（`bluetoothIDs`）。 */
    val bluetoothIds: List<String> = emptyList(),
)

/** 屏幕流协商结果。 */
class ScreenStreamInfo(
    val streamConnectionId: Long,
    val dataPort: Int,
    /**
     * 帧加密方式。
     *
     * 自研链路一定给出 ChaCha 或 AES-CTR；协议核链路给 [StreamFrameCipher.CoreSealed]
     * ——加密在 native 内完成，Kotlin 侧的内嵌发送器不得再加密。
     */
    val cipher: StreamFrameCipher,
)

/** 音频流协商结果（UDP 数据口 + 控制口 + 流密钥）。 */
class AudioStreamInfo(
    val streamConnectionId: Long,
    val dataPort: Int,
    val controlPort: Int,
    val streamKey: ByteArray,
    /** 协商出的采样率（Hz）；AudioSender 必须用同一值采集。 */
    val sampleRate: Int = 48_000,
)

/**
 * 配对产物密钥。
 *
 * 两个来源字段集不同：自研链路给 `phoneWriteKey / phoneReadKey / sharedSecret`，
 * 协议核额外交付 `Events` 双钥（事件通道加解密全在 Rust，Kotlin 只透传）。
 * 取并集，缺的留空数组——**空数组 = 该实现不提供该钥**，消费方不得当成全零密钥用。
 */
class ControlKeys(
    val phoneWriteKey: ByteArray,
    /** 手机→收的读钥（自研链路控制通道双向加密用）；协议核链路给空数组。 */
    val phoneReadKey: ByteArray,
    /** X25519 共享密钥原文（数据流密钥派生的 ikm）。 */
    val sharedSecret: ByteArray,
    /** Events 双钥（协议核 Done 交付）；自研链路给空数组。 */
    val eventsWriteKey: ByteArray = ByteArray(0),
    val eventsReadKey: ByteArray = ByteArray(0),
)

/**
 * iAP 隧道（type-130 DataStream）的最小接口面。
 *
 * 车机→手机的 iAP2 数据（含触摸 HID）走这条加密 TCP；手机→车机走事件通道。
 * 只暴露 [SessionController] 与 [io.github.shaonialife.carptocar.airplay.VirtualIap2Link]
 * 真正用到的两个成员——隧道内部的帧格式、密钥派生、计数都留在实现类里。
 */
interface IapTunnel {
    /** 车机→手机 iAP2 消息回调（CSM 帧）。 */
    var onIapMessage: ((io.github.shaonialife.carptocar.iap2.Iap2Csm.Frame) -> Unit)?

    /** 手机→车机方向写入（虚拟链路的出站字节）。@return 是否写出 */
    fun writeIapBytes(bytes: ByteArray): Boolean
}

/**
 * CarPlay 会话接口：`SessionController` 面向它编程，具体实现由链路开关决定。
 *
 * 成员顺序按「连接 → 流 → 资源仲裁 → 事件通道」分组，与两套实现的内部结构对应。
 * **加成员的门槛**：只有 `SessionController` 真的调用才加；纯内部方法留在实现类里。
 */
interface AirPlayLinkSession {
    /**
     * 底层会话的稳定标识。
     *
     * 适配器层层包装时，`SessionController` 的「这还是不是当前那一轮会话」判定必须看
     * 底层对象而不是包装壳——[ClientLinkSession] 返回被包住的 `AirPlayClient`，
     * 协议核实现返回自身。**不要**用包装壳的引用比较，那会在每次重新包装时都误判成
     * 「换了一轮会话」，进而把活着的会话当成过期对象踢掉。
     */
    val linkIdentity: Any

    // ---------------- 生命周期 ----------------

    /** 宿主置位表示「本轮作废」，实现应在下一个检查点收工。 */
    var cancelled: Boolean

    /** 会话已建立（SETUP 完成且 RECORD 成功）。 */
    val sessionEstablished: Boolean

    /** 本轮曾建立过会话（断开后仍为 true，用于区分「从未连上」与「连上又掉」）。 */
    val wasSessionEstablished: Boolean

    /** 控制连接是否还活着。 */
    val controlSessionAlive: Boolean

    /** 控制通道是否已就绪（可发命令）。 */
    val controlSessionReady: Boolean

    /** 当前使用的接收端地址（日志与 UI 展示）。 */
    val activeHost: String?

    val pairingInProgress: Boolean

    /** 车机以 403 拒绝配对（视 iAP2 信任为已配对，应转「已配对快路径」）。 */
    val pairingForbidden: Boolean

    val pairSetupRejected: Boolean
    val attemptedPairing: Boolean
    val pairingTrouble: PairingTrouble?
    val pairingTerminal: Boolean
    val pairingIntegrityFailed: Boolean
    val pairingUnsupported: Boolean

    /** 只发一次 RECORD（多流场景下避免重复 RECORD 弄死车机）。 */
    var restrictSingleRecord: Boolean

    // ---------------- 端点 ----------------

    val currentEndpoint: Iap2SenderSession.CarPlayEndpoint

    /** @return true = 端点确有变化（已切换）；false = 与当前一致，未做任何事 */
    fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean

    // ---------------- 连接 ----------------

    fun reconnect(timeoutMillis: Int = 5_000): Boolean

    fun skipPairing(reason: String)

    fun runPairing(windowMillis: Long = 120_000L): ControlKeys?

    /** 首轮配对失败后带更长窗口重试（车机有时要等第二轮才受理）。 */
    fun retryWithPairing(windowMillis: Long = 30_000L): ControlKeys?

    /** 加密 SETUP 被拒后以明文（et=NONE）重试一次。 */
    fun retryWithoutEncryption(reason: String)

    /** 会话建立：SETUP → 拿端口/显示/HID 描述。 */
    fun runSetup(): SetupInfo?

    /** 会话级 RECORD。 */
    fun record(): Boolean

    /** 等控制通道可发命令（端点晚到时宿主用）。 */
    fun awaitControlSession(timeoutMs: Long = 4_000L): Boolean

    fun close()

    fun closeGracefully(budgetMillis: Long = 3_000L)

    /** 拆流但保留控制连接。@return true = 干净收起 */
    fun teardownQuietly(): Boolean

    /** 拆整轮会话（含控制连接）。 */
    fun teardownSession(): Boolean

    // ---------------- 流 ----------------

    fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String? = null,
    ): ScreenStreamInfo?

    fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning = AudioTuning.RAW_SPF,
        /** >0 = 把本端 UDP 端口写进 `dataPort`（麦克风上行）；0 = 纯下行。 */
        micPort: Int = 0,
        /** 建完流是否顺手核对接收端音量；第二条流应传 false（音量是会话级的）。 */
        fixVolume: Boolean = true,
        /** 首次 SETUP 超时（毫秒）。第二条流要短，不能拖住 RECORD。 */
        setupTimeoutMillis: Int = 8_000,
    ): AudioStreamInfo?

    /** 申请主音频资源（优先级 + 是否临时借用）。 */
    fun acquireAudioResource(
        priority: Int,
        temporary: Boolean,
    ): Boolean

    /** 拆指定类型的流。 */
    fun teardownStream(
        type: Long,
        displayUuid: String? = null,
    ): Boolean

    // ---------------- 资源仲裁 ----------------

    /** 当前是否还有活跃流（视频/音频）。 */
    val hasActiveStreams: Boolean

    val phoneOwnsScreen: Boolean
    val phoneOwnsAudio: Boolean
    val phoneBorrowsAudio: Boolean
    val resourceDescription: String

    /** 车机把音频收走/还回来的通知。 */
    var onCarAudioTransfer: ((AirPlayResourceState.CarAudioTransfer) -> Unit)?

    fun acquireScreenResource(
        fromRequestUi: Boolean = false,
        respectBorrow: Boolean = false,
    ): Boolean

    fun releaseAudioBorrow(): Boolean

    /** 车机改判后复述本地资源状态。 */
    fun republishModes(): Boolean

    fun assertModes(): Boolean

    /** 本端语音/通话态（影响车机是否让出音频）。 */
    fun setOwnAppState(
        speech: AirPlaySpeechMode,
        phoneCall: Boolean,
    ): Boolean

    // ---------------- HID ----------------

    fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int = 0,
    ): Boolean

    // ---------------- 事件通道 / iAP2 ----------------

    fun connectEventChannel(
        sessionKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit = {},
    ): Boolean

    /** 手机→车机 iAP2 消息（事件通道 iAPSendMessage / 控制通道，由实现决定）。 */
    fun sendIap(data: ByteArray): Boolean

    /** 车机→手机事件通道的 iAP2 链路字节（喂虚拟链）。 */
    fun onIapEventBytes(data: ByteArray)

    /** 控制通道 iAP2 写（虚拟链路 ACK 等）。 */
    fun postControlIap(
        frame: ByteArray,
        isCurrentEpoch: () -> Boolean = { true },
    )

    /** iAP 隧道（type-130 DataStream）；无隧道能力返回 null。 */
    fun runIapTunnel(sessionKey: ByteArray?): IapTunnel?

    // ---------------- 事件回调（宿主注入） ----------------

    var onMediaKey: ((Int) -> Unit)?
    var onHidKeysReleased: ((Set<Int>) -> Unit)?
    var onLocationSamples: ((List<String>) -> Unit)?
    var nowPlayingSource: (() -> CarPlayNowPlaying?)?
    var artworkSource: ((Int) -> ByteArray?)?
    var callStateSource: (() -> Int)?

    fun pushNowPlaying(quiet: Boolean = false)

    fun pushCallState()

    fun postNavigationDucking(duck: Boolean): Boolean

    // ---------------- 音频档位 ----------------

    fun audioCandidates(
        preferCompat: Boolean = false,
        preferRate: Int? = null,
    ): List<AudioProfile>

    fun audioProfileForType(audioType: String): AudioProfile?

    fun callCandidates(): List<AudioProfile>

    fun microphoneCandidates(): List<AudioProfile>

    /** 探针：让车机把麦克风音频推到本端临时端口，返回收到的字节数（0 = 没收到）。 */
    fun probeMicrophoneInput(
        audioType: String,
        formatBit: Long,
        sampleRate: Int,
        listenMillis: Long = 6_000,
    ): Int

    fun refreshVolume(reason: String)

    // ---------------- 保活与统计 ----------------

    fun startKeepAlive(
        host: String,
        port: Int,
    )

    fun startFeedbackLoop()

    /** 车机汇报的接收帧率（/feedback）；null = 车机不汇报。 */
    val screenReceiveFps: Double?

    fun screenReceiveSummary(): String?

    /** 受端身份标识（音频档位记忆的键）。 */
    fun receiverIdentity(): String
}
