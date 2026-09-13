package com.incubator4.dynamic.rednote

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RednotePublisherRuntimeTest {
    @Test
    fun `startup with polling enabled starts detect task after login`() = runBlocking {
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = RednotePublisherConfig(pollingEnabled = true, cookie = "web_session=ok"),
            gateway = RecordingRednoteGateway(),
            scheduler = scheduler,
        )
        runtime.onLoad(testContext(taskScheduler = scheduler))
        runtime.onStart()

        assertTrue(scheduler.isRunning("rednote-detect"))
    }

    @Test
    fun `first poll initializes cursor without publishing old notes`() = runBlocking {
        val publisher = testPublisher(1, "64abc")
        val cursorStore = InMemoryRednoteCursorStore()
        val gateway = RecordingRednoteGateway()
        gateway.enqueueUserNotes(
            publisher.externalId,
            RednoteUserNotesPage(notes = listOf(testNote("old-1", publisher.externalId), testNote("old-2", publisher.externalId))),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = RednotePublisherConfig(pollingEnabled = true, replayWindowMinutes = 0),
            gateway = gateway,
            scheduler = scheduler,
            cursorStore = cursorStore,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher)), taskScheduler = scheduler))
        runtime.onStart()
        scheduler.runOnce("rednote-detect")

        assertEquals(emptyList(), updates.requests.map { it.update.key.externalId })
        assertTrue(cursorStore.get(publisher.id)?.recentUpdateKeys?.contains("old-1") == true)
        assertTrue(cursorStore.get(publisher.id)?.recentUpdateKeys?.contains("old-2") == true)
        assertEquals(emptyList(), gateway.enrichedNoteIds)
    }

    @Test
    fun `later poll publishes unseen notes and advances cursor`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val publisher = testPublisher(1, "64abc")
        val cursorStore = InMemoryRednoteCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = REDNOTE_NOTE_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "old-1",
                    lastSeenAtEpochSeconds = now - 600,
                    recentUpdateKeys = listOf("old-1"),
                )
            )
        )
        val gateway = RecordingRednoteGateway()
        gateway.enqueueUserNotes(
            publisher.externalId,
            RednoteUserNotesPage(
                notes = listOf(
                    testNote("new-1", publisher.externalId, createdAt = now - 60, title = "新笔记"),
                    testNote("old-1", publisher.externalId, createdAt = now - 1_200),
                ),
            ),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = RednotePublisherConfig(pollingEnabled = true, replayWindowMinutes = 0),
            gateway = gateway,
            scheduler = scheduler,
            cursorStore = cursorStore,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher)), taskScheduler = scheduler))
        runtime.onStart()
        scheduler.runOnce("rednote-detect")

        assertEquals(listOf("new-1"), updates.requests.map { it.update.key.externalId })
        assertTrue(cursorStore.get(publisher.id)?.recentUpdateKeys?.contains("new-1") == true)
        assertEquals(listOf("new-1"), gateway.enrichedNoteIds)
        val payload = updates.requests.single().update.payload
        assertTrue(payload is DynamicPayload)
    }

    @Test
    fun `failed publish does not advance cursor past the failed note`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val publisher = testPublisher(1, "64abc")
        val cursorStore = InMemoryRednoteCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = REDNOTE_NOTE_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "old-1",
                    lastSeenAtEpochSeconds = now - 600,
                    recentUpdateKeys = listOf("old-1"),
                )
            )
        )
        val gateway = RecordingRednoteGateway()
        gateway.enqueueUserNotes(
            publisher.externalId,
            RednoteUserNotesPage(
                notes = listOf(
                    testNote("new-2", publisher.externalId, createdAt = now - 30),
                    testNote("new-1", publisher.externalId, createdAt = now - 60),
                ),
            ),
        )
        val updates = RecordingSourceUpdatePublisher().apply {
            nextResult = SourceUpdatePublishResult.failed("主程序未收下")
        }
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = RednotePublisherConfig(pollingEnabled = true),
            gateway = gateway,
            scheduler = scheduler,
            cursorStore = cursorStore,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher)), taskScheduler = scheduler))
        runtime.onStart()
        scheduler.runOnce("rednote-detect")

        assertEquals(listOf("new-1"), updates.requests.map { it.update.key.externalId })
        assertFalse(cursorStore.get(publisher.id)?.recentUpdateKeys?.contains("new-1") == true)
        assertFalse(cursorStore.get(publisher.id)?.recentUpdateKeys?.contains("new-2") == true)
    }

    @Test
    fun `fetch publisher info maps snapshot`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            publishers = mapOf(
                "64abc" to RednotePublisherSnapshot(
                    userId = "64abc",
                    nickname = "测试博主",
                    avatarUrl = "https://example.com/avatar.png",
                    bannerUrl = "https://example.com/banner.png",
                )
            )
        )
        val runtime = runtime(gateway = gateway)
        runtime.onLoad(testContext())

        val info = runtime.fetchPublisherInfo("64abc")
        assertNotNull(info)
        assertEquals("测试博主", info.name)
        assertEquals("64abc", info.externalId)
        assertEquals("https://example.com/avatar.png", info.avatar.uri)
        assertEquals("https://example.com/banner.png", info.banner?.uri)
    }

    @Test
    fun `subscription update without dynamic event evicts cursor`() = runBlocking {
        val publisher = testPublisher(1, "64abc")
        val cursorStore = InMemoryRednoteCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = REDNOTE_NOTE_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "seen",
                    lastSeenAtEpochSeconds = 100,
                    recentUpdateKeys = listOf("seen"),
                )
            )
        )
        val subscriptions = FixedSubscriptionQueryService(listOf(publisher))
        val runtime = runtime(
            config = RednotePublisherConfig(pollingEnabled = false),
            cursorStore = cursorStore,
        )
        runtime.onLoad(testContext(subscriptions = subscriptions))
        val current = subscriptions.snapshots.single()
        val disabled = current.copy(
            subscriptions = current.subscriptions.map { item ->
                item.copy(subscription = item.subscription.copy(policy = SubscriptionPolicy(enabledEvents = emptySet())))
            }
        )
        subscriptions.snapshots = listOf(disabled)

        runtime.onSubscriptionChanged(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.UPDATED,
                subscription = disabled.subscriptions.single().subscription,
                publisher = publisher,
                subscriber = disabled.subscriptions.single().subscriber,
                changedAtEpochSeconds = 2,
            )
        )

        assertFalse(cursorStore.contains(publisher.id))
    }

    @Test
    fun `plugin lookup and subscription change delegate to runtime`() = runBlocking {
        val publisher = testPublisher(1, "64abc")
        val gateway = RecordingRednoteGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "ok"),
            publishers = mapOf(
                "64abc" to RednotePublisherSnapshot(userId = "64abc", nickname = "委托用户"),
            ),
        )
        val plugin = RednotePublisherPlugin(
            loadConfig = { RednotePublisherConfig() },
            gatewayFactory = { gateway },
            taskScheduler = ManualTaskScheduler(),
        )
        plugin.onLoad(testContext(subscriptions = FixedSubscriptionQueryService(listOf(publisher))))

        val info = plugin.fetchPublisherInfo("64abc")
        assertEquals("委托用户", info?.name)

        plugin.onSubscriptionChanged(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.UNSUBSCRIBED,
                subscription = publisherSnapshot(publisher, 1).subscriptions.single().subscription,
                publisher = publisher,
                subscriber = publisherSnapshot(publisher, 1).subscriptions.single().subscriber,
                changedAtEpochSeconds = 2,
            )
        )
    }

    private fun runtime(
        config: RednotePublisherConfig = RednotePublisherConfig(),
        gateway: RednoteGateway = RecordingRednoteGateway(),
        scheduler: ManualTaskScheduler = ManualTaskScheduler(),
        cursorStore: RednoteCursorStore? = null,
    ): RednotePublisherRuntime {
        return RednotePublisherRuntime(
            loadConfig = { config },
            gatewayFactory = { gateway },
            taskScheduler = scheduler,
            cursorStoreFactory = cursorStore?.let { store -> { store } },
        )
    }
}
