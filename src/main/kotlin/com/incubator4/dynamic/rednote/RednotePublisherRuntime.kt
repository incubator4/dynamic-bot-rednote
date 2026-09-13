package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor

private const val DEFAULT_PLUGIN_ID: String = "rednote-publisher"

private val logger = loggerFor<RednotePublisherRuntime>()

internal class RednotePublisherRuntime() :
    PublisherSourcePlugin,
    PublisherLoginProvider,
    ConfigurablePlugin<RednotePublisherConfig> {

    private var pluginId: String = DEFAULT_PLUGIN_ID

    override val platformId: PlatformId = PlatformId.of(REDNOTE_PLATFORM_ID)
    val platformDescriptor: PlatformDescriptor = PlatformDescriptor.of(
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

    private var useContextConfigService: Boolean = true
    private var useContextTaskScheduler: Boolean = true

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var config: RednotePublisherConfig
    private lateinit var gateway: RednoteGateway
    private lateinit var requestFailureHandler: RednoteRequestFailureHandler

    internal constructor(
        loadConfig: (String) -> RednotePublisherConfig,
        gatewayFactory: (RednotePublisherConfig) -> RednoteGateway,
        saveConfig: (String, RednotePublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
    ) : this() {
        this.loadConfig = loadConfig
        this.gatewayFactory = gatewayFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        useContextConfigService = false
        useContextTaskScheduler = false
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(PublisherLoginMethod.COOKIE)
    override val supportsCookieExport: Boolean = true

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        if (useContextTaskScheduler) {
            taskScheduler = context.taskScheduler
        }
        if (useContextConfigService) {
            loadConfig = { id -> context.configService.loadOrCreate(id) { RednotePublisherConfig() } }
            saveConfig = { id, next -> context.configService.save(id, next) }
        }

        config = loadConfig(pluginId)
        RednotePublisherConfigForm.validate(config)
        gateway = gatewayFactory(config)
        requestFailureHandler = RednoteRequestFailureHandler(
            configProvider = { config },
            notificationPublisher = context.notificationPublisher,
        )
        logger.info { "小红书插件已加载：pluginId=$pluginId，轮询启用=${config.pollingEnabled}" }
    }

    override suspend fun onStart() {
        val initial = checkLoginState()
        if (initial.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("小红书启动登录状态检查")
            logger.info {
                "小红书登录状态可用：账号=${initial.account?.name ?: initial.account?.userId ?: "未知"}"
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

    internal fun isPollingPaused(): Boolean {
        return ::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()
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
}
