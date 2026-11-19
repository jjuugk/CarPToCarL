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
            setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE, Capability.MEDIA_LYRICS),
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
            setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE, Capability.MEDIA_LYRICS),
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
}
