package io.github.shaonialife.carptocar.carlife

/**
 * CarLife 车机端（HU）协议常量与小工具。
 *
 * 协议细节来自 2026-10-23 实机验证（见 docs/carlife-hijack-findings.md）：
 * 手机端（MD）监听 7 个 TCP 端口，车机端主动连接；控制通道 8 字节头，
 * 其余通道 12 字节头；protobuf 手写编码，不引入 protobuf 运行时。
 */
internal object CarLifeProtocol {

    // ---- 端口 ----
    const val PORT_CMD = 7240
    const val PORT_VIDEO = 8240
    const val PORT_MEDIA = 9240
    const val PORT_TTS = 9241
    const val PORT_VR = 9242
    const val PORT_TOUCH = 9340
    const val PORT_DATA = 9440

    // ---- 消息 ID ----
    const val HU_PROTOCOL_VERSION = 98305
    const val MD_VERSION_MATCH = 65538
    const val HU_STATISTICS = 98343
    const val MD_INFO = 65540
    const val HU_INFO = 98307
    const val HU_AUTHEN_ACK = 98380
    const val HU_VIDEO_ENC_INIT = 98311
    const val VIDEO_ENC_START = 98313

    /**
     * 帧率变更请求 / 确认（`VIDEO_ENCODER_FRAME_RATE_CHANGE`）。
     *
     * 命令号来自手机包 `com.baidu.carlife.core.CommonParams` 的常量表：
     *  - MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE      = 98316
     *  - MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 65549
     *  - MSG_CMD_VIDEO_ENCODER_PAUSE = 98314 / RESET = 98315 / START = 98313
     *
     * 载荷是 `CarlifeVideoFrameRateProto.CarlifeVideoFrameRate`（field1 = frameRate）。
     * 98311 里自带的 fps 字段引擎**只回显不生效**，真正的帧率通道是这条。
     */
    const val HU_VIDEO_FRAME_RATE = 98316
    const val MD_VIDEO_FRAME_RATE_DONE = 65549

    /** 视频编码器暂停 / 复位（保留常量，便于排查，不要用来改帧率）。 */
    const val HU_VIDEO_ENC_PAUSE = 98314
    const val HU_VIDEO_ENC_RESET = 98315

    /**
     * 帧率变更命令候选 id。
     *
     * 字节码铁证（2026-10-24 第四轮逆向）：`Recorder.changeFrameRate` 的范围判定是
     * `clamp(frame, 3, 60)`（const/16 #3 / const/16 #60），**不是旧结论的 3~30**。
     * 所以这里把上限修正为 60——98316 直接发 60 是合法且会被引擎采纳的。
     */
    val FRAME_RATE_PROBE_IDS = intArrayOf(HU_VIDEO_FRAME_RATE)

    /** 手机端引擎的硬上限（字节码实证 `Recorder.changeFrameRate` 判 3..60）。 */
    const val FRAME_RATE_MAX = 60
    const val MD_VIDEO_ENC_INIT = 65544
    const val VIDEO_ENC_DONE = 65551
    const val HU_FEATURE_CONFIG = 98386
    const val MD_FEATURE_CONFIG = 65617
    const val MD_MODULE_STATUS = 65574
    const val HU_MODULE_CONTROL = 98344

    /**
     * 65574 里的模块号（`ModuleStatusManage` 的 7 个 `ModuleStatusModel`，字节码实证）。
     *
     * 这是判断「语音键到底有没有被引擎接住」的唯一现成信号：引擎真的起语音时会报
     * `module=4 status=1`，结束报 `=0`。我们发了 `0x68008 0x21` 之后若一直没有
     * `module=4`，说明引擎把这条硬键丢了（不是车机没按、也不是我们没发）。
     *
     * 名字来自 `ModuleStatusManage.<init>` 里逐个 new 的 `ModuleStatusModel(id, 0)`：
     * 1 电话 / 2 导航 / 3 音乐 / 4 语音 / 6 麦克风 / 8 巡航 / 9 跟车。
     */
    object Module {
        const val PHONE = 1
        const val NAVI = 2
        const val MUSIC = 3
        const val VR = 4
        const val MIC = 6
        const val CRUISE = 8
        const val CRUISE_FOLLOW = 9

        fun name(id: Int): String = when (id) {
            PHONE -> "电话"
            NAVI -> "导航"
            MUSIC -> "音乐"
            VR -> "语音"
            MIC -> "麦克风"
            CRUISE -> "巡航"
            CRUISE_FOLLOW -> "跟车"
            else -> "模块$id"
        }
    }

