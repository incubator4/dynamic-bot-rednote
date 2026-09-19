package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.SubscriptionSubscriber
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass

internal fun testContext(
    updates: SourceUpdatePublisher = SourceUpdatePublisher {
        SourceUpdatePublishResult.ignored("test")
    },
    subscriptions: SubscriptionQueryService = DummySubscriptionQueryService,
    notificationPublisher: SystemNotificationPublisher = SystemNotificationPublisher {
        SystemNotificationPublishResult.accepted()
    },
    taskScheduler: TaskScheduler = ManualTaskScheduler(),
): PluginContext {
    return PluginContext(
        pluginId = "rednote-publisher",
        descriptor = PluginDescriptor(
            id = "rednote-publisher",
            name = "小红书动态源",
            version = "0.0.1",
            mainClass = "com.incubator4.dynamic.rednote.RednotePublisherPlugin",
        ),
        configService = DummyConfigService,
        dataStore = DummyPluginDataStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        taskScheduler = taskScheduler,
        sourceUpdatePublisher = updates,
        sourceStateStore = DummySourceStateStore,
        subscriptionQueryService = subscriptions,
        notificationPublisher = notificationPublisher,
    )
}

internal fun testPublisher(id: Int, userId: String): Publisher {
    return Publisher(
        id = id,
        key = PublisherKey.of(REDNOTE_PLATFORM_ID, PublisherKind.USER, userId),
        name = "小红书用户 $userId",
        avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
        createTime = 1,
        createUser = 1,
    )
}

internal fun testNote(
    noteId: String,
    userId: String,
    createdAt: Long = 0,
    title: String = "笔记 $noteId",
    desc: String = "",
    type: RednoteNoteType = RednoteNoteType.NORMAL,
    coverUrl: String? = "https://example.com/cover.jpg",
    images: List<RednoteImageSnapshot> = emptyList(),
    videoUrl: String? = null,
    isTop: Boolean = false,
): RednoteNoteSnapshot {
    return RednoteNoteSnapshot(
        noteId = noteId,
        userId = userId,
        nickname = "小红书用户 $userId",
        avatarUrl = "https://example.com/avatar.png",
        title = title,
        desc = desc,
        type = type,
        createdAtEpochSeconds = createdAt,
        url = noteLink(noteId),
        isTop = isTop,
        images = images,
        coverUrl = coverUrl,
        videoUrl = videoUrl,
    )
}

internal open class RecordingRednoteGateway(
    var loginResult: PublisherLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "登录成功"),
    private val exportedCookie: String = "",
    private val publishers: Map<String, RednotePublisherSnapshot> = emptyMap(),
    private val userNotesPages: MutableMap<String, MutableList<RednoteUserNotesPage>> = mutableMapOf(),
    private val liveSnapshots: MutableMap<String, RednoteLiveSnapshot> = mutableMapOf(),
    var qrLoginResult: PublisherLoginResult = PublisherLoginResult(
        status = PublisherLoginStatus.UNSUPPORTED,
        message = "不支持小红书二维码登录",
    ),
    var qrChallenge: PublisherQrLoginChallenge = PublisherQrLoginChallenge(
        qrContent = "https://www.xiaohongshu.com/login",
        message = "请使用小红书 App 扫码并确认登录",
    ),
    var qrStatusUpdates: List<PublisherLoginResult> = emptyList(),
) : RednoteGateway {
    var loginCheckCount: Int = 0
        private set
    var qrLoginCount: Int = 0
        private set
    val fetchedUserIds: MutableList<String> = mutableListOf()
    val fetchedLiveUserIds: MutableList<String> = mutableListOf()
    val enrichedNoteIds: MutableList<String> = mutableListOf()

    override fun exportCookie(): String = exportedCookie

    override suspend fun checkLoginState(): PublisherLoginResult {
        loginCheckCount += 1
        return loginResult
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        pollIntervalMs: Long,
        timeoutMs: Long,
    ): PublisherLoginResult {
        qrLoginCount += 1
        onQrCode(qrChallenge)
        qrStatusUpdates.forEach { onStatusChanged(it) }
        return qrLoginResult
    }

    override suspend fun fetchPublisherSnapshot(userId: String): RednotePublisherSnapshot? {
        return publishers[userId]
    }

    override suspend fun fetchUserNotes(userId: String, cursor: String?): RednoteUserNotesPage {
        fetchedUserIds += userId
        val pages = userNotesPages[userId] ?: return RednoteUserNotesPage()
        return if (pages.isEmpty()) {
            RednoteUserNotesPage()
        } else {
            pages.removeAt(0)
        }
    }

    override suspend fun enrichNote(note: RednoteNoteSnapshot): RednoteNoteSnapshot {
        enrichedNoteIds += note.noteId
        return if (note.desc.isNotBlank() || note.images.isNotEmpty() || note.createdAtEpochSeconds > 0) {
            note
        } else {
            note.copy(desc = "补全文本 ${note.noteId}")
        }
    }

    fun enqueueUserNotes(userId: String, vararg pages: RednoteUserNotesPage) {
        userNotesPages.getOrPut(userId) { mutableListOf() }.addAll(pages)
    }

    override suspend fun fetchLiveSnapshot(userId: String): RednoteLiveSnapshot {
        fetchedLiveUserIds += userId
        return liveSnapshots[userId] ?: RednoteLiveSnapshot(userId = userId)
    }

    fun setLiveSnapshot(userId: String, snapshot: RednoteLiveSnapshot) {
        liveSnapshots[userId] = snapshot
    }
}

