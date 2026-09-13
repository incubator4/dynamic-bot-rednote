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
