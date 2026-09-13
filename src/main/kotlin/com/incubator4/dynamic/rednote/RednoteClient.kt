package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val request = HttpRequest.newBuilder(userMeUri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Origin", REDNOTE_HOME)
            .header("Referer", "$REDNOTE_HOME/")
            .header("Cookie", cookieHeader)
            .GET()
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
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
        private const val DESKTOP_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"

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

private fun looksLikeHtml(body: String): Boolean {
    val value = body.lowercase()
    return value.startsWith("<!doctype html") ||
        value.startsWith("<html") ||
        value.contains("<title>")
}
