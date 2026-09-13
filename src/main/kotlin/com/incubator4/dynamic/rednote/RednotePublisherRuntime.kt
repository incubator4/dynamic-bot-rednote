package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SubscriptionEventKind
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
    override val configDescription: String = "小红书动态轮询与登录配置。"
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

    private var useContextConfigService: Boolean = true
    private var useContextTaskScheduler: Boolean = true
    private var useContextStateStore: Boolean = true

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var sourceUpdatePublisher: SourceUpdatePublisher
    private lateinit var subscriptionQueryService: SubscriptionQueryService
    private lateinit var config: RednotePublisherConfig
    private lateinit var gateway: RednoteGateway
    private lateinit var mapper: RednoteDynamicMapper
    private lateinit var requestFailureHandler: RednoteRequestFailureHandler
    private lateinit var cursorStore: RednoteCursorStore
    private lateinit var detectTask: TaskDefinition

    private val detectMutex: Mutex = Mutex()
    private val publisherLock: Any = Any()

    @Volatile
    private var activePublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var pendingDetection: Boolean = false

    internal constructor(
        loadConfig: (String) -> RednotePublisherConfig,
        gatewayFactory: (RednotePublisherConfig) -> RednoteGateway,
        saveConfig: (String, RednotePublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
        cursorStoreFactory: (() -> RednoteCursorStore)? = null,
    ) : this() {
        this.loadConfig = loadConfig
        this.gatewayFactory = gatewayFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        useContextConfigService = false
        useContextTaskScheduler = false
        if (cursorStoreFactory != null) {
            this.cursorStoreFactory = cursorStoreFactory
            useContextStateStore = false
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
        if (useContextStateStore) {
            cursorStoreFactory = { SourceStateRednoteCursorStore(context.sourceStateStore) }
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
        detectTask = TaskDefinition(
            id = detectTaskId,
            name = "小红书笔记检测",
            description = "按配置间隔检测已订阅小红书用户的新笔记，并发布到主项目。",
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

    private suspend fun detectAndPublish() {
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
                detectAndPublishLocked()
            } while (pendingDetection)
            persistRuntimeCookieIfChanged()
        } finally {
            detectMutex.unlock()
        }
    }

    private suspend fun detectAndPublishLocked() {
        loadActivePublishers(logSummary = false)
        val publisherSnapshot = activePublishers
        if (publisherSnapshot.isEmpty()) {
            logger.debug { "小红书检测跳过：没有活跃订阅发布者" }
            return
        }

        val now = System.currentTimeMillis() / 1000
        for (publisher in publisherSnapshot.values) {
            if (requestFailureHandler.isPollingPaused()) {
                logger.debug { "小红书检测中止：轮询已暂停" }
                return
            }
            detectPublisherNotes(publisher, now)
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
            removePublisherFromSnapshot(publisherId)
            return
        }

        applyPublisherSnapshot(snapshot)
        if (config.pollingEnabled && ::taskScheduler.isInitialized && taskScheduler.isRunning(detectTaskId)) {
            detectAndPublish()
        }
    }

    private fun handleUnsubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshot(publisherId)
            cursorStore.evict(publisherId)
        } else {
            applyPublisherSnapshot(snapshot)
        }
    }

    private fun applyPublisherSnapshot(snapshot: PublisherSubscribers): Boolean {
        val publisherId = snapshot.publisher.id
        val hasDynamic = snapshot.hasDynamicEventSubscription()
        val becamePresent = synchronized(publisherLock) {
            val wasPresent = activePublishers.containsKey(publisherId)
            activePublishers = if (hasDynamic) {
                activePublishers + (publisherId to snapshot.publisher)
            } else {
                activePublishers - publisherId
            }
            hasDynamic && !wasPresent
        }
        if (!hasDynamic) {
            cursorStore.evict(publisherId)
        }
        return becamePresent
    }

    private fun removePublisherFromSnapshot(publisherId: Int) {
        synchronized(publisherLock) {
            activePublishers = activePublishers - publisherId
        }
    }

    private fun loadActivePublishers(logSummary: Boolean = true) {
        val loaded = subscriptionQueryService
            .findActivePublishersWithSubscribersBySourcePlatform(platformId.value)
            .filter { it.hasDynamicEventSubscription() }
            .map { it.publisher }
            .associateBy { it.id }
        synchronized(publisherLock) {
            activePublishers = loaded
        }
        if (logSummary) {
            logger.info { "小红书订阅发布者已加载：动态=${loaded.size}" }
        }
    }

    private suspend fun <T> runRednoteRequest(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return requestFailureHandler.run(operation, block)
    }

    private suspend fun publishSourceUpdate(update: top.colter.dynamic.core.data.SourceUpdate): Boolean {
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

    private fun PublisherSubscribers.hasDynamicEventSubscription(): Boolean {
        return subscriptions.any { item ->
            item.subscription.state == EntityState.ACTIVE &&
                item.subscriber.state.allowsActiveDelivery &&
                SubscriptionEventKind.DYNAMIC in item.subscription.policy.enabledEvents
        }
    }

    private fun Publisher.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private companion object {
        private const val SECONDS_PER_MINUTE: Long = 60L
    }
}
