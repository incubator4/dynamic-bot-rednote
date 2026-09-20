package com.incubator4.dynamic.rednote

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RednoteQrLoginTest {
    @Test
    fun `web sign matches known vectors`() {
        val create = buildRednoteWebSign(
            uri = REDNOTE_QR_CREATE_URI,
            jsonBody = "{}",
            a1 = "abc123",
            epochMillis = 1_729_214_251_341L,
        )
        assertEquals("OgMC1BTl0g4BZ21Wsg1i1gqBsgTlOjvGsjdk0jT+sBs3", create.xS)
        assertEquals("1729214251341", create.xT)
        assertEquals(
            "2UQAPsHC+aIjqArjwjHjNsQhPsHCH0rjNsQhPaHCH0P1PjhIHjIj2eHjwjQgynEDJ74AHjIj2ePjwjQhyoPTqBPT49pjHjIj2ecjwjHUN0P1PaHVHdWMH0ijGnQ0P/HAHjIj2eGjwjHl+AHEP0rFP0LlPAclHjIj2eqjwjQO8FMePLQLJemd+rQyP0bgq9qly/bdqLQA8MzV/9k9z7+x8BVIySc3qFQAPUHVHdWhH0ijHjIj2eDjwjFl+APlP0PI+eP7NsQhP/Zjw0bR",
            create.xSCommon,
        )

        val status = buildRednoteWebSign(
            uri = "$REDNOTE_QR_STATUS_URI?qr_id=1&code=2",
            jsonBody = null,
            a1 = "abc123",
            epochMillis = 1_729_214_251_341L,
        )
        assertEquals("1lZUZYq6sgsKsgFKOBMbOiTCOB5C0jO61gFb025ps653", status.xS)
    }

    @Test
    fun `xyw sign matches xhshow vectors for data apis`() {
        val content = buildRednoteGetContentString(
            REDNOTE_USER_POSTED_URI,
            linkedMapOf(
                "num" to "30",
                "cursor" to "",
                "user_id" to "64abc",
                "image_formats" to "jpg,webp,avif",
            ),
        )
        assertEquals(
            "/api/sns/web/v1/user_posted?num=30&cursor=&user_id=64abc&image_formats=jpg,webp,avif",
            content,
        )

        val payloadHex = buildXywPayloadHex(
            fullUri = content,
            a1 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV",
            timestampMs = "1729214251341",
        )
        assertEquals(
            "543e927a2f022aaea44bbdfbdfc67d0d7eb5b0d74f9ce58c5a15806619b82845bd2566223748726fefdf6601ef4eda6e1bd24464640db6e4766b0f35afede5f52271b9b997932456f37ab3b5ab8af6ab84c0da5c007de9041fa7c86ad682db2a3cb4a837fc5b149f183603d6b0437044f844a6548e7c53b4825d88cb42a004e6de7b023917a315a54e4005107c440766c58b7670a317921d318ab5cd04fc43245b2d353aadd1c7ae9a3a058a7a761d0801110216ff1b1ac285f8b847105aefb9f3e1cc0954dd8ccea7add5738d3b4e63",
            payloadHex,
        )

        val signed = buildRednoteXywSign(
            contentString = content,
            a1 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV",
            epochMillis = 1_729_214_251_341L,
        )
        assertEquals("1729214251341", signed.xT)
        assertEquals(
            "XYW_eyJzaWduU3ZuIjoiNTYiLCJzaWduVHlwZSI6IngyIiwiYXBwSWQiOiJ4aHMtcGMtd2ViIiwic2lnblZlcnNpb24iOiIxIiwicGF5bG9hZCI6IjU0M2U5MjdhMmYwMjJhYWVhNDRiYmRmYmRmYzY3ZDBkN2ViNWIwZDc0ZjljZTU4YzVhMTU4MDY2MTliODI4NDViZDI1NjYyMjM3NDg3MjZmZWZkZjY2MDFlZjRlZGE2ZTFiZDI0NDY0NjQwZGI2ZTQ3NjZiMGYzNWFmZWRlNWY1MjI3MWI5Yjk5NzkzMjQ1NmYzN2FiM2I1YWI4YWY2YWI4NGMwZGE1YzAwN2RlOTA0MWZhN2M4NmFkNjgyZGIyYTNjYjRhODM3ZmM1YjE0OWYxODM2MDNkNmIwNDM3MDQ0Zjg0NGE2NTQ4ZTdjNTNiNDgyNWQ4OGNiNDJhMDA0ZTZkZTdiMDIzOTE3YTMxNWE1NGU0MDA1MTA3YzQ0MDc2NmM1OGI3NjcwYTMxNzkyMWQzMThhYjVjZDA0ZmM0MzI0NWIyZDM1M2FhZGQxYzdhZTlhM2EwNThhN2E3NjFkMDgwMTExMDIxNmZmMWIxYWMyODVmOGI4NDcxMDVhZWZiOWYzZTFjYzA5NTRkZDhjY2VhN2FkZDU3MzhkM2I0ZTYzIn0=",
            signed.xS,
        )
        assertTrue(signed.xSCommon.isNotBlank())
    }

    @Test
    fun `qr challenge and status parsing`() {
        val challenge = parseRednoteQrChallenge(
            """
            {"code":0,"success":true,"msg":"成功","data":{
              "url":"https://www.xiaohongshu.com/mobile/login?qrId=1&xhs_code=2",
              "qr_id":"632031729213436637",
              "code":"384516"
            }}
            """.trimIndent(),
            nowEpochSeconds = 1_000,
        )
        assertEquals("632031729213436637", challenge.qrId)
        assertEquals("384516", challenge.code)
        assertEquals(1_000 + REDNOTE_QR_EXPIRES_SECONDS, challenge.expiresAtEpochSeconds)

        val waiting = parseRednoteQrStatus(
            """{"code":0,"success":true,"data":{"code_status":0}}""",
        )
        assertEquals(RednoteQrCodeStatus.WAITING, waiting.resolveStatus())

        val scanned = parseRednoteQrStatus(
            """{"code":0,"success":true,"data":{"code_status":1}}""",
        )
        assertEquals(RednoteQrCodeStatus.SCANNED, scanned.resolveStatus())

        val success = parseRednoteQrStatus(
            """
            {"code":0,"success":true,"data":{"code_status":2,"login_info":{
              "session":"sess-1","secure_session":"secure-1","user_id":"u-9"
            }}}
            """.trimIndent(),
        )
        assertEquals(RednoteQrCodeStatus.SUCCESS, success.resolveStatus())
        assertEquals(
            mapOf("web_session" to "sess-1", "secure_session" to "secure-1"),
            success.loginInfo?.toCookiePairs(),
        )
    }

    @Test
    fun `qr login runner polls until success`() = runBlocking {
        val statuses = mutableListOf(
            RednoteQrStatusSnapshot(code = 0, success = true, codeStatus = 0),
            RednoteQrStatusSnapshot(code = 0, success = true, codeStatus = 1),
            RednoteQrStatusSnapshot(
                code = 0,
                success = true,
                codeStatus = 2,
                loginInfo = RednoteQrLoginInfo(session = "sess", userId = "u1"),
            ),
        )
        val applied = mutableListOf<String>()
        var clock = 0L
        val challenges = mutableListOf<PublisherQrLoginChallenge>()
        val updates = mutableListOf<PublisherLoginStatus>()

        val result = runRednoteQrLogin(
            onQrCode = { challenges += it },
            onStatusChanged = { updates += it.status },
            createChallenge = {
                RednoteQrCodeChallenge(
                    qrId = "qr-1",
                    code = "code-1",
                    url = "https://www.xiaohongshu.com/mobile/login?qrId=qr-1",
                    expiresAtEpochSeconds = 2_000,
                )
            },
            pollStatus = { _, _ -> statuses.removeAt(0) },
            applyLoginInfo = { info -> info.session?.let(applied::add) },
            verifyLogin = {
                PublisherLoginResult(
                    status = PublisherLoginStatus.SUCCESS,
                    message = "小红书登录状态可用",
                    account = PublisherLoginAccount(userId = "u1", name = "用户"),
                )
            },
            pollIntervalMs = 1_000,
            timeoutMs = 10_000,
            delayMillis = { clock += it },
            nowMillis = { clock },
        )

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals(listOf("sess"), applied)
        assertEquals(1, challenges.size)
        assertTrue(updates.contains(PublisherLoginStatus.PENDING))
        assertEquals("用户", result.account?.name)
    }

    @Test
    fun `qr login runner expires when timeout elapses`() = runBlocking {
        var clock = 0L
        val result = runRednoteQrLogin(
            onQrCode = {},
            onStatusChanged = {},
            createChallenge = {
                RednoteQrCodeChallenge(
                    qrId = "qr-timeout",
                    code = "code-timeout",
                    url = "https://www.xiaohongshu.com/mobile/login?qrId=qr-timeout",
                    expiresAtEpochSeconds = 2_000,
                )
            },
            pollStatus = { _, _ ->
                RednoteQrStatusSnapshot(code = 0, success = true, codeStatus = 0)
            },
            applyLoginInfo = {},
            verifyLogin = { error("should not verify") },
            pollIntervalMs = 1_000,
            timeoutMs = 2_500,
            delayMillis = { clock += it },
            nowMillis = { clock },
        )
        assertEquals(PublisherLoginStatus.EXPIRED, result.status)
        assertTrue(result.message.contains("过期"))
    }

    @Test
    fun `qr login runner reports failed when session verify fails`() = runBlocking {
        val updates = mutableListOf<PublisherLoginResult>()
        val result = runRednoteQrLogin(
            onQrCode = {},
            onStatusChanged = { updates += it },
            createChallenge = {
                RednoteQrCodeChallenge(
                    qrId = "qr-2",
                    code = "code-2",
                    url = "https://www.xiaohongshu.com/mobile/login?qrId=qr-2",
                    expiresAtEpochSeconds = 2_000,
                )
            },
            pollStatus = { _, _ ->
                RednoteQrStatusSnapshot(
                    code = 0,
                    success = true,
                    codeStatus = 2,
                    loginInfo = RednoteQrLoginInfo(session = "bad", userId = "u1"),
                )
            },
            applyLoginInfo = {},
            verifyLogin = {
                PublisherLoginResult(PublisherLoginStatus.FAILED, "游客会话")
            },
            pollIntervalMs = 1_000,
            timeoutMs = 5_000,
            delayMillis = {},
            nowMillis = { 0L },
        )
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertTrue(result.message.contains("游客") || result.message.contains("校验"))
        assertTrue(updates.any { it.status == PublisherLoginStatus.FAILED })
    }

    @Test
    fun `runtime qr login persists cookie and supports qr method`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            loginResult = PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "小红书登录状态可用",
                account = PublisherLoginAccount(userId = "u1", name = "扫码用户"),
            ),
            exportedCookie = "web_session=from-qr; a1=guest",
            qrLoginResult = PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "小红书登录状态可用",
                account = PublisherLoginAccount(userId = "u1", name = "扫码用户"),
            ),
            qrChallenge = PublisherQrLoginChallenge(
                qrContent = "https://www.xiaohongshu.com/mobile/login?qrId=1",
                instruction = "请使用小红书 App 扫码并确认登录",
            ),
            qrStatusUpdates = listOf(
                PublisherLoginResult(PublisherLoginStatus.PENDING, "等待小红书 App 扫码"),
                PublisherLoginResult(PublisherLoginStatus.PENDING, "已扫码，请在手机上确认登录"),
            ),
        )
        var savedConfig: RednotePublisherConfig? = null
        val runtime = RednotePublisherRuntime(
            loadConfig = { RednotePublisherConfig(cookie = "web_session=old") },
            gatewayFactory = { gateway },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val challenges = mutableListOf<PublisherQrLoginChallenge>()
        val statuses = mutableListOf<PublisherLoginResult>()
        val result = runtime.loginByQrCode(
            onQrCode = { challenges += it },
            onStatusChanged = { statuses += it },
        )

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("扫码用户", result.account?.name)
        assertEquals("web_session=from-qr; a1=guest", savedConfig?.cookie)
        assertEquals(1, challenges.size)
        assertTrue(challenges.single().qrContent!!.contains("qrId=1"))
        assertEquals(2, statuses.size)
        assertTrue(PublisherLoginMethod.QR_CODE in runtime.supportedLoginMethods)
        assertTrue(PublisherLoginMethod.COOKIE in runtime.supportedLoginMethods)
        assertEquals(1, gateway.qrLoginCount)
    }

    @Test
    fun `runtime qr login failure restores previous cookie`() = runBlocking {
        val gateway = RecordingRednoteGateway(
            qrLoginResult = PublisherLoginResult(
                status = PublisherLoginStatus.EXPIRED,
                message = "小红书登录二维码已过期，请重新获取",
            ),
        )
        val runtime = RednotePublisherRuntime(
            loadConfig = { RednotePublisherConfig(cookie = "web_session=keep-me") },
            gatewayFactory = { gateway },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val result = runtime.loginByQrCode(onQrCode = {}, onStatusChanged = {})
        assertEquals(PublisherLoginStatus.EXPIRED, result.status)
        assertEquals("web_session=keep-me", runtime.currentConfig().cookie)
    }
}
