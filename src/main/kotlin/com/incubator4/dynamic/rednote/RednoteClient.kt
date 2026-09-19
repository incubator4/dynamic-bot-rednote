package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.tools.loggerFor
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private val logger = loggerFor<RednoteClient>()

internal class RednoteClient(
    private val config: RednotePublisherConfig,
    private val httpClient: HttpClient = defaultHttpClient(config.cookie),
    private val userMeUri: URI = URI.create(REDNOTE_USER_ME_URL),
    private val userOtherInfoUri: URI = URI.create(REDNOTE_USER_OTHERINFO_URL),
    private val userPostedUri: URI = URI.create(REDNOTE_USER_POSTED_URL),
    private val feedUri: URI = URI.create(REDNOTE_FEED_URL),
    private val qrCreateUri: URI = URI.create(REDNOTE_QR_CREATE_URL),
    private val qrStatusUri: URI = URI.create(REDNOTE_QR_STATUS_URL),
) {
    suspend fun checkLoginState(): PublisherLoginResult {
        val cookieHeader = currentCookieHeader()
        if (cookieHeader.isBlank()) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "小红书 Cookie 未配置",
            )
        }

        return try {
            val response = fetchUserMe(cookieHeader)
            toLoginResult(response.statusCode(), response.body())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "小红书登录状态检查失败",
            )
        }
    }

    suspend fun createQrLoginChallenge(
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
    ): RednoteQrCodeChallenge {
        ensureGuestCookies()
        val body = compactJsonObject(emptyMap())
        val response = sendSignedPost(
            absoluteUri = qrCreateUri,
            signUri = REDNOTE_QR_CREATE_URI,
            jsonBody = body,
            requireLoginCookie = false,
        )
        val payload = requireJsonBody(response, "小红书二维码创建", requireLoginCookie = false)
        return parseRednoteQrChallenge(payload, nowEpochSeconds = nowEpochSeconds)
    }

    suspend fun pollQrLoginStatus(qrId: String, code: String): RednoteQrStatusSnapshot {
        ensureGuestCookies()
        val query = mapOf("qr_id" to qrId, "code" to code)
        val absolute = uriWithQuery(qrStatusUri, query)
        val signUri = uriWithQuery(URI.create(REDNOTE_QR_STATUS_URI), query).toString()
        val response = sendSignedGet(
            absoluteUri = absolute,
            signUri = signUri,
            requireLoginCookie = false,
        )
        val payload = requireJsonBody(response, "小红书二维码状态", requireLoginCookie = false)
        return parseRednoteQrStatus(payload)
    }

    fun applyQrLoginInfo(loginInfo: RednoteQrLoginInfo) {
        val pairs = loginInfo.toCookiePairs()
        if (pairs.isEmpty()) return
        putCookies(pairs)
    }

    suspend fun fetchPublisherSnapshot(userId: String): RednotePublisherSnapshot? {
        val normalized = userId.trim().takeIf { it.isNotBlank() } ?: return null
        val response = sendGet(
            uri = uriWithQuery(userOtherInfoUri, mapOf("target_user_id" to normalized)),
        )
        val body = requireJsonBody(response, "小红书用户资料")
        return parseRednotePublisher(body)
    }

    suspend fun fetchUserNotes(userId: String, cursor: String? = null): RednoteUserNotesPage {
        val normalized = userId.trim()
        require(normalized.isNotBlank()) { "小红书用户 ID 不能为空" }
        val response = sendGet(
            uri = uriWithQuery(
                userPostedUri,
                mapOf(
                    "num" to USER_NOTES_PAGE_SIZE.toString(),
                    "cursor" to cursor.orEmpty(),
                    "user_id" to normalized,
                    "image_formats" to "jpg,webp,avif",
                ),
            ),
        )
        val body = requireJsonBody(response, "小红书用户笔记")
        return parseRednoteUserNotesPage(body, normalized)
    }

    suspend fun enrichNote(note: RednoteNoteSnapshot): RednoteNoteSnapshot {
        val payload = buildJsonObject {
            put("source_note_id", note.noteId)
            put("image_formats", buildJsonArray {
                add("jpg")
                add("webp")
                add("avif")
            })
            put(
                "extra",
                buildJsonObject {
                    put("need_body_topic", "1")
                },
            )
            put("xsec_source", "pc_user")
            note.xsecToken?.takeIf { it.isNotBlank() }?.let { put("xsec_token", it) }
        }
        val response = sendPost(feedUri, payload.toString())
        val body = requireJsonBody(response, "小红书笔记详情")
        return parseRednoteNoteDetail(body, note)
    }

    suspend fun fetchLiveSnapshot(userId: String): RednoteLiveSnapshot {
        val normalized = userId.trim()
        require(normalized.isNotBlank()) { "小红书用户 ID 不能为空" }
        val response = sendGet(
            uri = uriWithQuery(userOtherInfoUri, mapOf("target_user_id" to normalized)),
        )
        val body = requireJsonBody(response, "小红书直播状态")
        return parseRednoteLiveSnapshot(body, normalized)
    }

    fun exportCookieHeader(): String = currentCookieHeader()

    internal fun toLoginResult(statusCode: Int, body: String): PublisherLoginResult {
        if (statusCode == 401 || statusCode == 403) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "小红书登录状态不可用：HTTP $statusCode",
            )
        }
        if (looksLikeRiskControl(code = null, message = "", httpStatus = statusCode)) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "小红书请求疑似被风控（HTTP $statusCode），已停止继续尝试。请稍后再试或更新 Cookie。",
            )
        }
        if (statusCode !in 200..299) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "小红书登录状态检查失败：HTTP $statusCode",
            )
        }
        val trimmed = body.trim()
        if (trimmed.isEmpty() || looksLikeHtml(trimmed)) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "小红书登录状态不可用：当前会话没有返回账号信息",
            )
        }
        val snapshot = parseRednoteUserMe(trimmed)
        val result = snapshot.toLoginResult()
        if (result.status == PublisherLoginStatus.SUCCESS) {
            logger.info {
                "小红书当前账号识别成功：uid=${result.account?.userId ?: "未知"}，name=${result.account?.name ?: "未知"}"
            }
        }
        return result
    }

    private suspend fun fetchUserMe(cookieHeader: String): HttpResponse<String> {
        return send(
            HttpRequest.newBuilder(userMeUri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders(cookieHeader)
                .build(),
            requireLoginCookie = true,
        )
    }

    private suspend fun sendGet(uri: URI): HttpResponse<String> {
        return send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders(currentCookieHeader())
                .build(),
            requireLoginCookie = true,
        )
    }

    private suspend fun sendPost(uri: URI, jsonBody: String): HttpResponse<String> {
        return send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .applyCommonHeaders(currentCookieHeader())
                .build(),
            requireLoginCookie = true,
        )
    }

    private suspend fun sendSignedGet(
        absoluteUri: URI,
        signUri: String,
        requireLoginCookie: Boolean,
    ): HttpResponse<String> {
        val a1 = cookieValue("a1").orEmpty()
        val signs = buildRednoteWebSign(uri = signUri, jsonBody = null, a1 = a1)
        return send(
            HttpRequest.newBuilder(absoluteUri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders(currentCookieHeader())
                .applySignHeaders(signs)
                .build(),
            requireLoginCookie = requireLoginCookie,
        )
    }

    private suspend fun sendSignedPost(
        absoluteUri: URI,
        signUri: String,
        jsonBody: String,
        requireLoginCookie: Boolean,
    ): HttpResponse<String> {
        val a1 = cookieValue("a1").orEmpty()
        val signs = buildRednoteWebSign(uri = signUri, jsonBody = jsonBody, a1 = a1)
        return send(
            HttpRequest.newBuilder(absoluteUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .applyCommonHeaders(currentCookieHeader())
                .applySignHeaders(signs)
                .build(),
            requireLoginCookie = requireLoginCookie,
        )
    }

    private suspend fun send(
        request: HttpRequest,
        requireLoginCookie: Boolean = true,
    ): HttpResponse<String> {
        if (requireLoginCookie) {
            requireCookieConfigured()
        }
        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    }

    private fun requireJsonBody(
        response: HttpResponse<String>,
        operation: String,
        requireLoginCookie: Boolean = true,
    ): String {
        val statusCode = response.statusCode()
        val body = response.body().orEmpty()
        if (statusCode == 401 || statusCode == 403) {
            throw RednoteLoginException("小红书登录状态不可用：HTTP $statusCode")
        }
        if (looksLikeRiskControl(code = null, message = "", httpStatus = statusCode)) {
            throw RednoteBlockedException(
                "小红书请求疑似被风控（HTTP $statusCode），已停止继续尝试。请稍后再试或更新 Cookie。",
            )
        }
        if (statusCode == 406) {
            throw RednoteApiException("${operation}失败：请求未被接受（HTTP 406），请稍后重试或改用 Cookie 登录")
        }
        if (statusCode !in 200..299) {
            throw RednoteApiException("${operation}失败：HTTP $statusCode")
        }
        val trimmed = body.trim()
        if (trimmed.isEmpty() || looksLikeHtml(trimmed)) {
            if (requireLoginCookie) {
                throw RednoteLoginException("小红书登录状态不可用：当前会话没有返回 JSON")
            }
            throw RednoteApiException("${operation}失败：响应不是有效 JSON")
        }
        return trimmed
    }

    private fun requireCookieConfigured() {
        if (currentCookieHeader().isBlank()) {
            throw RednoteLoginException("小红书 Cookie 未配置")
        }
    }

    private fun ensureGuestCookies() {
        val existing = parseRednoteCookieInput(currentCookieHeader()).pairs
        if (existing.keys.any { it.equals("a1", ignoreCase = true) } &&
            existing.keys.any { it.equals("webId", ignoreCase = true) }
        ) {
            return
        }
        val guest = generateRednoteGuestIdentity()
        val next = linkedMapOf<String, String>()
        existing.forEach { (name, value) -> next[name] = value }
        if (!next.keys.any { it.equals("a1", ignoreCase = true) }) {
            next["a1"] = guest.a1
        }
        if (!next.keys.any { it.equals("webId", ignoreCase = true) }) {
            next["webId"] = guest.webId
        }
        putCookies(next)
    }

    private fun putCookies(pairs: Map<String, String>) {
        val cookieManager = httpClient.cookieHandler().orElse(null) as? CookieManager ?: return
        pairs.forEach { (name, value) ->
            if (name.isBlank()) return@forEach
            val parsed = HttpCookie(name, value).apply {
                domain = ".xiaohongshu.com"
                path = "/"
            }
            cookieManager.cookieStore.add(URI.create(REDNOTE_HOME), parsed)
        }
    }

    private fun cookieValue(name: String): String? {
        return parseRednoteCookieInput(currentCookieHeader()).pairs.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
    }

    private fun currentCookieHeader(): String {
        return mergeRednoteCookieHeaders(config.cookie, cookieStoreHeader())
    }

    private fun cookieStoreHeader(): String {
        val cookieManager = httpClient.cookieHandler().orElse(null) as? CookieManager ?: return ""
        return cookieManager.cookieStore.cookies
            .asSequence()
            .filterNot { it.hasExpired() }
            .filter { it.name.isNotBlank() }
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }

    companion object {
        private const val USER_NOTES_PAGE_SIZE: Int = 30

        internal fun defaultHttpClient(cookie: String): HttpClient {
            val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            parseRednoteCookieInput(cookie).pairs.forEach { (name, value) ->
                val parsed = HttpCookie(name, value).apply {
                    domain = ".xiaohongshu.com"
                    path = "/"
                }
                cookieManager.cookieStore.add(URI.create(REDNOTE_HOME), parsed)
            }
            return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        }
    }
}

private fun HttpRequest.Builder.applyCommonHeaders(cookieHeader: String): HttpRequest.Builder {
    header("Accept", "application/json, text/plain, */*")
    header("Accept-Language", "zh-CN,zh;q=0.9")
    header("User-Agent", DESKTOP_USER_AGENT)
    header("Origin", REDNOTE_HOME)
    header("Referer", "$REDNOTE_HOME/")
    if (cookieHeader.isNotBlank()) {
        header("Cookie", cookieHeader)
    }
    return this
}

private fun HttpRequest.Builder.applySignHeaders(signs: RednoteWebSignHeaders): HttpRequest.Builder {
    header("X-s", signs.xS)
    header("X-t", signs.xT)
    header("X-S-Common", signs.xSCommon)
    return this
}

private const val DESKTOP_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"

private fun uriWithQuery(base: URI, params: Map<String, String>): URI {
    val encoded = params.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }
    val raw = base.toString()
    val joiner = if (raw.contains('?')) "&" else "?"
    return URI.create("$raw$joiner$encoded")
}

private fun looksLikeHtml(body: String): Boolean {
    val value = body.lowercase()
    return value.startsWith("<!doctype html") ||
        value.startsWith("<html") ||
        value.contains("<title>")
}
