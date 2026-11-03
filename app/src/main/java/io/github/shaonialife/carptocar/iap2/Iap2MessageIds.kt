package io.github.shaonialife.carptocar.iap2

/**
 * 无线 CarPlay 会话中「发送端（iPhone 角色）」视角的消息目录。
 *
 * 完整无线时序（对照 reference/xcertplay Iap2WirelessControlClient 与
 * reference/carplay-wifi-extractor README 的抓包流程）：
 *
 *  1. 蓝牙 RFCOMM 建链（两台设备需先完成系统级蓝牙配对）
 *  2. 链路层握手：对端发 FF55 SYN → 我方 SYN-ACK → 双方 FF5A 参数协商
 *  3. 手机 → 配件: StartIdentification(0x1D00)
 *     配件 → 手机: IdentificationInformation(0x1D01)   // 车机身份 + 能力清单
 *     手机 → 配件: IdentificationAccepted(0x1D02)
 *  4. MFi 认证（0xAA00 段，配件证明自己；手机侧可跳过校验）
 *  5. 手机 → 配件: RequestAccessoryWiFiConfiguration(0x5702)
 *     配件 → 手机: AccessoryWiFiConfiguration(0x5703)   // 明文 TLV：SSID/密码/信道
 *  6. 手机加入热点，mDNS 解析配件的 _airplay._tcp
 *  7. 手机 → 配件: CarPlayAvailability(0x4300)
 *     配件 → 手机: CarPlayStartSession(0x4301)          // AirPlay 端口/地址/公钥
 *  8. 手机 → 配件: WirelessCarPlayUpdate(0x4E0D)、DeviceTransportIdentifier(0x4E0E)
 *  9. AirPlay 主会话（RTSP 风格 + H.264 流 + HID 触摸回传）—— Phase 2
 */
object Iap2MessageIds {
    // ---- 识别（Identification）----
    const val START_IDENTIFICATION = 0x1D00 // 手机 → 配件
    const val IDENTIFICATION_INFORMATION = 0x1D01 // 配件 → 手机
    const val IDENTIFICATION_ACCEPTED = 0x1D02 // 手机 → 配件
    const val IDENTIFICATION_REJECTED = 0x1D03 // 配件 → 手机

    // ---- 无线 CarPlay ----
    const val CARPLAY_AVAILABILITY = 0x4300 // 手机 → 配件：询问能否开始
    const val CARPLAY_START_SESSION = 0x4301 // 配件 → 手机：无线参数 + AirPlay 端点
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D // 手机 → 配件：无线可用性通知
    const val DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E // 手机 → 配件：蓝牙/USB 标识通知

    // ---- 设备状态问候（真 iPhone 在 0x4300 之前发送的一批消息）----
    const val DEVICE_INFORMATION_UPDATE = 0x4E09 // 手机 → 配件：设备名
    const val DEVICE_LANGUAGE_UPDATE = 0x4E0A // 手机 → 配件：语言
    const val DEVICE_TIME_UPDATE = 0x4E0B // 手机 → 配件：时间（u64 epoch + u16 tz + u8 dst）
    const val DEVICE_UUID_UPDATE = 0x4E0C // 手机 → 配件：UUID

    // ---- WiFi 配置交换 ----
    const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702 // 手机 → 配件
    const val ACCESSORY_WIFI_CONFIGURATION = 0x5703 // 配件 → 手机

    // ---- HID（Phase 2：车机触摸回传）----
    const val START_HID = 0x6800 // 配件 → 手机
    const val DEVICE_HID_REPORT = 0x6801 // 手机 → 配件（应答）
    const val ACCESSORY_HID_REPORT = 0x6802 // 配件 → 手机（触摸/旋钮事件）
    const val STOP_HID = 0x6803

    // ---- MFi 认证（单向：手机验证配件；发送端可跳过校验、自报成功）----
    const val MFI_REQUEST_CERTIFICATE = 0xAA00 // 手机 → 配件
    const val MFI_CERTIFICATE = 0xAA01 // 配件 → 手机（参数0=证书）
    const val MFI_REQUEST_CHALLENGE = 0xAA02 // 手机 → 配件（参数0=挑战 16B）
    const val MFI_RESPONSE = 0xAA03 // 配件 → 手机（参数0=签名）
    const val MFI_AUTHENTICATION_FAILED = 0xAA04 // 手机 → 配件
    const val MFI_AUTHENTICATION_SUCCEEDED = 0xAA05 // 手机 → 配件（自报结果）

