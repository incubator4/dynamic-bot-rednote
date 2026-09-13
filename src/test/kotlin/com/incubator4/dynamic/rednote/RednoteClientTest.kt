package com.incubator4.dynamic.rednote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RednoteClientTest {
    @Test
    fun `check login state reads account from user me endpoint`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/sns/web/v2/user/me") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val body = if (cookie.contains("web_session=valid")) {
                """{"code":0,"success":true,"data":{"guest":false,"user_id":"u1","nickname":"登录用户"}}"""
            } else {
                """{"code":0,"success":true,"data":{"guest":true}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val uri = URI.create("http://127.0.0.1:${server.address.port}/api/sns/web/v2/user/me")
            val httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val loggedIn = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=valid; a1=token"),
                httpClient = httpClient,
                userMeUri = uri,
            ).checkLoginState()
            assertEquals(PublisherLoginStatus.SUCCESS, loggedIn.status)
            assertEquals("u1", loggedIn.account?.userId)

            val guest = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=guest"),
                httpClient = httpClient,
                userMeUri = uri,
            ).checkLoginState()
            assertEquals(PublisherLoginStatus.FAILED, guest.status)
            assertTrue(guest.message.contains("游客"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing cookie does not request user me`() = runBlocking {
        val result = RednoteClient(RednotePublisherConfig(cookie = "   ")).checkLoginState()
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertEquals("小红书 Cookie 未配置", result.message)
    }

    @Test
    fun `html login page is treated as login failure`() {
        val result = RednoteClient(RednotePublisherConfig(cookie = "web_session=x"))
            .toLoginResult(200, "<!doctype html><html><title>登录</title></html>")
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertTrue(result.message.contains("登录状态不可用"))
    }
}