    /**
     * 98386 的确认（0 字节，引擎收到特征配置后回）。
     *
     * 实测时序：发 98386 → 约 0.7s 后收到 65570 → 再发 98311。所以它可以当
     * 「特征配置已被接受」的凭据，比盲发有用。
     */
    const val MD_FEATURE_CONFIG_DONE = 65570

    /**
     * 引擎要求「内容加密」握手（65642 / 0x1006A，RSA/AES 密钥交换的第一步）。
     *
     * **它是 98386 里 `CONTENT_ENCRYPTION=1` 的直接后果**（2026-11-11 18:48~18:53 四轮实证：
     * 每轮都是「发 98386（带该项）→ 同一毫秒收到 65642」，而不带该项的 2026-10-26 日志里
     * 一次都没出现）。我们没有任何密钥协商实现，收到之后引擎约 20 秒（实测 18.07s 起算于
     * 98311、20.1s 起算于建链）就把四条通道全部拆掉并重启会话——
     * 表现正是「CarLife 一直在重启」，同时媒体音频一个字节都不推（只有我们补的静音帧）。
     * 所以默认**不再下发** `CONTENT_ENCRYPTION`，这行日志留着当判据。
     */
    const val MD_ENCRYPT_SETUP = 65642

    /**
     * 手机端主动推送的媒体信息（歌名/歌手/专辑/封面/时长）。
     *
     * 命令号来自 `com.baidu.carlife.core.CommonParams.MSG_CMD_MEDIA_INFO = 0x00010035`，
     * 载荷是 `CarlifeMediaInfoListProto.CarlifeMediaInfoList`（见车机端参考实现
     * CTranRecvPackageProcess.cpp 的 0x10035 分支）。方向 MD→HU：手机 CarLife 内置
     * 播放器开始播放音乐时，由 `sendMusicInfoToVehicle` 主动推给车机。
     */
    const val MD_MEDIA_INFO = 65589

    /** 手机端媒体进度条（`MSG_CMD_MEDIA_PROGRESS_BAR = 0x00010036`，载荷 u32 progressBar）。 */
    const val MD_MEDIA_PROGRESS = 65590

    /**
     * 手机自发的状态广播，都是 0 字节、无需回应。
     *
     * 65563(0x1001B)/65560(0x10018) 在版本匹配前后就会到，属于引擎的常规状态上报。
     * 留着不打日志，避免每次握手都刷三行「未处理」，把真正有用的行挤掉。
     */
    val MD_IGNORED_IDS = intArrayOf(65560, 65563)

    // ---- 触控（9340 / Touch 通道，**控制帧格式** 8B 头）----
    //
    // 消息 ID 以 **HU 端官方 SDK** 为准（.tools/carlife-ref 的
    // CarLife-Android-Vehicle/src/com/baidu/carlifevehicle/CommonParams.java）：
    //
    //   0x68001 MSG_TOUCH_ACTION        复合动作（CarlifeTouchAction{action,x,y,…}）
    //   0x68002 MSG_TOUCH_ACTION_DOWN   单点（CarlifeTouchSinglePoint{x,y}）
    //   0x68003 MSG_TOUCH_ACTION_UP     单点
    //   0x68004 MSG_TOUCH_ACTION_MOVE   单点
    //   0x68005 SINGLE_CLICK · 0x68006 DOUBLE_CLICK · 0x68007 LONG_PRESS
    //   0x68008 CAR_HARD_KEY_CODE · 0x6800A ACTION_BEGIN · 0x6800D 复合（MD 端）
    //
    // **踩过的坑（2026-10-25）**：这里曾按「0x68001 DOWN / 0x68002 MOVE / 0x68003 UP」
    // 连号推断，结果整条手势全错位——`0x68002` 其实是 UP、`0x68003` 才是 UP 的邻居
    // 0x68003 同样是 UP（MOVE 在 0x68004）。于是车机端每次拖动变成：
    //   0x68001（复合动作，action 缺省=按下）→ 0x68002（=抬起！）→ …
    // 手指刚动第一下引擎就收到「抬起」，手势立刻结束，只剩原地一次点击。
    // 表现正是「能点、点得准，但拖不动、列表不跟手」。坐标映射一直是对的，别再去调缩放。
    const val TOUCH_ACTION = 425985 // 0x68001 复合动作：负载带原始动作码，双指手势走这里
    const val TOUCH_DOWN = 425986 // 0x68002
    const val TOUCH_UP = 425987 // 0x68003
    const val TOUCH_MOVE = 425988 // 0x68004

