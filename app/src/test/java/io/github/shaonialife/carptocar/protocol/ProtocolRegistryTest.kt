package io.github.shaonialife.carptocar.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 聚合层的路由契约。这些断言就是把「媒体键竞争 / 媒体信息广播 / 数据流配对」三件事
 * 从 SessionController 的散落 if 里抽成可验证的规则——新增协议时先跑这里。
 */
class ProtocolRegistryTest {
    @Before
    fun setUp() = ProtocolRegistry.reset()

    private class FakeModule(
        override val id: ProtocolId,
        private val provides: Set<Capability> = emptySet(),
        private val consumes: Set<Capability> = emptySet(),
        private val handler: (ProtocolEvent) -> Boolean = { true },
        private val onStart: () -> Boolean = { true },
        private val onStop: () -> Unit = {},
    ) : ProtocolModule {
        val handled = mutableListOf<ProtocolEvent>()
        var startCalls = 0
        var stopCalls = 0

        override fun provides(): Set<Capability> = provides

        override fun consumes(): Set<Capability> = consumes

        override fun start(): Boolean {
            startCalls++
            return onStart()
        }

        override fun stop() {
            stopCalls++
            onStop()
        }

        override fun onEvent(event: ProtocolEvent): Boolean {
            handled += event
            return handler(event)
        }
    }

    private fun mediaKey(from: ProtocolId = ProtocolId.SYSTEM) =
        ProtocolEvent.MediaKey(keyCode = 87, from = from)

    @Test
    fun firstMatchStopsAtFirstConsumerThatHandles() {
        val first =
            FakeModule(ProtocolId.CARLIFE, consumes = setOf(Capability.MEDIA_KEY), handler = { false })
        val second =
            FakeModule(ProtocolId.CARPLAY, consumes = setOf(Capability.MEDIA_KEY), handler = { true })
        ProtocolRegistry.register(first)
        ProtocolRegistry.register(second)

        val result = ProtocolRegistry.route(mediaKey(), Delivery.FIRST_MATCH)

        assertTrue(result.handled)
        assertEquals(listOf(second.id), result.consumedBy.map { it.id })
        assertEquals(1, first.handled.size) // 被问过但没吃下
        assertEquals(1, second.handled.size)
    }

    @Test
    fun whenNoConsumerHandlesResultIsUnhandled() {
        val consumer =
            FakeModule(ProtocolId.CARLIFE, consumes = setOf(Capability.MEDIA_KEY), handler = { false })
        ProtocolRegistry.register(consumer)

        val result = ProtocolRegistry.route(mediaKey(), Delivery.FIRST_MATCH)

        assertFalse(result.handled)
        assertTrue(result.consumedBy.isEmpty())
    }

    @Test
    fun allBroadcastsToEveryConsumerRegardlessOfReturn() {
        val a = FakeModule(ProtocolId.CARLIFE, consumes = setOf(Capability.MEDIA_INFO), handler = { false })
        val b = FakeModule(ProtocolId.CARPLAY, consumes = setOf(Capability.MEDIA_INFO), handler = { true })
        ProtocolRegistry.register(a)
        ProtocolRegistry.register(b)

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.MediaInfo(title = "歌曲", artist = "歌手", from = ProtocolId.SYSTEM),
                Delivery.ALL,
            )

        assertEquals(2, result.consumedBy.size) // 即使 a 返回 false 也算「已投递」
        assertEquals(1, a.handled.size)
        assertEquals(1, b.handled.size)
    }

    @Test
    fun eventIsNeverRoutedBackToItsOwnProducer() {
        val producer =
            FakeModule(ProtocolId.CARLIFE, consumes = setOf(Capability.MEDIA_INFO), handler = { true })
        ProtocolRegistry.register(producer)

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.MediaInfo(title = "歌曲", artist = "歌手", from = ProtocolId.CARLIFE),
                Delivery.ALL,
            )

        assertFalse(result.handled)
        assertTrue(producer.handled.isEmpty())
    }

    @Test
    fun bindDeliversOnlyToFirstConsumer() {
        val a = FakeModule(ProtocolId.CARLIFE, consumes = setOf(Capability.MEDIA_INFO), handler = { true })
        val b = FakeModule(ProtocolId.CARPLAY, consumes = setOf(Capability.MEDIA_INFO), handler = { true })
        ProtocolRegistry.register(a)
        ProtocolRegistry.register(b)

        val result =
            ProtocolRegistry.route(
                ProtocolEvent.MediaInfo(title = "歌曲", artist = "歌手", from = ProtocolId.CARPLAY),
                Delivery.BIND,
            )

        // BIND 是「一个源对一个汇」：即便两个模块声明能消费，也只投第一个
        assertEquals(listOf(ProtocolId.CARLIFE), result.consumedBy.map { it.id })
        assertTrue(a.handled.isNotEmpty())
        assertTrue(b.handled.isEmpty())
    }

    @Test
    fun providersAndConsumersAreComputedFromDeclarations() {
        val source = FakeModule(ProtocolId.CARLIFE, provides = setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE))
        val sink = FakeModule(ProtocolId.CARPLAY, consumes = setOf(Capability.VIDEO_SOURCE, Capability.AUDIO_SOURCE))
        ProtocolRegistry.register(source)
        ProtocolRegistry.register(sink)

        assertEquals(listOf(ProtocolId.CARLIFE), ProtocolRegistry.providers(Capability.VIDEO_SOURCE).map { it.id })
        assertEquals(listOf(ProtocolId.CARPLAY), ProtocolRegistry.consumers(Capability.VIDEO_SOURCE).map { it.id })
        assertEquals(ProtocolId.CARLIFE, ProtocolRegistry.get(ProtocolId.CARLIFE)?.id)
        assertEquals(2, ProtocolRegistry.all().size)
    }

    @Test
    fun startAllSkipsFailedModulesAndStopAllIsReentrant() {
        val ok = FakeModule(ProtocolId.CARLIFE, onStart = { true })
        val failed = FakeModule(ProtocolId.CARPLAY, onStart = { false })
        ProtocolRegistry.register(ok)
        ProtocolRegistry.register(failed)

        assertEquals(listOf(ProtocolId.CARLIFE), ProtocolRegistry.startAll())
        ProtocolRegistry.stopAll()
        ProtocolRegistry.stopAll()

        assertEquals(1, ok.stopCalls)
        assertEquals(0, failed.stopCalls) // 启动失败的模块没占资源，不该被 stop
    }

    @Test
    fun unregisterRemovesModuleFromRouting() {
        val consumer =
            FakeModule(ProtocolId.CARLIFE, consumes = setOf(Capability.MEDIA_KEY), handler = { true })
        ProtocolRegistry.register(consumer)
        ProtocolRegistry.unregister(ProtocolId.CARLIFE)

        assertFalse(ProtocolRegistry.route(mediaKey()).handled)
        assertNull(ProtocolRegistry.get(ProtocolId.CARLIFE))
    }
}
