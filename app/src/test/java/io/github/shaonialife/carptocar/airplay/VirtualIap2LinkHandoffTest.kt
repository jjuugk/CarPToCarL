package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2LinkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无线 CarPlay Wi-Fi iAP2 被动优先布防、有限补发 DETECT、节拍线程按需拉起与
 * 跨蓝牙/Wi-Fi 一致设备 UUID 的单元测试。
 */
class VirtualIap2LinkHandoffTest {
    private class HandoffHarness(
        controlChannelEnabled: Boolean = true,
        deviceUuid: String? = null,
    ) {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val car = Iap2LinkEngine()
        var clock = 0L
        var now = 1L

        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventSent.add(it)
                    true
                },
                sendControlIap = { bytes, isCurrentEpoch ->
                    if (isCurrentEpoch()) {
                        controlSent.add(bytes)
                        allControl.add(bytes)
                    }
                },
                tunnelWriter = { null },
                alive = { false },
                nowMillis = { clock },
                controlChannelEnabled = { controlChannelEnabled },
                deviceUuidProvider = deviceUuid?.let { fixed -> { fixed } },
            )

        fun tick() = link.onCarBytes(ByteArray(0))

        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        fun linkToCar() {
            while (controlSent.isNotEmpty()) {
                val batch = controlSent.toList()
                controlSent.clear()
                batch.forEach { car.feed(it, now) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            now += 600
            clock += 600
            car.advanceTime(now)
            linkToCar()
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        fun settle(maxRounds: Int = 8) {
            var last = -1
            var rounds = 0
            while (rounds++ < maxRounds && allControl.size != last) {
                last = allControl.size
                exchange()
            }
        }

        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            return controlFrames().drop(before).map { it.second }
        }

        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                var off = 0
                while (off + 9 <= chunk.size) {
                    if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
                        off += 1
                        continue
                    }
                    val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
                    if (frameLen < 9 || off + frameLen > chunk.size) break
                    if (frameLen > 9) {
                        val payload = chunk.copyOfRange(off + 9, off + frameLen - 1)
                        Iap2Csm.Parser().offer(payload).forEach { frame ->
                            out.add(payload to frame.messageId)
                        }
                    }
                    off += frameLen
                }
            }
            return out
        }

        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first
    }

    @Test
    fun encryptedSessionAcceptsPeerInitiatedDetectWithoutPriorArmMib3() {
        val h = HandoffHarness(controlChannelEnabled = true)
        // 模拟车机在事件通道主动先发 DETECT（如初始主屏被占用或 disableBluetooth 后切 Wi-Fi iAP2）
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
        // 随后车机发 SYN，完整进入 NORMAL 并经控制通道发 0x1D00 StartIdentification
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        assertTrue(h.controlFrames().any { it.second == 0x1D00 })
        // 对端已入站后，即使后续再调 armMib3() 也绝不再补发多余 DETECT
        val sentBeforeArm = h.allControl.size
        h.link.armMib3()
        assertEquals(sentBeforeArm, h.allControl.size)
    }

    @Test
    fun unrecognizedRawBytesDoNotSuppressSubsequentArmMib3Detect() {
        val h = HandoffHarness(controlChannelEnabled = true)
        // 喂入非 DETECT/SYN 的陈旧/无效字节，不应误触发被动布防，也不应抑制后续 armMib3() 发 DETECT
        h.link.onCarBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertTrue(h.controlSent.isEmpty())
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
    }

    @Test
    fun scheduleMib3ArmWaitsPassiveGraceAndSuppressesDetectWhenPeerInitiates() {
        val silent = HandoffHarness(controlChannelEnabled = true)
        silent.link.scheduleMib3Arm()
        assertTrue("宽限期内不应抢发 DETECT", silent.controlSent.isEmpty())
        silent.clock += VirtualIap2Link.PASSIVE_PEER_GRACE_MS - 1
        silent.tick()
        assertTrue("未满宽限期不应提前补发", silent.controlSent.isEmpty())
        silent.clock += 1
        silent.tick()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), silent.controlSent.map { it.toList() })

        val peerFirst = HandoffHarness(controlChannelEnabled = true)
        peerFirst.link.scheduleMib3Arm()
        peerFirst.clock += VirtualIap2Link.PASSIVE_PEER_GRACE_MS / 2
        peerFirst.link.onCarBytes(Iap2LinkEngine.MARKER)
        val afterPeerDetect = peerFirst.controlSent.size
        peerFirst.clock += VirtualIap2Link.PASSIVE_PEER_GRACE_MS * 2
        peerFirst.tick()
        assertEquals("对端主动发起后到期绝不再补发多余 DETECT", afterPeerDetect, peerFirst.controlSent.size)
    }

    @Test
    fun scheduleMib3ArmRetriesDetectUpToLimitAndCancelsWhenPeerReplies() {
        // 1. 持续静默的车机：+800ms / +2500ms / +5500ms 最多补发 3 次 DETECT，之后彻底停手
        val silent = HandoffHarness(controlChannelEnabled = true)
        silent.link.scheduleMib3Arm()
        silent.clock += VirtualIap2Link.PASSIVE_PEER_GRACE_MS
        silent.tick()
        assertEquals(1, silent.controlSent.size)
        silent.clock += VirtualIap2Link.MIB3_DETECT_RETRY_DELAYS_MS[0] - 1
        silent.tick()
        assertEquals(1, silent.controlSent.size)
        silent.clock += 1
        silent.tick()
        assertEquals(2, silent.controlSent.size)
        silent.clock += VirtualIap2Link.MIB3_DETECT_RETRY_DELAYS_MS[1]
        silent.tick()
        assertEquals(3, silent.controlSent.size)
        silent.clock += 60_000L
        silent.tick()
        assertEquals("补发次数必须有上限，静默车机绝不无限刷 DETECT", VirtualIap2Link.MIB3_DETECT_MAX_ATTEMPTS, silent.controlSent.size)
        assertTrue(silent.controlSent.all { it.contentEquals(Iap2LinkEngine.MARKER) })

        // 2. 第 1 次补发后收到对端 DETECT：立即取消剩余补发
        val latePeer = HandoffHarness(controlChannelEnabled = true)
        latePeer.link.scheduleMib3Arm()
        latePeer.clock += VirtualIap2Link.PASSIVE_PEER_GRACE_MS
        latePeer.tick()
        assertEquals(1, latePeer.controlSent.size)
        latePeer.clock += 400L
        latePeer.link.onCarBytes(Iap2LinkEngine.MARKER)
        val afterPeer = latePeer.controlSent.size
        latePeer.clock += 10_000L
        latePeer.tick()
        assertEquals("对端回应后不得再发后续 DETECT 补发", afterPeer, latePeer.controlSent.size)
    }

    @Test
    fun scheduleMib3ArmStartsLivePacerWhenCreatedBeforeSocketAlive() {
        var socketAlive = false
        val detectLatch = java.util.concurrent.CountDownLatch(1)
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = { true },
                sendControlIap = { bytes, isCurrentEpoch ->
                    if (isCurrentEpoch() && bytes.contentEquals(Iap2LinkEngine.MARKER)) {
                        detectLatch.countDown()
                    }
                },
                tunnelWriter = { null },
                alive = { socketAlive },
                controlChannelEnabled = { true },
            )
        try {
            // 建链前 socket 尚未就绪，此时 pacer 不会启动；就绪后调 scheduleMib3Arm 必须拉起活节拍并按期补发 DETECT
            socketAlive = true
            link.scheduleMib3Arm(delayMs = 50L)
            assertTrue(
                "连接就绪后 scheduleMib3Arm 必须拉起节拍线程并补发 DETECT",
                detectLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
            )
        } finally {
            socketAlive = false
            link.close()
        }
    }

    @Test
    fun wifiIap2BurstUsesInjectedDeterministicDeviceUuidMatchingClassicBt() {
        val expectedUuid =
            io.github.shaonialife.carptocar.util.DeviceIdentity
                .deviceUuid("B0:DE:31:01:65:22")
        val h = HandoffHarness(controlChannelEnabled = true, deviceUuid = expectedUuid)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "BMW 14431"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(64)))
        h.settle()

        val uuidFrame = h.lastRawFrameOf(0x4E0C)!!
        val params = Iap2Csm.parseParams(uuidFrame.copyOfRange(6, uuidFrame.size))
        val wireUuid =
            params
                .first { it.id == 0 }
                .value
                .dropLast(1)
                .toByteArray()
                .toString(Charsets.UTF_8)
        assertEquals(expectedUuid, wireUuid)
    }
}
