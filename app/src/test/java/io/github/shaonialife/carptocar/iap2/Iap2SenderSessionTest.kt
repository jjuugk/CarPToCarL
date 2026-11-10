package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 发送端会话对 mock 配件端的端到端回环测试：
 * 内存双工流两端各跑一个链路通道，mock 按 DiPlay/xcertplay 的配件侧时序应答，
 * 验证 Phase 1 全序列（识别→MFi 跳过→HID→WiFi 配置→CarPlay 启动→通知）。
 */
class Iap2SenderSessionTest {

    /**
     * [AccessoryDeclaration] 是**进程内单例**（跨蓝牙/隧道两条链路共享），生产里每轮
     * `SessionController.startConnection()` 都会 reset 一次。同一个 JVM 里连跑多个用例时
     * 必须手动清，否则上一个 mock 的清单与严格模式会漏进下一个用例。
     *
     * 2026-11-10 加严格模式后这一点变得致命：GM/Cinemo 形态的用例跑在前面会留下
     * `wifiGen=NEW` 的判定，紧接着的老协议用例**清单是并集**（20 项）→ 判 NEW →
     * 0x5702 被裁 → 拿不到 0x5703 → 端点为空，用例报「晚到 0x4301 未触发」。
     */
    @org.junit.Before
    fun resetSharedDeclaration() {
        AccessoryDeclaration.reset()
    }

    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 0x4E09 自报的是 **iAP2 身份**，不是本机蓝牙名。
     *
     * 真 iPhone 与市售 CarPlay 盒子这一层都是 `iPhone`（MIB3 隧道 Frida 抓包的逐字节
     * 常量同款）。2026-11-07 别克 E2LB 现场为了修车机蓝牙列表的「未知设备」，把
     * 0x4E09 改成发手机本地蓝牙名（BKQ-AN10）——修好了展示，却把自报身份弄偏了。
     * 本用例锁住两者分离：蓝牙名归蓝牙名，0x4E09 固定 iPhone。
     */
    @Test
    fun deviceInformationUpdateAlwaysReportsIPhone() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        Iap2SenderSession(
            phoneChannel,
            log = { line -> println("PHONE $line") },
            bluetoothMac = "AA:BB:CC:DD:EE:FF",
        ).run(timeoutMillis = 10_000L)

        phoneChannel.close()
        accessoryChannel.close()

