package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.data.hasSeen
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PLUGIN_ID: String = "rednote-publisher"

private val logger = loggerFor<RednotePublisherRuntime>()

internal class RednotePublisherRuntime() :
    PublisherSourcePlugin,
    PublisherLookupPlugin,
    PublisherLoginProvider,
    ConfigurablePlugin<RednotePublisherConfig> {

    private var pluginId: String = DEFAULT_PLUGIN_ID
    private val detectTaskId: String = "rednote-detect"

    override val platformId: PlatformId = PlatformId.of(REDNOTE_PLATFORM_ID)
    override val platformDescriptor: PlatformDescriptor = PlatformDescriptor.of(
        id = REDNOTE_PLATFORM_ID,
        displayName = "小红书",
        homepageUri = REDNOTE_HOME,
    )

    override val configId: String
        get() = pluginId
    override val configName: String = "小红书动态源"
    override val configDescription: String = "小红书笔记与直播轮询、登录配置。"
    override val configClass = RednotePublisherConfig::class
    override val configFormSpec = RednotePublisherConfigForm.spec

    private var loadConfig: (String) -> RednotePublisherConfig = { id ->
        error("小红书插件配置服务尚未初始化：$id")
    }
    private var saveConfig: (String, RednotePublisherConfig) -> Unit = { _, _ -> }
    private var gatewayFactory: (RednotePublisherConfig) -> RednoteGateway = { config ->
        RednoteHttpGateway(
            client = RednoteClient(config),
            requestIntervalMs = secondsToMillis(config.requestIntervalSeconds, minimumMillis = 1_000),
        )
    }
    private var cursorStoreFactory: () -> RednoteCursorStore = {
        error("小红书游标存储尚未初始化")
    }
    private var liveStatusStoreFactory: () -> RednoteLiveStatusStore = {
        error("小红书直播状态存储尚未初始化")
    }

    private var useContextConfigService: Boolean = true
    private var useContextTaskScheduler: Boolean = true
    private var useContextCursorStore: Boolean = true
    private var useContextLiveStore: Boolean = true

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var sourceUpdatePublisher: SourceUpdatePublisher
    private lateinit var subscriptionQueryService: SubscriptionQueryService
    private lateinit var config: RednotePublisherConfig
    private lateinit var gateway: RednoteGateway
    private lateinit var mapper: RednoteDynamicMapper
    private lateinit var requestFailureHandler: RednoteRequestFailureHandler
    private lateinit var cursorStore: RednoteCursorStore
    private lateinit var liveStatusStore: RednoteLiveStatusStore
    private lateinit var detectTask: TaskDefinition

    private val detectMutex: Mutex = Mutex()
    private val publisherLock: Any = Any()

    @Volatile
    private var dynamicPublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var livePublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var pendingDetection: Boolean = false

    internal constructor(
        loadConfig: (String) -> RednotePublisherConfig,
        gatewayFactory: (RednotePublisherConfig) -> RednoteGateway,
        saveConfig: (String, RednotePublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
        cursorStoreFactory: (() -> RednoteCursorStore)? = null,
        liveStatusStoreFactory: (() -> RednoteLiveStatusStore)? = null,
    ) : this() {
        this.loadConfig = loadConfig
        this.gatewayFactory = gatewayFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        useContextConfigService = false
        useContextTaskScheduler = false
        if (cursorStoreFactory != null) {
            this.cursorStoreFactory = cursorStoreFactory
            useContextCursorStore = false
        }
        if (liveStatusStoreFactory != null) {
            this.liveStatusStoreFactory = liveStatusStoreFactory
            useContextLiveStore = false
        }
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(PublisherLoginMethod.COOKIE)
    override val supportsCookieExport: Boolean = true

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        sourceUpdatePublisher = context.sourceUpdatePublisher
        subscriptionQueryService = context.subscriptionQueryService
        if (useContextTaskScheduler) {
            taskScheduler = context.taskScheduler
        }
        if (useContextCursorStore) {
            cursorStoreFactory = { SourceStateRednoteCursorStore(context.sourceStateStore) }
        }
        if (useContextLiveStore) {
            liveStatusStoreFactory = { SourceStateRednoteLiveStatusStore(context.sourceStateStore) }
        }
        if (useContextConfigService) {
            loadConfig = { id -> context.configService.loadOrCreate(id) { RednotePublisherConfig() } }
            saveConfig = { id, next -> context.configService.save(id, next) }
        }

        config = loadConfig(pluginId)
        RednotePublisherConfigForm.validate(config)
        gateway = gatewayFactory(config)
        mapper = RednoteDynamicMapper()
        requestFailureHandler = RednoteRequestFailureHandler(
            configProvider = { config },
            notificationPublisher = context.notificationPublisher,
        )
        cursorStore = cursorStoreFactory()
        liveStatusStore = liveStatusStoreFactory()
        detectTask = TaskDefinition(
            id = detectTaskId,
            name = "小红书笔记与直播检测",
            description = "按配置间隔检测已订阅小红书用户的新笔记和直播状态，并发布到主项目。",
            schedule = TaskSchedule.FixedDelay(config.pollingIntervalSeconds.seconds, runImmediately = true),
            action = { detectAndPublish() },
        )
        loadActivePublishers()
        logger.info { "小红书插件已加载：pluginId=$pluginId，轮询启用=${config.pollingEnabled}" }
    }

    override suspend fun onStart() {
        val initial = checkLoginState()
        if (initial.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("小红书启动登录状态检查")
            logger.info {
                "小红书登录状态可用：账号=${initial.account?.name ?: initial.account?.userId ?: "未知"}"
            }
            if (config.pollingEnabled) {
                val started = bootstrapLoggedInState()
                logger.info { "小红书轮询已就绪：任务新启动=$started" }
            }
            return
        }
        if (config.pollingEnabled) {
            logger.warn {
                "小红书轮询暂不启动：登录状态=${initial.status}，原因=${initial.message}"
            }
        } else {
            logger.info {
                "小红书轮询未启用；当前登录状态=${initial.status}，原因=${initial.message}"
            }
        }
    }

    override suspend fun onStop() {
        if (::taskScheduler.isInitialized) {
            taskScheduler.stop(detectTaskId)
        }
        runCatching { persistRuntimeCookieIfChanged() }
            .onFailure { logger.warn(it) { "停止小红书插件前回存 Cookie 失败" } }
        logger.info { "小红书插件已停止" }
    }

    override suspend fun onUnload() {
        runCatching { persistRuntimeCookieIfChanged() }
            .onFailure { logger.warn(it) { "卸载小红书插件前回存 Cookie 失败" } }
    }

    override fun currentConfig(): RednotePublisherConfig {
        return if (::config.isInitialized) config else loadConfig(pluginId)
    }

    override fun applyConfig(next: RednotePublisherConfig): ConfigApplyResult {
        RednotePublisherConfigForm.validate(next)
        val previous = currentConfig()
        if (previous == next) {
            return ConfigApplyResult(changed = false, message = "小红书配置未变化")
        }

        config = next
        if (::gateway.isInitialized) {
            gateway = gatewayFactory(next)
        }

        val restartRequired = previous.pollingEnabled != next.pollingEnabled ||
            previous.pollingIntervalSeconds != next.pollingIntervalSeconds ||
            previous.requestIntervalSeconds != next.requestIntervalSeconds ||
            previous.cookie != next.cookie

        return ConfigApplyResult(
            changed = true,
            restartRequired = restartRequired,
            restartTargets = if (restartRequired) listOf("小红书插件") else emptyList(),
            message = if (restartRequired) {
                "小红书配置已保存；需要重启小红书插件以重建轮询服务"
            } else {
                "小红书配置已保存并生效"
            },
        )
    }

    override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
        val normalized = normalizeUserId(userId) ?: return null
        val snapshot = runRednoteRequest("发布者资料查询 uid=$normalized") {
            gateway.fetchPublisherSnapshot(normalized)
        }.getOrNull() ?: return null
        return snapshot.toPublisherInfo()
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        val result = try {
            gateway.checkLoginState()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "小红书登录状态检查失败",
            )
        }
        if (result.status == PublisherLoginStatus.SUCCESS) {
            persistRuntimeCookieIfChanged()
        }
        return result
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val cookies = try {
            parseRednoteCookieInput(cookie)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "小红书 Cookie 无法解析",
            )
        }
        if (cookies.isEmpty()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "小红书 Cookie 不能为空")
        }

        val previous = currentConfig()
        val next = previous.copy(cookie = cookies.header)
        config = next
        gateway = gatewayFactory(next)
        val result = checkLoginState()
        if (result.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("Cookie 登录")
            if (!persistRuntimeCookieIfChanged()) {
                saveConfig(pluginId, config)
            }
            if (config.pollingEnabled && ::taskScheduler.isInitialized && ::detectTask.isInitialized) {
                bootstrapLoggedInState()
            }
        } else {
            config = previous
            gateway = gatewayFactory(previous)
        }
        return result
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "一期不支持小红书二维码登录，请使用 Cookie 登录",
        )
    }

    override suspend fun exportCookie(): String? {
        return currentConfig().cookie.trim().takeIf { it.isNotBlank() }
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        if (event.publisher.platformId != platformId) return

        when (event.changeType) {
            SubscriptionChangeType.SUBSCRIBED -> handleSubscribed(event)
            SubscriptionChangeType.UPDATED -> handleSubscribed(event)
            SubscriptionChangeType.UNSUBSCRIBED -> handleUnsubscribed(event)
        }
    }

    internal fun isPollingPaused(): Boolean {
        return ::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()
    }

    private suspend fun detectAndPublish(skipLiveDetection: Boolean = false) {
        if (!config.pollingEnabled) return
        if (::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()) {
            logger.debug { "小红书检测跳过：登录状态失效或疑似风控，轮询请求已暂停" }
            return
        }
        if (!detectMutex.tryLock()) {
            pendingDetection = true
            logger.debug { "小红书检测仍在执行，本轮已标记为补跑" }
            return
        }

        try {
            do {
                pendingDetection = false
                detectAndPublishLocked(skipLiveDetection)
            } while (pendingDetection)
            persistRuntimeCookieIfChanged()
        } finally {
            detectMutex.unlock()
        }
    }

    private suspend fun detectAndPublishLocked(skipLiveDetection: Boolean) {
        loadActivePublishers(logSummary = false)
        val dynamicPublisherSnapshot = dynamicPublishers
        val livePublisherSnapshot = livePublishers
        if (dynamicPublisherSnapshot.isEmpty() && livePublisherSnapshot.isEmpty()) {
            logger.debug { "小红书检测跳过：没有活跃订阅发布者" }
            return
        }

        val now = System.currentTimeMillis() / 1000
        for (publisher in dynamicPublisherSnapshot.values) {
            if (requestFailureHandler.isPollingPaused()) {
                logger.debug { "小红书检测中止：轮询已暂停" }
                return
            }
            detectPublisherNotes(publisher, now)
        }
        if (requestFailureHandler.isPollingPaused()) return
        if (!skipLiveDetection) {
            detectLiveStatusChanges(livePublisherSnapshot, now)
        }
    }

    private suspend fun detectPublisherNotes(publisher: Publisher, now: Long) {
        val userId = normalizeUserId(publisher.externalId) ?: return
        val page = runRednoteRequest("用户笔记拉取 uid=$userId") {
            gateway.fetchUserNotes(userId)
        }.getOrNull() ?: return

        val notes = page.notes.filter { it.noteId.isNotBlank() }
        val initialCursor = cursorStore.get(publisher.id)
        if (notes.isEmpty()) {
            if (initialCursor == null) {
                cursorStore.ensureBaseline(publisher.id, now)
            }
            return
        }

        if (initialCursor == null && config.replayWindowMinutes <= 0) {
            markNotesSeen(publisher.id, notes, now)
            logger.debug {
                "小红书笔记游标已初始化：publisherId=${publisher.id}，uid=$userId，notes=${notes.size}"
            }
            return
        }

        var cursor: SourceCursor = initialCursor
            ?: cursorStore.ensureBaseline(publisher.id, replayWindowStart(now))
        val replayStart = replayWindowStart(now).takeIf { config.replayWindowMinutes > 0 }

        for (note in notes.asReversed()) {
            if (cursor.hasSeen(note.noteId)) continue

            val enriched = enrichNoteForPublish(note)
            if (requestFailureHandler.isPollingPaused()) return
            val occurredAt = enriched.createdAtEpochSeconds.takeIf { it > 0 } ?: now
            if (replayStart != null && occurredAt < replayStart) {
                cursor = cursorStore.markSeen(publisher.id, note.noteId, occurredAt)
                continue
            }
            if (occurredAt < cursor.lastSeenAtEpochSeconds && cursor.lastSeenAtEpochSeconds > 0) {
                cursor = cursorStore.markSeen(publisher.id, note.noteId, occurredAt)
                continue
            }

            val update = mapper.map(enriched, publisher)
            if (update == null) {
                cursor = cursorStore.markSeen(publisher.id, note.noteId, occurredAt)
                continue
            }
            logger.info {
                "小红书检测到新笔记：publisher=${publisher.displayLabel()}，uid=$userId，noteId=${note.noteId}"
            }
            if (publishSourceUpdate(update)) {
                cursor = cursorStore.markSeen(publisher.id, note.noteId, occurredAt)
            } else {
                logger.warn {
                    "小红书笔记发布失败，已停止该发布者本轮后续处理，游标暂不越过失败笔记：noteId=${note.noteId}"
                }
                return
            }
        }
    }

    private suspend fun detectLiveStatusChanges(publisherSnapshot: Map<Int, Publisher>, now: Long) {
        if (!config.liveDetectionEnabled || publisherSnapshot.isEmpty()) return

        for (publisher in publisherSnapshot.values) {
            if (requestFailureHandler.isPollingPaused()) {
                logger.debug { "小红书直播检测中止：轮询已暂停" }
                return
            }
            val userId = normalizeUserId(publisher.externalId) ?: continue
            val snapshot = runRednoteRequest("直播状态拉取 uid=$userId") {
                gateway.fetchLiveSnapshot(userId)
            }.getOrNull() ?: continue
            val previous = liveStatusStore.get(publisher.id)
            val current = buildLiveState(publisher, snapshot, previous, now)
            val update = buildLiveUpdate(publisher, previous, current, now)
            if (update != null) {
                logger.info {
                    "小红书检测到直播状态变化：publisher=${publisher.displayLabel()}，event=${update.eventType.value}，roomId=${current.roomId}"
                }
            }
            if (update == null || publishSourceUpdate(update)) {
                liveStatusStore.save(current)
            } else {
                logger.warn {
                    "小红书直播状态发布失败，已保留旧状态：publisher=${publisher.displayLabel()}，roomId=${current.roomId}"
                }
            }
        }
    }

    private suspend fun ensureLiveBaseline(publisher: Publisher): Boolean {
        if (!config.liveDetectionEnabled || liveStatusStore.get(publisher.id) != null) return false
        val userId = normalizeUserId(publisher.externalId) ?: return false
        val now = System.currentTimeMillis() / 1000
        val snapshot = runRednoteRequest("直播状态基线 uid=$userId") {
            gateway.fetchLiveSnapshot(userId)
        }.getOrNull() ?: return true
        liveStatusStore.save(buildLiveState(publisher, snapshot, previous = null, observedAt = now))
        return true
    }

    private fun buildLiveState(
        publisher: Publisher,
        snapshot: RednoteLiveSnapshot,
        previous: PublisherLiveStatus?,
        observedAt: Long,
    ): PublisherLiveStatus {
        val status = when (snapshot.status) {
            LiveStatus.OPEN -> LiveStatus.OPEN
            LiveStatus.CLOSE, LiveStatus.ROUND -> LiveStatus.CLOSE
        }
        val roomId = snapshot.roomId.takeIf { it.isNotBlank() } ?: previous?.roomId.orEmpty()
        val title = snapshot.title.takeIf { it.isNotBlank() }
            ?: previous?.title?.takeIf { it.isNotBlank() }
            ?: publisher.name
        val cover = snapshot.coverUrl?.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.COVER) }
            ?: previous?.cover
        val area = snapshot.area?.takeIf { it.isNotBlank() } ?: previous?.area
        val startedAt = if (status == LiveStatus.OPEN) {
            snapshot.startedAtEpochSeconds
                ?: previous?.takeIf { it.status == LiveStatus.OPEN }?.startedAtEpochSeconds
                ?: observedAt
        } else {
            previous?.startedAtEpochSeconds ?: snapshot.startedAtEpochSeconds
        }
        return PublisherLiveStatus(
            publisherId = publisher.id,
            roomId = roomId,
            status = status,
            title = title,
            cover = cover,
            area = area,
            startedAtEpochSeconds = startedAt,
            lastObservedAtEpochSeconds = observedAt,
        )
    }

    private fun buildLiveUpdate(
        publisher: Publisher,
        previous: PublisherLiveStatus?,
        current: PublisherLiveStatus,
        observedAt: Long,
    ): SourceUpdate? {
        if (previous == null) return null

        val previousOpen = previous.status == LiveStatus.OPEN
        val currentOpen = current.status == LiveStatus.OPEN
        if (previousOpen == currentOpen) return null

        val eventType = if (currentOpen) SourceEventType.LIVE_STARTED else SourceEventType.LIVE_ENDED
        val startedAt = if (eventType == SourceEventType.LIVE_STARTED) {
            current.startedAtEpochSeconds ?: observedAt
        } else {
            previous.startedAtEpochSeconds ?: current.startedAtEpochSeconds
        }
        val endedAt = if (eventType == SourceEventType.LIVE_ENDED) observedAt else null
        val eventTime = when (eventType) {
            SourceEventType.LIVE_STARTED -> startedAt ?: observedAt
            SourceEventType.LIVE_ENDED -> endedAt ?: observedAt
            else -> observedAt
        }
        val roomId = current.roomId.ifBlank { previous.roomId }
        val title = current.title.ifBlank { previous.title }
        return SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = eventType,
                externalId = "$roomId:$eventTime",
            ),
            publisher = publisher.toInfo(),
            occurredAtEpochSeconds = eventTime,
            observedAtEpochSeconds = observedAt,
            link = liveRoomLink(roomId),
            payload = LivePayload(
                roomId = roomId,
                title = title,
                area = current.area ?: previous.area,
                cover = current.cover ?: previous.cover,
                status = current.status,
                previousStatus = previous.status,
                startedAtEpochSeconds = startedAt,
                endedAtEpochSeconds = endedAt,
            ),
        )
    }

    private fun markNotesSeen(publisherId: Int, notes: List<RednoteNoteSnapshot>, now: Long) {
        notes.asReversed().forEach { note ->
            val timestamp = note.createdAtEpochSeconds.takeIf { it > 0 } ?: now
            cursorStore.markSeen(publisherId, note.noteId, timestamp)
        }
    }

    private suspend fun bootstrapLoggedInState(): Boolean {
        loadActivePublishers()
        return startDetectionTask()
    }

    private suspend fun handleSubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshots(publisherId)
            return
        }

        val interests = applyPublisherSnapshot(snapshot)
        if (interests.becameDynamicPresent && cursorStore.get(publisherId) == null) {
            cursorStore.ensureBaseline(publisherId, event.subscription.createdAtEpochSeconds)
        }
        if (config.pollingEnabled && ::taskScheduler.isInitialized && taskScheduler.isRunning(detectTaskId) && interests.hasAnyInterest) {
            val liveBaselineRequested = interests.becameLivePresent && ensureLiveBaseline(snapshot.publisher)
            if (interests.hasDynamic || !liveBaselineRequested) {
                detectAndPublish(skipLiveDetection = liveBaselineRequested)
            }
        }
    }

    private fun handleUnsubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshots(publisherId)
            cursorStore.evict(publisherId)
            liveStatusStore.evict(publisherId)
        } else {
            applyPublisherSnapshot(snapshot)
        }
    }

    private fun applyPublisherSnapshot(snapshot: PublisherSubscribers): PublisherInterests {
        val publisherId = snapshot.publisher.id
        val hasDynamic = snapshot.hasEnabledEvent(SubscriptionEventKind.DYNAMIC)
        val hasLive = config.liveDetectionEnabled && snapshot.hasLiveEventSubscription()
        val interests = synchronized(publisherLock) {
            val wasDynamicPresent = dynamicPublishers.containsKey(publisherId)
            val wasLivePresent = livePublishers.containsKey(publisherId)
            dynamicPublishers = if (hasDynamic) {
                dynamicPublishers + (publisherId to snapshot.publisher)
            } else {
                dynamicPublishers - publisherId
            }
            livePublishers = if (hasLive) {
                livePublishers + (publisherId to snapshot.publisher)
            } else {
                livePublishers - publisherId
            }
            PublisherInterests(
                hasDynamic = hasDynamic,
                hasLive = hasLive,
                becameDynamicPresent = hasDynamic && !wasDynamicPresent,
                becameLivePresent = hasLive && !wasLivePresent,
            )
        }
        if (!interests.hasDynamic) {
            cursorStore.evict(publisherId)
        }
        if (!interests.hasLive) {
            liveStatusStore.evict(publisherId)
        }
        return interests
    }

    private fun removePublisherFromSnapshots(publisherId: Int) {
        synchronized(publisherLock) {
            dynamicPublishers = dynamicPublishers - publisherId
            livePublishers = livePublishers - publisherId
        }
    }

    private fun loadActivePublishers(logSummary: Boolean = true) {
        val snapshots = subscriptionQueryService
            .findActivePublishersWithSubscribersBySourcePlatform(platformId.value)
        val loadedDynamic = snapshots
            .filter { it.hasEnabledEvent(SubscriptionEventKind.DYNAMIC) }
            .map { it.publisher }
            .associateBy { it.id }
        val loadedLive = snapshots
            .filter { config.liveDetectionEnabled && it.hasLiveEventSubscription() }
            .map { it.publisher }
            .associateBy { it.id }
        synchronized(publisherLock) {
            dynamicPublishers = loadedDynamic
            livePublishers = loadedLive
        }
        if (logSummary) {
            logger.info { "小红书订阅发布者已加载：动态=${loadedDynamic.size}，直播=${loadedLive.size}" }
        }
    }

    private suspend fun <T> runRednoteRequest(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return requestFailureHandler.run(operation, block)
    }

    private suspend fun publishSourceUpdate(update: SourceUpdate): Boolean {
        logger.debug {
            "小红书提交来源更新到主项目：event=${update.eventType.value}，update=${update.key.stableValue()}，publisher=${update.publisher.name}"
        }
        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
                sourcePlugin = pluginId,
                update = update,
            )
        )
        if (result.accepted) {
            logger.debug {
                "小红书来源更新已进入主项目：update=${update.key.stableValue()}，结果=${result.message}"
            }
        } else {
            logger.warn {
                "小红书来源更新发布失败，游标暂不推进：update=${update.key.stableValue()}，原因=${result.message}"
            }
        }
        return result.accepted
    }

    private suspend fun enrichNoteForPublish(note: RednoteNoteSnapshot): RednoteNoteSnapshot {
        return runRednoteRequest("笔记详情补全 noteId=${note.noteId}") {
            gateway.enrichNote(note)
        }.getOrElse { error ->
            logger.warn {
                "小红书笔记详情补全失败，使用列表摘要继续处理：noteId=${note.noteId}，原因=${error.message ?: "未知错误"}"
            }
            note
        }
    }

    private fun startDetectionTask(): Boolean {
        val started = taskScheduler.start(detectTask)
        if (started) {
            logger.info { "小红书检测任务已启动：taskId=$detectTaskId" }
        } else {
            logger.debug { "小红书检测任务已在运行：taskId=$detectTaskId" }
        }
        return started
    }

    private fun persistRuntimeCookieIfChanged(): Boolean {
        if (!::gateway.isInitialized) return false
        val latest = gateway.exportCookie().trim().takeIf { it.isNotBlank() } ?: return false
        if (latest == config.cookie.trim()) return false
        config = config.copy(cookie = latest)
        runCatching {
            saveConfig(pluginId, config)
        }.onFailure {
            logger.warn(it) { "回存小红书运行期 Cookie 失败" }
        }
        logger.debug { "小红书运行期 Cookie 已回存配置" }
        return true
    }

    private fun replayWindowStart(now: Long): Long {
        return now - config.replayWindowMinutes.coerceAtLeast(0).toLong() * SECONDS_PER_MINUTE
    }

    private fun normalizeUserId(userId: String): String? {
        return userId.trim().takeIf { it.isNotBlank() }
    }

    private fun RednotePublisherSnapshot.toPublisherInfo(): PublisherInfo {
        val normalizedUserId = normalizeUserId(userId) ?: userId
        return PublisherInfo(
            key = PublisherKey.of(
                platformId = REDNOTE_PLATFORM_ID,
                kind = PublisherKind.USER,
                externalId = normalizedUserId,
            ),
            name = nickname.takeIf { it.isNotBlank() } ?: "小红书用户 $normalizedUserId",
            avatar = MediaRef(
                uri = avatarUrl?.takeIf { it.isNotBlank() } ?: REDNOTE_DEFAULT_AVATAR,
                kind = MediaKind.AVATAR,
            ),
            banner = bannerUrl?.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.COVER) },
        )
    }

    private fun PublisherSubscribers.hasLiveEventSubscription(): Boolean {
        return hasEnabledEvent(SubscriptionEventKind.LIVE_STARTED) ||
            hasEnabledEvent(SubscriptionEventKind.LIVE_ENDED)
    }

    private fun PublisherSubscribers.hasEnabledEvent(kind: SubscriptionEventKind): Boolean {
        return subscriptions.any { item ->
            item.subscription.state == EntityState.ACTIVE &&
                item.subscriber.state.allowsActiveDelivery &&
                kind in item.subscription.policy.enabledEvents
        }
    }

    private fun Publisher.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private data class PublisherInterests(
        val hasDynamic: Boolean,
        val hasLive: Boolean,
        val becameDynamicPresent: Boolean,
        val becameLivePresent: Boolean,
    ) {
        val hasAnyInterest: Boolean
            get() = hasDynamic || hasLive
    }

    private companion object {
        private const val SECONDS_PER_MINUTE: Long = 60L
    }
}
