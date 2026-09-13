package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RednotePublisherRuntimeAuthTest {
    @Test
    fun `cookie login persists cookie and restores previous cookie on failure`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            loginResult = PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "小红书登录状态可用",
                account = PublisherLoginAccount(userId = "u1", name = "测试用户"),
            ),
            exportedCookie = "web_session=valid; a1=token",
        )
        var savedConfig: RednotePublisherConfig? = null
        val runtime = RednotePublisherRuntime(
            loadConfig = { RednotePublisherConfig(cookie = "web_session=old") },
            gatewayFactory = { gateway },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val result = runtime.loginByCookie("web_session=valid; a1=token")

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("测试用户", result.account?.name)
        assertEquals("web_session=valid; a1=token", savedConfig?.cookie)
        assertEquals("web_session=valid; a1=token", runtime.exportCookie())
        assertTrue(PublisherLoginMethod.COOKIE in runtime.supportedLoginMethods)
        assertFalse(PublisherLoginMethod.QR_CODE in runtime.supportedLoginMethods)

        gateway.loginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "游客会话")
        val failed = runtime.loginByCookie("web_session=guest")
        assertEquals(PublisherLoginStatus.FAILED, failed.status)
        assertEquals("web_session=valid; a1=token", runtime.currentConfig().cookie)
    }

    @Test
    fun `empty cookie and qr login stay failed or unsupported`() = runBlocking {
        val runtime = RednotePublisherRuntime(
            loadConfig = { RednotePublisherConfig() },
            gatewayFactory = { RecordingRednoteGateway() },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val empty = runtime.loginByCookie("   ")
        assertEquals(PublisherLoginStatus.FAILED, empty.status)
        assertTrue(empty.message.contains("Cookie"))

        val qr = runtime.loginByQrCode(onQrCode = {}, onStatusChanged = {})
        assertEquals(PublisherLoginStatus.UNSUPPORTED, qr.status)
        assertTrue(qr.message.contains("二维码"))
    }

    @Test
    fun `json cookie login is accepted`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "小红书登录状态可用"),
            exportedCookie = "web_session=from-json; a1=token",
        )
        var savedConfig: RednotePublisherConfig? = null
        val runtime = RednotePublisherRuntime(
            loadConfig = { RednotePublisherConfig() },
            gatewayFactory = { gateway },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val result = runtime.loginByCookie(
            """[{"name":"web_session","value":"from-json"},{"name":"a1","value":"token"}]""",
        )

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("web_session=from-json; a1=token", savedConfig?.cookie)
    }

    @Test
    fun `startup login check does not pause polling`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "Cookie 已失效"),
        )
        val runtime = RednotePublisherRuntime(
            loadConfig = {
                RednotePublisherConfig(
                    pollingEnabled = true,
                    maxConsecutiveLoginFailures = 1,
                    cookie = "web_session=expired",
                )
            },
            gatewayFactory = { gateway },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())
        runtime.onStart()
        repeat(2) { runtime.checkLoginState() }

        assertEquals(3, gateway.loginCheckCount)
        assertFalse(runtime.isPollingPaused())
    }

    @Test
    fun `plugin delegates cookie login to runtime`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "小红书登录状态可用"),
            exportedCookie = "web_session=ok",
        )
        val plugin = RednotePublisherPlugin(
            loadConfig = { RednotePublisherConfig() },
            gatewayFactory = { gateway },
            taskScheduler = ManualTaskScheduler(),
        )
        plugin.onLoad(testContext())

        val result = plugin.loginByCookie("web_session=ok")
        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals(setOf(PublisherLoginMethod.COOKIE), plugin.supportedLoginMethods)
        assertTrue(plugin.supportsCookieExport)
        assertEquals("web_session=ok", plugin.exportCookie())
    }
}

private class RecordingRednoteGateway(
    var loginResult: PublisherLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "登录成功"),
    private val exportedCookie: String = "",
) : RednoteGateway {
    var loginCheckCount: Int = 0
        private set

    override fun exportCookie(): String = exportedCookie

    override suspend fun checkLoginState(): PublisherLoginResult {
        loginCheckCount += 1
        return loginResult
    }
}

private fun testContext(
    notificationPublisher: SystemNotificationPublisher = SystemNotificationPublisher {
        SystemNotificationPublishResult.accepted()
    },
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
        taskScheduler = ManualTaskScheduler(),
        sourceUpdatePublisher = SourceUpdatePublisher {
            SourceUpdatePublishResult.ignored("test")
        },
        sourceStateStore = DummySourceStateStore,
        subscriptionQueryService = DummySubscriptionQueryService,
        notificationPublisher = notificationPublisher,
    )
}

private class ManualTaskScheduler : TaskScheduler {
    private val tasks: MutableMap<String, TaskDefinition> = linkedMapOf()
    private val running: MutableSet<String> = linkedSetOf()

    override fun start(task: TaskDefinition): Boolean {
        tasks[task.id] = task
        return running.add(task.id)
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

private object DummyConfigService : ConfigService {
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

private object DummyPluginDataStore : PluginDataStore {
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

private object DummySourceStateStore : SourceStateStore {
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

private object DummySubscriptionQueryService : SubscriptionQueryService {
    override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? = null

    override fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): List<PublisherSubscribers> {
        return emptyList()
    }
}
