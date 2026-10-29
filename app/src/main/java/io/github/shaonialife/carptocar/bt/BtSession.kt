package io.github.shaonialife.carptocar.bt

import android.content.Context
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.util.DeviceIdentity

/**
 * BT 控制会话核心流程（Server/Client 共用，此前两份近乎复制）：
 * 构建发送端会话 → Phase 1 交付端点 → 保活泵（阻塞直至链路断开）。
 * socket 封装 / activeChannel 登记 / 生命周期收尾由两条路径各自处理。
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    onSessionReady: (Iap2SenderSession) -> Unit = {},
    /** 保活泵退出（链路断开）回调——用于释放主控的「会话处理中」互斥，见调用处注释。 */
    onLinkClosed: () -> Unit = {},
    /**
     * 强制按别克 E5（B233）建链（用户在下拉框里指定了别克档）。
     * 默认 false：其它车机仍按 0x1D01 身份文本自动判定，流程不变。
     */
    forceBuickE5: Boolean = false,
) {
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            resolveBluetoothMac(context, log),
            DeviceIdentity.deviceName(context),
            onWifiCredentials,
            onEndpointUpdate,
            // MFi 挑战长度按车机身份持久化（省掉 20B 静默 6 秒的试探）
            context.applicationContext,
            nowPlayingProvider,
            forceBuickE5,
        )
    val endpoint = session.run(timeoutMillis)
    onEndpoint(endpoint)
    // Phase 2 过渡：保持控制通道存活记录配件消息；AirPlay 配对由 onEndpoint 回调发起
    onSessionReady(session)
    session.pumpIncomingForever()
    // 链路断开（保活泵退出）：通知主控释放「会话处理中」——否则车机拆链后立刻回连
    // 带来的新端点会被**僵尸轮次**堵门：车机 15s 看门狗 + 我方 WiFi 加入长等待的
    // 窗口错配，让新端点只能干等到用户放弃（上游 0.1.14 奥迪 3315 真机定谳）。
    onLinkClosed()
}

/**
 * 会话启动时解析真实蓝牙 MAC（0x4300 / 0x4E0E 用）。
 *
 * **必须 forceRetry**：`DeviceIdentity` 对解析失败有 10 秒静默窗口，而 BT 会话往往在
 * Shizuku 刚就绪的同一毫秒构造——首次解析（Shizuku 尚未 ready）失败后，10 秒内的所有
 * 调用都直接返回占位 `02:00:00:00:00:00`。奥迪会拿 0x4300 的传输标识与 RFCOMM 对端
 * 核对，占位值导致它**静默丢弃**（链路层照常 ACK、CSM 层不再回应）。
 * 2026-10-19 奥迪 0.1.7 日志即此：整轮都是占位值，车机始终没下发 0x4301。
 */
private fun resolveBluetoothMac(
    context: Context,
    log: (String) -> Unit,
): String {
    val cached = DeviceIdentity.cachedBluetoothMac()
    if (cached != null) return cached
    val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
        log(
            "BT: ⚠ 未能解析真实蓝牙 MAC（仍是占位值）——车机会校验 0x4300 的传输标识，" +
                "占位值可能导致它不下发 0x4301（Shizuku 已移除，App 无法读系统蓝牙地址）",
        )
    } else {
        log("BT: 本机蓝牙 MAC=$mac（0x4300/0x4E0E 与 ctrl 广播 TXT id 同源）")
    }
    return mac
}
