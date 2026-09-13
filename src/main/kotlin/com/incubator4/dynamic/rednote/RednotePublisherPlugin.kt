package com.incubator4.dynamic.rednote

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.task.TaskScheduler

public class RednotePublisherPlugin private constructor(
    private val runtime: RednotePublisherRuntime,
) :
    PublisherSourcePlugin,
    PublisherLookupPlugin,
    PublisherLoginProvider,
    ConfigurablePlugin<RednotePublisherConfig> {

    public constructor() : this(RednotePublisherRuntime())

    internal constructor(
        loadConfig: (String) -> RednotePublisherConfig,
        gatewayFactory: (RednotePublisherConfig) -> RednoteGateway,
        saveConfig: (String, RednotePublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
        cursorStoreFactory: (() -> RednoteCursorStore)? = null,
        liveStatusStoreFactory: (() -> RednoteLiveStatusStore)? = null,
    ) : this(
        RednotePublisherRuntime(
            loadConfig = loadConfig,
            gatewayFactory = gatewayFactory,
            saveConfig = saveConfig,
            taskScheduler = taskScheduler,
            cursorStoreFactory = cursorStoreFactory,
            liveStatusStoreFactory = liveStatusStoreFactory,
        ),
    )

    override val platformId: PlatformId
        get() = runtime.platformId
    override val platformDescriptor: PlatformDescriptor
        get() = runtime.platformDescriptor

    override val configId: String
        get() = runtime.configId
    override val configName: String
        get() = runtime.configName
    override val configDescription: String
        get() = runtime.configDescription
    override val configClass = RednotePublisherConfig::class
    override val configFormSpec = RednotePublisherConfigForm.spec

    override val supportedLoginMethods: Set<PublisherLoginMethod>
        get() = runtime.supportedLoginMethods
    override val supportsCookieExport: Boolean
        get() = runtime.supportsCookieExport

    override suspend fun onLoad(context: PluginContext) {
        runtime.onLoad(context)
    }

    override suspend fun onStart() {
        runtime.onStart()
    }

    override suspend fun onStop() {
        runtime.onStop()
    }

    override suspend fun onUnload() {
        runtime.onUnload()
    }

    override fun currentConfig(): RednotePublisherConfig {
        return runtime.currentConfig()
    }

    override fun applyConfig(next: RednotePublisherConfig): ConfigApplyResult {
        return runtime.applyConfig(next)
    }

    override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
        return runtime.fetchPublisherInfo(userId)
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return runtime.checkLoginState()
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        return runtime.loginByCookie(cookie)
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return runtime.loginByQrCode(onQrCode, onStatusChanged)
    }

    override suspend fun exportCookie(): String? {
        return runtime.exportCookie()
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        runtime.onSubscriptionChanged(event)
    }
}
