package com.incubator4.dynamic.rednote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.LiveStatus

class RednoteParsersTest {
    @Test
    fun `parse publisher otherinfo payload`() {
        val snapshot = parseRednotePublisher(
            """
            {
              "code": 0,
              "success": true,
              "data": {
                "basic_info": {
                  "user_id": "64abc",
                  "nickname": "测试博主",
                  "red_id": "123456",
                  "images": "https://example.com/avatar.png",
                  "banner_image": "https://example.com/banner.png",
                  "desc": "简介"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("64abc", snapshot?.userId)
        assertEquals("测试博主", snapshot?.nickname)
        assertEquals("123456", snapshot?.redId)
        assertEquals("https://example.com/avatar.png", snapshot?.avatarUrl)
        assertEquals("https://example.com/banner.png", snapshot?.bannerUrl)
        assertEquals("简介", snapshot?.description)
    }

    @Test
    fun `parse user posted notes page`() {
        val page = parseRednoteUserNotesPage(
            """
            {
              "code": 0,
              "success": true,
              "data": {
                "cursor": "next",
                "has_more": true,
                "notes": [
                  {
                    "note_id": "n1",
                    "display_title": "第一篇",
                    "type": "normal",
                    "sticky": true,
                    "xsec_token": "tok",
                    "user": { "user_id": "64abc", "nickname": "博主", "avatar": "https://example.com/a.png" },
                    "cover": { "url": "https://example.com/cover.jpg", "width": 1080, "height": 1440 },
                    "interact_info": { "liked_count": "1.2万", "comment_count": "12", "collected_count": "3", "share_count": "1" }
                  },
                  {
                    "note_id": "n2",
                    "display_title": "视频",
                    "type": "video",
                    "user": { "user_id": "64abc" }
                  }
                ]
              }
            }
            """.trimIndent(),
            fallbackUserId = "64abc",
        )

        assertTrue(page.hasMore)
        assertEquals("next", page.cursor)
        assertEquals(listOf("n1", "n2"), page.notes.map { it.noteId })
        assertEquals("第一篇", page.notes[0].title)
        assertTrue(page.notes[0].isTop)
        assertEquals(12_000L, page.notes[0].metrics.likes)
        assertEquals(RednoteNoteType.VIDEO, page.notes[1].type)
        assertEquals("https://www.xiaohongshu.com/explore/n1?xsec_token=tok&xsec_source=pc_user", page.notes[0].url)
    }

    @Test
    fun `parse note detail feed payload`() {
        val fallback = testNote("n1", "64abc", title = "列表标题")
        val detailed = parseRednoteNoteDetail(
            """
            {
              "code": 0,
              "success": true,
              "data": {
                "items": [
                  {
                    "id": "n1",
                    "note_card": {
                      "note_id": "n1",
                      "type": "video",
                      "title": "详情标题",
                      "desc": "详情正文 #旅行[话题]#",
                      "time": 1700000000,
                      "user": { "user_id": "64abc", "nickname": "博主" },
                      "image_list": [
                        { "url": "https://example.com/1.jpg", "width": 1080, "height": 1440 }
                      ],
                      "video": {
                        "capa": { "duration": 12 },
                        "media": { "stream": { "h264": [ { "master_url": "https://example.com/v.mp4" } ] } }
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent(),
            fallback,
        )

        assertEquals("详情标题", detailed.title)
        assertEquals("详情正文 #旅行[话题]#", detailed.desc)
        assertEquals(1_700_000_000L, detailed.createdAtEpochSeconds)
        assertEquals(RednoteNoteType.VIDEO, detailed.type)
        assertEquals("https://example.com/v.mp4", detailed.videoUrl)
        assertEquals(12L, detailed.videoDurationSeconds)
        assertEquals("https://example.com/1.jpg", detailed.images.single().url)
    }

    @Test
    fun `login and risk control json throw typed errors`() {
        val login = assertFailsWith<RednoteLoginException> {
            parseRednoteSuccessData(
                """{"code":-100,"success":false,"msg":"登录已过期"}""",
                "invalid",
            )
        }
        assertTrue(login.message!!.contains("登录"))

        val blocked = assertFailsWith<RednoteBlockedException> {
            parseRednoteSuccessData(
                """{"code":300012,"success":false,"msg":"请求被风控拦截"}""",
                "invalid",
            )
        }
        assertTrue(blocked.message!!.contains("风控"))
    }

    @Test
    fun `missing publisher id returns null`() {
        assertNull(
            parseRednotePublisher("""{"code":0,"success":true,"data":{"basic_info":{"nickname":"无ID"}}}"""),
        )
    }

    @Test
    fun `count parser understands wan suffix`() {
        assertEquals(12_000L, parseRednoteCount("1.2万"))
        assertEquals(3L, parseRednoteCount("3"))
        assertFalse(parseRednoteCount(" ").let { it != null })
    }

    @Test
    fun `parse live snapshot from otherinfo live object`() {
        val live = parseRednoteLiveSnapshot(
            """
            {
              "code": 0,
              "success": true,
              "data": {
                "basic_info": { "user_id": "64abc", "nickname": "博主" },
                "live": {
                  "user_id": "64abc",
                  "room_id": "54123",
                  "live_link": "xhsdiscover://live_audience?room_id=54123",
                  "title": "晚上好",
                  "cover": "https://example.com/live.jpg",
                  "start_time": 1700000000
                }
              }
            }
            """.trimIndent(),
            fallbackUserId = "64abc",
        )

        assertEquals("64abc", live.userId)
        assertEquals("54123", live.roomId)
        assertEquals(LiveStatus.OPEN, live.status)
        assertEquals("晚上好", live.title)
        assertEquals("https://example.com/live.jpg", live.coverUrl)
        assertEquals(1_700_000_000L, live.startedAtEpochSeconds)
    }

    @Test
    fun `missing live object is treated as closed`() {
        val live = parseRednoteLiveSnapshot(
            """{"code":0,"success":true,"data":{"basic_info":{"user_id":"64abc"}}}""",
            fallbackUserId = "64abc",
        )
        assertEquals(LiveStatus.CLOSE, live.status)
        assertEquals("64abc", live.userId)
        assertEquals("", live.roomId)
    }

    @Test
    fun `live room id can be parsed from livestream url`() {
        assertEquals("54123", parseRednoteLiveRoomId("https://www.xiaohongshu.com/livestream/54123"))
        assertEquals("54123", parseRednoteLiveRoomId("xhsdiscover://live_audience?room_id=54123"))
        assertEquals(1_700_000_000L, parseRednoteEpochSeconds(1_700_000_000_000L))
    }
}