    // ---- 配件 bring-up 期间发来的订阅请求（接收并忽略即可）----
    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val NOW_PLAYING_UPDATE = 0x5001
    const val STOP_NOW_PLAYING_UPDATES = 0x5002
    const val START_ROUTE_GUIDANCE_UPDATES = 0x5200
    const val START_POWER_UPDATES = 0xAE00
    const val START_COMMUNICATIONS_UPDATES = 0x4157
    const val START_CALL_STATE_UPDATES = 0x4154
    const val STOP_CALL_STATE_UPDATES = 0x4156
    const val START_LIST_UPDATES = 0x4170
    const val STOP_LIST_UPDATES = 0x4172

    // ---- 车机（配件）侧的其余启用模块，见 docs/mib3-firmware-3874.md 第 5 节 ----
    // 命名只求「日志里看得懂是哪一族的帧」，具体子类型用原始 ID 一并打印。

    /** 带外蓝牙配对：无线 CarPlay 第二次连接时交换 BT link key（MIB3 已启用该模块）。 */
    const val OOB_BT_PAIRING_BASE = 0x00B0

    /** 配件请求手机启动 CarPlay 应用（MIB3：launchMethod=1，无弹窗直接启动）。 */
    const val REQUEST_APP_LAUNCH = 0xEA02

    /** MFi 证书序号（**仅控制会话版本 2** 使用，配件 → 手机）。 */
    const val MFI_AUTH_SERIAL_NUMBER = 0xAA06

    /** 定位：0xFFFA 订阅 / 0xFFFB 样本（车机每秒推一次 GPS）/ 0xFFFC 停止 / 0xFFF0 GPRMC 状态。 */
    const val START_LOCATION_INFORMATION = 0xFFFA
    const val LOCATION_INFORMATION = 0xFFFB
    const val STOP_LOCATION_INFORMATION = 0xFFFC
    const val LOCATION_GPRMC_STATUS = 0xFFF0

    /** 车况（MIB3 本车未启用，dataGeneratorEnabled=false）。 */
    const val START_VEHICLE_STATUS_UPDATES = 0xA100
    const val VEHICLE_STATUS_UPDATE = 0xA101

    // ---- ExternalAccessory 会话（链路 2 号会话承载 EA 数据；触摸 HID 高概率载体）----
    const val EA_OPEN_SESSION = 0xEA00 // 手机 → 配件（奥迪可收清单明确支持）
    const val EA_CLOSE_SESSION = 0xEA01 // 手机 → 配件
    const val EA_SESSION_STATUS = 0xEA03 // 配件 → 手机（奥迪发送清单明确声明）

    // ==================================================================================
    // 消息目录补全（2026-11-03）
    //
    // 来源：大众 MEB ICAS3 固件（`ICAS3GP_CN4_VW_R4220L` / MU 6422 / CarPlay 插件
    // 320.17.7，iAP2 3.0）反汇编出的 74 条 `send*` API 表（`iap2_messages.csv`）。
    // 补这些**不改任何协议行为**，只让日志与能力清单报告能打出名字——此前这些都显示
    // 成 `0x####`，真车排障时看不出是哪一族的帧。
    //
    // 分节按 CSV 的 `session_class`；所有方向以**手机（iAP2 device）**视角标注，
    // 与上文的既有约定一致（CSV 里统一是配件侧 `send*`，方向不能照抄）。
    // ==================================================================================

    /** SessionDestinationInformation：目的地信息（导航目的地交给配件）。 */
    const val START_DESTINATION_INFORMATION = 0x10DA
    const val STOP_DESTINATION_INFORMATION = 0x10DC
    const val DESTINATION_INFORMATION_STATUS = 0x10DD

    /** SessionIdentification：识别的取消/更新（`0x1D01` 之后的增量更新走 0x1D06）。 */
    const val CANCEL_IDENTIFICATION = 0x1D05
    const val IDENTIFICATION_UPDATE = 0x1D06

    /**
     * `0x4301` 的状态回执。
     *
     * **编号是推断**：ICAS3 固件里只有 `sendCarPlayStartSession(0x4301)` 一个符号，
     * 响应按相邻编号给出（报告第 9.2 节自标 `0x4302*`）。仅用于日志命名，不参与判定。
     */
    const val CARPLAY_START_SESSION_STATUS = 0x4302

