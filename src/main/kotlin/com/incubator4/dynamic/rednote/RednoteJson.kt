package com.incubator4.dynamic.rednote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal val REDNOTE_JSON: Json = Json {
    ignoreUnknownKeys = true
}

internal fun JsonObject.string(vararg keys: String): String? {
    keys.forEach { key ->
        val value = this[key].asTrimmedString()
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.boolean(vararg keys: String): Boolean? {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.booleanOrNull?.let { return it }
        primitive.longOrNull?.let { return it != 0L }
        when (primitive.contentOrNull?.trim()?.lowercase()) {
            "1", "true", "yes" -> return true
            "0", "false", "no" -> return false
        }
    }
    return null
}

internal fun JsonObject.long(vararg keys: String): Long? {
    keys.forEach { key ->
        val element = this[key] ?: return@forEach
        val primitive = element as? JsonPrimitive ?: return@forEach
        primitive.longOrNull?.let { return it }
        parseRednoteCount(primitive.contentOrNull)?.let { return it }
    }
    return null
}

internal fun JsonObject.int(vararg keys: String): Int? {
    return long(*keys)?.toInt()
}

internal fun JsonObject.obj(vararg keys: String): JsonObject? {
    keys.forEach { key ->
        val value = this[key] as? JsonObject
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.array(vararg keys: String): JsonArray? {
    keys.forEach { key ->
        val value = this[key] as? JsonArray
        if (value != null) return value
    }
    return null
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asTrimmedString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

internal fun parseJsonObject(json: String, errorMessage: String): JsonObject {
    return runCatching { REDNOTE_JSON.parseToJsonElement(json).jsonObject }.getOrElse {
        throw RednoteApiException(errorMessage)
    }
}

internal fun parseRednoteCount(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    value.toLongOrNull()?.let { return it }
    val normalized = value.replace(",", "").replace("+", "").trim()
    val multiplier = when {
        normalized.endsWith("亿") -> 100_000_000.0
        normalized.endsWith("万") -> 10_000.0
        else -> 1.0
    }
    val number = normalized
        .removeSuffix("亿")
        .removeSuffix("万")
        .trim()
        .toDoubleOrNull()
        ?: return null
    return (number * multiplier).toLong()
}

internal fun firstHttpUrl(vararg urls: String?): String? {
    return urls.firstNotNullOfOrNull(::normalizeHttpUrl)
}

internal fun normalizeHttpUrl(url: String?): String? {
    val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) -> value
        value.startsWith("//") -> "https:$value"
        else -> null
    }
}
