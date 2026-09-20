package com.incubator4.dynamic.rednote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.data.LiveStatus
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

    @Test
    fun `user posted notes are fetched from list endpoint`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var requestedUserId: String? = null
        var requestXs: String? = null
        server.createContext("/api/sns/web/v1/user_posted") { exchange ->
            requestedUserId = exchange.requestURI.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("user_id=") }
                ?.substringAfter("=")
            requestXs = exchange.requestHeaders.getFirst("X-s")
            val body = """
                {"code":0,"success":true,"data":{"notes":[{"note_id":"n1","display_title":"笔记","type":"normal","user":{"user_id":"64abc"}}],"has_more":false}}
            """.trimIndent()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val client = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=valid; a1=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV"),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                userPostedUri = URI.create("http://127.0.0.1:$port/api/sns/web/v1/user_posted"),
            )
            val page = client.fetchUserNotes("64abc")
            assertEquals("64abc", requestedUserId)
            assertEquals(listOf("n1"), page.notes.map { it.noteId })
            assertTrue(requestXs.orEmpty().startsWith("XYW_"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `live snapshot is fetched from user otherinfo`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var requestedUserId: String? = null
        var requestXs: String? = null
        server.createContext("/api/sns/web/v1/user/otherinfo") { exchange ->
            requestedUserId = exchange.requestURI.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("target_user_id=") }
                ?.substringAfter("=")
            requestXs = exchange.requestHeaders.getFirst("X-s")
            val body = """
                {"code":0,"success":true,"data":{"basic_info":{"user_id":"64abc"},"live":{"room_id":"54123","title":"直播中的房间","cover":"https://example.com/live.jpg"}}}
            """.trimIndent()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=valid; a1=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV"),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                userOtherInfoUri = URI.create("http://127.0.0.1:${server.address.port}/api/sns/web/v1/user/otherinfo"),
            )
            val live = client.fetchLiveSnapshot("64abc")
            assertEquals("64abc", requestedUserId)
            assertEquals("54123", live.roomId)
            assertEquals(LiveStatus.OPEN, live.status)
            assertEquals("直播中的房间", live.title)
            assertTrue(requestXs.orEmpty().startsWith("XYW_"))
        } finally {
            server.stop(0)
        }
    }
}
