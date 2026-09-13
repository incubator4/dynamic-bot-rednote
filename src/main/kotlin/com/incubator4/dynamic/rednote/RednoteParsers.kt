package com.incubator4.dynamic.rednote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.data.LiveStatus

internal fun parseRednotePublisher(json: String): RednotePublisherSnapshot? {
    val data = parseRednoteSuccessData(json, "小红书用户资料响应不是有效 JSON")
    val basic = data.obj("basic_info", "user") ?: data
    val userId = basic.string("user_id", "userid", "userId") ?: data.string("user_id", "userid", "userId")
    if (userId.isNullOrBlank()) return null
    return RednotePublisherSnapshot(
        userId = userId,
        nickname = basic.string("nickname", "nick_name", "name") ?: "小红书用户 $userId",
        avatarUrl = firstHttpUrl(
            basic.string("imageb", "images", "avatar"),
            data.string("imageb", "images", "avatar"),
        ),
        bannerUrl = firstHttpUrl(
            basic.string("banner_image", "banner", "background_image"),
        ),
        redId = basic.string("red_id", "redId"),
        description = basic.string("desc", "description"),
    )
}

internal fun parseRednoteLiveSnapshot(json: String, fallbackUserId: String): RednoteLiveSnapshot {
    val data = parseRednoteSuccessData(json, "小红书直播状态响应不是有效 JSON")
    val live = data.obj("live", "live_info", "user_live") ?: data.obj("live_room")
    val userId = live?.string("user_id", "userid", "userId")
        ?: data.obj("basic_info", "user")?.string("user_id", "userid", "userId")
        ?: data.string("user_id", "userid", "userId")
        ?: fallbackUserId
    if (live == null) {
        return RednoteLiveSnapshot(userId = userId)
    }

    val room = live.obj("room", "live_room")
    val roomId = firstNonBlank(
        live.string("room_id", "roomId", "live_id", "liveId"),
        room?.string("room_id", "roomId", "id"),
        parseRednoteLiveRoomId(live.string("live_link", "link", "url", "jump_url")),
        parseRednoteLiveRoomId(room?.string("live_link", "link", "url")),
    ).orEmpty()
    val living = live.boolean("has_living", "is_living", "living", "hasLive", "has_live")
        ?: room?.boolean("has_living", "is_living", "living")
        ?: live.long("status", "live_status", "liveStatus")?.let { it == 1L }
        ?: room?.long("status", "live_status")?.let { it == 1L }
        ?: roomId.isNotBlank()
    val resolvedRoomId = roomId.ifBlank { userId.takeIf { living }.orEmpty() }
    val title = firstNonBlank(
        live.string("title", "room_name", "display_title"),
        room?.string("title", "name", "room_name"),
    )?.takeUnless { it == "直播中" }.orEmpty()
    val coverUrl = firstHttpUrl(
        live.string("cover", "cover_url", "image", "cover_image"),
        parseCoverUrl(live.obj("cover") ?: room?.obj("cover")),
        room?.string("cover", "cover_url", "image"),
    )
    val area = firstNonBlank(
        live.string("area", "category", "partition"),
        room?.string("area", "category"),
    )
    val startedAt = parseRednoteEpochSeconds(
        live.long("start_time", "started_at", "live_start_time", "time", "startTime")
            ?: room?.long("start_time", "started_at", "time"),
    )
    return RednoteLiveSnapshot(
        userId = userId,
        roomId = resolvedRoomId,
        status = if (living) LiveStatus.OPEN else LiveStatus.CLOSE,
        title = title,
        coverUrl = coverUrl,
        area = area,
        startedAtEpochSeconds = startedAt.takeIf { living },
    )
}

