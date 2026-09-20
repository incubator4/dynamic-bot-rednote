package com.incubator4.dynamic.rednote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.config.ConfigNumberKind
import top.colter.dynamic.core.plugin.PublisherLoginStatus

class RednoteAuthTest {
    @Test
    fun `cookie header and json inputs are normalized`() {
        val header = parseRednoteCookieInput("web_session=abc; a1=def; Path=/; domain=.xiaohongshu.com")
        assertEquals("web_session=abc; a1=def", header.header)
        assertTrue(header.has("web_session"))

        val array = parseRednoteCookieInput(
            """[{"name":"web_session","value":"from-json"},{"name":"a1","value":"token"}]""",
        )
        assertEquals("web_session=from-json; a1=token", array.header)

        val obj = parseRednoteCookieInput("""{"web_session":"map-session","a1":"map-a1"}""")
        assertEquals("web_session=map-session; a1=map-a1", obj.header)
        assertTrue(parseRednoteCookieInput("   ").isEmpty())

        val aliased = parseRednoteCookieInput("a1=token; web_session=abc; secure_session=sec")
        assertEquals("a1=token; web_session=abc; web_session_sec=sec", aliased.header)
        assertTrue(aliased.has("web_session_sec"))
        assertFalse(aliased.has("secure_session"))

        val cliJson = parseRednoteCookieInput(
            """{"a1":"cli-a1","web_session":"cli-sess","web_session_sec":"cli-sec","saved_at":1710000000.5}""",
        )
        assertEquals("a1=cli-a1; web_session=cli-sess; web_session_sec=cli-sec", cliJson.header)
        assertTrue(cliJson.missingRequiredLoginCookies().isEmpty())
        assertEquals(listOf("a1"), parseRednoteCookieInput("web_session=only").missingRequiredLoginCookies())
    }

    @Test
    fun `user me success maps account and rejects guest session`() {
        val loggedIn = parseRednoteUserMe(
            """
            {
              "code": 0,
              "success": true,
              "data": {
                "guest": false,
                "user_id": "5d0abc",
                "red_id": "123456",
                "nickname": "测试用户",
                "images": "https://example.com/avatar.png"
              }
            }
            """.trimIndent(),
        ).toLoginResult()

        assertEquals(PublisherLoginStatus.SUCCESS, loggedIn.status)
        assertEquals("5d0abc", loggedIn.account?.userId)
        assertEquals("测试用户", loggedIn.account?.name)
        assertEquals("https://example.com/avatar.png", loggedIn.account?.avatar?.uri)

        val guest = parseRednoteUserMe(
            """{"code":0,"success":true,"data":{"guest":true}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, guest.status)
        assertTrue(guest.message.contains("游客"))
        assertNull(guest.account)
    }

    @Test
    fun `user me login expiry and risk control are classified`() {
        val expired = parseRednoteUserMe(
            """{"code":-1,"success":false,"msg":"登录已过期"}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, expired.status)
        assertTrue(expired.message.contains("登录"))

        val blocked = parseRednoteUserMe(
            """{"code":300012,"success":false,"msg":"请求被风控拦截"}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, blocked.status)
        assertTrue(blocked.message.contains("风控"))
        assertTrue(looksLikeRiskControl(300012, "请求被风控拦截", httpStatus = 200))
        assertTrue(looksLikeRiskControl(null, "", httpStatus = 461))
    }

    @Test
    fun `guest identity follows xiaohongshu-cli hex layout`() {
        val guest = generateRednoteGuestIdentity(
            random = kotlin.random.Random(1),
            epochMillis = 1_729_214_251_341L,
        )
        assertEquals(52, guest.a1.length)
        assertTrue(guest.a1.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(guest.a1.contains("1729214251341"))
        assertEquals(32, guest.webId.length)
        assertTrue(guest.webId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `invalid cookie json fails with chinese message`() {
        val error = assertFailsWith<RednoteLoginException> {
            parseRednoteCookieInput("[not-json")
        }
        assertTrue(error.message!!.contains("Cookie JSON"))
    }
}

class RednotePublisherConfigFormTest {
    @Test
    fun `form should group visible settings and hide cookie`() {
        val fields = RednotePublisherConfigForm.spec.fields
        val sections = fields.groupBy { it.section }

        assertEquals(setOf("轮询与风控", "笔记与直播"), sections.keys)
        assertEquals(
            listOf(
                "pollingEnabled",
                "pollingIntervalSeconds",
                "requestIntervalSeconds",
                "replayWindowMinutes",
                "maxConsecutiveLoginFailures",
            ),
            sections.getValue("轮询与风控").map { it.path },
        )
        assertEquals(listOf("liveDetectionEnabled"), sections.getValue("笔记与直播").map { it.path })
        assertFalse(fields.any { it.path == "cookie" })
        assertTrue(fields.all { it.label.any { ch -> ch in '\u4e00'..'\u9fff' } })
    }

    @Test
    fun `form should keep restart and number constraints`() {
        val fields = RednotePublisherConfigForm.spec.fields.associateBy { it.path }

        assertTrue(fields.getValue("pollingEnabled").restartRequired)
        assertTrue(fields.getValue("pollingIntervalSeconds").restartRequired)
        assertTrue(fields.getValue("requestIntervalSeconds").restartRequired)
        assertEquals(60L, fields.getValue("pollingIntervalSeconds").min)
        assertEquals(1L, fields.getValue("requestIntervalSeconds").min)
        assertEquals(ConfigNumberKind.INTEGER, fields.getValue("replayWindowMinutes").numberKind)
        assertEquals(ConfigNumberKind.INTEGER, fields.getValue("maxConsecutiveLoginFailures").numberKind)
        assertEquals("直播检测", fields.getValue("liveDetectionEnabled").label)
        assertFalse(fields.getValue("liveDetectionEnabled").restartRequired)
    }

    @Test
    fun `validator should reject invalid values`() {
        RednotePublisherConfigForm.validate(RednotePublisherConfig())

        assertFailsWith<IllegalArgumentException> {
            RednotePublisherConfigForm.validate(RednotePublisherConfig(pollingIntervalSeconds = 59.0))
        }
        assertFailsWith<IllegalArgumentException> {
            RednotePublisherConfigForm.validate(RednotePublisherConfig(requestIntervalSeconds = 0.9))
        }
        assertFailsWith<IllegalArgumentException> {
            RednotePublisherConfigForm.validate(RednotePublisherConfig(replayWindowMinutes = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            RednotePublisherConfigForm.validate(RednotePublisherConfig(maxConsecutiveLoginFailures = -1))
        }
    }
}
