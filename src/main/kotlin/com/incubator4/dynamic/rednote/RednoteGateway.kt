package com.incubator4.dynamic.rednote

import kotlinx.coroutines.delay
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class RednoteNoteType {
    NORMAL,
    VIDEO,
}

internal data class RednotePublisherSnapshot(
    val userId: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val redId: String? = null,
    val description: String? = null,
)

internal data class RednoteNoteSnapshot(
    val noteId: String,
    val userId: String,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val title: String = "",
    val desc: String = "",
    val type: RednoteNoteType = RednoteNoteType.NORMAL,
    val createdAtEpochSeconds: Long = 0,
    val url: String? = null,
    val xsecToken: String? = null,
    val isTop: Boolean = false,
    val images: List<RednoteImageSnapshot> = emptyList(),
    val coverUrl: String? = null,
    val videoUrl: String? = null,
    val videoDurationSeconds: Long? = null,
    val metrics: RednoteNoteMetrics = RednoteNoteMetrics(),
)

internal data class RednoteImageSnapshot(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

internal data class RednoteNoteMetrics(
    val likes: Long? = null,
    val comments: Long? = null,
    val collects: Long? = null,
    val shares: Long? = null,
)

internal data class RednoteUserNotesPage(
    val notes: List<RednoteNoteSnapshot> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean = false,
)

internal data class RednoteLiveSnapshot(
    val userId: String,
    val roomId: String = "",
    val status: LiveStatus = LiveStatus.CLOSE,
    val title: String = "",
    val coverUrl: String? = null,
    val area: String? = null,
    val startedAtEpochSeconds: Long? = null,
)

internal interface RednoteGateway {
    fun exportCookie(): String = ""

    suspend fun checkLoginState(): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持小红书登录状态检查",
        )
    }

    suspend fun fetchPublisherSnapshot(userId: String): RednotePublisherSnapshot? = null

    suspend fun fetchUserNotes(userId: String, cursor: String? = null): RednoteUserNotesPage {
        return RednoteUserNotesPage()
    }

    suspend fun enrichNote(note: RednoteNoteSnapshot): RednoteNoteSnapshot {
        return note
    }

    suspend fun fetchLiveSnapshot(userId: String): RednoteLiveSnapshot {
        return RednoteLiveSnapshot(userId = userId)
    }
}

internal class RednoteHttpGateway(
    private val client: RednoteClient,
    private val requestIntervalMs: Long,
) : RednoteGateway {
    override fun exportCookie(): String = client.exportCookieHeader()

    override suspend fun checkLoginState(): PublisherLoginResult {
        return withRequestInterval {
            client.checkLoginState()
        }
    }

    override suspend fun fetchPublisherSnapshot(userId: String): RednotePublisherSnapshot? {
        return withRequestInterval {
            client.fetchPublisherSnapshot(userId)
        }
    }

    override suspend fun fetchUserNotes(userId: String, cursor: String?): RednoteUserNotesPage {
        return withRequestInterval {
            client.fetchUserNotes(userId, cursor)
        }
    }

    override suspend fun enrichNote(note: RednoteNoteSnapshot): RednoteNoteSnapshot {
        return withRequestInterval {
            client.enrichNote(note)
        }
    }

    override suspend fun fetchLiveSnapshot(userId: String): RednoteLiveSnapshot {
        return withRequestInterval {
            client.fetchLiveSnapshot(userId)
        }
    }

    private suspend fun <T> withRequestInterval(block: suspend () -> T): T {
        return try {
            block()
        } finally {
            if (requestIntervalMs > 0) {
                delay(requestIntervalMs)
            }
        }
    }
}

internal fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
    return (seconds * 1_000.0).toLong().coerceAtLeast(minimumMillis)
}

internal fun noteLink(noteId: String, xsecToken: String? = null): String {
    val base = "$REDNOTE_HOME/explore/$noteId"
    val token = xsecToken?.trim()?.takeIf { it.isNotBlank() } ?: return base
    val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8)
    return "$base?xsec_token=$encoded&xsec_source=pc_user"
}

internal fun userProfileLink(userId: String): String {
    return "$REDNOTE_HOME/user/profile/$userId"
}

internal fun liveRoomLink(roomId: String): String {
    val normalized = roomId.trim()
    return if (normalized.isBlank()) REDNOTE_LIVE_HOME else "$REDNOTE_LIVE_HOME/$normalized"
}
