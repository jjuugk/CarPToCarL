package io.github.shaonialife.carptocar.iap2

/**
 * iAP2 消息目录（ID → 规范名）。
 *
 * 唯一事实源：Apple 内部规格档案 `iap2messages-internal.i2mspecarchive`，
 * 经 catplay 的 `core/catplay_csm/src/msg/iap2.rs` 的 `register_packet_family!`
 * 注册表导出（144 条，全表无遗漏）。`docs/CARPLAY-WIRELESS-HEADUNIT-SPEC.md`
 * 附录 A 是同一份内容的可读版。
 *
 * **为什么单独一张表**：[Iap2MessageIds] 只收录「本项目用到的」消息，其余一律
 * `0xNNNN` 字面量。而 [Iap2CapabilityReport] 要回答的问题是「车机声明的这条消息
 * 我们认不认识」——不认识的才值得按报文体反推。用「本项目用没用过」当判据会把
 * `0x4E09`（我们天天发）、`0xAA00`（每次认证都发）这类消息也报成「陌生 ID」，
 * 现场据此误判成「车机出了新东西」。
 *
 * 约定：**只放规范里真有的号**。由本项目推断、规范里查不到的号（如 `0xEA04`）
 * 绝不允许写进来——那会把「猜测」伪装成「规范」。
 *
 * 注：`0xEA00~0xEA03`（ExternalAccessory 会话）保留在表里，只为让诊断日志能把车机
 * 声明的那几条显示成名字；本项目**已移除 EA 车机控制**，不再发起 EA 会话。
 */
object Iap2MessageCatalog {
    /** 配对 / 识别 / MFi 认证 / 电话通讯（39 条）。 */
    private val IDENTIFICATION: Map<Int, String> =
        mapOf(
            0x00B0 to "StartOOBBTPairing",
            0x00B1 to "OOBBTPairingAccessoryInformation",
            0x00B2 to "OOBBTPairingLinkKeyInformation",
            0x00B3 to "OOBBTPairingCompletionInformation",
            0x00B4 to "StopOOBBTPairing",
            0x0B00 to "StartBluetoothPairing",
            0x0B01 to "BluetoothPairingAccessoryInformation",
            0x0B02 to "BluetoothPairingStatus",
            0x0B03 to "StopBluetoothPairing",
            0x1D00 to "StartIdentification",
            0x1D01 to "IdentificationInformation",
            0x1D02 to "IdentificationAccepted",
            0x1D03 to "IdentificationRejected",
            0x1D05 to "CancelIdentification",
            0x1D06 to "IdentificationInformationUpdate",
            0x4154 to "StartCallStateUpdates",
            0x4155 to "CallStateUpdate",
            0x4156 to "StopCallStateUpdates",
            0x4157 to "StartCommunicationsUpdates",
            0x4158 to "CommunicationsUpdate",
            0x4159 to "StopCommunicationsUpdates",
            0x415A to "InitiateCall",
            0x415B to "AcceptCall",
            0x415C to "EndCall",
            0x415D to "SwapCalls",
            0x415E to "MergeCalls",
            0x415F to "HoldStatusUpdate",
            0x4160 to "MuteStatusUpdate",
            0x4161 to "SendDTMF",
            0x4170 to "StartListUpdates",
            0x4171 to "ListUpdate",
            0x4172 to "StopListUpdates",
            0xAA00 to "RequestAuthenticationCertificate",
            0xAA01 to "AuthenticationCertificate",
            0xAA02 to "RequestAuthenticationChallengeResponse",
            0xAA03 to "AuthenticationResponse",
            0xAA04 to "AuthenticationFailed",
            0xAA05 to "AuthenticationSucceeded",
            0xAA06 to "AccessoryAuthenticationSerialNumber",
        )

    /** 无线 CarPlay / WiFi / EA / HID / USB / BLE（42 条）。 */
    private val WIRELESS: Map<Int, String> =
        mapOf(
            0x4300 to "CarPlayAvailability",
            0x4301 to "CarPlayStartSession",
            0x4302 to "AvailableDigitalCarKeys",
            0x4303 to "MatchedDigitalCarKeys",
            0x4E01 to "BluetoothComponentInformation",
            0x4E03 to "StartBluetoothConnectionUpdates",
            0x4E04 to "BluetoothConnectionUpdate",
            0x4E05 to "StopBluetoothConnectionUpdates",
            0x4E09 to "DeviceInformationUpdate",
            0x4E0A to "DeviceLanguageUpdate",
            0x4E0B to "DeviceTimeUpdate",
            0x4E0C to "DeviceUUIDUpdate",
            0x4E0D to "WirelessCarPlayUpdate",
            0x4E0E to "DeviceTransportIdentifierNotification",
            0x5700 to "RequestWiFiInformation",
            0x5701 to "WiFiInformation",
            0x5702 to "RequestAccessoryWiFiConfigurationInformation",
            0x5703 to "AccessoryWiFiConfigurationInformation",
            0x6800 to "StartHID",
            0x6801 to "DeviceHIDReport",
            0x6802 to "AccessoryHIDReport",
            0x6803 to "StopHID",
            0x6806 to "StartNativeHID",
            0x6807 to "HIDComponentUpdate",
            0x7E00 to "StartUSBHostMode",
            0x7E01 to "USBModeNotification",
            0x7E02 to "StopUSBHostMode",
            0xB100 to "StartBluetoothLowEnergyUpdates",
            0xB101 to "DeviceBluetoothLowEnergyUpdate",
            0xB102 to "AccessoryBluetoothLowEnergyUpdate",
            0xB103 to "AccessoryBluetoothLowEnergyPairingInformation",
            0xB104 to "DeviceBluetoothLowEnergyPairingData",
            0xB105 to "AccessoryBluetoothLowEnergyPairingData",
            0xB106 to "DeviceBluetoothLowEnergyPairingInformation",
            0xB107 to "StopBluetoothLowEnergyUpdates",
            0xDA00 to "StartUSBDeviceModeAudio",
            0xDA01 to "USBDeviceModeAudioInformation",
            0xDA02 to "StopUSBDeviceModeAudio",
            0xEA00 to "StartExternalAccessoryProtocolSession",
            0xEA01 to "StopExternalAccessoryProtocolSession",
            0xEA02 to "RequestAppLaunch",
            0xEA03 to "StatusExternalAccessoryProtocolSession",
        )