internal fun parseRednoteLiveRoomId(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (value.matches(Regex("""[A-Za-z0-9]+"""))) return value
    Regex("""(?:room_id|roomId)=([^&/?#]+)""")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return Regex("""/(?:livestream|live_room|live)/([^/?#]+)""")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal fun parseRednoteEpochSeconds(raw: Long?): Long? {
    val value = raw ?: return null
    if (value <= 0L) return null
    return if (value > 10_000_000_000L) value / 1_000L else value
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

internal fun parseRednoteUserNotesPage(json: String, fallbackUserId: String): RednoteUserNotesPage {
    val data = parseRednoteSuccessData(json, "小红书用户笔记响应不是有效 JSON")
    val notes = (data.array("notes", "items") ?: JsonArray(emptyList()))
        .mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseRednoteNote(obj, fallbackUserId)
        }
    return RednoteUserNotesPage(
        notes = notes,
        cursor = data.string("cursor"),
        hasMore = data.boolean("has_more") == true,
    )
}

internal fun parseRednoteNoteDetail(json: String, fallback: RednoteNoteSnapshot): RednoteNoteSnapshot {
    val data = parseRednoteSuccessData(json, "小红书笔记详情响应不是有效 JSON")
    val items = data.array("items") ?: JsonArray(emptyList())
    val card = items.firstNotNullOfOrNull { item ->
        val obj = item as? JsonObject ?: return@firstNotNullOfOrNull null
        obj.obj("note_card") ?: obj.takeIf { it.string("note_id", "id") != null }
    }
    val parsed = card?.let { parseRednoteNote(it, fallback.userId) }
    return mergeNoteSnapshots(fallback, parsed)
}

internal fun parseRednoteNote(obj: JsonObject, fallbackUserId: String = ""): RednoteNoteSnapshot? {
    val card = obj.obj("note_card") ?: obj
    val noteId = card.string("note_id", "id", "noteId")
        ?: obj.string("note_id", "id", "noteId")
        ?: return null
    val user = card.obj("user") ?: obj.obj("user")
    val userId = user?.string("user_id", "userid", "userId")?.takeIf { it.isNotBlank() }
        ?: fallbackUserId.takeIf { it.isNotBlank() }
        ?: return null
    val cover = parseCoverUrl(card.obj("cover") ?: obj.obj("cover"))
    val images = parseImageList(card.array("image_list") ?: obj.array("image_list"))
    val video = card.obj("video") ?: obj.obj("video")
    val interact = card.obj("interact_info") ?: obj.obj("interact_info")
    val xsecToken = card.string("xsec_token") ?: obj.string("xsec_token")
    return RednoteNoteSnapshot(
        noteId = noteId,
        userId = userId,
        nickname = user?.string("nickname", "nick_name", "name"),
        avatarUrl = firstHttpUrl(user?.string("avatar", "images", "imageb")),
        title = card.string("title", "display_title") ?: obj.string("display_title", "title").orEmpty(),
        desc = card.string("desc", "description").orEmpty(),
        type = if (card.string("type")?.equals("video", ignoreCase = true) == true) {
            RednoteNoteType.VIDEO
        } else {
            RednoteNoteType.NORMAL
        },
        createdAtEpochSeconds = card.long("time", "timestamp") ?: 0L,
        url = noteLink(noteId, xsecToken),
        xsecToken = xsecToken,
        isTop = card.boolean("sticky") == true || obj.boolean("sticky") == true,
        images = images,
        coverUrl = cover ?: images.firstOrNull()?.url,
        videoUrl = parseVideoUrl(video),
        videoDurationSeconds = video?.obj("capa")?.long("duration") ?: video?.long("duration"),
        metrics = RednoteNoteMetrics(
            likes = interact?.long("liked_count", "like_count"),
            comments = interact?.long("comment_count"),
            collects = interact?.long("collected_count", "collect_count"),
            shares = interact?.long("share_count"),
        ),
    )
}

internal fun parseRednoteSuccessData(json: String, invalidJsonMessage: String): JsonObject {
    val root = parseJsonObject(json, invalidJsonMessage)
    val code = root.long("code")
    val message = root.string("msg", "message").orEmpty()
    val success = root.boolean("success")
    if (looksLikeRiskControl(code, message)) {
        throw RednoteBlockedException(
            message.ifBlank { "小红书请求疑似被风控，已停止继续尝试。请稍后再试或更新 Cookie。" },
        )
    }
    if (success == false || (code != null && code != 0L)) {
        if (looksLikeLoginFailure(message) || code == -100L) {
            throw RednoteLoginException(message.ifBlank { "小红书登录状态不可用" })
        }
        throw RednoteApiException(message.ifBlank { "小红书接口请求失败" })
    }
    return root.obj("data") ?: JsonObject(emptyMap())
}

private fun parseCoverUrl(cover: JsonObject?): String? {
    if (cover == null) return null
    return firstHttpUrl(
        cover.string("url", "url_default", "url_pre"),
        cover.array("info_list")
            ?.mapNotNull { (it as? JsonObject)?.string("url") }
            ?.firstOrNull(),
    )
}

private fun parseImageList(array: JsonArray?): List<RednoteImageSnapshot> {
    if (array == null) return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = firstHttpUrl(
            obj.string("url", "url_size_large"),
            obj.array("info_list")
                ?.mapNotNull { (it as? JsonObject)?.string("url") }
                ?.lastOrNull(),
        ) ?: return@mapNotNull null
        RednoteImageSnapshot(
            url = url,
            width = obj.int("width"),
            height = obj.int("height"),
        )
    }
}

private fun parseVideoUrl(video: JsonObject?): String? {
    if (video == null) return null
    val stream = video.obj("media")?.obj("stream") ?: video.obj("stream")
    val candidates = listOf("h264", "h265", "h266", "av1")
        .mapNotNull { key -> stream?.array(key) }
        .flatten()
        .mapNotNull { element -> (element as? JsonObject)?.string("master_url", "backup_url_1", "url") }
    return firstHttpUrl(
        *candidates.toTypedArray(),
        video.string("url", "master_url"),
    )
}

private fun mergeNoteSnapshots(
    fallback: RednoteNoteSnapshot,
    detailed: RednoteNoteSnapshot?,
): RednoteNoteSnapshot {
    if (detailed == null) return fallback
    return fallback.copy(
        userId = detailed.userId.ifBlank { fallback.userId },
        nickname = detailed.nickname ?: fallback.nickname,
        avatarUrl = detailed.avatarUrl ?: fallback.avatarUrl,
        title = detailed.title.ifBlank { fallback.title },
        desc = detailed.desc.ifBlank { fallback.desc },
        type = if (detailed.type == RednoteNoteType.VIDEO || fallback.type == RednoteNoteType.VIDEO) {
            RednoteNoteType.VIDEO
        } else {
            RednoteNoteType.NORMAL
        },
        createdAtEpochSeconds = detailed.createdAtEpochSeconds.takeIf { it > 0 }
            ?: fallback.createdAtEpochSeconds,
        url = detailed.url ?: fallback.url,
        xsecToken = detailed.xsecToken ?: fallback.xsecToken,
        isTop = detailed.isTop || fallback.isTop,
        images = detailed.images.ifEmpty { fallback.images },
        coverUrl = detailed.coverUrl ?: fallback.coverUrl,
        videoUrl = detailed.videoUrl ?: fallback.videoUrl,
        videoDurationSeconds = detailed.videoDurationSeconds ?: fallback.videoDurationSeconds,
        metrics = RednoteNoteMetrics(
            likes = detailed.metrics.likes ?: fallback.metrics.likes,
            comments = detailed.metrics.comments ?: fallback.metrics.comments,
            collects = detailed.metrics.collects ?: fallback.metrics.collects,
            shares = detailed.metrics.shares ?: fallback.metrics.shares,
        ),
    )
}