    /**
     * [TOUCH_ACTION] 负载里的动作码——就是 Android `MotionEvent` 的动作码，
     * 指针索引放在高字节（`action or (index shl 8)`），与官方 HU SDK
     * 「新触摸方式」`sendAction(event.getX(), event.getY(), event.getAction())` 一致。
     *
     * 为什么双指必须走这条路：单点消息（0x68002/3/4）的负载是
     * `CarlifeTouchSinglePoint{x,y}`，**没有指针概念**，第二根手指无从表达——
     * 旧实现直接把 slot 1 丢掉，双指缩放自然按不出来。
     */
    object TouchAction {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
        const val POINTER_INDEX_SHIFT = 8

        /** 给动作码带上指针索引（槽 0 → 手指 0，槽 1 → 手指 1）。 */
        fun withPointer(
            base: Int,
            pointerIndex: Int,
        ): Int = base or (pointerIndex shl POINTER_INDEX_SHIFT)
    }

    // ---- 车机 GPS（0x18010，HU → MD）----
    //
    // 命令号与载荷来自官方车机端 SDK：
    //   `CommonParams.MSG_CMD_CAR_GPS = 0x00018010`
    //   `CTranRecvPackageProcess::sendCmdCarGPS` → `CarlifeCarGps` protobuf
    //   `CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeCarGpsProto.proto`
    //
    // 方向是**车机 → 手机**：车把自己的定位喂给 CarLife+，手机端地图在
    // 「车机定位优先」时直接用它。这正是我们要的通道——车机 GPS 比车内手机强。
    //
    // **25 个字段里前 24 个是 required**：少一个 protobuf 解析就整条丢弃，所以
    // 未知项必须显式补 0，不能省略（[carGps] 已按 proto 顺序全量写出）。
    const val HU_PAUSE_MEDIA = 98318 // 0x0001800E MSG_CMD_PAUSE_MEDIA（兼容旧版 MD 控制指令）
    const val HU_CAR_GPS = 98320 // 0x00018010

    // ---- 车机硬键（0x68008，HU → MD，控制通道）----
    //
    // `CommonParams.MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008`，
    // 载荷 `CarlifeCarHardKeyCode{ keycode = 1 }`。
    // CarLife+ 8.8.5 `CarlifeConnectInitUtils` 实际处理的媒体/语音硬键：
    //   `KEYCODE_SEEK_SUB = 0x0F`（上一曲）· `KEYCODE_SEEK_ADD = 0x10`（下一曲）
    //   `KEYCODE_MEDIA_START = 0x1F`（开始播放，幂等）· `KEYCODE_MEDIA_STOP = 0x20`（暂停播放，幂等）
    //   `KEYCODE_VR_START = 0x21`（起语音识别）· `KEYCODE_VR_STOP = 0x22`（停语音识别）
    const val TOUCH_CAR_HARD_KEY_CODE = 425992 // 0x00068008

    object HardKey {
        /** 上一曲。 */
        const val SEEK_SUB = 0x0F

        /** 下一曲。 */
        const val SEEK_ADD = 0x10

        /** 开始/恢复音乐播放（幂等，对应 `startMusic(false)`）。 */
        const val MEDIA_START = 0x1F

        /** 暂停音乐播放（幂等，对应 `pauseMusic(false)`）。 */
        const val MEDIA_STOP = 0x20

        /** 起语音识别（小度）。 */
        const val VR_START = 0x21

        /** 停语音识别。 */
        const val VR_STOP = 0x22
    }

    const val HEARTBEAT = 131074

    /** CONNECT_TYPE：5 = Wi-Fi 局域网 */
    const val CONNECT_TYPE_WIFI = 5

    // ---- 音频（9240 媒体 / 9241 TTS）----
    //
    // 音频通道是**裸字节流**，每包 = 12B 包头 + 负载：
    //   [0..3] 负载长度 BE | [4..7] 时间戳 ms BE | [8..11] 包类型 BE
    // 手机端 `CommonParams` / `PCMMediaManager` 与官方 Vehicle Lib 一致：
    //   INIT = 0x30001、STOP = 0x30002、PAUSE = 0x30003、RESUME_PLAY = 0x30004、NORMAL = 0x30006。
    const val AUDIO_HEAD_LEN = 12
    const val MUSIC_PKG_INIT = 0x30001
    const val MUSIC_PKG_STOP = 0x30002
    const val MUSIC_PKG_PAUSE = 0x30003
    const val MUSIC_PKG_RESUME = 0x30004
    const val MUSIC_PKG_NORMAL = 0x30006

