package com.incubator4.dynamic.rednote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.util.LinkedHashMap

internal const val REDNOTE_PLATFORM_ID: String = "rednote"
internal const val REDNOTE_HOME: String = "https://www.xiaohongshu.com"
internal const val REDNOTE_USER_ME_URI: String = "/api/sns/web/v2/user/me"
internal const val REDNOTE_USER_OTHERINFO_URI: String = "/api/sns/web/v1/user/otherinfo"
internal const val REDNOTE_USER_POSTED_URI: String = "/api/sns/web/v1/user_posted"
internal const val REDNOTE_FEED_URI: String = "/api/sns/web/v1/feed"
internal const val REDNOTE_USER_ME_URL: String = "https://edith.xiaohongshu.com$REDNOTE_USER_ME_URI"
internal const val REDNOTE_USER_OTHERINFO_URL: String = "https://edith.xiaohongshu.com$REDNOTE_USER_OTHERINFO_URI"
internal const val REDNOTE_USER_POSTED_URL: String = "https://edith.xiaohongshu.com$REDNOTE_USER_POSTED_URI"
internal const val REDNOTE_FEED_URL: String = "https://edith.xiaohongshu.com$REDNOTE_FEED_URI"
internal const val REDNOTE_FEED_RAP_API: String = "//edith.xiaohongshu.com$REDNOTE_FEED_URI"
internal const val REDNOTE_LIVE_HOME: String = "$REDNOTE_HOME/livestream"
internal const val REDNOTE_DEFAULT_AVATAR: String = "https://www.xiaohongshu.com/favicon.ico"

internal open class RednoteApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class RednoteLoginException(
    message: String,
    cause: Throwable? = null,
) : RednoteApiException(message, cause)

internal class RednoteBlockedException(
    message: String,
    cause: Throwable? = null,
) : RednoteApiException(message, cause)

internal data class RednoteCookieSet(
    val pairs: Map<String, String>,
) {
    val header: String
        get() = pairs.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    fun has(name: String): Boolean = pairs.keys.any { it.equals(name, ignoreCase = true) }

    fun isEmpty(): Boolean = pairs.isEmpty()
}

internal data class RednoteUserMeSnapshot(
    val code: Long? = null,
    val success: Boolean? = null,
    val message: String? = null,
    val guest: Boolean? = null,
    val userId: String? = null,
    val redId: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
)

internal val REDNOTE_REQUIRED_LOGIN_COOKIES: List<String> = listOf("a1", "web_session")

internal fun parseRednoteCookieInput(raw: String): RednoteCookieSet {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return RednoteCookieSet(emptyMap())
    val parsed = when {
        trimmed.startsWith("[") -> parseCookieJsonArray(trimmed)
        trimmed.startsWith("{") -> parseCookieJsonObject(trimmed)
        else -> parseCookieHeader(trimmed)
    }
    return RednoteCookieSet(canonicalizeRednoteCookies(parsed.pairs))
}

internal fun mergeRednoteCookieHeaders(vararg headers: String?): String {
    return RednoteCookieSet(
        canonicalizeRednoteCookies(parseCookieHeader(headers.filterNotNull().joinToString("; ")).pairs),
    ).header
}

internal fun RednoteCookieSet.missingRequiredLoginCookies(): List<String> {
    return REDNOTE_REQUIRED_LOGIN_COOKIES.filter { !has(it) }
}

internal fun missingRequiredLoginCookieMessage(missing: Collection<String>): String {
    return "小红书 Cookie 缺少必要字段：${missing.joinToString("、")}。请从浏览器导出包含 a1 和 web_session 的完整 Cookie。"
}

/**
 * Align cookie names with the Xiaohongshu web session used by
 * [xiaohongshu-cli](https://github.com/jackwener/xiaohongshu-cli):
 * `secure_session` from QR payloads is persisted as `web_session_sec`.
 */
internal fun canonicalizeRednoteCookies(pairs: Map<String, String>): Map<String, String> {
    val values = LinkedHashMap<String, String>()
    val hasCanonicalSecure = pairs.keys.any { it.equals("web_session_sec", ignoreCase = true) }
    pairs.forEach { (name, value) ->
        if (name.isBlank() || value.isBlank()) return@forEach
        if (name.equals("saved_at", ignoreCase = true)) return@forEach
        val canonical = when {
            name.equals("secure_session", ignoreCase = true) -> "web_session_sec"
            else -> name
        }
        if (canonical == "web_session_sec" &&
            hasCanonicalSecure &&
            !name.equals("web_session_sec", ignoreCase = true)
        ) {
            return@forEach
        }
        values[canonical] = value
    }
    return values
}

