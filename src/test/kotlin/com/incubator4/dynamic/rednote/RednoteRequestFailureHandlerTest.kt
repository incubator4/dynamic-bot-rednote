package com.incubator4.dynamic.rednote

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RednoteRequestFailureHandlerTest {
    @Test
    fun `pause polling after consecutive login failures and recover after success`() = runBlocking {
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        val handler = RednoteRequestFailureHandler(
            configProvider = { RednotePublisherConfig(maxConsecutiveLoginFailures = 2) },
            notificationPublisher = SystemNotificationPublisher { request ->
                notifications += request
                SystemNotificationPublishResult.accepted()
            },
        )

        handler.run("小红书登录检查") {
            throw RednoteLoginException("Cookie 已失效")
        }
        assertFalse(handler.isPollingPaused())

        handler.run("小红书登录检查") {
            throw RednoteLoginException("Cookie 已失效")
        }
        assertTrue(handler.isPollingPaused())
        assertEquals("rednote.login_paused", notifications.single().type)

        handler.run("小红书登录检查") { "ok" }
        assertFalse(handler.isPollingPaused())
        assertEquals("rednote.login_recovered", notifications.last().type)
    }

    @Test
    fun `risk control pauses polling immediately`() = runBlocking {
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        val handler = RednoteRequestFailureHandler(
            configProvider = { RednotePublisherConfig(maxConsecutiveLoginFailures = 3) },
            notificationPublisher = SystemNotificationPublisher { request ->
                notifications += request
                SystemNotificationPublishResult.accepted()
            },
        )

        handler.run("小红书登录检查") {
            throw RednoteBlockedException("请求被风控拦截")
        }

        assertTrue(handler.isPollingPaused())
        assertEquals("rednote.risk_paused", notifications.single().type)
    }
}