internal class InMemoryRednoteCursorStore(
    initial: Map<Int, SourceCursor> = emptyMap(),
) : RednoteCursorStore {
    private val cursors: MutableMap<Int, SourceCursor> = initial.toMutableMap()

    override fun get(publisherId: Int): SourceCursor? = cursors[publisherId]

    override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
        return cursors[publisherId] ?: markSeen(publisherId, "__baseline__$timestamp", timestamp)
    }

    override fun markSeen(publisherId: Int, noteId: String, timestamp: Long): SourceCursor {
        val recent = LinkedHashSet(cursors[publisherId]?.recentUpdateKeys.orEmpty())
        recent.add(noteId)
        val cursor = SourceCursor(
            publisherId = publisherId,
            sourceKey = REDNOTE_NOTE_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            lastSeenUpdateKey = noteId,
            lastSeenAtEpochSeconds = timestamp,
            recentUpdateKeys = recent.toList(),
        )
        cursors[publisherId] = cursor
        return cursor
    }

    override fun evict(publisherId: Int) {
        cursors.remove(publisherId)
    }

    fun contains(publisherId: Int): Boolean = publisherId in cursors
}

internal class InMemoryRednoteLiveStatusStore(
    initial: Map<Int, PublisherLiveStatus> = emptyMap(),
) : RednoteLiveStatusStore {
    private val states: MutableMap<Int, PublisherLiveStatus> = initial.toMutableMap()

    override fun get(publisherId: Int): PublisherLiveStatus? = states[publisherId]

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        states[state.publisherId] = state
        return state
    }

    override fun evict(publisherId: Int) {
        states.remove(publisherId)
    }
}

internal class RecordingSourceUpdatePublisher : SourceUpdatePublisher {
    val requests: MutableList<SourceUpdatePublishRequest> = mutableListOf()
    var nextResult: SourceUpdatePublishResult = SourceUpdatePublishResult.enqueued(1)

    override suspend fun publish(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
        requests += request
        return nextResult
    }
}

internal class FixedSubscriptionQueryService(
    publishers: List<Publisher>,
    policy: SubscriptionPolicy = SubscriptionPolicy.default(),
) : SubscriptionQueryService {
    var snapshots: List<PublisherSubscribers> = publishers.mapIndexed { index, publisher ->
        publisherSnapshot(publisher, index + 1, policy)
    }

    override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
        return snapshots.firstOrNull { it.publisher.id == publisherId }
    }

    override fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): List<PublisherSubscribers> {
        return snapshots.filter { it.publisher.platformId.value == platformId }
    }
}