internal fun parseRednoteUserMe(json: String): RednoteUserMeSnapshot {
    val root = parseJsonObject(json, "小红书登录状态响应不是有效 JSON")
    val data = root["data"] as? JsonObject
    return RednoteUserMeSnapshot(
        code = root["code"]?.jsonPrimitive?.longOrNull,
        success = root["success"]?.jsonPrimitive?.booleanOrNull,
        message = root.string("msg", "message"),
        guest = data?.boolean("guest"),
        userId = data?.string("user_id", "userid", "userId"),
        redId = data?.string("red_id", "redId"),
        nickname = data?.string("nickname", "name"),
        avatarUrl = firstHttpUrl(
            data?.string("images", "imageb", "avatar"),
        ),
    )
}

internal fun RednoteUserMeSnapshot.toLoginResult(): PublisherLoginResult {
    val detail = message?.trim().orEmpty()
    if (looksLikeRiskControl(code, detail)) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = detail.ifBlank { "小红书请求疑似被风控，已停止继续尝试。请稍后再试或更新 Cookie。" },
        )
    }
    if (isApiFailure()) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = loginFailureMessage(detail.ifBlank { "小红书登录状态不可用" }),
        )
    }
    if (guest == true || userId.isNullOrBlank()) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = "小红书 Cookie 仍是游客会话，请重新登录后导入完整 Cookie",
        )
    }
    return PublisherLoginResult(
        status = PublisherLoginStatus.SUCCESS,
        message = "小红书登录状态可用",
        account = PublisherLoginAccount(
            userId = userId,
            name = nickname?.takeIf { it.isNotBlank() } ?: redId,
            avatar = avatarUrl?.let { MediaRef(it, MediaKind.AVATAR) },
        ),
    )
}

internal fun looksLikeRiskControl(code: Long?, message: String, httpStatus: Int? = null): Boolean {
    if (httpStatus == 461 || httpStatus == 471) return true
    if (code == 300012L || code == 461L || code == 471L) return true
    val value = message.lowercase()
    return value.contains("风控") ||
        value.contains("验证") ||
        value.contains("拦截") ||
        value.contains("captcha") ||
        value.contains("risk")
}

internal fun looksLikeLoginFailure(message: String): Boolean {
    val value = message.lowercase()
    return value.contains("登录") ||
        value.contains("登陆") ||
        value.contains("未登录") ||
        value.contains("过期") ||
        value.contains("游客") ||
        value.contains("guest") ||
        value.contains("login") ||
        value.contains("auth") ||
        value.contains("cookie")
}

private fun RednoteUserMeSnapshot.isApiFailure(): Boolean {
    if (success == false) return true
    val apiCode = code ?: return false
    return apiCode != 0L
}

private fun loginFailureMessage(detail: String): String {
    return if (looksLikeLoginFailure(detail)) {
        "小红书登录状态不可用：$detail"
    } else {
        detail
    }
}

private fun parseCookieHeader(header: String): RednoteCookieSet {
    val values = LinkedHashMap<String, String>()
    header.split(';').forEach { raw ->
        val index = raw.indexOf('=')
        if (index <= 0) return@forEach
        val name = raw.substring(0, index).trim()
        val value = raw.substring(index + 1).trim()
        if (name.isBlank() || name.equals("path", ignoreCase = true) ||
            name.equals("domain", ignoreCase = true) ||
            name.equals("expires", ignoreCase = true) ||
            name.equals("max-age", ignoreCase = true) ||
            name.equals("samesite", ignoreCase = true) ||
            name.equals("secure", ignoreCase = true) ||
            name.equals("httponly", ignoreCase = true)
        ) {
            return@forEach
        }
        values[name] = value
    }
    return RednoteCookieSet(values)
}

private fun parseCookieJsonArray(raw: String): RednoteCookieSet {
    val array = runCatching { REDNOTE_JSON.parseToJsonElement(raw).jsonArray }.getOrElse {
        throw RednoteLoginException("小红书 Cookie JSON 无法解析")
    }
    val values = LinkedHashMap<String, String>()
    array.forEach { item ->
        val obj = item as? JsonObject ?: return@forEach
        val name = obj.string("name", "key") ?: return@forEach
        val value = obj.string("value") ?: return@forEach
        values[name] = value
    }
    return RednoteCookieSet(values)
}

private fun parseCookieJsonObject(raw: String): RednoteCookieSet {
    val obj = runCatching { REDNOTE_JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
        throw RednoteLoginException("小红书 Cookie JSON 无法解析")
    }
    if (obj.containsKey("name") && obj.containsKey("value")) {
        val name = obj.string("name") ?: return RednoteCookieSet(emptyMap())
        val value = obj.string("value") ?: return RednoteCookieSet(emptyMap())
        return RednoteCookieSet(mapOf(name to value))
    }
    val values = LinkedHashMap<String, String>()
    obj.forEach { (name, element) ->
        val primitive = element as? JsonPrimitive ?: return@forEach
        val value = primitive.contentOrNull?.trim().orEmpty()
        if (name.isNotBlank() && value.isNotEmpty()) {
            values[name] = value
        }
    }
    return RednoteCookieSet(values)
}

