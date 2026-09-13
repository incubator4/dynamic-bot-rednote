package com.incubator4.dynamic.rednote

import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.UpdateKey

internal class RednoteDynamicMapper {
    fun map(source: RednoteNoteSnapshot, fallbackPublisher: Publisher): SourceUpdate? {
        val noteId = source.noteId.trim().takeIf { it.isNotBlank() } ?: return null
        val publisher = buildPublisher(source, fallbackPublisher)
        val link = source.url?.takeIf { it.isNotBlank() } ?: noteLink(noteId, source.xsecToken)
        val blocks = buildBlocks(source, link)
        if (blocks.isEmpty()) return null
        return SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = SourceEventType.DYNAMIC_CREATED,
                externalId = noteId,
            ),
            publisher = publisher,
            occurredAtEpochSeconds = source.createdAtEpochSeconds.takeIf { it > 0 }
                ?: (System.currentTimeMillis() / 1000),
            observedAtEpochSeconds = System.currentTimeMillis() / 1000,
            link = link,
            payload = DynamicPayload(
                labels = buildLabels(source),
                title = source.title.takeIf { it.isNotBlank() },
                blocks = blocks,
                metrics = buildMetrics(source.metrics),
            ),
        )
    }

    private fun buildPublisher(source: RednoteNoteSnapshot, fallbackPublisher: Publisher): PublisherInfo {
        val fallback = fallbackPublisher.toInfo()
        val userId = source.userId.trim().takeIf { it.isNotBlank() } ?: fallback.externalId
        return fallback.copy(
            key = PublisherKey.of(
                platformId = REDNOTE_PLATFORM_ID,
                kind = PublisherKind.USER,
                externalId = userId,
            ),
            name = source.nickname?.takeIf { it.isNotBlank() } ?: fallback.name,
            avatar = source.avatarUrl.toMediaRef(MediaKind.AVATAR) ?: fallback.avatar,
        )
    }

    private fun buildLabels(source: RednoteNoteSnapshot): List<DynamicLabel> {
        return listOfNotNull(
            DynamicLabel("置顶", DynamicLabelKind.BADGE, "rednote.isTop").takeIf { source.isTop },
            DynamicLabel("视频", DynamicLabelKind.BADGE, "rednote.video")
                .takeIf { source.type == RednoteNoteType.VIDEO },
        )
    }

    private fun buildBlocks(
        source: RednoteNoteSnapshot,
        link: String,
    ): List<top.colter.dynamic.core.data.DynamicBlock> {
        return buildList {
            buildContent(source)?.let { content ->
                add(
                    TextBlock(
                        content = content,
                        link = link,
                        sourceKind = "rednote.text",
                    )
                )
            }
            if (source.type == RednoteNoteType.VIDEO) {
                source.toVideoCard(link)?.let(::add)
            }
            source.images
                .mapNotNull { it.toImageItem() }
                .ifEmpty {
                    source.coverUrl.toMediaRef(MediaKind.IMAGE)
                        ?.takeIf { source.type != RednoteNoteType.VIDEO }
                        ?.let { listOf(ImageItem(image = it)) }
                        .orEmpty()
                }
                .takeIf { it.isNotEmpty() }
                ?.let { images ->
                    add(
                        ImageGridBlock(
                            images = images,
                            link = link,
                            sourceKind = "rednote.image",
                        )
                    )
                }
        }
    }

    private fun buildContent(source: RednoteNoteSnapshot): DynamicContent? {
        val title = source.title.trim()
        val desc = source.desc.trim()
        val text = when {
            title.isNotEmpty() && desc.isNotEmpty() && desc != title -> "$title\n$desc"
            title.isNotEmpty() -> title
            desc.isNotEmpty() -> desc
            else -> ""
        }
        if (text.isEmpty()) return null
        return DynamicContent(text.scanPlainContentNodes())
    }

    private fun RednoteNoteSnapshot.toVideoCard(link: String): MediaCardBlock? {
        val resolvedTitle = title.trim().ifBlank { desc.trim() }.ifBlank { "小红书视频" }
        return MediaCardBlock(
            style = MediaCardStyle.LARGE,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.VIDEO,
                sourceKind = "rednote.video",
                id = noteId,
                title = resolvedTitle,
                description = desc.trim(),
                cover = (coverUrl ?: images.firstOrNull()?.url).toMediaRef(MediaKind.COVER),
                durationSeconds = videoDurationSeconds,
                mediaUri = videoUrl,
                link = url ?: link,
            ),
        )
    }

    private fun RednoteImageSnapshot.toImageItem(): ImageItem? {
        return ImageItem(
            image = url.toMediaRef(MediaKind.IMAGE) ?: return null,
            width = width,
            height = height,
        )
    }

    private fun buildMetrics(metrics: RednoteNoteMetrics): List<DynamicMetric> {
        return listOfNotNull(
            metrics.likes.toMetric("like"),
            metrics.comments.toMetric("comment"),
            metrics.collects.toMetric("collect"),
            metrics.shares.toMetric("share"),
        )
    }

    private fun Long?.toMetric(key: String): DynamicMetric? {
        val value = this ?: return null
        return DynamicMetric(
            key = key,
            raw = value,
            display = value.toDisplayCount(),
        )
    }

    private fun Long.toDisplayCount(): String {
        return when {
            this >= 100_000_000L -> "%.1f亿".format(this / 100_000_000.0).replace(".0", "")
            this >= 10_000L -> "%.1f万".format(this / 10_000.0).replace(".0", "")
            else -> toString()
        }
    }

    private fun String?.toMediaRef(kind: MediaKind): MediaRef? {
        val value = normalizeHttpUrl(this) ?: return null
        return MediaRef(uri = value, kind = kind)
    }

    private fun String.scanPlainContentNodes(): List<DynamicContentNode> {
        if (isEmpty()) return emptyList()
        val nodes = mutableListOf<DynamicContentNode>()
        var cursor = 0
        while (cursor < length) {
            val next = nextPlainToken(this, cursor)
            if (next == null) {
                nodes += DynamicContentNodeText(substring(cursor))
                break
            }
            if (next.start > cursor) {
                nodes += DynamicContentNodeText(substring(cursor, next.start))
            }
            nodes += when (next.kind) {
                PlainTokenKind.MENTION -> DynamicContentNodeMention(text = next.text)
                PlainTokenKind.TOPIC -> DynamicContentNodeTag(
                    text = next.text,
                    tagType = DynamicContentTagType.TOPIC,
                    externalId = next.text.trim('#').removeSuffix("[话题]").takeIf { it.isNotBlank() },
                )
                PlainTokenKind.URL -> DynamicContentNodeLink(
                    text = next.text,
                    url = next.text,
                )
            }
            cursor = next.end
        }
        return nodes.mergeAdjacentTextNodes()
    }

    private fun nextPlainToken(text: String, start: Int): PlainToken? {
        val mention = MENTION_REGEX.find(text, start)
        val topic = TOPIC_REGEX.find(text, start)
        val url = URL_REGEX.find(text, start)
        val match = listOfNotNull(
            mention?.let { PlainToken(PlainTokenKind.MENTION, it.range.first, it.range.last + 1, it.value) },
            topic?.let { PlainToken(PlainTokenKind.TOPIC, it.range.first, it.range.last + 1, it.value) },
            url?.let { PlainToken(PlainTokenKind.URL, it.range.first, it.range.last + 1, it.value) },
        ).minByOrNull { it.start }
        return match
    }

    private fun List<DynamicContentNode>.mergeAdjacentTextNodes(): List<DynamicContentNode> {
        if (isEmpty()) return this
        val merged = mutableListOf<DynamicContentNode>()
        forEach { node ->
            val previous = merged.lastOrNull()
            if (node is DynamicContentNodeText && previous is DynamicContentNodeText) {
                merged[merged.lastIndex] = DynamicContentNodeText(previous.text + node.text)
            } else {
                merged += node
            }
        }
        return merged
    }

    private data class PlainToken(
        val kind: PlainTokenKind,
        val start: Int,
        val end: Int,
        val text: String,
    )

    private enum class PlainTokenKind {
        MENTION,
        TOPIC,
        URL,
    }

    private companion object {
        private val TOPIC_REGEX: Regex = Regex("""#([^#\s\[\]]+)(?:\[话题\])?#""")
        private val MENTION_REGEX: Regex = Regex("""@([^\s@#]+)""")
        private val URL_REGEX: Regex = Regex("""https?://[^\s]+""")
    }
}