internal fun publisherSnapshot(
    publisher: Publisher,
    index: Int,
    policy: SubscriptionPolicy = SubscriptionPolicy.default(),
): PublisherSubscribers {
    val subscriber = Subscriber(
        id = index,
        address = TargetAddress.of("onebot", TargetKind.GROUP, "1000"),
        name = "测试群",
        state = SubscriberState.ACTIVE,
        createTime = 1,
        createUser = 1,
    )
    return PublisherSubscribers(
        publisher = publisher,
        subscriptions = listOf(
            SubscriptionSubscriber(
                subscription = Subscription(
                    id = index,
                    subscriberId = subscriber.id,
                    publisherId = publisher.id,
                    createdAtEpochSeconds = 1,
                    updatedAtEpochSeconds = 1,
                    policy = policy,
                ),
                subscriber = subscriber,
            )
        ),
    )
}

internal fun livePolicy(): SubscriptionPolicy {
    return SubscriptionPolicy(
        enabledEvents = setOf(
            SubscriptionEventKind.LIVE_STARTED,
            SubscriptionEventKind.LIVE_ENDED,
        ),
    )
}

internal fun testLiveSnapshot(
    userId: String,
    roomId: String = "room-$userId",
    status: LiveStatus = LiveStatus.CLOSE,
    title: String = "小红书直播",
    coverUrl: String? = "https://example.com/live-cover.jpg",
    startedAt: Long? = null,
): RednoteLiveSnapshot {
    return RednoteLiveSnapshot(
        userId = userId,
        roomId = roomId,
        status = status,
        title = title,
        coverUrl = coverUrl,
        startedAtEpochSeconds = startedAt,
    )
}

internal class ManualTaskScheduler : TaskScheduler {
    private val tasks: MutableMap<String, TaskDefinition> = linkedMapOf()
    private val running: MutableSet<String> = linkedSetOf()

    override fun start(task: TaskDefinition): Boolean {
        tasks[task.id] = task
        return running.add(task.id)
    }

    suspend fun runOnce(id: String) {
        require(id in running) { "任务未运行：$id" }
        val task = tasks[id] ?: error("任务不存在：$id")
        task.action()
    }

    override fun start(id: String): Boolean = if (id in tasks) running.add(id) else false

    override suspend fun stop(id: String): Boolean = running.remove(id)

    override suspend fun restart(id: String): Boolean {
        stop(id)
        return start(id)
    }

    override suspend fun stopAll() {
        running.clear()
    }

    override suspend fun shutdown() {
        running.clear()
        tasks.clear()
    }

    override fun isRunning(id: String): Boolean = id in running

    override fun snapshot(id: String): TaskSnapshot? = null

    override fun snapshots(): List<TaskSnapshot> = emptyList()
}

internal object DummyConfigService : ConfigService {
    override fun <T : Any> loadOrCreate(
        pluginId: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T = defaultProvider()

    override fun <T : Any> save(pluginId: String, config: T) = Unit

    override fun <T : Any> reload(
        pluginId: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
    ): T = error("未配置测试配置：$pluginId")

    override fun exists(pluginId: String): Boolean = false

    override fun delete(pluginId: String): Boolean = false

    override fun resolvePath(pluginId: String): Path = createTempDirectory("rednote-config").resolve("$pluginId.yml")
}

internal object DummyPluginDataStore : PluginDataStore {
    override val dataDir: Path = createTempDirectory("rednote-data")

    override fun <T : Any> loadOrCreate(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T = defaultProvider()

    override fun <T : Any> save(name: String, value: T) = Unit

    override fun <T : Any> reload(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
    ): T = error("未配置测试数据：$name")

    override fun exists(name: String): Boolean = false

    override fun delete(name: String): Boolean = false

    override fun resolvePath(name: String): Path = dataDir.resolve("$name.yml")
}

internal object DummySourceStateStore : SourceStateStore {
    override fun findCursor(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
    ): SourceCursor? = null

    override fun ensureCursorBaseline(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        timestamp: Long,
    ): SourceCursor = SourceCursor(publisherId, sourceKey, eventType, "__baseline__$timestamp", timestamp)

    override fun markCursorSeen(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        updateKey: String,
        timestamp: Long,
    ): SourceCursor = SourceCursor(publisherId, sourceKey, eventType, updateKey, timestamp, listOf(updateKey))

    override fun findLatestLiveStatus(publisherId: Int): PublisherLiveStatus? = null

    override fun saveLiveStatus(state: PublisherLiveStatus): PublisherLiveStatus = state
}

internal object DummySubscriptionQueryService : SubscriptionQueryService {
    override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? = null

    override fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): List<PublisherSubscribers> {
        return emptyList()
    }
}
