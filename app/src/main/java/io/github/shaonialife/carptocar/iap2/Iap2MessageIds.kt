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

    /**
     * EA 会话数据（双向）：EA 隧道里的裸字节流由这条承载。
     *
     * **注意 ID 与载荷形态都是推断**：0xEA03 之后的数据帧不在 `carplay.json` 的
     * 可收/可发清单里（那份清单只列 0xEA00/0xEA01/0xEA02/0xEA03），0xEA04 取自
     * iAP2 EA 模块的惯用编号，载荷按「整段裸字节塞进 body」发送。MIB3 车机控制
     * （viwi）依赖它，首次实车必须看日志确认：车机是否回 0xEA03、0xEA04 是否
     * 有来有回。详见 `docs/mib3-viwi-control.md` 的「未确认项」。
     */
    const val EA_SESSION_DATA = 0xEA04

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
            // 规范里**没有** 0xEA04（EA 段只到 0xEA03）。必须自曝是推断，
            // 不能让日志把它显示成一个「看起来有出处」的消息名。
            EA_SESSION_DATA -> "0xEA04(推断未确认)"
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
            // 其余 144 条规范消息交给同一张目录（Apple 规格档案生成）。
            // 这里**不再写泛化区间**（如 0xAE00..0xAE03）：泛化名会盖掉
            // 目录里的精确名（0xAE02 是 StopPowerUpdates，不是「PowerUpdates」）。
            else -> Iap2MessageCatalog.nameOf(messageId) ?: "0x%04X".format(messageId)
        }
}
