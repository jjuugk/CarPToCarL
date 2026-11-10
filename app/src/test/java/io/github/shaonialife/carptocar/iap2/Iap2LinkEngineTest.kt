package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两个链路引擎通过直接交换输出字节完成握手与数据往返（无线程、无 I/O）。 */
class Iap2LinkEngineTest {
    @Test
    fun handshakeReachesNormalOnBothSides() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()

        phone.start(0)
        accessory.start(0)

        exchange(phone, accessory)
        exchange(phone, accessory)
        exchange(phone, accessory)

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, accessory.state)
        assertEquals(4, accessory.peerSynchronization?.maxOutgoing)
        assertTrue(phone.peerSynchronization?.sessions?.any { it.id == Iap2LinkEngine.SESSION_CONTROL } == true)
    }

    /**
     * 事件通道形态：车机只反复发 marker、一条同步包都不发（Honda HFT 2026-10-20 实测
     * 60 秒 60 个 marker），说明它卡在探测态、在等**我们**的会话表。
     * 已处于 NEGOTIATING 时 [Iap2LinkEngine.initiate] 必须仍能发出「marker + FF5A SYN」，
     * 双方随即收敛到 NORMAL——这是切歌按键通路的入口。
     */
    @Test
    fun initiateFromNegotiatingSendsMarkerAndSynThenReachesNormal() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        // 双方都是静默待命（只发 marker 不发起协商）
        phone.enterNegotiating()
        accessory.enterNegotiating()

        phone.initiate(0)
        val out = phone.takeOutput()
        assertArrayEquals("首帧必须是 marker", Iap2LinkEngine.MARKER, out.copyOfRange(0, 6))
        val frames = parseFrames(out)
        assertEquals("marker 之后必须紧跟一条同步包", 1, frames.size)
        assertTrue("同步包 control 必须带 SYN 位（0x80）", frames[0].control and 0x80 != 0)

        // 车机侧收到「marker + 本表 SYN」→ 回 SYN|ACK（这就是日志里应当出现的推进）
        accessory.feed(out, tick())
        assertEquals("车机侧收到我们的会话表后必须进入 NORMAL", Iap2LinkEngine.State.NORMAL, accessory.state)
        phone.feed(accessory.takeOutput(), tick())
        assertEquals("实际 phone=${phone.state}", Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(
            "双方都必须采纳对方的会话表",
            phone.peerSynchronization != null && accessory.peerSynchronization != null,
        )
    }

    /**
     * 轮次用尽后必须能停手：控制通道上无限 500ms 打点会把车机 CarPlay 栈刷懵，
     * 所以上层按轮次重开、轮次用尽后调 [Iap2LinkEngine.stopNegotiationRetries]。
     * 停手后不得再产出任何出站字节，状态保持 NEGOTIATING（等车机自己发 CSM）。
     */
    @Test
    fun stopNegotiationRetriesSilencesResends() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()
        phone.initiate(0)
        phone.takeOutput()

        phone.advanceTime(1_000L)
        assertTrue("未停手时必须仍在重发 marker/同步包", phone.takeOutput().isNotEmpty())

        phone.stopNegotiationRetries()
        phone.advanceTime(60_000L)
        assertTrue("停手后不得再有任何出站字节", phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    @Test
    fun controlPayloadRoundTripBothDirections() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val phoneToAccessory = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(phoneToAccessory, 1))
        exchange(phone, accessory)
        val receivedAtAccessory = pollControls(accessory)
        assertEquals(1, receivedAtAccessory.size)
        assertArrayEquals(phoneToAccessory, receivedAtAccessory[0])

        val accessoryToPhone = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "CarPlay HU"))
        assertTrue(accessory.sendControl(accessoryToPhone, 2))
        exchange(phone, accessory)
        val receivedAtPhone = pollControls(phone)
        assertEquals(1, receivedAtPhone.size)
        assertArrayEquals(accessoryToPhone, receivedAtPhone[0])
    }

    @Test
    fun duplicateFrameIsReAcknowledgedNotRedelivered() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val payload = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(payload, 1))
        // 截获 phone 输出的数据帧，重复喂给 accessory 两次
        val dataFrame = phone.takeOutput()
        accessory.feed(dataFrame, 2)
        accessory.feed(dataFrame, 3)

        assertEquals(1, pollControls(accessory).size)
    }

    @Test
    fun windowBacklogFlushesAfterAcknowledgement() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val accessory = Iap2LinkEngine(maxOutgoing = 8)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val frames = (0 until 5).map { Iap2Csm.encode(0x5000 + it) }
        frames.forEach { assertTrue(phone.sendControl(it, 10)) }
        // 窗口 2：只有前 2 帧立即发出，其余积压；ACK 后由 handleAcknowledgement 冲刷
        var now = 11L
        while (pollControls(accessory).size < 5) {
            exchange(phone, accessory)
            now += 1
            phone.advanceTime(now)
            accessory.advanceTime(now)
            assertTrue(!phone.isDead && !accessory.isDead)
        }
    }

    // ---- 协商细节回归 ----

    /**
     * SYN-ACK 必须使用**新的**发送序号（对端按 `SYN.seq + 1` 校验）。
     *
     * 2026-10-19 BMW 00000 实车日志：我方 SYN 与 SYN-ACK 同为 seq=99，车机判为重复帧
     * 丢弃，14 轮协商全部超时、133 条 SYN 未换回一条 ACK。catplay 用 `take_own_seq()`
     * 消耗新序号，这里锁死该行为。
     */
    @Test
    fun synAckUsesFreshSequenceNumber() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        // 初始序号每条链路随机（手册 4.3 / catplay take_own_seq），只能断言相对关系
        val ownSyn = parseFrames(phone.takeOutput()).single { it.control == 0x80 }

        phone.feed(synFrame(peerSequence = 161), 1)
        val frames = parseFrames(phone.takeOutput())
        val synAck = frames.single { it.control == 0xC0 }

        assertEquals("ack 必须回显对端 SYN 序号", 161, synAck.ack)
        assertEquals("SYN-ACK 序号必须是 SYN 的下一个（模 256）", (ownSyn.sequence + 1) and 0xFF, synAck.sequence)
    }

    /**
     * 协商期间**每一条** SYN 都要回 SYN|ACK。
     *
     * BMW 每轮重试都用新序号重开协商；旧实现只在首次采纳时回显，其余只回裸 ACK，
     * 车机永远走不完协商。catplay `on_recv_neg_host` 对每条 SYN 都发 SYN+ACK。
     */
    @Test
    fun everySynGetsASynAckReply() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        val ownSyn = parseFrames(phone.takeOutput()).single { it.control == 0x80 }

        // 车机连续三轮用不同序号重开协商（复刻日志 161 → 191 → 40）
        val sequences = listOf(161, 191, 40)
        val replies = ArrayList<Pair<Int, Int>>() // (synAckSeq, ack)
        sequences.forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            parseFrames(phone.takeOutput())
                .filter { it.control == 0xC0 }
                .forEach { replies += it.sequence to it.ack }
        }

        assertEquals("每条 SYN 都应有 SYN|ACK", sequences.size, replies.size)
        assertEquals(sequences, replies.map { it.second })
        // 序号逐次递增（初始序号随机，只能从 ownSyn 起算）
        assertEquals(
            listOf(1, 2, 3).map { (ownSyn.sequence + it) and 0xFF },
            replies.map { it.first },
        )
    }

    /** 同一个 SYN 的字节级重发必须幂等：整对帧原样重放，不推进序号、不产生新序号。 */
    @Test
    fun retransmittedSynKeepsTheSameEcho() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        val ownSyn = parseFrames(phone.takeOutput()).single { it.control == 0x80 }

        phone.feed(synFrame(peerSequence = 77), 1)
        val first = phone.takeOutput()
        phone.feed(synFrame(peerSequence = 77), 2)
        val again = phone.takeOutput()

        assertArrayEquals("重传同一 SYN 应原样重放", first, again)
        assertEquals((ownSyn.sequence + 1) and 0xFF, parseFrames(first).single { it.control == 0xC0 }.sequence)
    }

    /** 裸 ACK 不能与已发出的 SYN|ACK 撞序号（BMW 会把同序号帧当重复丢弃）。 */
    @Test
    fun ackSequenceNeverDuplicatesTheSynAck() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        val emitted = ArrayList<Int>()
        listOf(161, 191, 40).forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            emitted += parseFrames(phone.takeOutput()).map { it.sequence }
        }
        assertEquals("序号必须严格递增且无重复", emitted.size, emitted.distinct().size)
        // 初始序号随机 → 不能用 sorted（跨 255→0 回绕时会失败），按模 256 严格 +1 校验
        emitted.zipWithNext().forEach { (previous, next) ->
            assertEquals("序号必须每次 +1（模 256）", (previous + 1) and 0xFF, next)
        }
    }

    // ---- 握手拒绝（车机侧 sendLackOfBackwardCompatibility）----

    /**
     * 车机用**非 DETECT** 的握手应答拒绝我们时，必须几帧内定性，而不是干等上层 60 秒预算。
     *
     * 固件侧（MIB3 SW3874，`iap2::CIAP2LinkHandshake`）的路径是
     * `sendHandshake → evaluateHandshakeAnswer → 不兼容时 sendLackOfBackwardCompatibility`，
     * 也就是说「我不支持你」正是以 `FF55` 族里非 marker 的报文出现的。
     * 旧实现把整族当杂字节逐字节丢弃，日志里只剩「协商无进展」。
     *
     * 第一次只记日志（个别车机会先发私有握手包再进入参数协商，一刀切会杀掉可用链路），
     * 第二次才判拒绝——判据是「反复出现 + 一条 LSP 都没采纳」。
     */
    @Test
    fun repeatedNonDetectHandshakeAnswerKillsNegotiation() {
        val phone = Iap2LinkEngine()
        val seen = ArrayList<Iap2LinkEngine.PeerHandshake>()
        phone.onPeerHandshake = { seen += it }
        phone.start(0)
        phone.takeOutput()

        val answer = handshakeAnswer()
        phone.feed(answer, 1)
        assertEquals("第一次只记日志、不拆链", Iap2LinkEngine.State.NEGOTIATING, phone.state)
        assertEquals(1, phone.peerHandshakeRejections)
        assertEquals("诊断回调必须把原始字节带出来", 1, seen.size)
        assertTrue("raw 前缀应是 FF 55 02 00，实际=${seen[0].hex()}", seen[0].hex().startsWith("FF 55 02 00"))
        assertTrue("非 DETECT 必须标注 isDetect=false", !seen[0].isDetect)

        phone.feed(answer, 2)
        assertEquals("第二次必须判拒绝", Iap2LinkEngine.State.DEAD, phone.state)
        val reason = deadReason(phone)
        assertTrue("原因必须点名 sendLackOfBackwardCompatibility，实际=$reason", reason.contains("rejected link"))
    }

    /**
     * 标准 marker（`FF 55 02 00 EE 10`）**不是**拒绝：本田/奥迪等车机每秒重发它，
     * 旧实现靠帧头扫描丢弃，新实现要显式认得出来，绝不能因此拆链、也不该刷日志。
     */
    @Test
    fun standardDetectMarkerIsNotARejection() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()
        var callbacks = 0
        phone.onPeerHandshake = { callbacks++ }

        repeat(5) { index -> phone.feed(Iap2LinkEngine.MARKER, index.toLong()) }

        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
        assertEquals(0, phone.peerHandshakeRejections)
        assertEquals("marker 每秒重发，不该走诊断回调", 0, callbacks)
    }

    /** 握手应答被 TCP/RFCOMM 拆成单字节送达时也要能认出来（真实链路上很常见）。 */
    @Test
    fun handshakeAnswerSplitAcrossFeedsIsStillRecognised() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        handshakeAnswer().forEachIndexed { index, byte -> phone.feed(byteArrayOf(byte), index.toLong()) }

        assertEquals(1, phone.peerHandshakeRejections)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    /**
     * 对端 LSP 的 LinkVersion 与本端不同 → 立即终止（确定性证据，不等第二次）。
     *
     * LSP 首字节就是 7 个链路参数里的 `LinkVersion`；`SynchronizationPayload.decode`
     * 只认 1，别的版本会返回 null——旧实现静默丢弃后继续 500ms 重发本表，直到预算耗尽。
     */
    @Test
    fun unsupportedLspLinkVersionKillsNegotiationImmediately() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        phone.feed(synFrameWithLinkVersion(2), 1)

        assertEquals(Iap2LinkEngine.State.DEAD, phone.state)
        // 注意：deadReason 会取走 Dead 事件，只能取一次
        val reason = deadReason(phone)
        assertTrue("原因必须点名 LinkVersion，实际=$reason", reason.contains("LinkVersion=2"))
    }

    /** 已经采纳过 LSP（参数协商已开始/已 NORMAL）后，FF55 的怪包只记日志，不许拆链。 */
    @Test
    fun handshakeAnswerAfterLspAdoptionDoesNotKillLink() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        repeat(3) { index -> phone.feed(handshakeAnswer(), 100L + index) }

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(3, phone.peerHandshakeRejections)
    }

    /** 关掉快速失败后行为与改动前一致：只记日志，链路继续等。 */
    @Test
    fun failFastOnHandshakeRejectionCanBeDisabled() {
        val phone = Iap2LinkEngine()
        phone.failFastOnHandshakeRejection = false
        phone.start(0)
        phone.takeOutput()

        repeat(3) { index -> phone.feed(handshakeAnswer(), index.toLong()) }

        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
        assertEquals(3, phone.peerHandshakeRejections)
    }

    // ---- 工具 ----

    private class ParsedFrame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val frameLength: Int,
    )

    /** 解析一段输出里的所有 FF5A 帧（跳过前置 marker）。 */
    private fun parseFrames(bytes: ByteArray): List<ParsedFrame> {
        val frames = ArrayList<ParsedFrame>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                offset++
                continue
            }
            val frame = parseFrame(bytes, offset)
            frames += frame
            offset += frame.frameLength
        }
        return frames
    }

    /** 解析一帧 FF5A（长度含 9 字节头 + 载荷 + 载荷校验和）。 */
    private fun parseFrame(
        bytes: ByteArray,
        offset: Int,
    ): ParsedFrame {
        require(bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0x5A.toByte()) {
            "offset=$offset 不是 FF5A 帧头"
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        return ParsedFrame(
            control = bytes[offset + 4].toInt() and 0xFF,
            sequence = bytes[offset + 5].toInt() and 0xFF,
            ack = bytes[offset + 6].toInt() and 0xFF,
            frameLength = length,
        )
    }

    /**
     * 构造车机侧的 SYN 帧（含 marker）。
     *
     * 帧头是 **9 字节**：`FF5A | u16 长度 | u8 控制 | u8 seq | u8 ack | u8 会话 | u8 头校验和`，
     * 所以 seq 在帧内偏移 5、头校验和在偏移 8（不是 7——那一位是会话 ID）。
     */
    private fun synFrame(peerSequence: Int): ByteArray {
        val engine = Iap2LinkEngine()
        engine.start(0)
        val raw = engine.takeOutput()
        val synOffset = 6 // 前置 marker 6 字节
        raw[synOffset + 5] = peerSequence.toByte()
        // 头校验和 = 前 8 字节之和的补码
        var sum = 0
        for (index in synOffset until synOffset + 8) sum += raw[index].toInt() and 0xFF
        raw[synOffset + 8] = (-sum and 0xFF).toByte()
        return raw
    }

    /**
     * 构造一条 LSP 里 `LinkVersion` 被改过的车机 SYN 帧（含前置 marker）。
     *
     * 载荷校验和必须跟着重算，否则引擎会先判校验失败、走不到版本判定。
     */
    private fun synFrameWithLinkVersion(version: Int): ByteArray {
        val raw = synFrame(peerSequence = 33)
        val synOffset = 6
        val payloadStart = synOffset + Iap2LinkEngine.HEADER_BYTES
        raw[payloadStart] = version.toByte()
        val length = ((raw[synOffset + 2].toInt() and 0xFF) shl 8) or (raw[synOffset + 3].toInt() and 0xFF)
        var sum = 0
        for (index in payloadStart until synOffset + length - 1) sum += raw[index].toInt() and 0xFF
        raw[synOffset + length - 1] = (-sum and 0xFF).toByte()
        return raw
    }

    /** 车机侧「非 DETECT 握手应答」的最小样本（`FF 55 | u16LE 载荷长度 | 载荷`）。 */
    private fun handshakeAnswer(payload: ByteArray = byteArrayOf(0x55, 0xAA.toByte())): ByteArray =
        byteArrayOf(0xFF.toByte(), 0x55, payload.size.toByte(), 0x00) + payload

    /** 取链路终止原因（没有 Dead 事件就返回空串，断言会直接失败在 contains 上）。 */
    private fun deadReason(engine: Iap2LinkEngine): String {
        while (true) {
            when (val event = engine.pollEvent() ?: return "") {
                is Iap2LinkEngine.Event.Dead -> return event.reason ?: ""
                is Iap2LinkEngine.Event.Control -> Unit
            }
        }
    }

    // ---- EAK / RTO 回归 ----
    /**
     * 收到 EAK 必须**立刻**重传，不等本地 RTO。
     *
     * 2026-10-28 大众实测：车机在 12.595 发 EAK（ctl=0x60，要求从 106 重传），
     * 我们靠本地 4 秒 RTO 到 16.515 才补——那一轮 bring-up 总共只有 17 秒。
     */
    @Test
    fun extendedAckTriggersImmediateRetransmit() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        assertTrue(phone.sendControl(Iap2Csm.encode(0x1D02), 10))
        val sent = parseFrames(phone.takeOutput()).single { it.control == 0x40 }
        // 故意不把数据帧喂给车机（模拟丢包），只回一条 EAK 说「我从这条开始没收到」
        phone.feed(extendedAckFrame(ack = sent.sequence), 50)

        val retransmitted = parseFrames(phone.takeOutput()).filter { it.control == 0x40 }
        assertEquals("EAK 后必须立即重发一帧（不等 4s RTO）", 1, retransmitted.size)
        assertEquals("重传必须沿用原序号", sent.sequence, retransmitted[0].sequence)
    }

    /**
     * 重传节拍跟随对端在 sync 里声明的 RTO，本地默认值只作兜底。
     * 大众声明 2000ms，而本地默认 4000ms——差的那 2 秒在十几秒的窗口里很贵。
     */
    @Test
    fun retransmissionUsesPeerDeclaredTimeout() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine(retransmissionTimeoutMillis = 2_000)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        assertTrue(phone.sendControl(Iap2Csm.encode(0x1D02), 10))
        phone.takeOutput()
        phone.advanceTime(2_100)
        val frames = parseFrames(phone.takeOutput())
        assertEquals(
            "对端声明 RTO=2s，2.1s 到点就该重传（本地默认 4s 时这里不会有输出）",
            1,
            frames.count { it.control == 0x40 },
        )
    }

    /** 构造车机侧的 EAK 帧（control=0x60 = ACK|EAK，无载荷，长度 9）。 */
    private fun extendedAckFrame(
        ack: Int,
        sequence: Int = 3,
    ): ByteArray {
        val frame = ByteArray(9)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = 0
        frame[3] = 9
        frame[4] = 0x60
        frame[5] = sequence.toByte()
        frame[6] = ack.toByte()
        frame[7] = 10
        var sum = 0
        for (index in 0 until 8) sum += frame[index].toInt() and 0xFF
        frame[8] = (-sum and 0xFF).toByte()
        return frame
    }

    private fun exchange(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        b.feed(a.takeOutput(), tick())
        a.feed(b.takeOutput(), tick())
    }

    private fun exchangeUntilNormal(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        var guard = 0
        while ((a.state != Iap2LinkEngine.State.NORMAL || b.state != Iap2LinkEngine.State.NORMAL) &&
            guard++ < 10
        ) {
            exchange(a, b)
        }
    }

    private fun pollControls(engine: Iap2LinkEngine): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            when (val event = engine.pollEvent() ?: return out) {
                is Iap2LinkEngine.Event.Control -> out += event.bytes
                is Iap2LinkEngine.Event.Dead -> error("engine died: ${event.reason}")
            }
        }
    }

    private var clock = 0L

    private fun tick(): Long = ++clock
}
