package com.incubator4.dynamic.rednote

import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RednoteDynamicMapperTest {
    private val mapper = RednoteDynamicMapper()
    private val fallbackPublisher = Publisher(
        id = 1,
        key = PublisherKey.of(REDNOTE_PLATFORM_ID, PublisherKind.USER, "user1"),
        name = "测试用户",
        avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
        createTime = 1,
        createUser = 1,
    )

    @Test
    fun `map image note to text and image grid in original order`() {
        val update = mapper.map(
            testNote(
                noteId = "note-1",
                userId = "user1",
                createdAt = 1_700_000_000,
                title = "图文标题",
                desc = "正文 @测试用户 参与 #旅行[话题]# https://example.com/page",
                images = listOf(
                    RednoteImageSnapshot("https://example.com/1.jpg", width = 1080, height = 1440),
                    RednoteImageSnapshot("https://example.com/2.jpg"),
                ),
                coverUrl = "https://example.com/cover.jpg",
                isTop = true,
            ),
            fallbackPublisher,
        )

        assertNotNull(update)
        assertEquals("note-1", update.key.externalId)
        assertEquals("https://www.xiaohongshu.com/explore/note-1", update.link)
        val payload = assertIs<DynamicPayload>(update.payload)
        assertEquals("图文标题", payload.title)
        assertTrue(payload.labels.any { it.text == "置顶" && it.kind == DynamicLabelKind.BADGE })
        val text = assertIs<TextBlock>(payload.blocks.first())
        assertTrue(text.content.nodes.any { it is DynamicContentNodeMention && it.text == "@测试用户" })
        assertTrue(text.content.nodes.any { it is DynamicContentNodeTag && it.text.contains("旅行") })
        assertTrue(text.content.nodes.any { it is DynamicContentNodeLink && it.url == "https://example.com/page" })
        val images = assertIs<ImageGridBlock>(payload.blocks[1])
        assertEquals(listOf("https://example.com/1.jpg", "https://example.com/2.jpg"), images.images.map { it.image.uri })
    }

    @Test
    fun `map video note to large media card`() {
        val update = mapper.map(
            testNote(
                noteId = "video-1",
                userId = "user1",
                title = "视频标题",
                desc = "视频简介",
                type = RednoteNoteType.VIDEO,
                coverUrl = "https://example.com/cover.jpg",
                videoUrl = "https://example.com/video.mp4",
            ),
            fallbackPublisher,
        )

        assertNotNull(update)
        val payload = assertIs<DynamicPayload>(update.payload)
        assertTrue(payload.labels.any { it.text == "视频" })
        val card = assertIs<MediaCardBlock>(payload.blocks.single { it is MediaCardBlock })
        assertEquals(MediaCardStyle.LARGE, card.style)
        assertEquals(DynamicMediaCardKind.VIDEO, card.card.kind)
        assertEquals("https://example.com/video.mp4", card.card.mediaUri)
        assertEquals("https://example.com/cover.jpg", card.card.cover?.uri)
    }

    @Test
    fun `map list snapshot cover when images are missing`() {
        val update = mapper.map(
            testNote(
                noteId = "cover-only",
                userId = "user1",
                title = "只有封面",
                images = emptyList(),
                coverUrl = "https://example.com/cover.jpg",
            ),
            fallbackPublisher,
        )

        assertNotNull(update)
        val payload = assertIs<DynamicPayload>(update.payload)
        val images = assertIs<ImageGridBlock>(payload.blocks.single { it is ImageGridBlock })
        assertEquals("https://example.com/cover.jpg", images.images.single().image.uri)
    }
}
