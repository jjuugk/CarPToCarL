package io.github.shaonialife.carptocar.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 两个门面的契约：能力声明对得上、事件确实委托给注入函数。
 * 阶段 4 把真实函数接进来之前，这些断言保证门面本身不会「吞事件」。
 */
class ProtocolModulesTest {
    @Before
    fun setUp() = ProtocolRegistry.reset()

    @Test
    fun carLifeDeclaresSourcesAndControlConsumers() {
        val module = CarLifeModule()
        assertEquals(
            setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE, Capability.MEDIA_LYRICS, Capability.PLAYBACK_INTENT),
            module.provides(),
        )
        assertEquals(
            setOf(Capability.MEDIA_KEY, Capability.VOICE_KEY, Capability.GPS, Capability.TOUCH),
            module.consumes(),
        )
    }

    @Test
    fun carPlayDeclaresControlProvidersAndMediaConsumers() {
        val module = CarPlayModule()
        assertEquals(
            setOf(Capability.MEDIA_KEY, Capability.VOICE_KEY, Capability.GPS, Capability.TOUCH, Capability.MEDIA_INFO),
            module.provides(),
        )
        assertEquals(
            setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE, Capability.MEDIA_LYRICS, Capability.PLAYBACK_INTENT),
            module.consumes(),
        )
    }

    @Test
    fun carLifeDelegatesMediaKeyAndVoiceKey() {
        val seen = mutableListOf<String>()
        val module =
            CarLifeModule(
                onMediaKey = { seen += "key:$it"; true },
                onVoiceKey = { code, long -> seen += "voice:$code:$long"; true },
            )

        assertTrue(module.onEvent(ProtocolEvent.MediaKey(87, ProtocolId.CARPLAY)))
        assertTrue(module.onEvent(ProtocolEvent.VoiceKey(1234, longPress = true, from = ProtocolId.CARPLAY)))

        assertEquals(listOf("key:87", "voice:1234:true"), seen)
    }

    @Test
    fun carLifeIgnoresEventsItOnlyProduces() {
        val module = CarLifeModule()
        assertFalse(module.onEvent(ProtocolEvent.MediaInfo("标题", "歌手", from = ProtocolId.SYSTEM)))
        assertFalse(module.onEvent(ProtocolEvent.Lyrics(listOf("词"), from = ProtocolId.CARLIFE)))
    }

    @Test
    fun carPlayDelegatesLyricsToHook() {
        var received: List<String> = emptyList()
        val module = CarPlayModule(onLyrics = { lines, _ -> received = lines; true })

        assertTrue(module.onEvent(ProtocolEvent.Lyrics(listOf("第一行", "第二行"), from = ProtocolId.CARLIFE)))
        assertEquals(listOf("第一行", "第二行"), received)
    }

    @Test
    fun registryRoutesCarKeyToCarLifeThroughBothFacades() {
        var handledKey = -1
        val carLife = CarLifeModule(onMediaKey = { handledKey = it; true })
        val carPlay = CarPlayModule()
        ProtocolRegistry.register(carPlay)
        ProtocolRegistry.register(carLife)

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.MediaKey(keyCode = 87, from = ProtocolId.CARPLAY),
                Delivery.FIRST_MATCH,
            )

        assertTrue(result.handled)
        assertEquals(ProtocolId.CARLIFE, result.consumedBy.single().id)
        assertEquals(87, handledKey)
    }

    @Test
    fun registryRoutesLyricsFromCarLifeToCarPlay() {
        var received: List<String> = emptyList()
        val carLife = CarLifeModule()
        val carPlay = CarPlayModule(onLyrics = { lines, _ -> received = lines; true })
        ProtocolRegistry.register(carLife)
        ProtocolRegistry.register(carPlay)

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.Lyrics(listOf("歌词"), from = ProtocolId.CARLIFE),
                Delivery.BIND,
            )

        assertTrue(result.handled)
        assertEquals(ProtocolId.CARPLAY, result.consumedBy.single().id)
        assertEquals(listOf("歌词"), received)
    }

    @Test
    fun startAndStopDelegateToInjectedFunctions() {
        var starts = 0
        var stops = 0
        val module =
            CarLifeModule(
                starter = { starts++; true },
                stopper = { stops++ },
            )
        ProtocolRegistry.register(module)

        assertTrue(ProtocolRegistry.startAll().contains(ProtocolId.CARLIFE))
        ProtocolRegistry.stopAll()
        ProtocolRegistry.stopAll()

        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    // ---- 阶段 4 接线契约：SessionController.dispatchMediaKey 靠 hasConsumer 二选一 ----

    /**
     * `hasConsumer` 是「走聚合层还是走本地原路径」的开关，必须只认**消费方**。
     * CarPlay 声明的是 provides(MEDIA_KEY)，若被误当成消费者，装配一半就会把
     * 媒体键全部吞掉（CarPlayModule.onMediaKey 恒返回 false），车机按键彻底失灵。
     */
    @Test
    fun hasConsumerCountsOnlyDeclaredConsumers() {
        assertFalse(ProtocolRegistry.hasConsumer(Capability.MEDIA_KEY))

        ProtocolRegistry.register(CarPlayModule())
        assertFalse(ProtocolRegistry.hasConsumer(Capability.MEDIA_KEY))
        assertTrue(ProtocolRegistry.hasConsumer(Capability.VIDEO_SOURCE))

        ProtocolRegistry.register(CarLifeModule())
        assertTrue(ProtocolRegistry.hasConsumer(Capability.MEDIA_KEY))
        assertTrue(ProtocolRegistry.hasConsumer(Capability.VOICE_KEY))
        assertTrue(ProtocolRegistry.hasConsumer(Capability.TOUCH))
    }

    /** 已装配分支：事件来源是 SYSTEM（车机/系统媒体键，不是某个协议模块）也必须能到达 CarLife。 */
    @Test
    fun installedFleetConsumesSystemMediaKeyExactlyOnce() {
        var calls = 0
        var lastKey = -1
        ProtocolRegistry.register(CarPlayModule())
        ProtocolRegistry.register(
            CarLifeModule(
                onMediaKey = { lastKey = it; calls++; true },
            ),
        )

        val handled =
            ProtocolRegistry
                .route(
                    ProtocolEvent.MediaKey(keyCode = 87, from = ProtocolId.SYSTEM),
                    Delivery.FIRST_MATCH,
                ).handled

        assertTrue(handled)
        // 恰好一次：装配后调用方只走 Registry，不再回落本地函数（两者互斥）
        assertEquals(1, calls)
        assertEquals(87, lastKey)
    }

    /** 没人接管时 `handled` 必须为 false，调用方才好继续走 AudioManager 系统焦点兜底。 */
    @Test
    fun unhandledMediaKeyLeavesRoomForSystemFocusFallback() {
        ProtocolRegistry.register(CarLifeModule(onMediaKey = { false }))

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.MediaKey(keyCode = 87, from = ProtocolId.SYSTEM),
                Delivery.FIRST_MATCH,
            )

        assertFalse(result.handled)
    }

    /** reset 必须连启动记录一起清，否则下一个用例的 stopAll 会误调上一个用例的 stopper。 */
    @Test
    fun resetAlsoClearsStartedState() {
        var stops = 0
        ProtocolRegistry.register(CarLifeModule(stopper = { stops++ }))
        ProtocolRegistry.startAll()

        ProtocolRegistry.reset()
        ProtocolRegistry.stopAll()

        assertEquals(0, stops)
    }

    // ---- 阶段 5：语音键与触摸收口（输入状态机留在原地，只投「判定完的动作」）----

    /** 语音键：VoiceKeyRouter 判定后的硬键码经 Registry 投给 CarLife，参数原样到达。 */
    @Test
    fun installedFleetConsumesVoiceHardKey() {
        val seen = mutableListOf<Int>()
        ProtocolRegistry.register(CarPlayModule())
        ProtocolRegistry.register(
            CarLifeModule(onVoiceKey = { code, _ -> seen += code; true }),
        )

        val handled =
            ProtocolRegistry
                .route(
                    ProtocolEvent.VoiceKey(keyCode = 0x21, from = ProtocolId.CARPLAY),
                    Delivery.FIRST_MATCH,
                ).handled

        assertTrue(handled)
        assertEquals(listOf(0x21), seen)
    }

    /** 引擎没接管时为 false，调用方据此写「未送达」日志——既不能静默，也不该抛。 */
    @Test
    fun unhandledVoiceKeyReportsFalseSoCallerCanLog() {
        ProtocolRegistry.register(CarLifeModule(onVoiceKey = { _, _ -> false }))

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.VoiceKey(keyCode = 0x22, from = ProtocolId.CARPLAY),
                Delivery.FIRST_MATCH,
            )

        assertFalse(result.handled)
    }

    /** 触摸：down / 坐标 / 槽位必须原样到达——错一位点击就漂到别的槽或位置。 */
    @Test
    fun touchCarriesDownCoordinatesAndSlotVerbatim() {
        var seen: List<Any> = emptyList()
        ProtocolRegistry.register(
            CarLifeModule(
                onTouch = { down, x, y, slot -> seen = listOf(down, x, y, slot); true },
            ),
        )

        val handled =
            ProtocolRegistry
                .route(
                    ProtocolEvent.Touch(down = true, x = 320, y = 180, pointerId = 1, from = ProtocolId.CARPLAY),
                    Delivery.FIRST_MATCH,
                ).handled

        assertTrue(handled)
        assertEquals(listOf(true, 320, 180, 1), seen)
    }

    /** CarLife 未激活（handleTouch 返回 false）时必须交还调用方继续走注入路径，不能被吞。 */
    @Test
    fun unhandledTouchLeavesRoomForVirtualDisplayInjection() {
        ProtocolRegistry.register(CarLifeModule(onTouch = { _, _, _, _ -> false }))

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.Touch(down = false, x = 0, y = 0, pointerId = 0, from = ProtocolId.CARPLAY),
                Delivery.FIRST_MATCH,
            )

        assertFalse(result.handled)
    }

    // ---- 阶段 6：播放意图收口（源端状态 → 汇侧资源调和）----

    /**
     * 播放意图从 CarLife（源）投给 CarPlay（汇）：三个布尔原样到达。
     * 与 AUDIO_SOURCE 的区别是「要不要保持一条流」，不是「有没有样本」。
     */
    @Test
    fun playbackIntentReachesCarPlayWithAllThreeFlags() {
        var seen: List<Boolean> = emptyList()
        ProtocolRegistry.register(CarLifeModule())
        ProtocolRegistry.register(
            CarPlayModule(onPlaybackIntent = { media, navigation, newMedia -> seen = listOf(media, navigation, newMedia); true }),
        )

        val handled =
            ProtocolRegistry
                .route(
                    ProtocolEvent.PlaybackIntent(
                        media = true,
                        navigation = false,
                        newMediaIntent = true,
                        from = ProtocolId.CARLIFE,
                    ),
                    Delivery.FIRST_MATCH,
                ).handled

        assertTrue(handled)
        assertEquals(listOf(true, false, true), seen)
    }

    /**
     * `SessionController.onSourceAudioPlayback` 的二分派开关。
     *
     * 必须只有 CarPlay 算消费者：CarLife 声明的是 provides(PLAYBACK_INTENT)。
     * 若顺序搞反（CarLife 被当成消费者），事件会被它吞掉（返回 false），
     * `audioIntent.setPlayback` 与 `reconcile()` 就再也不会执行——表现为
     * 「有声音但音频流不切换 / 切歌后不重新协商」。
     */
    @Test
    fun playbackIntentHasConsumerOnlyAfterCarPlayRegisters() {
        ProtocolRegistry.register(CarLifeModule())
        assertFalse(ProtocolRegistry.hasConsumer(Capability.PLAYBACK_INTENT))

        ProtocolRegistry.register(CarPlayModule())
        assertTrue(ProtocolRegistry.hasConsumer(Capability.PLAYBACK_INTENT))
        assertEquals(
            listOf(ProtocolId.CARPLAY),
            ProtocolRegistry.consumers(Capability.PLAYBACK_INTENT).map { it.id },
        )
    }
}