    /** SessionCarPlayConnection 的另两条可用性通知（`0x4300` 家族之外）。 */

    /** SessionCommunications：通话控制（免提按键 → 手机执行）。 */
    const val INITIAL_CALL = 0x415A
    const val ACCEPT_CALL = 0x415B
    const val END_CALL = 0x415C
    const val SWAP_CALLS = 0x415D
    const val MERGE_CALLS = 0x415E
    const val HOLD_STATUS_UPDATE = 0x415F
    const val MUTE_STATUS_UPDATE = 0x4160
    const val SEND_DTMF = 0x4161
    const val STOP_COMMUNICATIONS_UPDATES = 0x4159

    /** SessionMediaLibrary*：媒体库浏览/点播（ICAS3 全量声明，我们只应答订阅）。 */
    const val START_MEDIA_LIBRARY_INFORMATION = 0x4C00
    const val STOP_MEDIA_LIBRARY_INFORMATION = 0x4C02
    const val START_MEDIA_LIBRARY_UPDATES = 0x4C03
    const val STOP_MEDIA_LIBRARY_UPDATES = 0x4C05
    const val PLAY_MEDIA_LIBRARY_CURRENT_SELECTION = 0x4C06
    const val PLAY_MEDIA_LIBRARY_ITEMS = 0x4C07
    const val PLAY_MEDIA_LIBRARY_COLLECTION = 0x4C08
    const val PLAY_MEDIA_LIBRARY_SPECIAL = 0x4C09

    /** SessionBTConnection：配件侧蓝牙连接状态（`0x4E01` 报组件能力）。 */
    const val BT_COMPONENT_INFORMATION = 0x4E01
    const val START_BT_CONNECTION_UPDATES = 0x4E03
    const val STOP_BT_CONNECTION_UPDATES = 0x4E05

    /** SessionNowPlaying：手机主动推送曲目信息（订阅是 0x5000）。 */
    const val SET_NOW_PLAYING_INFORMATION = 0x5003

    /** SessionRouteGuidance：停止转向信息下发（订阅是 0x5200）。 */
    const val STOP_ROUTE_GUIDANCE_UPDATES = 0x5203

    /** SessionAssistiveTouch：辅助触控（车机旋钮/触摸板驱动屏幕上的浮动球）。 */
    const val START_ASSISTIVE_TOUCH = 0x5400
    const val STOP_ASSISTIVE_TOUCH = 0x5401
    const val START_ASSISTIVE_TOUCH_INFORMATION = 0x5402
    const val STOP_ASSISTIVE_TOUCH_INFORMATION = 0x5404

    /** SessionVoiceOver：车机旋钮驱动旁白光标（ICAS3 全量声明）。 */
    const val REQUEST_VOICE_OVER_MOVE_CURSOR = 0x5601
    const val REQUEST_VOICE_OVER_ACTIVATE_CURSOR = 0x5602
    const val REQUEST_VOICE_OVER_SCROLL_PAGE = 0x5603
    const val REQUEST_VOICE_OVER_SPEAK_TEXT = 0x5606
    const val REQUEST_VOICE_OVER_PAUSE_TEXT = 0x5608
    const val REQUEST_VOICE_OVER_RESUME_TEXT = 0x5609
    const val START_VOICE_OVER_UPDATES = 0x560B
    const val STOP_VOICE_OVER_UPDATES = 0x560D
    const val REQUEST_VOICE_OVER_CONFIGURATION = 0x560E
    const val START_VOICE_OVER_CURSOR_UPDATES = 0x560F
    const val STOP_VOICE_OVER_CURSOR_UPDATES = 0x5611
    const val START_VOICE_OVER = 0x5612
    const val STOP_VOICE_OVER = 0x5613

    /**
     * SessionWiFiInformation：配件**反向**索要手机的 Wi-Fi 信息。
     *
     * 与我们发出的 `0x5702`（索要 AP 凭据）方向相反：ICAS3 声明了它，
     * 少数车机会先问手机的 Wi-Fi 状态再决定下发哪套凭据。
     */
    const val REQUEST_WIFI_INFORMATION = 0x5700

