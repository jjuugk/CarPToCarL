package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.AccessoryDeclaration
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2ArtworkSupport
import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2LinkEngine
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.MfiLengthMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIB3 WiFi 隧道握手（VirtualIap2Link 服务端 × 裸引擎扮演车机客户端）。
 *
 * 时序即 AADisplay 以 Frida 抓到的真机行为：手机 arm（发一次 detect）→ 车机 SYN →
 * 手机 SYN|ACK →（链路 NORMAL）手机 0x1D00 → 车机 0x1D01 → 手机 0x1D02+0xAA00 →
 * （0xAA06 → 0xAA00）→ 0xAA01 → 手机 0xAA02 挑战 **20B** → 车机 0xAA03 → 手机
 * 0xAA05 + 突发 → 车机订阅（0x5000/0x4157/0x4154/0x4170）→ 对应空态应答。
 */

/**
 * 按链路帧头拆分（FF5A + u16 长度 + 9B 头）：引擎一次 takeOutput 可能拼多帧，
 * marker（6B，非 FF5A）跳过。
 */
private fun splitLinkFrames(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        // 长度字段 = 整帧长度（含 9B 头），非载荷长
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        // 帧结构：9B 头（含头校验）+ 载荷 + 1B 尾部载荷校验（len=整帧长度）；
        // 裸 ACK/回显帧无载荷（整帧恰 9B）——跳过而非中断，同块其后的数据帧照常解析
        if (frameLen < 9 || off + frameLen > chunk.size) break
        if (frameLen > 9) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

class VirtualIap2LinkTest {
    private class Harness(
        controlChannelEnabled: Boolean = true,
        deviceUuid: String? = null,
    ) {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val car = Iap2LinkEngine()
        /** 可变歌名源：测试「订阅先于歌名」「内容变化」两条路径。 */
        var nowPlaying: CarPlayNowPlaying? = null
        var nowPlayingOverride: CarPlayNowPlaying? = null
        var artwork: Map<Int, ByteArray> = emptyMap()
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
                // 关闭节拍线程：测试内确定性驱动（无重传干扰）
                alive = { false },
                nowMillis = { clock },
                nowPlayingProvider = { nowPlaying },
                artworkProvider = { artwork[it] },
                nowPlayingOverride = { nowPlayingOverride },
                controlChannelEnabled = { controlChannelEnabled },
                deviceUuidProvider = deviceUuid?.let { fixed -> { fixed } },
            )
        var clock = 0L
        var now = 1L

        /** 手动驱动一次节拍：空字节对链路引擎是 no-op，只会跑一遍 drainLocked。 */
        fun tick() = link.onCarBytes(ByteArray(0))

        /** 车机→手机：喂字节并冲刷链路出站。 */
        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        /** 手机→车机：把控制通道出站字节喂回车机引擎。 */
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
            // 车机侧驱动计时器：采纳对端表后按超时机制补裸 ACK（真机行为同此——
            // BT 日志里我们的 SYN|ACK 之后车机立即回 ACK）
            car.advanceTime(now)
            linkToCar()
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        /**
         * 一直跑到出站不再增长为止。
         *
         * 发送窗口按**对端声明的 maxOutgoing** 计（本 harness 的车机用引擎默认 4），
         * 所以一次突发（MFi 之后那 8 条）会分成几批发：先发满窗口，等车机 ACK 再放下一批。
         * 生产环境由 ACK + 节拍线程驱动，测试里没有线程，必须显式多跑几轮——
         * 否则只能看到第一批，断言会误判成「后面几条没发」。
         */
        fun settle(maxRounds: Int = 8) {
            var last = -1
            var rounds = 0
            while (rounds++ < maxRounds && allControl.size != last) {
                last = allControl.size
                exchange()
            }
        }

        /** 车机发一条 CSM，返回手机随后经控制通道发出的 CSM 消息号序列。 */
        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            val ids = controlFrames().drop(before).map { it.second }
            return ids
        }

        /** 控制通道出站 →（链路帧拆分）→ 完整 CSM 帧字节与其消息号。
         *  每个链路帧载荷恰好一条 CSM：必须逐帧新建 Parser——SYN|ACK 的 LSP 载荷
         *  不是 CSM，共用有状态解析器会污染后续帧的边界。 */
        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                splitLinkFrames(chunk).forEach { payload ->
                    Iap2Csm.Parser().offer(payload).forEach { frame ->
                        out.add(payload to frame.messageId)
                    }
                }
            }
            return out
        }

        /** 最近一条指定消息号的完整 CSM 帧字节（Frida 常量逐字节比对用）。 */
        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first

        /** 已发 0xAA02 挑战的长度序列（param 0 长度；多条时按出现顺序）。 */
        fun challengeLengths(): List<Int> =
            controlFrames()
                .filter { it.second == 0xAA02 }
                .map { (raw, _) ->
                    Iap2Csm
                        .parseParams(raw.copyOfRange(6, raw.size))
                        .first()
                        .value.size
                }
    }

    @Test
    fun mib3ArmSendsExactlyOneDetectOnControlChannel() {
        val h = Harness()
        h.link.armMib3()
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun plaintextSessionPreArmMarkerGetsNoReply() {
        val h = Harness(controlChannelEnabled = false)
        h.link.begin()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlSent.isEmpty())
        assertTrue(h.eventSent.isEmpty())
    }

    /**
     * Honda HFT（2026-10-20 实测）：车机在事件通道上 1Hz 反复发 marker，说明它卡在
     * 探测态、在等**我们**的会话表。连着第 2 个 marker 起必须升级为「marker + 本表
     * 同步包」，且**只能走控制通道**——出站写在事件通道上车机不读（r6 踩过的坑）。
     */
    @Test
    fun repeatedMarkersUpgradeToControlChannelHandshake() {
        val h = Harness()
        h.link.armMib3()
        val detect = h.controlSent.size
        // 首个对端 DETECT 立即回显 marker，随后再次收到 DETECT 而仍无 SYN 时升级发会话同步表
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(Iap2LinkEngine.MARKER.toList(), h.controlSent.last().toList())
        assertEquals(detect + 1, h.controlSent.size)
        val afterFirst = h.controlSent.size
        h.clock += 1_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val sent = h.controlSent.drop(afterFirst)
        assertTrue("重复 marker 后必须补发协商字节", sent.isNotEmpty())
        val payload = sent.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertTrue(
            "补发的第一批必须含 marker（FF 55）",
            payload.size >= 6 && payload[0] == 0xFF.toByte() && payload[1] == 0x55.toByte(),
        )
        assertTrue(
            "marker 之后必须紧跟 FF5A 会话表（车机等的就是它）",
            payload.size >= 12 && payload[6] == 0xFF.toByte() && payload[7] == 0x5A.toByte(),
        )
        assertTrue(
            "会话表必须带 SYN 位（0x80）",
            (payload[10].toInt() and 0x80) != 0,
        )
        assertTrue("出站绝不能落到事件通道", h.eventSent.isEmpty())
    }

    @Test
    fun fullMib3HandshakeWithBurstAndSubscriptions() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // detect(1) + SYN|ACK + 0x1D00 已走控制通道；车机已收到 0x1D00
        assertTrue(h.controlFrames().map { it.second }.contains(0x1D00))

        // 0x1D01 → 0x1D02 + 0xAA00
        assertEquals(
            listOf(0x1D02, 0xAA00),
            h.carSends(0x1D01, Iap2Csm.string(0, "Audi_MMI_0000") + Iap2Csm.string(1, "AUDI 0000")),
        )

        // 0xAA06（auth serial）→ 再发 0xAA00
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))

        // 0xAA01（证书）→ 0xAA02 挑战 20B（MIB3 真机长度；DiPlay 形态为 32B）
        val challengeFrames = h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(0xAA02), challengeFrames)
        val challengeBody =
            h
                .lastRawFrameOf(0xAA02)!!
                .copyOfRange(6, h.lastRawFrameOf(0xAA02)!!.size)
                .let { Iap2Csm.parseParams(it).first() }
        assertEquals(20, challengeBody.value.size)

        // 0xAA03（签名应答）→ 0xAA05 + 突发（0x4E0C/0x4E0A/0x4E0B/0x4E09）。
        // **0xA100 / 0xFFFA / 0xFFF0 自 1.2.36 起不发**：它们是抓包常量，别克与宝马
        // 收到后数十毫秒到数秒内重置 WiFi/CarPlay 组件（2026-11-11 日志）。
        // 窗口按对端声明的 4 计：这 5 条会分成 4+1 两批，靠车机 ACK 放行；顺序不变。
        val burstFrom = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.settle()
        assertEquals(
            listOf(0xAA05, 0x4E0C, 0x4E0A, 0x4E0B, 0x4E09),
            h.controlFrames().drop(burstFrom).map { it.second },
        )

        // 订阅应答：0x5000 → 0x5001 空态；0x4157 → 0x4158；0x4154 → 0x4155；0x4170 → 0x4171
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4171), h.carSends(0x4170))
    }

    @Test
    fun postAuthDeviceStateUsesNulTerminatedUtf8AndNegotiatedAccessoryLanguage() {
        AccessoryDeclaration.reset()
        try {
            val h = Harness()
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()

            // 模拟车机在 0x1D01 参数 12 声明当前语言为 en（仅支持 en/de，不支持硬编码 zh）
            h.carSends(
                0x1D01,
                Iap2Csm.string(0, "CarPlay_HU") +
                    Iap2Csm.string(1, "HU_GEN2") +
                    Iap2Csm.string(12, "en"),
            )
            h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(608)))
            val burstFrom = h.controlFrames().size
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(68)))
            h.settle()

            val burst = h.controlFrames().drop(burstFrom)
            assertEquals(listOf(0xAA05, 0x4E0C, 0x4E0A, 0x4E0B, 0x4E09), burst.map { it.second })

            for (msgId in listOf(0x4E0C, 0x4E0A, 0x4E09)) {
                val raw = h.lastRawFrameOf(msgId)!!
                val param0 = Iap2Csm.parseParams(raw.copyOfRange(6, raw.size)).first { it.id == 0 }.value
                assertTrue("0x${msgId.toString(16)} UTF-8 参数必须以 NUL 结尾", param0.isNotEmpty() && param0.last() == 0.toByte())
            }
            val langRaw = h.lastRawFrameOf(0x4E0A)!!
            val langBytes = Iap2Csm.parseParams(langRaw.copyOfRange(6, langRaw.size)).first { it.id == 0 }.value
            assertEquals(listOf('e'.code.toByte(), 'n'.code.toByte(), 0.toByte()), langBytes.toList())
        } finally {
            AccessoryDeclaration.reset()
        }
    }

    @Test
    fun nowPlayingIdleReplyIsTheFridaConstantVerbatim() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "x"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.settle() // 突发先走完（窗口只放 4 条），否则订阅应答会排在它后面
        h.carSends(0x5000)
        // 逐字节等于 Frida 抓包常量——字段布局只存在于真机行为里，重新编码极易走样
        assertEquals(
            VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE.toList(),
            h.lastRawFrameOf(0x5001)!!.toList(),
        )
        assertEquals(
            VirtualIap2Link.MIB3_COMMUNICATIONS_UPDATE_IDLE.toList(),
            run {
                h.carSends(0x4157)
                h.lastRawFrameOf(0x4158)!!.toList()
            },
        )
    }

    /** 走完 MIB3 识别 + MFi，停在「车机即将订阅」之前。 */
    private fun Harness.readyForSubscriptions() {
        link.armMib3()
        car.start(0)
        exchangeUntilNormal()
        carSends(0x1D01, Iap2Csm.string(0, "Audi") + Iap2Csm.string(1, "MMI"))
        carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        settle() // 突发按对端窗口分批发，先走完再让车机订阅
    }

    private fun song(
        title: String,
        elapsedMs: Int = 0,
    ) = CarPlayNowPlaying(
        title = title,
        artist = "歌手",
        album = "专辑",
        durationMs = 180_000,
        status = Iap2NowPlaying.STATUS_PLAYING,
        elapsedMs = elapsedMs,
        appName = "CarLife+",
    )

    /**
     * 真机（vivo Android 17 / 通知监听器晚绑定）复现：车机先订阅 0x5000 拿到空态，
     * 歌名稍后才存在且**再也不会有事件**。旧实现永久停在空态；现在由链路节拍自愈。
     */
    @Test
    fun nowPlayingArrivingAfterSubscriptionIsPushedByPoll() {
        val h = Harness()
        h.readyForSubscriptions()
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(
            VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE.toList(),
            h.lastRawFrameOf(0x5001)!!.toList(),
        )

        // 歌名晚到，且不触发任何 MediaSession / CarLife 事件
        h.nowPlaying = song("晚到的歌")
        h.clock += VirtualIap2Link.NOW_PLAYING_POLL_MILLIS
        h.tick()
        val updates = h.controlFrames().filter { it.second == 0x5001 }
        assertEquals("订阅空态 + 周期补推应恰好两条 0x5001", 2, updates.size)
        assertTrue(
            "补推必须是带歌名的真帧而非空态常量：${updates.last().first.size}B",
            String(updates.last().first, Charsets.UTF_8).contains("晚到的歌"),
        )

        // 内容未变：再过一周期也不重发（进度 elapsedMs 变化不算换歌）
        h.nowPlaying = song("晚到的歌", elapsedMs = 9_000)
        h.clock += VirtualIap2Link.NOW_PLAYING_POLL_MILLIS
        h.tick()
        assertEquals("内容未变不该重发", 2, h.controlFrames().count { it.second == 0x5001 })

        // 换歌（逐行歌词写进 title 同理）→ 再推一次
        h.nowPlaying = song("下一首")
        h.clock += VirtualIap2Link.NOW_PLAYING_POLL_MILLIS
        h.tick()
        assertEquals("换歌应再推一次", 3, h.controlFrames().count { it.second == 0x5001 })
    }

    /** 周期补推只在车机订阅过 0x5000 之后生效，避免改变既有会话形态。 */
    @Test
    fun nowPlayingPollRequiresSubscriptionAndKeyIgnoresProgress() {
        val h = Harness()
        h.readyForSubscriptions()
        h.nowPlaying = song("未订阅的歌")
        h.clock += VirtualIap2Link.NOW_PLAYING_POLL_MILLIS * 2
        h.tick()
        assertEquals(
            "车机没订阅 0x5000 时不该主动发 0x5001",
            0,
            h.controlFrames().count { it.second == 0x5001 },
        )

        val np = song("同一首歌")
        assertEquals(
            "播放进度变化不该触发重推",
            h.link.nowPlayingKey(np),
            h.link.nowPlayingKey(np.copy(elapsedMs = 9_000)),
        )
        assertNotEquals(
            "换歌必须改变内容键",
            h.link.nowPlayingKey(np),
            h.link.nowPlayingKey(np.copy(title = "另一首")),
        )
    }

    @Test
    fun navigationOverrideRequiresSubscriptionAndRestoresTheSameSong() {
        val h = Harness()
        h.readyForSubscriptions()
        h.nowPlaying = song("音乐标题")
        h.nowPlayingOverride = song("转向指引")

        h.link.pushNowPlaying()
        h.tick()
        h.settle()
        assertTrue(String(h.lastRawFrameOf(0x5001)!!, Charsets.UTF_8).contains("音乐标题"))

        h.carSends(0x5000)
        h.settle()
        assertTrue(String(h.lastRawFrameOf(0x5001)!!, Charsets.UTF_8).contains("转向指引"))
        h.nowPlayingOverride = null
        h.clock += VirtualIap2Link.NOW_PLAYING_POLL_MILLIS
        h.tick()
        h.settle()
        assertTrue(String(h.lastRawFrameOf(0x5001)!!, Charsets.UTF_8).contains("音乐标题"))

        h.carSends(0x5002)
        h.nowPlayingOverride = song("迟到指引")
        h.link.pushNowPlaying()
        h.tick()
        h.settle()
        assertTrue(String(h.lastRawFrameOf(0x5001)!!, Charsets.UTF_8).contains("音乐标题"))
    }

    @Test
    fun navigationArtworkTimeoutDoesNotDisableMusicArtwork() {
        Iap2ArtworkSupport.reset()
        try {
            val h = Harness()
            h.readyForSubscriptions()
            val guide = song("转向指引").copy(artworkId = 21, navigationArtwork = true)
            h.nowPlayingOverride = guide
            h.artwork = mapOf(21 to byteArrayOf(1), 22 to byteArrayOf(2))

            h.carSends(0x5000)
            h.settle()
            assertEquals(Iap2NowPlaying.encode(guide).toList(), h.lastRawFrameOf(0x5001)?.toList())

            h.clock += VirtualIap2Link.ARTWORK_START_TIMEOUT_MILLIS
            h.tick()
            h.link.pushNowPlaying()
            h.tick()
            h.settle()
            assertFalse(Iap2ArtworkSupport.unsupported)
            assertEquals(Iap2NowPlaying.encode(guide.copy(artworkId = null)).toList(), h.lastRawFrameOf(0x5001)?.toList())

            val music = song("音乐标题").copy(artworkId = 22)
            h.nowPlaying = music
            h.nowPlayingOverride = null
            h.link.pushNowPlaying()
            h.tick()
            h.settle()
            assertEquals(Iap2NowPlaying.encode(music).toList(), h.lastRawFrameOf(0x5001)?.toList())
        } finally {
            Iap2ArtworkSupport.reset()
        }
    }

    @Test
    fun tunnelMfiUsesRememberedLengthPerCar() {
        // 与其它测试不同身份，避免进程内记忆串扰
        val identity = "MemCar MemModel MemMfr tunnel"
        MfiLengthMemory.recordSuccess(null, identity, 32)
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MemCar") + Iap2Csm.string(1, "MemModel") + Iap2Csm.string(2, "MemMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        // 记忆命中：32B 直接排第一，不再从 20B 试起
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test
    fun silentChallengeRetriesNextLengthWithinSession() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SilCar") + Iap2Csm.string(1, "SilMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(20), h.challengeLengths())
        // 推进时钟越过 6s 静默窗口并驱动节拍，不注入额外 marker 以免重置已认证链路周期
        h.clock += 7_000
        h.tick()
        assertEquals(listOf(20, 32), h.challengeLengths())
        // 32B 得到应答 → 0xAA05 + 突发照常
        val after = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.settle() // 突发分两批（窗口 4）
        val emitted = h.controlFrames().drop(after).map { it.second }
        assertTrue("frames=$emitted", emitted.containsAll(listOf(0xAA05, 0x4E0C, 0x4E09)))
        // 抓包常量那三条必须**一条都不再发**：它们是车机 CarPlay 组件崩溃的嫌疑源。
        assertFalse("不应再发 0xFFFA：frames=$emitted", emitted.contains(0xFFFA))
        assertFalse("不应再发 0xFFF0：frames=$emitted", emitted.contains(0xFFF0))
        assertFalse("不应再发 0xA100（对端声明可收也不发）：frames=$emitted", emitted.contains(0xA100))
    }

    @Test
    fun allChallengeLengthsSilentStillSendsAuthenticationSucceededLikeBluetoothPath() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MuteCar") + Iap2Csm.string(1, "MuteMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        val before = h.controlFrames().size
        // 三档都不应答：每次越过该档的静默窗口再驱动节拍
        repeat(3) {
            h.clock += 7_000
            h.tick()
        }
        h.settle()
        assertEquals(listOf(20, 32, 16), h.challengeLengths())
        val emitted = h.controlFrames().drop(before).map { it.second }
        assertTrue("挑战全部无应答也要跳过校验发 0xAA05 和问候：frames=$emitted", emitted.containsAll(listOf(0xAA05, 0x4E0C, 0x4E09)))
        assertTrue(h.link.controlSessionReady)
    }

    @Test
    fun ecdsaCertificateSelects32ByteChallengeFirstOnColdStart() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "EcdsaCar") + Iap2Csm.string(1, "EcdsaMfr"))
        val certWithEcdsaOid =
            ByteArray(64) +
                byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01) +
                ByteArray(64)
        h.carSends(0xAA01, Iap2Csm.param(0, certWithEcdsaOid))
        assertEquals("ECDSA P-256 证书冷启动应直接首选 32B 挑战", listOf(32), h.challengeLengths())
    }

    @Test
    fun linkInterruptDuringChallengeHardRejectsStaleSucceededLength() {
        io.github.shaonialife.carptocar.iap2.MfiLengthMemory
            .recordSuccess(null, "InterruptCar InterruptMfr tunnel", 20)
        val first = Harness()
        first.link.armMib3()
        first.car.start(0)
        first.exchangeUntilNormal()
        first.carSends(0x1D01, Iap2Csm.string(0, "InterruptCar") + Iap2Csm.string(1, "InterruptMfr"))
        first.carSends(0xAA01, Iap2Csm.param(0, ByteArray(512)))
        assertEquals(listOf(20), first.challengeLengths())
        // 20B 挑战等待期间车机拆链关闭：必须按硬拒绝推翻旧的 succeeded=20 记忆
        first.link.close()

        val second = Harness()
        second.link.armMib3()
        second.car.start(0)
        second.exchangeUntilNormal()
        second.carSends(
            0x1D01,
            Iap2Csm.string(0, "InterruptCar") + Iap2Csm.string(1, "InterruptMfr"),
        )
        // RSA 证书的冷启动基线本是 20B 优先：硬拒绝后 20B 必须让位
        second.carSends(0xAA01, Iap2Csm.param(0, ByteArray(512)))
        assertEquals("硬拒绝 20B 后下一轮必须首选 32B", listOf(32), second.challengeLengths())
    }

    /**
     * 0x6802 取参数：本田 HFT 实测帧 `00 06 00 00 00 03 | 00 05 00 01 04`。
     *
     * param 0 恒为 `00 03`（报告类型），param 1 才是 HID 报文（0x04 = 下一首）。
     * 旧实现取「第一个短参数」→ 永远拿到 00 03 → 位图全 0 → 按键点不出动作。
     */
    @Test
    fun hidReportComesFromParamOneNotParamZero() {
        // 单行书写：ktlint 对该规则要求「多行则一参数一行」，单行更贴近原始帧的可读形式
        val body = byteArrayOf(0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x05, 0x00, 0x01, 0x04)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x04), report?.toList())
    }

    @Test
    fun hidReportFallsBackToLastShortParamWhenOnlyOneGiven() {
        val body = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x08)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x08), report?.toList())
    }

    @Test
    fun startPowerUpdatesRepliesSingleCombinedPowerUpdateFrameAndStopsOnAe02() {
        AccessoryDeclaration.reset()
        try {
            val h = Harness()
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            h.carSends(0x1D01, Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr"))
            h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
            h.settle()

            val before = h.controlFrames().size
            h.carSends(0xAE00, Iap2Csm.param(4, ByteArray(0)) + Iap2Csm.param(5, ByteArray(0)) + Iap2Csm.param(6, ByteArray(0)))
            h.settle()
            val replies = h.controlFrames().drop(before)
            assertEquals(listOf(0xAE01), replies.map { it.second })
            val params = Iap2Csm.parseParams(replies.single().first.copyOfRange(6, replies.single().first.size))
            assertEquals(listOf(4, 5, 6), params.map { it.id })

            val beforeStop = h.controlFrames().size
            h.carSends(0xAE02)
            h.settle()
            assertTrue(h.controlFrames().drop(beforeStop).isEmpty())
        } finally {
            AccessoryDeclaration.reset()
        }
    }

    @Test
    fun earlyPowerUpdatesDeferredUntilAuthFinishedAndCancelledByAe02AndLinkRestart() {
        AccessoryDeclaration.reset()
        try {
            // 1) 认证前 0xAE00 延后到 0xAA03 完成后发 1 次 0xAE01
            val deferred = Harness()
            deferred.link.armMib3()
            deferred.car.start(0)
            deferred.exchangeUntilNormal()
            deferred.carSends(0x1D01, Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr"))
            deferred.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            val beforeEarly = deferred.controlFrames().size
            deferred.carSends(0xAE00)
            deferred.settle()
            assertTrue("认证完成前不应先发 0xAE01", deferred.controlFrames().drop(beforeEarly).none { it.second == 0xAE01 })
            val beforeAuth = deferred.controlFrames().size
            deferred.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
            deferred.settle()
            assertEquals(1, deferred.controlFrames().drop(beforeAuth).count { it.second == 0xAE01 })

            // 2) 认证前 0xAE00 紧跟 0xAE02 取消：0xAA03 后绝不再补发 0xAE01
            val cancelled = Harness()
            cancelled.link.armMib3()
            cancelled.car.start(0)
            cancelled.exchangeUntilNormal()
            cancelled.carSends(0x1D01, Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr"))
            cancelled.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            cancelled.carSends(0xAE00)
            cancelled.carSends(0xAE02)
            cancelled.settle()
            val beforeCancelledAuth = cancelled.controlFrames().size
            cancelled.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
            cancelled.settle()
            assertEquals(0, cancelled.controlFrames().drop(beforeCancelledAuth).count { it.second == 0xAE01 })

            // 3) 认证前 0xAE00 后对端重发 DETECT 重启链路：旧挂起标志必须清零，新链路认证完不发旧 0xAE01
            val restarted = Harness()
            restarted.link.armMib3()
            restarted.car.start(0)
            restarted.exchangeUntilNormal()
            restarted.carSends(0x1D01, Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr"))
            restarted.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            restarted.carSends(0xAE00)
            restarted.settle()
            val freshCar = Iap2LinkEngine()
            restarted.link.onCarBytes(Iap2LinkEngine.MARKER)
            restarted.controlSent.clear()
            freshCar.start(0)
            repeat(8) {
                val out = freshCar.takeOutput()
                if (out.isNotEmpty()) restarted.link.onCarBytes(out)
                while (restarted.controlSent.isNotEmpty()) {
                    val batch = restarted.controlSent.toList()
                    restarted.controlSent.clear()
                    batch.forEach { freshCar.feed(it, restarted.now) }
                }
                restarted.now += 600
                restarted.clock += 600
                freshCar.advanceTime(restarted.now)
                if (freshCar.state == Iap2LinkEngine.State.NORMAL) return@repeat
            }
            assertEquals(Iap2LinkEngine.State.NORMAL, freshCar.state)
            fun freshSend(id: Int, body: ByteArray = ByteArray(0)) {
                assertTrue(freshCar.sendControl(Iap2Csm.encode(id, body), restarted.now))
                repeat(6) {
                    val out = freshCar.takeOutput()
                    if (out.isNotEmpty()) restarted.link.onCarBytes(out)
                    while (restarted.controlSent.isNotEmpty()) {
                        val batch = restarted.controlSent.toList()
                        restarted.controlSent.clear()
                        batch.forEach { freshCar.feed(it, restarted.now) }
                    }
                    restarted.now += 600
                    restarted.clock += 600
                    freshCar.advanceTime(restarted.now)
                }
            }
            freshSend(0x1D01, Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr"))
            freshSend(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            val beforeNewAuth = restarted.controlFrames().size
            freshSend(0xAA03, Iap2Csm.param(0, ByteArray(96)))
            assertEquals(0, restarted.controlFrames().drop(beforeNewAuth).count { it.second == 0xAE01 })
        } finally {
            AccessoryDeclaration.reset()
        }
    }

    @Test
    fun mib3BurstOmitsVehicleStatusUpdateEvenWhenDeclaredInIdentification() {
        AccessoryDeclaration.reset()
        try {
            val h = Harness()
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            val receiveList = byteArrayOf(0xA1.toByte(), 0x00, 0x4E, 0x0C, 0x4E, 0x0A, 0x4E, 0x0B, 0x4E, 0x09)
            h.carSends(
                0x1D01,
                Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr") + Iap2Csm.param(7, receiveList),
            )
            h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            val burstFrom = h.controlFrames().size
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
            h.settle()
            val burst = h.controlFrames().drop(burstFrom)
            // 五帧突发（0xAA05 + 4×0x4E0x）刚好落入 peerWindow=5，不再夹带 0xA100
            assertEquals(listOf(0xAA05, 0x4E0C, 0x4E0A, 0x4E0B, 0x4E09), burst.map { it.second })
        } finally {
            AccessoryDeclaration.reset()
        }
    }

    @Test
    fun startRouteGuidanceWithoutActiveRouteDoesNotSendEmpty5201AndPushesWhenRouteStarts() {
        AccessoryDeclaration.reset()
        io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.reset()
        try {
            val h = Harness()
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            val receiveList = byteArrayOf(0x52, 0x01, 0x52, 0x02, 0x4E, 0x0C, 0x4E, 0x0A, 0x4E, 0x0B, 0x4E, 0x09)
            h.carSends(
                0x1D01,
                Iap2Csm.string(0, "TestCar") + Iap2Csm.string(1, "Mfr") + Iap2Csm.param(7, receiveList),
            )
            h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
            h.settle()

            // 尚无活跃导航时收到 0x5200：只登记订阅，绝不发 0 字节空 0x5201
            val beforeSub = h.controlFrames().size
            h.carSends(0x5200, Iap2Csm.u16(0, 1))
            h.settle()
            assertTrue(
                "无活跃导航时不应发送空 0x5201：frames=${h.controlFrames().drop(beforeSub).map { it.second }}",
                h.controlFrames().drop(beforeSub).none { it.second == 0x5201 },
            )
            assertTrue(
                io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.isSubscribed(
                    io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.Link.TUNNEL,
                ),
            )

            // 导航开始后主动推送 0x5202 + 0x5201，且携带车机订阅的 componentId=1
            val beforeNav = h.controlFrames().size
            io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.publish(
                io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn(
                    action = io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide.ACTION_START,
                    nextTurn = io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide.LEFT_TURN,
                    rawNextTurn = 7,
                    roadName = "测试路",
                    remainDistance = 120,
                ),
            )
            h.link.pushRouteGuidance()
            h.tick()
            h.settle()
            val navFrames = h.controlFrames().drop(beforeNav)
            assertEquals(listOf(0x5202, 0x5202, 0x5201), navFrames.map { it.second })
            navFrames.forEach { (raw, _) ->
                val comp = Iap2Csm.parseParams(raw.copyOfRange(6, raw.size)).first { it.id == 0 }
                assertEquals(listOf<Byte>(0x00, 0x01), comp.value.toList())
            }
        } finally {
            io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.reset()
            AccessoryDeclaration.reset()
        }
    }

    @Test
    fun controlChannelTrimsCommunicationsAndListRepliesToRequestedFields() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "TestCar"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.settle()

        val commsReq = listOf(0, 1, 7, 9, 17).fold(ByteArray(0)) { acc, id -> acc + Iap2Csm.param(id, ByteArray(0)) }
        assertEquals(listOf(0x4158), h.carSends(0x4157, commsReq))
        val commsRaw = h.lastRawFrameOf(0x4158)!!
        val commsParams = Iap2Csm.parseParams(commsRaw.copyOfRange(6, commsRaw.size))
        assertEquals(listOf(0, 1, 7, 9, 17), commsParams.map { it.id })
        assertEquals(31, commsRaw.size)

        val listReq =
            Iap2Csm.param(0x0001, ByteArray(0)) +
                Iap2Csm.u16(0x0003, 0) +
                Iap2Csm.u8(0x0004, 0)
        assertEquals(listOf(0x4171), h.carSends(0x4170, listReq))
        val listRaw = h.lastRawFrameOf(0x4171)!!
        val listParams = Iap2Csm.parseParams(listRaw.copyOfRange(6, listRaw.size))
        assertEquals(listOf(0x0000), listParams.map { it.id })
        assertEquals(11, listRaw.size)
    }

    @Test
    fun unsolicitedOrEmptyMfiResponseDoesNotCompleteControlSession() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SpoofCar"))

        // 尚未发出 0xAA02 挑战前就收到 0xAA03：绝不能置为就绪
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertFalse(h.link.controlSessionReady)

        // 收到 0xAA01 后手机发了 0xAA02，但车机回空载荷 0xAA03：仍不能置为就绪
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, ByteArray(0))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(0)))
        assertFalse(h.link.controlSessionReady)

        // 真正带非空参数 0 的签名到达后才完成认证
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertTrue(h.link.controlSessionReady)
    }

    @Test
    fun unsolicitedPushesWaitForMfiAndDeclaredSubscriptions() {
        AccessoryDeclaration.reset()
        io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.reset()
        try {
            val h = Harness()
            h.nowPlaying = song("抢跑歌名").copy(artworkId = 1)
            h.artwork = mapOf(1 to byteArrayOf(1, 2, 3, 4))
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()

            // 0x1D01 声明车机会发 0x5000 和 0x4154，此时尚未完成 MFi（0xAA03）
            val sendList = byteArrayOf(0x50, 0x00, 0x41, 0x54)
            val recvList = byteArrayOf(0x50, 0x01, 0x41, 0x55, 0x52, 0x01, 0x52, 0x02)
            h.carSends(
                0x1D01,
                Iap2Csm.string(0, "BMW 14431") +
                    Iap2Csm.param(6, sendList) +
                    Iap2Csm.param(7, recvList),
            )
            h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(612)))

            // MFi 进行中：任何主动补推都不得发出 0x5001 / 0x4155 / 0x5201 / 封面 SETUP
            val beforeMidMfi = h.allControl.size
            assertFalse("MFi 完成前不得主动推 0x5001", h.link.pushNowPlaying())
            h.link.pushCallState()
            h.link.pushRouteGuidance()
            h.link.pushRouteGuidanceHeartbeat()
            h.tick()
            assertEquals("MFi 完成前主动补推不得向控制通道排入任何帧", beforeMidMfi, h.allControl.size)

            // MFi 完成，但车机声明会发 0x5000/0x4154 且尚未订阅：仍不得抢推 0x5001/0x4155
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(64)))
            h.settle()
            val beforePreSub = h.controlFrames().size
            assertFalse("车机声明会发 0x5000 且未订阅前不得抢推 0x5001", h.link.pushNowPlaying())
            h.link.pushCallState()
            h.tick()
            assertTrue(
                "未收到 0x5000/0x4154 前不得抢推",
                h.controlFrames().drop(beforePreSub).isEmpty(),
            )

            // 车机发 0x5000 订阅后才推送 0x5001，后续 pushNowPlaying 正常放行
            assertEquals(listOf(0x5001), h.carSends(0x5000))
            h.nowPlaying = song("订阅后新歌")
            assertTrue(h.link.pushNowPlaying())
            h.tick()
            h.settle()
            assertTrue(String(h.lastRawFrameOf(0x5001)!!, Charsets.UTF_8).contains("订阅后新歌"))
        } finally {
            io.github.shaonialife.carptocar.iap2.Iap2RouteGuidance.reset()
            AccessoryDeclaration.reset()
        }
    }

    @Test
    fun supersedingPendingArtworkCancelsPreviousSetupBeforeSendingNext() {
        Iap2ArtworkSupport.reset()
        try {
            val h = Harness()
            h.readyForSubscriptions()
            h.artwork = mapOf(1 to byteArrayOf(1, 2, 3), 2 to byteArrayOf(4, 5, 6))
            h.nowPlaying = song("第一首").copy(artworkId = 1)
            h.carSends(0x5000)
            h.settle()

            val beforeSecond = h.allControl.size
            h.nowPlaying = song("第二首").copy(artworkId = 2)
            assertTrue(h.link.pushNowPlaying())
            h.tick()
            h.settle()

            val ftPayloads =
                h.allControl.drop(beforeSecond).flatMap { chunk ->
                    val out = ArrayList<ByteArray>()
                    var off = 0
                    while (off + 9 <= chunk.size) {
                        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
                            off++
                            continue
                        }
                        val len = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
                        if (len < 9 || off + len > chunk.size) break
                        val sid = chunk[off + 7].toInt() and 0xFF
                        if (sid == Iap2LinkEngine.SESSION_FILE_TRANSFER && len > 9) {
                            out.add(chunk.copyOfRange(off + 9, off + len - 1))
                        }
                        off += len
                    }
                    out
                }
            assertTrue(
                "新封面取代未完成旧封面时必须先发 CANCEL(id=1) 再发 SETUP(id=2)：actual=${ftPayloads.map { it.take(2) }}",
                ftPayloads.size >= 2 &&
                    ftPayloads[0].size == 2 &&
                    ftPayloads[0][0] == 1.toByte() &&
                    ftPayloads[0][1] == io.github.shaonialife.carptocar.iap2.Iap2ArtworkTransfer.CMD_CANCEL.toByte() &&
                    ftPayloads[1][0] == 2.toByte() &&
                    ftPayloads[1][1] == io.github.shaonialife.carptocar.iap2.Iap2ArtworkTransfer.CMD_SETUP.toByte(),
            )
        } finally {
            Iap2ArtworkSupport.reset()
        }
    }

    @Test
    fun appleOidCertifiedChallengeLengthSurvivesLinkInterruptAndHealsStaleHardReject() {
        val identity = "OidCar OidModel tunnel"
        MfiLengthMemory.forget(null, identity)
        // 预置旧版遗留的误判硬拒绝记忆（模拟手机 SharedPreferences 已被前版污染）
        MfiLengthMemory.recordHardRejected(null, identity, 32)
        try {
            // 含 Apple 扩展 OID 1.2.840.113635.100.6.36（明确认证 32B）
            val oid32Cert =
                byteArrayOf(
                    0x30, 0x0B,
                    0x06, 0x09,
                    0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x63, 0x64, 0x06, 0x24,
                )
            val first = Harness()
            first.link.armMib3()
            first.car.start(0)
            first.exchangeUntilNormal()
            first.carSends(0x1D01, Iap2Csm.string(0, "OidCar") + Iap2Csm.string(1, "OidModel"))
            first.carSends(0xAA01, Iap2Csm.param(0, oid32Cert))
            assertEquals("Apple OID 明确认证的 32B 必须压过旧 hardRejected 排在首位", listOf(32), first.challengeLengths())

            // 挑战期间外部断链关闭：不得把 OID 认证长度记进 hardRejected
            first.link.close()
            MfiLengthMemory.forget(null, identity)
            val second = Harness()
            second.link.armMib3()
            second.car.start(0)
            second.exchangeUntilNormal()
            second.carSends(0x1D01, Iap2Csm.string(0, "OidCar") + Iap2Csm.string(1, "OidModel"))
            second.carSends(0xAA01, Iap2Csm.param(0, oid32Cert))
            second.link.close()
            val afterInterrupt = MfiLengthMemory.load(null, identity)
            assertFalse("Apple OID 认证长度在外部断链时不得记入 hardRejected", 32 in afterInterrupt.hardRejected)
        } finally {
            MfiLengthMemory.forget(null, identity)
        }
    }
}