    // ---- TTS（9241 端口）：导航播报 / 语音提示 ----
    //
    // 与媒体通道完全独立：
    //   0x40001 → 格式宣告（CarlifeMusicInit 同构）
    //   0x40002 → 停止（CarLife Vehicle Lib MSG_TTS_STOP，长度 0）
    //   0x40003 → 裸 PCM（导航播报数据）
    const val TTS_PKG_INIT = 0x40001
    const val TTS_PKG_STOP = 0x40002
    const val TTS_PKG_NORMAL = 0x40003

    /** TTS 未宣告格式时的兜底采样率（导航播报常见 16k 单声道，AudioSender 会重采样到 48k）。 */
    const val TTS_DEFAULT_RATE = 16_000

    // ---- 帧格式 ----

    /** 控制通道（Channel 1）：[u16 len][u16 reserved][u32 service_type] */
    fun packCommand(serviceType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU16(out, 0, payload.size)
        writeU16(out, 2, 0)
        writeU32(out, 4, serviceType)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    /** 数据通道（非 Channel 1）：[u32 len][u32 seq][u32 service_type] */
    fun packStream(seq: Int, serviceType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        writeU32(out, 0, payload.size)
        writeU32(out, 4, seq)
        writeU32(out, 8, serviceType)
        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    // ---- protobuf 手写编码 ----

    fun pbVarint(field: Int, value: Int): ByteArray = tagBytes(field, 0) + varintBytes(value.toLong())

    fun pbString(field: Int, value: String): ByteArray = pbBytes(field, value.toByteArray(Charsets.UTF_8))

    fun pbBytes(field: Int, value: ByteArray): ByteArray = tagBytes(field, 2) + varintBytes(value.size.toLong()) + value

    /**
     * protobuf 的 tag 本身也是 varint：字段号 ≥ 16 时（tag ≥ 0x80）必须写两个字节。
     *
     * 踩过的坑（2026-11-04）：这里原本是 `byteArrayOf(((field shl 3) or wire).toByte())`，
     * 字段 16 会写成单字节 `0x80`——对端读到的 tag 低 7 位是 0、且没有结束位，于是继续
     * 读下一个字节当高位，整条消息从第 16 个字段起全部错位（车机 GPS 的 pdop 之后全丢）。
     * 之前的消息字段号都 ≤ 15，所以这个 bug 一直没暴露。
     */
    private fun tagBytes(
        field: Int,
        wire: Int,
    ): ByteArray = varintBytes((field.toLong() shl 3) or wire.toLong())

    private fun varintBytes(input: Long): ByteArray {
        var value = input
        val out = ArrayList<Byte>(8)
        while (true) {
            val low = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.add(low.toByte())
                return out.toByteArray()
            }
            out.add((low or 0x80).toByte())
        }
    }

    // ---- 载荷构造 ----

    /**
     * 协议版本 **3.0**，不是 4.0。
     *
     * MD 端 `updateVehicleVersion()`：`major==4` 或 `(3,2)` 会置「新车机版本」，
     * 从而走 RSA/AES 加密鉴权且永不下发 65611，会话静默挂死；而手机支持列表是
     * {1.0, 2.0, 3.0, 4.0}，发 3.0 既 match=1 又走老版明文路径。
     */
    fun protocolVersion(): ByteArray = pbVarint(1, 3) + pbVarint(2, 0)

    fun statisticsInfo(): ByteArray =
        pbString(1, "12345678") + // cuid
            pbString(2, "4.0.0") + // versionName
            pbVarint(3, 16) + // versionCode
            pbString(4, "20029999") // channel

    /**
     * 车机设备信息。字段号必须与官方车机版 `CarlifeDeviceInfoProto` 一致：
     * 1 os, 2 board, 3 bootloader, 4 brand, 5 cpuAbi, 6 cpuAbi2, 7 device, 8 display…
     * 早期按猜测把字符串写进 sdkInt 等整型位，对端 protobuf 解析抛异常后静默断链。
     */
    fun deviceInfo(
        display: String = "1280x720",
    ): ByteArray =
        pbString(1, "Android") +
            pbString(2, "msm8998") +
            pbString(3, "unknown") +
            pbString(4, "baidu") +
            pbString(5, "arm64-v8a") +
            pbString(6, "arm64-v8a") +
            pbString(7, "CarLifeVehicle") +
            pbString(8, display)

    /**
     * 每多少个 P 帧插一个 I 帧。
     *
     * 原值 300 = 20fps 下 **15 秒才一个 I 帧**：车机端一旦丢包/花屏要熬 15 秒才自愈，
     * 且 I 帧稀少意味着长时间没有完整画面重建。改成 20（=1 秒）后：
     *  - 车机端花屏 1 秒内自愈；
     *  - 每秒都有完整帧，平均码率随之上升（I 帧远大于 P 帧），1080p 下的块效应明显减轻。
     */
    const val I_FRAME_INTERVAL_DEFAULT = 20

    /**
     * 特征配置：CONNECT_TYPE + I 帧间隔 + 分辨率直通 + 内容加密 + 歌词支持。
     *
     * 2026-10-25 逆向 `FeatureConfigManager.excute()`（手机引擎）实证两条关键开关：
     *  - `DISABLE_RESOLUTION_OPTION = 1` → `Recorder.useVehicleResolution = true`：
     *    引擎不再按宽度把 98311 上报的车机分辨率分档（1412 会被压成 1280×720），
     *    而是直接用 `mVehiclWidth × mVehiclHigh`（即 98311 的真实宽高）编码，
     *    **21:9 车机不再被横向拉伸**。
     *  - `MEDIA_LYRIC_SUPPORT = 1` → 引擎 dispatch `carlife_other_media_lyric_support`，
     *    开启歌词支持。
     *
     * 2026-11-08 曾补 `CONTENT_ENCRYPTION = 1`（参考 OcarPlay 的同一份清单）。**2026-11-11 撤回**：
     * 带上它以后引擎立刻发 65642（见 [MD_ENCRYPT_SETUP]）要 RSA/AES 密钥，我们不应答 →
     * 约 20 秒后整条会话被引擎拆掉、CarLife+ 重启，且媒体音频再也不推 PCM。
     * 同一台荣耀机 2026-10-26（不带该项）连续放音 6 分钟，媒体音频格式、PCM 都正常，
     * 所以这是一条可 A/B 的单变量：默认关，需要时再显式打开。
     */
    fun featureConfig(
        connectType: Int = CONNECT_TYPE_WIFI,
        iFrameInterval: Int = I_FRAME_INTERVAL_DEFAULT,
        useVehicleResolution: Boolean = true,
        mediaLyricSupport: Boolean = true,
        contentEncryption: Boolean = false,
    ): ByteArray {
        val items = ArrayList<ByteArray>()
        items += pbString(1, "I_FRAME_INTERVAL") + pbVarint(2, iFrameInterval)
        items += pbString(1, "CONNECT_TYPE") + pbVarint(2, connectType)
        items += pbString(1, "DISABLE_RESOLUTION_OPTION") + pbVarint(2, if (useVehicleResolution) 1 else 0)
        items += pbString(1, "CONTENT_ENCRYPTION") + pbVarint(2, if (contentEncryption) 1 else 0)
        items += pbString(1, "MEDIA_LYRIC_SUPPORT") + pbVarint(2, if (mediaLyricSupport) 1 else 0)
        var list = ByteArray(0)
        for (item in items) list += pbBytes(2, item)
        val cnt = items.size
        return pbVarint(1, cnt) + list + pbVarint(3, 0) + pbString(4, "CarPToCarL") + pbString(5, "00:11:22:33:44:55")
    }

    /**
     * 车机 → 手机的编码参数申请（98311）。
     *
     * 8.8.5 的 `CarlifeVideoEncoderInfo` 比旧版 proto 多 6 个字段（逆向 `writeTo` 实证）：
     * ```
     * 1 width  int32 | 2 height int32 | 3 frameRate int32
     * 4 saveAreaWidth int32 | 5 saveAreaHeight int32 | 6 saveAreaX int32 | 7 saveAreaY int32
     * 8 drawUIOutsideSafeArea bool | 9 needAdapterScreen bool
     * ```
     * 这些不是装饰：`MediaFormatUtils.setVendorEncoderParams` 会把它们打包进
     * `dispatcherAction("carlife_encode_format", map)` 交给**厂商适配层（荣耀 mix 模块）**，
     * 由厂商层决定最终 `MediaFormat` 的 width/height（返回非 null 时覆盖引擎自己算的值）。
     * 所以只发 width/height 时，厂商层拿不到「车机真实画布 / 安全区 / 是否需适配」，
     * 会按自己的规则回 1280×720 → 21:9 车机被横向拉伸（2026-10-25 实测）。
     *
     * @param needAdapterScreen 车机画面比例与编码比例不一致时请引擎做适配（等比，不拉伸）
     * @param saveArea 车机安全区 [宽, 高, x, y]；null 表示不声明
     */
    fun videoEncoderInfo(
        width: Int,
        height: Int,
        fps: Int,
        needAdapterScreen: Boolean = false,
        saveArea: IntArray? = null,
        drawUIOutsideSafeArea: Boolean = false,
    ): ByteArray {
        var out = pbVarint(1, width) + pbVarint(2, height) + pbVarint(3, fps)
        if (saveArea != null && saveArea.size == 4) {
            out += pbVarint(4, saveArea[0]) +
                pbVarint(5, saveArea[1]) +
                pbVarint(6, saveArea[2]) +
                pbVarint(7, saveArea[3])
        }
        if (drawUIOutsideSafeArea) out += pbVarint(8, 1)
        if (needAdapterScreen) out += pbVarint(9, 1)
        return out
    }

    /** `CarlifeVideoFrameRate{frameRate = 1}`。 */
    fun videoFrameRate(fps: Int): ByteArray = pbVarint(1, fps)

    /** 模块状态列表回复：把每个模块状态置 1（开启）。 */
    fun moduleControl(moduleIds: List<Int>): ByteArray {
        var out = pbVarint(1, moduleIds.size)
        for (id in moduleIds) {
            out += pbBytes(2, pbVarint(1, id) + pbVarint(2, 1))
        }
        return out
    }

    /** 车机硬键：`CarlifeCarHardKeyCode{ keycode = 1 }`（键值见 [HardKey]）。 */
    fun carHardKeyCode(keycode: Int): ByteArray = pbVarint(1, keycode)

    /**
     * 车机 GPS：`CarlifeCarGps`（25 字段，前 24 个 required）。
     *
     * 单位换算来自官方 C 结构体 `S_CAR_GPS` 的注释（`CCarLifeLibWrapper.h`）：
     * 纬度/经度 1/1,000,000 度；高度 0.1 米；速度 0.01 km/h；航向 0.1 度；
     * hdop/pdop/vdop 0.1；三轴速度 0.01 m/s。
     *
     * **required 字段一个都不能省**——手机端是强类型 protobuf，缺字段会抛解析异常并
     * 丢掉整条消息。未知项（信号质量 dBHz、定位误差）显式补 0 而不是省略。
     *
     * @param fix 定位快照（经纬度必填）
     * @param nowMillis 时间戳（字段 25，u64，毫秒）
     */
    fun carGps(
        fix: CarGpsFix,
        nowMillis: Long,
    ): ByteArray {
        val lat = Math.round(fix.latitude * 1_000_000.0).toInt()
        val lon = Math.round(fix.longitude * 1_000_000.0).toInt()
        val height = Math.round((fix.altitudeMeters ?: 0.0) * 10.0).toInt()
        val speed = Math.round((fix.speedKmh ?: 0.0) * 100.0).toInt().coerceAtLeast(0)
        val heading = Math.round((fix.headingDeg ?: 0.0) * 10.0).toInt().coerceAtLeast(0)
        // 三轴速度：由地速 + 航向分解（0.01 m/s）。航向未知时只给北向=地速，东向=0。
        val speedMps = (fix.speedKmh ?: 0.0) / 3.6
        val rad = Math.toRadians(fix.headingDeg ?: 0.0)
        val north = Math.round(speedMps * Math.cos(rad) * 100.0).toInt()
        val east = Math.round(speedMps * Math.sin(rad) * 100.0).toInt()

        var out = pbVarint(1, 1) // antennaState：1 = 天线正常（车机只在这条通道有数据时才发）
        out += pbVarint(2, 0) // signalQuality：车机未给 dBHz，界面信号格用，补 0
        out += pbVarint(3, lat)
        out += pbVarint(4, lon)
        out += pbVarint(5, height)
        out += pbVarint(6, speed)
        out += pbVarint(7, heading)
        out += pbVarint(8, fix.utcYear)
        out += pbVarint(9, fix.utcMonth)
        out += pbVarint(10, fix.utcDay)
        out += pbVarint(11, fix.utcHour)
        out += pbVarint(12, fix.utcMinute)
        out += pbVarint(13, fix.utcSecond)
        out += pbVarint(14, fix.fixQuality)
        out += pbVarint(15, Math.round((fix.hdop ?: 0.0) * 10.0).toInt())
        out += pbVarint(16, Math.round((fix.pdop ?: 0.0) * 10.0).toInt())
        out += pbVarint(17, Math.round((fix.vdop ?: 0.0) * 10.0).toInt())
        out += pbVarint(18, fix.satellitesUsed)
        out += pbVarint(19, fix.satellitesVisible)
        out += pbVarint(20, 0) // horPosError：未提供
        out += pbVarint(21, 0) // vertPosError：未提供
        out += pbVarint(22, north)
        out += pbVarint(23, east)
        out += pbVarint(24, 0) // vertSpeed
        out += pbVarint64(25, nowMillis)
        return out
    }

    /** uint64 / 大数 varint（时间戳超过 Int 范围）。 */
    fun pbVarint64(
        field: Int,
        value: Long,
    ): ByteArray = tagBytes(field, 0) + varintBytes(value)

    /**
     * 单点触控负载 `CarlifeTouchSinglePoint{x=1, y=2}`（proto 里只有这两个字段）。
     * 注意动作不在负载里——DOWN/MOVE/UP 分别用 [TOUCH_DOWN]/[TOUCH_MOVE]/[TOUCH_UP]。
     */
    fun touchPoint(
        x: Int,
        y: Int,
    ): ByteArray = pbVarint(1, x) + pbVarint(2, y)

    /**
     * 复合动作负载 `CarlifeTouchAction{action=1, x=2, y=3}`。
     *
     * `action` 是**原始 MotionEvent 动作码**，指针索引用 Android 的高字节约定：
     * 第二根手指按下 = `ACTION_POINTER_DOWN or (1 shl 8)` = 261，抬起 = 262。
     * 这是官方 HU SDK「新触摸方式」（`TouchListenerManager.sendAction`）的用法，
     * 也是这条通道里**唯一能表达「哪根手指」**的地方——单点消息（0x68002/3/4）
     * 的负载只有 x/y，多点手势靠它无从表达，这正是「双指按不出来」的根因。
     */
    fun touchAction(
        action: Int,
        x: Int,
        y: Int,
    ): ByteArray = pbVarint(1, action) + pbVarint(2, x) + pbVarint(3, y)

    /** 解析 65574 模块状态列表，返回 (moduleId, status) 列表。 */
    fun parseModuleStatus(payload: ByteArray): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var index = 0
        // 手写解析必须自己守边界：这个包来自车机引擎（重启/拼包异常时会截断），
        // 越界异常会冒到 CarLifeHuClient.workerLoop 的 catch → finally 直接 stop()，
        // 也就是「一个畸形包把整条 CarLife 连接（cmd/视频/音频）全部拆掉重来」。
        try {
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                if (tag == 0x08) {
                    // cnt，跳过
                    while (index < payload.size && (payload[index].toInt() and 0x80) != 0) index++
                    index++
                } else if (tag == 0x12) {
                    if (index >= payload.size) break
                    val length = payload[index].toInt() and 0xFF
                    index++
                    val end = (index + length).coerceAtMost(payload.size)
                    var moduleId = 0
                    var status = 0
                    var cursor = index
                    while (cursor < end) {
                        val subTag = payload[cursor].toInt() and 0xFF
                        cursor++
                        when (subTag) {
                            0x08 -> {
                                if (cursor >= end) break
                                moduleId = payload[cursor].toInt() and 0xFF
                            }

                            0x10 -> {
                                if (cursor >= end) break
                                status = payload[cursor].toInt() and 0xFF
                            }

                            else -> break
                        }
                        cursor++
                    }
                    result += moduleId to status
                    index = end
                } else {
                    break
                }
            }
        } catch (_: RuntimeException) {
            // 截断/畸形包：返回已解析出来的部分，绝不把异常交给上层（见上）。
        }
        return result
    }

    /**
     * 解析 `CarlifeMediaInfoList`（0x10035 的载荷）为媒体信息列表。
     *
     * proto 定义（CarlifeMediaInfoListProto.java）：
     *   CarlifeMediaInfoList { required int32 cnt = 1; repeated CarlifeMediaInfo mediaInfo = 2; }
     *   CarlifeMediaInfo     { required string source=1; required string song=2;
     *                          required string artist=3; required string album=4;
     *                          required bytes albumArt=5; required int32 duration=6;
     *                          required int32 playlistNum=7; required string songId=8;
     *                          required int32 mode=9; }
     *
     * 手写 wire 解析（不引 protobuf 运行时）：
     *   field 1 (cnt)      → tag 0x08 (varint)
     *   field 2 (mediaInfo)→ tag 0x12 (length-delimited，内嵌完整 CarlifeMediaInfo 子消息)
     */
    fun parseMediaInfoList(payload: ByteArray): List<CarLifeMediaInfo> {
        val result = ArrayList<CarLifeMediaInfo>()
        var index = 0
        // 同 parseModuleStatus：截断包不能让异常逃到 workerLoop（否则整条 CarLife
        // 连接被 finally 的 stop() 拆掉）。
        try {
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                when (tag) {
                    0x08 -> {
                        // cnt（varint），跳过即可
                        index = skipVarint(payload, index)
                    }

                    0x12 -> {
                        // mediaInfo 子消息（length-delimited）
                        val length = readVarint(payload, index)
                        index += varintLen(payload, index)
                        val end = (index + length).coerceAtMost(payload.size)
                        if (index <= end) parseMediaInfo(payload, index, end)?.let { result += it }
                        index = end
                    }

                    else -> {
                        // 未知字段：无法安全跳过，停止解析（保护性退出）
                        return result
                    }
                }
            }
        } catch (_: RuntimeException) {
            // 返回已解析部分
        }
        return result
    }

    /** 解析单个 CarlifeMediaInfo 子消息；解析不出歌名时返回 null。 */
    private fun parseMediaInfo(
        payload: ByteArray,
        start: Int,
        end: Int,
    ): CarLifeMediaInfo? {
        var source = ""
        var song = ""
        var artist = ""
        var album = ""
        var albumArt: ByteArray? = null
        var duration = 0
        var playlistNum = 0
        var songId = ""
        var mode = 0
        var index = start
        while (index < end) {
            val tag = payload[index].toInt() and 0xFF
            index++
            val field = tag ushr 3
            val wireType = tag and 0x07
            when {
                // string/bytes（length-delimited, wireType=2）
                wireType == 2 -> {
                    val length = readVarint(payload, index)
                    index += varintLen(payload, index)
                    val valueEnd = (index + length).coerceAtMost(end)
                    // varint 可能跨过子消息边界（畸形包）：此时 index > valueEnd，
                    // copyOfRange 会抛 IllegalArgumentException。
                    if (index > valueEnd) return null
                    val bytes = payload.copyOfRange(index, valueEnd)
                    when (field) {
                        1 -> source = bytes.toString(Charsets.UTF_8)
                        2 -> song = bytes.toString(Charsets.UTF_8)
                        3 -> artist = bytes.toString(Charsets.UTF_8)
                        4 -> album = bytes.toString(Charsets.UTF_8)
                        5 -> albumArt = bytes
                        8 -> songId = bytes.toString(Charsets.UTF_8)
                    }
                    index = valueEnd
                }

                // int32（varint, wireType=0）
                wireType == 0 -> {
                    val value = readVarint(payload, index)
                    index += varintLen(payload, index)
                    when (field) {
                        6 -> duration = value
                        7 -> playlistNum = value
                        9 -> mode = value
                    }
                }

                // wireType=5 (fixed32) 或未知：跳过固定长度/放弃
                else -> return null
            }
        }
        if (song.isBlank() && artist.isBlank()) return null
        return CarLifeMediaInfo(source, song, artist, album, albumArt, duration, playlistNum, songId, mode)
    }

    private fun readVarint(
        payload: ByteArray,
        index: Int,
    ): Int {
        var result = 0
        var shift = 0
        var i = index
        while (i < payload.size && shift < 35) {
            val b = payload[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun varintLen(
        payload: ByteArray,
        index: Int,
    ): Int {
        var len = 0
        var i = index
        while (i < payload.size && len < 5) {
            len++
            if (payload[i].toInt() and 0x80 == 0) break
            i++
        }
        return len
    }

    private fun skipVarint(
        payload: ByteArray,
        index: Int,
    ): Int = index + varintLen(payload, index)

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}

/** CarLife 车机侧收到的一条媒体信息（0x10035 载荷的单个元素）。 */
data class CarLifeMediaInfo(
    val source: String,
    val song: String,
    val artist: String,
    val album: String,
    val albumArt: ByteArray?,
    val duration: Int,
    val playlistNum: Int,
    val songId: String,
    val mode: Int,
)

/**
 * 一条「车机 GPS」快照（[CarLifeProtocol.HU_CAR_GPS] 的输入）。
 *
 * 与 `iap2/NmeaLocation.kt` 的 `NmeaFix` 分开：这边是 CarLife 协议的单位约定
 * （度 / 0.1 米 / 0.01 km/h / 0.1 度），那边是 NMEA 的原始语义（ddmm.mmmm、
 * 节、UTC 时分秒）。转换放在两边各自最清楚的位置，避免协议层被迫认识 NMEA。
 */
data class CarGpsFix(
    /** 十进制度；南纬为负。 */
    val latitude: Double,
    /** 十进制度；西经为负。 */
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedKmh: Double?,
    val headingDeg: Double?,
    /** NMEA fix quality：0=无效、1=GPS、2=DGPS、4=RTK…（0 时上层不该发）。 */
    val fixQuality: Int,
    val satellitesUsed: Int,
    val satellitesVisible: Int,
    val hdop: Double?,
    val pdop: Double?,
    val vdop: Double?,
    val utcYear: Int,
    val utcMonth: Int,
    val utcDay: Int,
    val utcHour: Int,
    val utcMinute: Int,
    val utcSecond: Int,
)