    /** 媒体库 / NowPlaying / 导航 / 辅助功能（46 条）。 */
    private val MEDIA_NAV: Map<Int, String> =
        mapOf(
            0x0D00 to "StartRoadObjectDetectionUpdates",
            0x0D01 to "RoadObjectDetectionUpdate",
            0x0D02 to "StopRoadObjectDetectionUpdates",
            0x10DA to "StartDestinationInformation",
            0x10DB to "DestinationInformation",
            0x10DC to "StopDestinationInformation",
            0x10DD to "DestinationInformationStatus",
            0x4C00 to "StartMediaLibraryInformation",
            0x4C01 to "MediaLibraryInformation",
            0x4C02 to "StopMediaLibraryInformation",
            0x4C03 to "StartMediaLibraryUpdates",
            0x4C04 to "MediaLibraryUpdate",
            0x4C05 to "StopMediaLibraryUpdates",
            0x4C06 to "PlayMediaLibraryCurrentSelection",
            0x4C07 to "PlayMediaLibraryItems",
            0x4C08 to "PlayMediaLibraryCollection",
            0x4C09 to "PlayMediaLibrarySpecial",
            0x5000 to "StartNowPlayingUpdates",
            0x5001 to "NowPlayingUpdate",
            0x5002 to "StopNowPlayingUpdates",
            0x5003 to "SetNowPlayingInformation",
            0x5200 to "StartRouteGuidanceUpdates",
            0x5201 to "RouteGuidanceUpdate",
            0x5202 to "RouteGuidanceManeuverInformation",
            0x5203 to "StopRouteGuidanceUpdates",
            0x5204 to "LaneGuidanceInformation",
            0x5400 to "StartAssistiveTouch",
            0x5401 to "StopAssistiveTouch",
            0x5402 to "StartAssistiveTouchInformation",
            0x5403 to "AssistiveTouchInformation",
            0x5404 to "StopAssistiveTouchInformation",
            0x5601 to "RequestVoiceOverMoveCursor",
            0x5602 to "RequestVoiceOverActivateCursor",
            0x5603 to "RequestVoiceOverScrollPage",
            0x5606 to "RequestVoiceOverSpeakText",
            0x5608 to "RequestVoiceOverPauseText",
            0x5609 to "RequestVoiceOverResumeText",
            0x560B to "StartVoiceOverUpdates",
            0x560C to "VoiceOverUpdate",
            0x560D to "StopVoiceOverUpdates",
            0x560E to "RequestVoiceOverConfiguration",
            0x560F to "StartVoiceOverCursorUpdates",
            0x5610 to "VoiceOverCursorUpdate",
            0x5611 to "StopVoiceOverCursorUpdates",
            0x5612 to "StartVoiceOver",
            0x5613 to "StopVoiceOver",
        )

    /** 车辆状态 / 电源 / 定位（17 条）。 */
    private val VEHICLE: Map<Int, String> =
        mapOf(
            0xA100 to "StartVehicleStatusUpdates",
            0xA101 to "VehicleStatusUpdate",
            0xA102 to "StopVehicleStatusUpdates",
            0xAD00 to "StartAppDiscoveryUpdates",
            0xAD01 to "AppDiscoveryUpdate",
            0xAD02 to "StopAppDiscoveryUpdates",
            0xAD03 to "RequestAppDiscoveryAppIcons",
            0xAD04 to "AppDiscoveryAppIcon",
            0xAE00 to "StartPowerUpdates",
            0xAE01 to "PowerUpdate",
            0xAE02 to "StopPowerUpdates",
            0xAE03 to "PowerSourceUpdate",
            0xAE05 to "AccessoryPowerUpdate",
            0xFFF0 to "GPRMCDataStatusValuesNotification",
            0xFFFA to "StartLocationInformation",
            0xFFFB to "LocationInformation",
            0xFFFC to "StopLocationInformation",
        )

    /** 全表。段与段分开只为可读，查表没有分段语义。 */
    private val NAMES: Map<Int, String> =
        IDENTIFICATION + WIRELESS + MEDIA_NAV + VEHICLE

    /** 规范名；规范里没有这个号则返回 null（调用方决定怎么显示）。 */
    fun nameOf(messageId: Int): String? = NAMES[messageId]

    /** 此号是否在 iAP2 规范里存在。 */
    fun contains(messageId: Int): Boolean = NAMES.containsKey(messageId)

    /** 目录条目数（单测用于锁住「表被误删」）。 */
    val size: Int get() = NAMES.size
}
