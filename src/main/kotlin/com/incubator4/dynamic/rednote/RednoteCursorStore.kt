package com.incubator4.dynamic.rednote

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.plugin.SourceStateStore

internal const val REDNOTE_NOTE_FEED_KEY: String = "rednote.note.feed"

internal interface RednoteCursorStore {
    fun get(publisherId: Int): SourceCursor?

    fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor

    fun markSeen(publisherId: Int, noteId: String, timestamp: Long): SourceCursor

    fun evict(publisherId: Int)
}

internal class SourceStateRednoteCursorStore(
    private val stateStore: SourceStateStore,
) : RednoteCursorStore {
    private val cache: MutableMap<Int, SourceCursor> = ConcurrentHashMap()

    override fun get(publisherId: Int): SourceCursor? {
        cache[publisherId]?.let { return it }
        return stateStore
            .findCursor(
                publisherId = publisherId,
                sourceKey = REDNOTE_NOTE_FEED_KEY,
                eventType = SourceEventType.DYNAMIC_CREATED,
            )
            ?.also { cache[publisherId] = it }
    }

    override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
        val updated = stateStore.ensureCursorBaseline(
            publisherId = publisherId,
            sourceKey = REDNOTE_NOTE_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun markSeen(publisherId: Int, noteId: String, timestamp: Long): SourceCursor {
        val updated = stateStore.markCursorSeen(
            publisherId = publisherId,
            sourceKey = REDNOTE_NOTE_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            updateKey = noteId,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun evict(publisherId: Int) {
        cache.remove(publisherId)
    }
}
