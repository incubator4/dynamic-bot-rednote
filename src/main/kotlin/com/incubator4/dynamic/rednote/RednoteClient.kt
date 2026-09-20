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
    private val qrActivateUri: URI = URI.create(REDNOTE_QR_ACTIVATE_URL),
    private val qrCreateUri: URI = URI.create(REDNOTE_QR_CREATE_URL),
    private val qrUserInfoUri: URI = URI.create(REDNOTE_QR_USERINFO_URL),
    private val qrStatusUri: URI = URI.create(REDNOTE_QR_STATUS_URL),
) {
    private val sessionCookies = LinkedHashMap<String, String>()
    private var includeConfigCookie: Boolean = true

    suspend fun checkLoginState(): PublisherLoginResult {
        val cookies = parseRednoteCookieInput(currentCookieHeader())
        if (cookies.isEmpty()) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "小红书 Cookie 未配置",
            )
        }
        val missing = cookies.missingRequiredLoginCookies()
        if (missing.isNotEmpty()) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = missingRequiredLoginCookieMessage(missing),
            )
        }

        return try {
            val response = fetchUserMe()
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
        beginGuestQrSession()
        runCatching { activateLoginSession() }
        val body = compactJsonObject(linkedMapOf("qr_type" to 1))
        val response = sendXywSignedPost(
            absoluteUri = qrCreateUri,
            apiPath = REDNOTE_QR_CREATE_URI,
            jsonBody = body,
            requireLoginCookie = false,
        )
        val payload = requireJsonBody(response, "小红书二维码创建", requireLoginCookie = false)
        return parseRednoteQrChallenge(payload, nowEpochSeconds = nowEpochSeconds)
    }

    suspend fun pollQrLoginStatus(qrId: String, code: String): RednoteQrStatusSnapshot {
        val body = compactJsonObject(linkedMapOf("qrId" to qrId, "code" to code))
        val response = sendXywSignedPost(
            absoluteUri = qrUserInfoUri,
            apiPath = REDNOTE_QR_USERINFO_URI,
            jsonBody = body,
            requireLoginCookie = false,
            extraHeaders = mapOf("service-tag" to REDNOTE_QR_USERINFO_SERVICE_TAG),
        )
        val payload = requireJsonBody(response, "小红书二维码状态", requireLoginCookie = false)
        val snapshot = parseRednoteQrStatus(payload)
        snapshot.loginInfo?.takeIf { it.hasSessionCookie() }?.let(::applyQrLoginInfo)
        return snapshot
    }

    suspend fun completeQrLogin(
        qrId: String,
        code: String,
        confirmedUserId: String?,
        retries: Int = REDNOTE_QR_COMPLETE_RETRIES,
        retryDelayMs: Long = REDNOTE_QR_COMPLETE_RETRY_MILLIS,
        delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): RednoteQrLoginInfo {
        var lastInfo = RednoteQrLoginInfo()
        repeat(retries.coerceAtLeast(1)) { attempt ->
            val snapshot = fetchQrLoginCompletion(qrId, code)
            snapshot.loginInfo?.let { info ->
                applyQrLoginInfo(info)
                lastInfo = info.copy(userId = info.userId ?: lastInfo.userId)
            }
            val completedUserId = snapshot.confirmedUserId()
            if (!confirmedUserId.isNullOrBlank() &&
                completedUserId == confirmedUserId &&
                lastInfo.hasSessionCookie()
            ) {
                return lastInfo.copy(userId = completedUserId)
            }
            val selfUserId = runCatching { fetchUserMeSnapshot()?.userId }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (!selfUserId.isNullOrBlank() &&
                (confirmedUserId.isNullOrBlank() || selfUserId == confirmedUserId)
            ) {
                return lastInfo.copy(userId = selfUserId)
            }
            if (confirmedUserId.isNullOrBlank() && lastInfo.hasSessionCookie()) {
                return lastInfo
            }
            if (attempt + 1 < retries) {
                delayMillis(retryDelayMs.coerceAtLeast(1_000))
            }
        }
        throw RednoteApiException("小红书扫码已确认，但未能取得登录会话，请重新扫码")
    }

    fun applyQrLoginInfo(loginInfo: RednoteQrLoginInfo) {
        val pairs = loginInfo.toCookiePairs()
        if (pairs.isEmpty()) return
        putCookies(pairs)
    }

    suspend fun fetchPublisherSnapshot(userId: String): RednotePublisherSnapshot? {
        val normalized = userId.trim().takeIf { it.isNotBlank() } ?: return null
        val response = sendXywSignedGet(
            absoluteBaseUri = userOtherInfoUri,
            apiPath = REDNOTE_USER_OTHERINFO_URI,
            params = linkedMapOf("target_user_id" to normalized),
            userId = normalized,
        )
        val body = requireJsonBody(response, "小红书用户资料")
        return parseRednotePublisher(body)
    }

    suspend fun fetchUserNotes(userId: String, cursor: String? = null): RednoteUserNotesPage {
        val normalized = userId.trim()
        require(normalized.isNotBlank()) { "小红书用户 ID 不能为空" }
        val response = sendXywSignedGet(
            absoluteBaseUri = userPostedUri,
            apiPath = REDNOTE_USER_POSTED_URI,
            params = linkedMapOf(
                "num" to USER_NOTES_PAGE_SIZE.toString(),
                "cursor" to cursor.orEmpty(),
                "user_id" to normalized,
                "image_formats" to "jpg,webp,avif",
            ),
            userId = normalized,
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
        val jsonBody = payload.toString()
        val response = sendXywSignedPost(
            absoluteUri = feedUri,
            apiPath = REDNOTE_FEED_URI,
            jsonBody = jsonBody,
            xRapApi = REDNOTE_FEED_RAP_API,
        )
        val body = requireJsonBody(response, "小红书笔记详情")
        return parseRednoteNoteDetail(body, note)
    }

    suspend fun fetchLiveSnapshot(userId: String): RednoteLiveSnapshot {
        val normalized = userId.trim()
        require(normalized.isNotBlank()) { "小红书用户 ID 不能为空" }
        val response = sendXywSignedGet(
            absoluteBaseUri = userOtherInfoUri,
            apiPath = REDNOTE_USER_OTHERINFO_URI,
            params = linkedMapOf("target_user_id" to normalized),
            userId = normalized,
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

    private suspend fun fetchUserMe(): HttpResponse<String> {
        return sendXywSignedGet(
            absoluteBaseUri = userMeUri,
            apiPath = REDNOTE_USER_ME_URI,
            params = emptyMap(),
        )
    }

    private suspend fun fetchUserMeSnapshot(): RednoteUserMeSnapshot? {
        val response = fetchUserMe()
        if (response.statusCode() !in 200..299) return null
        val body = response.body().orEmpty().trim()
        if (body.isEmpty() || looksLikeHtml(body)) return null
        return parseRednoteUserMe(body)
    }

    private suspend fun activateLoginSession() {
        val response = sendXywSignedPost(
            absoluteUri = qrActivateUri,
            apiPath = REDNOTE_QR_ACTIVATE_URI,
            jsonBody = compactJsonObject(emptyMap()),
            requireLoginCookie = false,
        )
        val payload = requireJsonBody(response, "小红书登录激活", requireLoginCookie = false)
        parseRednoteActivateSession(payload).takeIf { it.hasSessionCookie() }?.let(::applyQrLoginInfo)
    }

    private suspend fun fetchQrLoginCompletion(qrId: String, code: String): RednoteQrStatusSnapshot {
        val query = linkedMapOf("qr_id" to qrId, "code" to code)
        val response = sendXywSignedGet(
            absoluteBaseUri = qrStatusUri,
            apiPath = REDNOTE_QR_STATUS_URI,
            params = query,
            requireLoginCookie = false,
        )
        val payload = requireJsonBody(response, "小红书扫码完成", requireLoginCookie = false)
        return parseRednoteQrStatus(payload)
    }

    private suspend fun sendXywSignedGet(
        absoluteBaseUri: URI,
        apiPath: String,
        params: Map<String, String?>,
        userId: String? = null,
        requireLoginCookie: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val contentString = buildRednoteGetContentString(apiPath, params)
        val a1 = cookieValue("a1").orEmpty()
        val signs = buildRednoteXywSign(contentString = contentString, a1 = a1, userId = userId)
        val absoluteUri = absoluteUriWithSignedQuery(absoluteBaseUri, contentString)
        return send(
            HttpRequest.newBuilder(absoluteUri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders(currentCookieHeader())
                .applySignHeaders(signs)
                .applyExtraHeaders(extraHeaders)
                .build(),
            requireLoginCookie = requireLoginCookie,
        )
    }

    private suspend fun sendXywSignedPost(
        absoluteUri: URI,
        apiPath: String,
        jsonBody: String,
        userId: String? = null,
        xRapApi: String? = null,
        requireLoginCookie: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val contentString = apiPath + jsonBody
        val a1 = cookieValue("a1").orEmpty()
        val signs = buildRednoteXywSign(contentString = contentString, a1 = a1, userId = userId)
        val request = HttpRequest.newBuilder(absoluteUri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .applyCommonHeaders(currentCookieHeader())
            .applySignHeaders(signs)
            .applyExtraHeaders(extraHeaders)
        // feed / search / publish endpoints require the x-rap-param risk-control header.
        if (xRapApi != null) {
            request.header("x-rap-param", RednoteXrap.build(api = xRapApi, bodyJson = jsonBody))
        }
        return send(request.build(), requireLoginCookie = requireLoginCookie)
    }

    private suspend fun send(
        request: HttpRequest,
        requireLoginCookie: Boolean = true,
    ): HttpResponse<String> {
        if (requireLoginCookie) {
            requireCookieConfigured()
        }
        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
        mergeResponseCookies(response)
        return response
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

    private fun beginGuestQrSession() {
        includeConfigCookie = false
        sessionCookies.clear()
        clearCookieStore()
        val guest = generateRednoteGuestIdentity()
        putCookies(linkedMapOf("a1" to guest.a1, "webId" to guest.webId))
    }

    private fun putCookies(pairs: Map<String, String>) {
        val canonical = canonicalizeRednoteCookies(pairs)
        if (canonical.isEmpty()) return
        sessionCookies.putAll(canonical)
        val cookieManager = httpClient.cookieHandler().orElse(null) as? CookieManager ?: return
        canonical.forEach { (name, value) ->
            if (name.isBlank()) return@forEach
            val parsed = HttpCookie(name, value).apply {
                domain = ".xiaohongshu.com"
                path = "/"
            }
            cookieManager.cookieStore.add(URI.create(REDNOTE_HOME), parsed)
        }
    }

    private fun mergeResponseCookies(response: HttpResponse<*>) {
        val values = LinkedHashMap<String, String>()
        response.headers().allValues("Set-Cookie").forEach { raw ->
            values.putAll(parseRednoteCookieInput(raw).pairs)
        }
        if (values.isNotEmpty()) {
            putCookies(values)
        }
    }

    private fun clearCookieStore() {
        val cookieManager = httpClient.cookieHandler().orElse(null) as? CookieManager ?: return
        cookieManager.cookieStore.removeAll()
    }

    private fun cookieValue(name: String): String? {
        return parseRednoteCookieInput(currentCookieHeader()).pairs.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
    }

    private fun currentCookieHeader(): String {
        val configCookie = if (includeConfigCookie) config.cookie else null
        return mergeRednoteCookieHeaders(
            configCookie,
            RednoteCookieSet(sessionCookies).header,
            cookieStoreHeader(),
        )
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
    header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    header("User-Agent", DESKTOP_USER_AGENT)
    header("Origin", REDNOTE_HOME)
    header("Referer", "$REDNOTE_HOME/")
    header("sec-ch-ua", SEC_CH_UA)
    header("sec-ch-ua-mobile", "?0")
    header("sec-ch-ua-platform", "\"Windows\"")
    header("sec-fetch-dest", "empty")
    header("sec-fetch-mode", "cors")
    header("sec-fetch-site", "same-site")
    if (cookieHeader.isNotBlank()) {
        header("Cookie", cookieHeader)
    }
    return this
}

private fun HttpRequest.Builder.applyExtraHeaders(headers: Map<String, String>): HttpRequest.Builder {
    headers.forEach { (name, value) -> header(name, value) }
    return this
}

private fun HttpRequest.Builder.applySignHeaders(signs: RednoteWebSignHeaders): HttpRequest.Builder {
    header("X-s", signs.xS)
    header("X-t", signs.xT)
    header("X-S-Common", signs.xSCommon)
    header("x-b3-traceid", signs.xB3TraceId)
    header("x-xray-traceid", signs.xXrayTraceId)
    header("x-mns", signs.xMns)
    header("xy-direction", signs.xyDirection)
    return this
}

internal const val DESKTOP_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"
private const val SEC_CH_UA: String =
    "\"Not:A-Brand\";v=\"99\", \"Microsoft Edge\";v=\"149\", \"Chromium\";v=\"149\""

/**
 * Attach the already-signed query from [contentString] onto [base] without re-encoding,
 * so the HTTP URL matches the XYW content string.
 */
private fun absoluteUriWithSignedQuery(base: URI, contentString: String): URI {
    val query = contentString.substringAfter('?', missingDelimiterValue = "")
    val rawBase = base.toString().substringBefore('?')
    return if (query.isEmpty()) {
        URI.create(rawBase)
    } else {
        URI.create("$rawBase?$query")
    }
}

private fun looksLikeHtml(body: String): Boolean {
    val value = body.lowercase()
    return value.startsWith("<!doctype html") ||
        value.startsWith("<html") ||
        value.contains("<title>")
}
