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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RednoteClientTest {
    @Test
    fun `check login state reads account from user me endpoint`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var requestXs: String? = null
        server.createContext("/api/sns/web/v2/user/me") { exchange ->
            requestXs = exchange.requestHeaders.getFirst("X-s")
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
            assertTrue(requestXs.orEmpty().startsWith("XYW_"))

            val guest = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=guest; a1=token"),
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
    fun `cookie without a1 is rejected before user me`() = runBlocking {
        val result = RednoteClient(RednotePublisherConfig(cookie = "web_session=only")).checkLoginState()
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertTrue(result.message.contains("a1"))
    }

    @Test
    fun `qr login activate poll and complete persist web session cookies`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var createBody: String? = null
        var createXs: String? = null
        var userinfoTag: String? = null
        var userinfoXs: String? = null
        var createCookie: String? = null
        var pollCount = 0
        server.createContext("/api/sns/web/v1/login/activate") { exchange ->
            val body = """{"code":0,"success":true,"data":{"session":"guest-sess","secure_session":"guest-sec","user_id":"guest"}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/sns/web/v1/login/qrcode/create") { exchange ->
            createBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            createXs = exchange.requestHeaders.getFirst("X-s")
            createCookie = exchange.requestHeaders.getFirst("Cookie")
            val body = """{"code":0,"success":true,"data":{"qr_id":"qr-1","code":"384516","url":"https://www.xiaohongshu.com/mobile/login?qrId=qr-1"}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/qrcode/userinfo") { exchange ->
            pollCount += 1
            userinfoTag = exchange.requestHeaders.getFirst("service-tag")
            userinfoXs = exchange.requestHeaders.getFirst("X-s")
            val body = if (pollCount == 1) {
                """{"code":0,"success":true,"data":{"codeStatus":0}}"""
            } else {
                """{"code":0,"success":true,"data":{"codeStatus":2,"userId":"u1"}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/sns/web/v1/login/qrcode/status") { exchange ->
            val body = """{"code":0,"success":true,"data":{"code_status":2,"login_info":{"session":"real-sess","secure_session":"real-sec","user_id":"u1"}}}"""
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Set-Cookie", "web_session=real-sess; Path=/; Domain=.xiaohongshu.com")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/sns/web/v2/user/me") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val body = if (cookie.contains("web_session=real-sess")) {
                """{"code":0,"success":true,"data":{"guest":false,"user_id":"u1","nickname":"扫码用户"}}"""
            } else {
                """{"code":0,"success":true,"data":{"guest":true}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val client = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=old; a1=old-a1"),
                userMeUri = URI.create("http://127.0.0.1:$port/api/sns/web/v2/user/me"),
                qrActivateUri = URI.create("http://127.0.0.1:$port/api/sns/web/v1/login/activate"),
                qrCreateUri = URI.create("http://127.0.0.1:$port/api/sns/web/v1/login/qrcode/create"),
                qrUserInfoUri = URI.create("http://127.0.0.1:$port/api/qrcode/userinfo"),
                qrStatusUri = URI.create("http://127.0.0.1:$port/api/sns/web/v1/login/qrcode/status"),
            )
            val challenge = client.createQrLoginChallenge(nowEpochSeconds = 1_000)
            assertEquals("qr-1", challenge.qrId)
            assertTrue(createBody.orEmpty().contains("\"qr_type\":1"))
            assertTrue(createXs.orEmpty().startsWith("XYW_"))
            assertTrue(createCookie.orEmpty().contains("a1="))
            assertFalse(createCookie.orEmpty().contains("web_session=old"))

            val waiting = client.pollQrLoginStatus(challenge.qrId, challenge.code)
            assertEquals(RednoteQrCodeStatus.WAITING, waiting.resolveStatus())
            val confirmed = client.pollQrLoginStatus(challenge.qrId, challenge.code)
            assertEquals(RednoteQrCodeStatus.SUCCESS, confirmed.resolveStatus())
            assertEquals("u1", confirmed.confirmedUserId())
            assertEquals("webcn", userinfoTag)
            assertTrue(userinfoXs.orEmpty().startsWith("XYW_"))

            val completed = client.completeQrLogin(
                qrId = challenge.qrId,
                code = challenge.code,
                confirmedUserId = "u1",
                retryDelayMs = 1_000,
                delayMillis = {},
            )
            assertEquals("real-sess", completed.session)
            assertTrue(client.exportCookieHeader().contains("web_session=real-sess"))
            assertTrue(client.exportCookieHeader().contains("web_session_sec=real-sec"))

            val login = client.checkLoginState()
            assertEquals(PublisherLoginStatus.SUCCESS, login.status)
            assertEquals("扫码用户", login.account?.name)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `complete qr login waits until session cookies arrive`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var statusCount = 0
        server.createContext("/api/sns/web/v1/login/qrcode/status") { exchange ->
            statusCount += 1
            val body = if (statusCount == 1) {
                """{"code":0,"success":true,"data":{"code_status":2,"userId":"u1"}}"""
            } else {
                """{"code":0,"success":true,"data":{"code_status":2,"login_info":{"session":"real-sess","secure_session":"real-sec","user_id":"u1"}}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/sns/web/v2/user/me") { exchange ->
            val body = """{"code":0,"success":true,"data":{"guest":true}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val client = RednoteClient(
                config = RednotePublisherConfig(cookie = "web_session=guest; a1=token"),
                userMeUri = URI.create("http://127.0.0.1:$port/api/sns/web/v2/user/me"),
                qrStatusUri = URI.create("http://127.0.0.1:$port/api/sns/web/v1/login/qrcode/status"),
            )
            val completed = client.completeQrLogin(
                qrId = "qr-1",
                code = "384516",
                confirmedUserId = "u1",
                retries = 3,
                retryDelayMs = 1_000,
                delayMillis = {},
            )
            assertEquals(2, statusCount)
            assertEquals("real-sess", completed.session)
            assertEquals("real-sec", completed.secureSession)
            assertTrue(client.exportCookieHeader().contains("web_session=real-sess"))
        } finally {
            server.stop(0)
        }
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