        assertTrue("mock 没收到 0x4E09——脚本可能已错位", accessory.deviceNames.isNotEmpty())
        assertTrue(
            "0x4E09 必须自报 iPhone，实际=${accessory.deviceNames}",
            accessory.deviceNames.all { it == Iap2SenderSession.IAP2_DEVICE_NAME },
        )
    }

    /**
     * 奥迪 MMI 0000 形态：识别帧只声明发 0x5703/0xea03（不发 0x4301），Phase 1
     * 因此不等待；车机在保活期间（Phase 1 之后）才补发 0x4301。
     * 旧实现把这条消息当 keep-alive 只记长度，配对永远不会重启——本用例锁住
     * 「晚到 0x4301 必须解析成端点并回调」这一行为。
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, audiDeclarations = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // 车机声明不发 0x4301：Phase 1 必须立即返回空端点，而不是白等 3s 窗口
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("晚到 0x4301 未触发端点回调", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * 凯迪拉克 CT5（GM/Cinemo，对应上游 cardeck PR #19）：新协议车机声明会发 0x4301，
     * 但窗口内未到——**不拆链**，返回空端点进入保活；保活泵把晚到的 0x4301 解析成
     * 完整端点经 onEndpointUpdate 回调（奥迪 MMI 9310 晚到 2 分 35 秒的同族形态）。
     *
     * 旧实现在这里抛 IOException 拆链重连，CT5 实测 6 轮循环车机始终静默、
     * 车机屏「无法连接到 CarPlay 请重试」——本用例锁住新的不拆链行为。
     */
    @Test
    fun newProtocolCarWithoutCredentialsKeepsLinkForLateStartSession() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, newProtocol = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:BE:EF:01",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val endpoint = session.run(timeoutMillis = 20_000L)
        // 没有任何 AP 信息，但车机声明会发 0x4301 —— 返回空端点而不是抛异常
        assertEquals("", endpoint.ssid)

        val pumpThread = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pumpThread.isDaemon = true
        pumpThread.start()
        assertTrue("晚到 0x4301 未在 10s 内经 onEndpointUpdate 回调", updateLatch.await(10, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals("secret-passphrase", late.passphrase)
        assertEquals(49152, late.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
        pumpThread.join(2_000)
    }

    /**
     * 别克 E2LB（GM/Cinemo）在 0x1D01 的「会发」清单里列了 0xAA06 认证序列号——
     * 真 iPhone 是「先要序列号、拿到后再要完整证书」。旧实现直接要证书，车机那条
     * 「发序列号」的分支从头到尾没被触发过（2026-11-07 12:45 日志 6 轮里 0xaa06 零次）。
     *
     * 本用例锁住这条握手：收到 0xAA06 必须再发一次 0xAA00，否则两边互等到超时。
     */
    @Test
    fun authSerialNumberIsRequestedBeforeCertificate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, newProtocol = true, serialNumberFirst = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "84:A1:B7:D1:95:33",
            ).run(timeoutMillis = 20_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * Audi MMI 0000 形态：0x4301 的地址字段按定长 NUL 填充（IPv6 后 22 个、IPv4 后 5 个）。
     * 旧实现只去掉**一个**尾部 NUL，得到 `"10.174.189.1\0\0\0\0\0"`——它 contains('.')=true、
     * contains(':')=false，能骗过所有「是不是 IPv4」的判断，但 Android 的 getaddrinfo 解不了，
     * 表现为 `Unable to resolve host "10.174.189.1"`（2026-10-18 实测：配对成功后视频端口
     * 与 keepAlive 全挂、会话 10 秒被拆）。
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
            ).run(timeoutMillis = 10_000L)

        // 每个地址都必须干净：没有 NUL、没有空白
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "地址含 NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "地址含首尾空白：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // 端口解析不受填充影响（同一帧里的 u32 参数）
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /** 奥迪形态：识别帧声明只发 0x5703/0xea03（不发 StartHID/0x4301）。 */
        private val audiDeclarations: Boolean = false,
        /** Phase 1 结束后再补发 0x4301（车机窗口晚开的真实时序）。 */
        private val lateStartSession: Boolean = false,
        /**
         * 地址字段按真实车机的定长填充（Audi MMI 0000 实测：IPv6 后跟 22 个 NUL、
         * IPv4 后跟 5 个）。用于锁定「必须去掉全部尾部 NUL」这一行为。
         */
        private val padAddresses: Boolean = false,
        /**
         * 凯迪拉克 CT5（GM/Cinemo）形态：清单完整但无 0x5702/0x5703，
         * 且应等到 Phase 1 结束（保活期）才补发 0x4301。
         */
        private val newProtocol: Boolean = false,
        /**
         * 车机先回 0xAA06（MFi 序列号）、再给完整证书——真 iPhone 与参考实现的
         * modernBootstrap 分支都是这个次序（别克 E2LB 的发送清单里就列着 0xAA06）。
         */
        private val serialNumberFirst: Boolean = false,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        /**
         * 收到的 0x4E09 自报设备名（解析出的可打印部分）。
         *
         * 用来锁住「iAP2 身份固定 iPhone」这条：真机 / 盒子的 0x4E09 都是 `iPhone`，
         * 发本地蓝牙名会让车机把发送端归类成普通蓝牙设备。
         */
        val deviceNames = java.util.concurrent.CopyOnWriteArrayList<String>()

        /** 早到、但当前脚本还没轮到看的目标帧（如先于 StartHID 到达的 0x4300）。 */
        private val stashed = ArrayDeque<Iap2Csm.Frame>()

        /** 收到过的「车机可收清单未声明」的消息（新协议严格模式下应当一条都收不到）。 */
        private val seenUndeclared = HashSet<Int>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, "MockCarPlay"),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                if (audiDeclarations) {
                    Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03))
                } else if (newProtocol) {
                    // 凯迪拉克 CT5（GM/Cinemo）实测清单：发 11 项含 0x4301、收 9 项，
                    // 两个清单都不含 0x57xx —— AP 信息只能在 0x4301 里
                    Iap2Csm.u16List(
                        6,
                        intArrayOf(0xaa06, 0x6800, 0x6802, 0x6803, 0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x5000, 0x5002, 0x4301),
                    ) +
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0x6801, 0x4c01, 0x4c04, 0x5001, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4300),
                        )
                } else {
                    ByteArray(0)
                },
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            if (serialNumberFirst) {
                // 车机把序列号先推过来：发送端必须再要一次完整证书，否则双方互等。
                send(
                    Iap2MessageIds.MFI_AUTH_SERIAL_NUMBER,
                    Iap2Csm.param(0, byteArrayOf(0x49, 0x50, 0x41, 0x5f, 0x01, 0x02)),
                )
                expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            }
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // 设备状态问候**紧跟 0xAA05**（2026-10-26 起；上游 PR #17 的结论：
            // 车机据这批消息判定发送端已就绪，晚发会被当成还没起来）。
            //
            // 0x4E0D 是否发，看代际：老协议（奥迪/清单未提供）无条件发；新协议车机
            // （两清单合计 ≥10 项仍无 0x57xx，如 GM/Cinemo）只在它声明能收时才发。
            // 凯迪拉克 CT5 的 9 项可收清单里没有 0x4E0D —— 本 mock 就是那个形态，
            // 因此这里不该 expect 它，断言在 assertUndeclaredFramesCut 里做。
            if (!newProtocol) {
                expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            }
            // 顺序不限（新协议代按真 iPhone 的 UUID→时间→语言→名称，旧代保持原序）
            expectAll(
                Iap2MessageIds.DEVICE_UUID_UPDATE,
                Iap2MessageIds.DEVICE_TIME_UPDATE,
                Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
                Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
            )

            // 订阅 + StartHID（奥迪形态不发，发送端应直接跳过等待）
            if (!audiDeclarations) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi 配置：GM/Cinemo 形态的清单里没有 0x5702，发送端因此根本不会发它
            if (!newProtocol) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
            }
            // 迟到一条订阅请求（真实车机 bring-up 常见：0x5000 与 0x5703 相邻到达）。
            // 它必须被暂存而不是在 expectOrNull 里被反复重读——旧实现把非预期帧塞回
            // 「自己下一轮优先读取」的队列，同一帧无限循环（2026-10-18 日志刷屏根因）。
            send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
            // GM/Cinemo 形态：没有凭据可发（0x4301 才是唯一来源）
            if (!newProtocol) {
                send(
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                    Iap2Csm.string(1, "MockAP"),
                    Iap2Csm.string(2, "secret-passphrase"),
                    Iap2Csm.u8(3, 2),
                    Iap2Csm.u8(4, 36),
                )
            }

            // CarPlay 启动（问候已在 0xAA05 之后收过，此处不再重复期望）
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            if (!lateStartSession) {
                sendStartSession()
            }

            if (newProtocol) {
                // 凯迪拉克的可收清单里没有 0x4E0D（无线可用）与 0x4E0E（传输标识）。
                // 2026-11-10 起新协议车机走严格模式：两条都按声明裁掉。
                // 判据见 assertUndeclaredFramesCut。
                // 窗口给够 8.6 秒（> 新协议 0x4301 的 8s 等待窗），期间把收到的帧都记下来，
                // 窗口过后再补发 0x4301——这样才真正落在「Phase 1 已结束、晚到补发」的时序上。
                assertUndeclaredFramesCut(8_600)
                sendStartSession()
            } else {
                expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
                if (lateStartSession) {
                    // 车机打开 CarPlay 窗口后补发（Phase 1 早已结束）
                    Thread.sleep(150)
                    sendStartSession()
                }
            }
        }

        /**
         * 断言「车机没声明能收」的两条消息在新协议车机上一律**不发**。
         *
         * 来源是凯迪拉克 CT5（GM/Cinemo）的可收清单
         * `0x6801 0x4c01 0x4c04 0x5001 0x4e09 0x4e0a 0x4e0b 0x4e0c 0x4300`——
         * 其中没有 0x4E0D（无线可用）也没有 0x4E0E（传输标识）。
         *
         * 2026-11-10 定案（严格模式）：
         * - **0x4E0D 裁掉**：奥迪 MMI 3232（唯一完整连通过的那台）蓝牙清单 7 项里
         *   **有** 0x4e0d，所以裁掉对奥迪零影响；而 GM/Cinemo 三台（E2LB /
         *   BUICK_3138 / CT5）清单都没有，也全部在「ACK 完我们最后一帧」之后只剩心跳。
         *   旧的「照发」依据是「ACK 逐条都回了」，但链路层 ACK 只证明 CRC 通过。
         * - **0x4E0E 一起裁掉**：它同样不在清单里，且只在 Phase 1 结束/催问时发，
         *   裁掉不影响会话建立（0x4300 才是驱动 0x4301 的请求，属协议骨架，永不裁）。
         *
         * 老协议（[audiDeclarations] / 清单未到）**不裁**，仍照发——另一条分支的
         * `expect(DEVICE_TRANSPORT_IDENTIFIER)` 就是锁这一点。
         */
        private fun assertUndeclaredFramesCut(windowMillis: Long) {
            val deadlineAt = System.nanoTime() + windowMillis * 1_000_000L
            while (true) {
                val remaining = (deadlineAt - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                recvFrame(remaining) ?: continue
            }
            check(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE !in seenUndeclared) {
                "新协议车机的可收清单里没有 0x4E0D，应当按声明裁掉，但实际发了"
            }
            check(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER !in seenUndeclared) {
                "新协议严格模式下 0x4E0E（传输标识）也应裁掉，但实际收到 " +
                    seenUndeclared.joinToString(" ") { "0x%04x".format(it) }
            }
        }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** 地址参数：真实车机按定长 NUL 填充；默认（不填充）与之前行为一致。 */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // 至少保留一个 NUL 结尾（CSM 字符串语义），其余补到固定宽度
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        /**
         * 期望收到 [messageId]。
         *
         * 允许其间穿插「设备状态问候 / 传输标识 / 可用性」这类**可随时补发**的消息：
         * 2026-10-26 起问候紧跟 `0xAA05` 发送（上游 PR #17 的结论），而等待 0x4301
         * 期间还会周期性催问——两者都会打乱「严格一问一答」的脚本。这里改成
         * 「跳过非目标消息」并记数，跳太多仍然判定失败（防止真的抓错帧）。
         */
        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            // 已经收到过、只是当时还没轮到它看的目标帧：直接拿走
            stashed.firstOrNull { it.messageId == messageId }?.let {
                stashed.remove(it)
                println("MOCK got 0x${it.messageId.toString(16)}（暂存区）")
                return
            }
            val deadlineAt = System.nanoTime() + 10_000_000_000L
            var skipped = 0
            while (System.nanoTime() < deadlineAt) {
                val frame =
                    recvFrame(10_000)
                        ?: error("mock timed out waiting 0x${messageId.toString(16)}")
                if (frame.messageId == messageId) {
                    println("MOCK got 0x${frame.messageId.toString(16)}")
                    return
                }
                if (frame.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += frame.messageId
                // 0x4300 可能先于 StartHID 到达（新协议代不再在 0x6800 上空等 2s）：
                // 不能像其它帧那样丢掉，否则后面永远等不到它。
                if (frame.messageId == Iap2MessageIds.CARPLAY_AVAILABILITY) {
                    stashed += frame
                    continue
                }
                skipped++
                println("MOCK skip 0x${frame.messageId.toString(16)}（在等 0x${messageId.toString(16)}）")
                check(skipped <= 64) {
                    "mock 等 0x${messageId.toString(16)} 期间跳过了 $skipped 条消息——脚本可能已错位"
                }
            }
            error("mock timed out waiting 0x${messageId.toString(16)}")
        }

        /**
         * 期望收到 [messageIds] 里的每一条，**顺序不限**。
         *
         * 问候那 4 条的顺序是实现细节（真 iPhone / 参考实现是 UUID→时间→语言→名称，
         * 老实现是语言→名称→时间→UUID），脚本按固定顺序断言只会在换序时误报。
         */
        private fun expectAll(vararg messageIds: Int) {
            val remaining = messageIds.toMutableSet()
            val deadlineAt = System.nanoTime() + 10_000_000_000L
            var skipped = 0
            while (remaining.isNotEmpty() && System.nanoTime() < deadlineAt) {
                val frame =
                    recvFrame(10_000)
                        ?: error("mock timed out waiting ${remaining.joinToString { "0x%04x".format(it) }}")
                if (frame.messageId in remaining) {
                    remaining -= frame.messageId
                    println("MOCK got 0x${frame.messageId.toString(16)}")
                    continue
                }
                if (frame.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += frame.messageId
                skipped++
                check(skipped <= 64) { "mock 等问候期间跳过了 $skipped 条消息——脚本可能已错位" }
            }
            check(remaining.isEmpty()) {
                "mock 没等到问候：" + remaining.joinToString(" ") { "0x%04x".format(it) }
            }
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                if (it.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += it.messageId
                noteDeviceName(it)
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            frames.first().let {
                if (it.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += it.messageId
                noteDeviceName(it)
            }
            return frames.first()
        }

        /** 记下 0x4E09 的报文体（CSM 参数头里有二进制长度，只取可打印字符）。 */
        private fun noteDeviceName(frame: Iap2Csm.Frame) {
            if (frame.messageId != Iap2MessageIds.DEVICE_INFORMATION_UPDATE) return
            val text = String(frame.body, Charsets.UTF_8).filter { it.code in 32..126 }
            if (text.isNotEmpty()) deviceNames += text
        }

        private companion object {
            /**
             * 凯迪拉克 CT5 可收清单里缺的两条（见 [assertUndeclaredFramesCut]）。
             *
             * 只用来「记录有没有出现」：新协议严格模式下两条都该被裁掉，
             * 出现任意一条都会让断言失败。
             */
            val UNDECLARED_FOR_CADILLAC =
                setOf(
                    Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
                    Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
                )
        }
    }
}
