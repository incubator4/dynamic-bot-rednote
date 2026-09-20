package com.incubator4.dynamic.rednote

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
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
        assertTrue(PublisherLoginMethod.QR_CODE in runtime.supportedLoginMethods)

        gateway.loginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "游客会话")
        val failed = runtime.loginByCookie("web_session=guest")
        assertEquals(PublisherLoginStatus.FAILED, failed.status)
        assertEquals("web_session=valid; a1=token", runtime.currentConfig().cookie)
    }

    @Test
    fun `empty cookie login stays failed`() = runBlocking {
        val runtime = RednotePublisherRuntime(
            loadConfig = { RednotePublisherConfig() },
            gatewayFactory = { RecordingRednoteGateway() },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val empty = runtime.loginByCookie("   ")
        assertEquals(PublisherLoginStatus.FAILED, empty.status)
        assertTrue(empty.message.contains("Cookie"))

        val missingA1 = runtime.loginByCookie("web_session=only")
        assertEquals(PublisherLoginStatus.FAILED, missingA1.status)
        assertTrue(missingA1.message.contains("a1"))
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

        val result = plugin.loginByCookie("web_session=ok; a1=token")
        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals(
            setOf(PublisherLoginMethod.COOKIE, PublisherLoginMethod.QR_CODE),
            plugin.supportedLoginMethods,
        )
        assertTrue(plugin.supportsCookieExport)
        assertEquals("web_session=ok", plugin.exportCookie())
    }
}

