package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CarLifeMediaPlaybackTest {
    @Test
    fun `setup init stop init without focus and pcm does not authorize playback`() {
        val playback = CarLifeMediaPlayback()

        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        assertFalse(playback.playing)

        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        assertFalse(playback.playing)

        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `car pause blocks residual pcm and car resume restores playback without needing 0x30004`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onCarPause()
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        // 暂停期间引擎残留 PCM 不得重新点燃播放。
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        // 车机解除静音：即便第三方播放器只继续推裸 PCM 而不发 0x30004，也能立即恢复。
        playback.onCarResume()
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `explicit media session pause vetoes car resume`() {
        val playback = CarLifeMediaPlayback()
        playback.onSessionPlayback(false)
        playback.onCarResume()
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)
    }

    @Test
    fun `carlife hardkey and audio packet constants match verified md wire protocol`() {
        assertEquals(0x0F, CarLifeProtocol.HardKey.SEEK_SUB)
        assertEquals(0x10, CarLifeProtocol.HardKey.SEEK_ADD)
        assertEquals(0x1F, CarLifeProtocol.HardKey.MEDIA_START)
        assertEquals(0x20, CarLifeProtocol.HardKey.MEDIA_STOP)
        assertEquals(98318, CarLifeProtocol.HU_PAUSE_MEDIA)
        assertEquals(0x30002, CarLifeProtocol.MUSIC_PKG_STOP)
        assertEquals(0x30003, CarLifeProtocol.MUSIC_PKG_PAUSE)
        assertEquals(0x30004, CarLifeProtocol.MUSIC_PKG_RESUME)
        assertEquals(0x40002, CarLifeProtocol.TTS_PKG_STOP)
        assertEquals(0x40003, CarLifeProtocol.TTS_PKG_NORMAL)
    }

    @Test
    fun `focus released before trailing 0x30003 pause does not deadlock next focus gained play`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        // 控制通道（7240）的音乐焦点=0 早于媒体通道（9240）的 0x30003 PAUSE 到达：
        // 尾随 PAUSE 不得抹掉下一轮焦点的起播资格。
        playback.onActivity(CarLifeMediaActivity.FOCUS_RELEASED)
        playback.onActivity(CarLifeMediaActivity.PAUSE)
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `pause and focus release followed by focus gain and pcm resumes even without new 0x30001 init`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onActivity(CarLifeMediaActivity.PAUSE)
        playback.onActivity(CarLifeMediaActivity.FOCUS_RELEASED)
        assertFalse(playback.playing)

        // 同一 9240 连接在采样率未变时可能直接恢复推裸 PCM，不重发 0x30001 INIT。
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `car pause does not reclaim native radio on format rebuild while focus never released`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onCarPause()
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertFalse(playback.playing)

        // 只有焦点真正释放后再重新获得，才开启新一轮有效播放。
        playback.onActivity(CarLifeMediaActivity.FOCUS_RELEASED)
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
    }

    @Test
    fun `unmute after 42s mute does not trigger false 9240 silence reconnect`() {
        val clock = AtomicLong(1_000L)
        val expected = AtomicBoolean(false)
        val mediaConnections = AtomicInteger(0)
        val accepted = CopyOnWriteArrayList<Socket>()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { cmdServer ->
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { mediaServer ->
                val cmdThread =
                    Thread {
                        runCatching {
                            val s = cmdServer.accept()
                            accepted += s
                            val input = DataInputStream(s.getInputStream())
                            val head = ByteArray(8)
                            input.readFully(head)
                            val len = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                            if (len > 0) input.readFully(ByteArray(len))
                            // 回 65538 协议匹配成功（field1 matchStatus = 1）
                            val matchFrame = CarLifeProtocol.packCommand(CarLifeProtocol.MD_VERSION_MATCH, byteArrayOf(0x08, 0x01))
                            s.getOutputStream().write(matchFrame)
                            s.getOutputStream().flush()
                            while (!s.isClosed && s.getInputStream().read() >= 0) {
                                // drain commands
                            }
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val mediaThread =
                    Thread {
                        while (!mediaServer.isClosed) {
                            val s = runCatching { mediaServer.accept() }.getOrNull() ?: break
                            accepted += s
                            mediaConnections.incrementAndGet()
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }

                val client =
                    CarLifeHuClient(
                        log = {},
                        onVideoFrame = { _, _, _ -> },
                        audioExpected = { expected.get() },
                        orderedInit = false,
                        settleReinit = false,
                        monotonicMillis = { clock.get() },
                        connectSocket = { port ->
                            when (port) {
                                CarLifeProtocol.PORT_CMD -> Socket("127.0.0.1", cmdServer.localPort)
                                CarLifeProtocol.PORT_MEDIA -> Socket("127.0.0.1", mediaServer.localPort)
                                else -> throw java.io.IOException("unused port $port")
                            }
                        },
                    )
                try {
                    assertTrue(client.start())
                    assertEquals(1, mediaConnections.get())

                    // 车机静音 42 秒（expected=false）：沉默看门狗轮询刷新基线，随后解除静音不应误杀 9240。
                    clock.addAndGet(42_000L)
                    client.notifyVideoFrameForTest()
                    client.superviseMediaOnceForTest()
                    expected.set(true)
                    client.noteMediaExpectationChanged()
                    clock.addAndGet(900L)
                    client.notifyVideoFrameForTest()
                    client.superviseMediaOnceForTest()
                    assertEquals(1, mediaConnections.get())

                    // 若恢复播放后连续 25 秒仍无 PCM，则看门狗正常重连 9240 自愈。
                    clock.addAndGet(25_000L)
                    client.notifyVideoFrameForTest()
                    client.superviseMediaOnceForTest()
                    assertEquals(2, mediaConnections.get())
                } finally {
                    client.stop()
                    accepted.forEach { runCatching { it.close() } }
                    cmdThread.join(1_000)
                    mediaThread.join(1_000)
                }
            }
        }
    }

    @Test
    fun `touch channel automatically reconnects when 9340 socket breaks or opens late`() {
        val clock = AtomicLong(5_000L)
        val touchAllowed = AtomicBoolean(false)
        val receivedFrames = AtomicInteger(0)
        val secondFrameLatch = CountDownLatch(2)
        val accepted = CopyOnWriteArrayList<Socket>()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { cmdServer ->
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { touchServer ->
                Thread {
                    runCatching {
                        val s = cmdServer.accept()
                        accepted += s
                        val input = DataInputStream(s.getInputStream())
                        val head = ByteArray(8)
                        input.readFully(head)
                        val len = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                        if (len > 0) input.readFully(ByteArray(len))
                        val matchFrame = CarLifeProtocol.packCommand(CarLifeProtocol.MD_VERSION_MATCH, byteArrayOf(0x08, 0x01))
                        s.getOutputStream().write(matchFrame)
                        s.getOutputStream().flush()
                        while (!s.isClosed && s.getInputStream().read() >= 0) {
                            // drain
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                Thread {
                    while (!touchServer.isClosed) {
                        val s = runCatching { touchServer.accept() }.getOrNull() ?: break
                        accepted += s
                        val input = DataInputStream(s.getInputStream())
                        runCatching {
                            val head = ByteArray(8)
                            input.readFully(head)
                            val len = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                            if (len > 0) input.readFully(ByteArray(len))
                            if (receivedFrames.incrementAndGet() == 1) {
                                // 模拟触控通道中途断链（对端关闭后本地再写将触发重连）
                                s.close()
                            }
                            secondFrameLatch.countDown()
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                val client =
                    CarLifeHuClient(
                        log = {},
                        onVideoFrame = { _, _, _ -> },
                        orderedInit = false,
                        settleReinit = false,
                        monotonicMillis = { clock.get() },
                        connectSocket = { port ->
                            when (port) {
                                CarLifeProtocol.PORT_CMD -> Socket("127.0.0.1", cmdServer.localPort)
                                CarLifeProtocol.PORT_TOUCH -> {
                                    if (!touchAllowed.get()) throw java.io.IOException("9340 not open yet")
                                    Socket("127.0.0.1", touchServer.localPort)
                                }
                                else -> throw java.io.IOException("unused port $port")
                            }
                        },
                    )
                try {
                    // 启动时 9340 未就绪，后续开放后首次触控应自动建连成功
                    assertTrue(client.start())
                    touchAllowed.set(true)
                    clock.addAndGet(2_000L)
                    client.touchDown(500, 300)
                    Thread.sleep(150)
                    assertEquals(1, receivedFrames.get())

                    // 对端已关掉第一条 9340 连接；推进时钟并连续下发触控，应自动重连到新连接并送达
                    clock.addAndGet(2_000L)
                    client.touchMove(520, 310)
                    Thread.sleep(100)
                    clock.addAndGet(2_000L)
                    client.touchUp(520, 310)
                    assertTrue(secondFrameLatch.await(2, TimeUnit.SECONDS))
                } finally {
                    client.stop()
                    accepted.forEach { runCatching { it.close() } }
                }
            }
        }
    }
}