    /** SessionAppDiscovery：车机上的 App 列表发现（CarPlay 之外的投屏形态用）。 */
    const val START_APP_DISCOVERY_UPDATES = 0xAD00
    const val STOP_APP_DISCOVERY_UPDATES = 0xAD02
    const val REQUEST_APP_DISCOVERY_APP_ICONS = 0xAD03

    /** SessionPower：电源状态（充电中/电量，车机用于显示与省电策略）。 */
    const val STOP_POWER_UPDATES = 0xAE02
    const val POWER_SOURCE_UPDATE = 0xAE03

    /** SessionUSBDeviceModeAudio：有线下的 USB 音频通路（无线不用）。 */
    const val START_USB_DEVICE_MODE_AUDIO = 0xDA00
    const val STOP_USB_DEVICE_MODE_AUDIO = 0xDA02

    fun nameOf(messageId: Int): String =
        when (messageId) {
            START_IDENTIFICATION -> "StartIdentification"
            IDENTIFICATION_INFORMATION -> "IdentificationInformation"
            IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
            IDENTIFICATION_REJECTED -> "IdentificationRejected"
            CARPLAY_AVAILABILITY -> "CarPlayAvailability"
            CARPLAY_START_SESSION -> "CarPlayStartSession"
            WIRELESS_CARPLAY_UPDATE -> "WirelessCarPlayUpdate"
            DEVICE_TRANSPORT_IDENTIFIER -> "DeviceTransportIdentifier"
            REQUEST_ACCESSORY_WIFI_CONFIGURATION -> "RequestAccessoryWiFiConfiguration"
            ACCESSORY_WIFI_CONFIGURATION -> "AccessoryWiFiConfiguration"
            START_HID -> "StartHID"
            DEVICE_HID_REPORT -> "DeviceHIDReport"
            ACCESSORY_HID_REPORT -> "AccessoryHIDReport"
            STOP_HID -> "StopHID"
            EA_OPEN_SESSION -> "StartEASession"
            EA_CLOSE_SESSION -> "StopEASession"
            EA_SESSION_STATUS -> "EASessionStatus"
            START_NOW_PLAYING_UPDATES -> "StartNowPlayingUpdates"
            NOW_PLAYING_UPDATE -> "NowPlayingUpdate"
            STOP_NOW_PLAYING_UPDATES -> "StopNowPlayingUpdates"
            START_ROUTE_GUIDANCE_UPDATES -> "StartRouteGuidanceUpdates"
            START_POWER_UPDATES -> "StartPowerUpdates"
            START_COMMUNICATIONS_UPDATES -> "StartCommunicationsUpdates"
            START_CALL_STATE_UPDATES -> "StartCallStateUpdates"
            STOP_CALL_STATE_UPDATES -> "StopCallStateUpdates"
            START_LIST_UPDATES -> "StartListUpdates"
            STOP_LIST_UPDATES -> "StopListUpdates"
            REQUEST_APP_LAUNCH -> "RequestAppLaunch"
            MFI_AUTH_SERIAL_NUMBER -> "AccessoryAuthenticationSerialNumber"
            START_LOCATION_INFORMATION -> "StartLocationInformation"
            LOCATION_INFORMATION -> "LocationInformation"
            STOP_LOCATION_INFORMATION -> "StopLocationInformation"
            LOCATION_GPRMC_STATUS -> "LocationGPRMCStatus"
            START_VEHICLE_STATUS_UPDATES -> "StartVehicleStatusUpdates"
            VEHICLE_STATUS_UPDATE -> "VehicleStatusUpdate"
            // ---- 2026-11-03 补全（ICAS3 / iap2_messages.csv）----
            START_DESTINATION_INFORMATION -> "StartDestinationInformation"
            STOP_DESTINATION_INFORMATION -> "StopDestinationInformation"
            DESTINATION_INFORMATION_STATUS -> "DestinationInformationStatus"
            CANCEL_IDENTIFICATION -> "CancelIdentification"
            IDENTIFICATION_UPDATE -> "IdentificationUpdate"
            CARPLAY_START_SESSION_STATUS -> "CarPlayStartSessionStatus"
            INITIAL_CALL -> "InitialCall"
            ACCEPT_CALL -> "AcceptCall"
            END_CALL -> "EndCall"
            SWAP_CALLS -> "SwapCalls"
            MERGE_CALLS -> "MergeCalls"
            HOLD_STATUS_UPDATE -> "HoldStatusUpdate"
            MUTE_STATUS_UPDATE -> "MuteStatusUpdate"
            SEND_DTMF -> "SendDTMF"
            STOP_COMMUNICATIONS_UPDATES -> "StopCommunicationsUpdates"
            START_MEDIA_LIBRARY_INFORMATION -> "StartMediaLibraryInformation"
            STOP_MEDIA_LIBRARY_INFORMATION -> "StopMediaLibraryInformation"
            START_MEDIA_LIBRARY_UPDATES -> "StartMediaLibraryUpdates"
            STOP_MEDIA_LIBRARY_UPDATES -> "StopMediaLibraryUpdates"
            PLAY_MEDIA_LIBRARY_CURRENT_SELECTION -> "PlayMediaLibraryCurrentSelection"
            PLAY_MEDIA_LIBRARY_ITEMS -> "PlayMediaLibraryItems"
            PLAY_MEDIA_LIBRARY_COLLECTION -> "PlayMediaLibraryCollection"
            PLAY_MEDIA_LIBRARY_SPECIAL -> "PlayMediaLibrarySpecial"
            BT_COMPONENT_INFORMATION -> "BTComponentInformation"
            START_BT_CONNECTION_UPDATES -> "StartBTConnectionUpdates"
            STOP_BT_CONNECTION_UPDATES -> "StopBTConnectionUpdates"
            SET_NOW_PLAYING_INFORMATION -> "SetNowPlayingInformation"
            STOP_ROUTE_GUIDANCE_UPDATES -> "StopRouteGuidanceUpdates"
            START_ASSISTIVE_TOUCH -> "StartAssistiveTouch"
            STOP_ASSISTIVE_TOUCH -> "StopAssistiveTouch"
            START_ASSISTIVE_TOUCH_INFORMATION -> "StartAssistiveTouchInformation"
            STOP_ASSISTIVE_TOUCH_INFORMATION -> "StopAssistiveTouchInformation"
            REQUEST_VOICE_OVER_MOVE_CURSOR -> "RequestVoiceOverMoveCursor"
            REQUEST_VOICE_OVER_ACTIVATE_CURSOR -> "RequestVoiceOverActivateCursor"
            REQUEST_VOICE_OVER_SCROLL_PAGE -> "RequestVoiceOverScrollPage"
            REQUEST_VOICE_OVER_SPEAK_TEXT -> "RequestVoiceOverSpeakText"
            REQUEST_VOICE_OVER_PAUSE_TEXT -> "RequestVoiceOverPauseText"
            REQUEST_VOICE_OVER_RESUME_TEXT -> "RequestVoiceOverResumeText"
            START_VOICE_OVER_UPDATES -> "StartVoiceOverUpdates"
            STOP_VOICE_OVER_UPDATES -> "StopVoiceOverUpdates"
            REQUEST_VOICE_OVER_CONFIGURATION -> "RequestVoiceOverConfiguration"
            START_VOICE_OVER_CURSOR_UPDATES -> "StartVoiceOverCursorUpdates"
            STOP_VOICE_OVER_CURSOR_UPDATES -> "StopVoiceOverCursorUpdates"
            START_VOICE_OVER -> "StartVoiceOver"
            STOP_VOICE_OVER -> "StopVoiceOver"
            REQUEST_WIFI_INFORMATION -> "RequestWiFiInformation"
            START_APP_DISCOVERY_UPDATES -> "StartAppDiscoveryUpdates"
            STOP_APP_DISCOVERY_UPDATES -> "StopAppDiscoveryUpdates"
            REQUEST_APP_DISCOVERY_APP_ICONS -> "RequestAppDiscoveryAppIcons"
            STOP_POWER_UPDATES -> "StopPowerUpdates"
            POWER_SOURCE_UPDATE -> "PowerSourceUpdate"
            START_USB_DEVICE_MODE_AUDIO -> "StartUSBDeviceModeAudio"
            STOP_USB_DEVICE_MODE_AUDIO -> "StopUSBDeviceModeAudio"
            in OOB_BT_PAIRING_BASE..0x00B4 -> "OOBBTPairing(0x%02X)".format(messageId)
            0x4155 -> "CallStateUpdate"
            0x4158 -> "CommunicationsUpdate"
            0x4171 -> "ListUpdate"
            in 0xAE00..0xAE03 -> "PowerUpdates(0x%04X)".format(messageId)
            else -> "0x%04X".format(messageId)
        }
}
