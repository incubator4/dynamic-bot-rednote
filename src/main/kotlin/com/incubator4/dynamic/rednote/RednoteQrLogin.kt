package com.incubator4.dynamic.rednote

import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge

internal const val REDNOTE_QR_CREATE_URL: String =
    "https://edith.xiaohongshu.com/api/sns/web/v1/login/qrcode/create"
internal const val REDNOTE_QR_STATUS_URL: String =
    "https://edith.xiaohongshu.com/api/sns/web/v1/login/qrcode/status"
internal const val REDNOTE_QR_CREATE_URI: String = "/api/sns/web/v1/login/qrcode/create"
internal const val REDNOTE_QR_STATUS_URI: String = "/api/sns/web/v1/login/qrcode/status"

internal const val REDNOTE_QR_EXPIRES_SECONDS: Long = 180
internal const val REDNOTE_QR_POLL_INTERVAL_MILLIS: Long = 2_000
internal const val REDNOTE_QR_TIMEOUT_MILLIS: Long = 180_000

internal enum class RednoteQrCodeStatus {
    WAITING,
    SCANNED,
    SUCCESS,
    EXPIRED,
    CANCELED,
    FAILED,
}

internal data class RednoteQrCodeChallenge(
    val qrId: String,
    val code: String,
    val url: String,
    val expiresAtEpochSeconds: Long,
)

internal data class RednoteQrLoginInfo(
    val session: String? = null,
    val secureSession: String? = null,
    val userId: String? = null,
)

internal data class RednoteQrStatusSnapshot(
    val code: Long? = null,
    val success: Boolean? = null,
    val message: String? = null,
    val codeStatus: Int? = null,
    val loginInfo: RednoteQrLoginInfo? = null,
)

internal fun parseRednoteQrChallenge(
    json: String,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
): RednoteQrCodeChallenge {
    val root = parseJsonObject(json, "小红书二维码创建响应不是有效 JSON")
    requireApiSuccess(root, "小红书二维码创建失败")
    val data = root.obj("data")
        ?: throw RednoteApiException("小红书二维码创建响应缺少 data")
    val qrId = data.string("qr_id", "qrId")?.trim().orEmpty()
    val code = data.string("code")?.trim().orEmpty()
    val url = data.string("url")?.trim().orEmpty()
    if (qrId.isBlank() || code.isBlank() || url.isBlank()) {
        throw RednoteApiException("小红书二维码创建响应缺少 qr_id / code / url")
    }
    return RednoteQrCodeChallenge(
        qrId = qrId,
        code = code,
        url = url,
        expiresAtEpochSeconds = nowEpochSeconds + REDNOTE_QR_EXPIRES_SECONDS,
    )
}

internal fun parseRednoteQrStatus(json: String): RednoteQrStatusSnapshot {
    val root = parseJsonObject(json, "小红书二维码状态响应不是有效 JSON")
    val data = root.obj("data")
    val login = data?.obj("login_info", "loginInfo")
    return RednoteQrStatusSnapshot(
        code = root.long("code"),
        success = root.boolean("success"),
        message = root.string("msg", "message"),
        codeStatus = data?.int("code_status", "codeStatus"),
        loginInfo = login?.let {
            RednoteQrLoginInfo(
                session = it.string("session"),
                secureSession = it.string("secure_session", "secureSession"),
                userId = it.string("user_id", "userId"),
            )
        },
    )
}

internal fun RednoteQrCodeChallenge.toPublisherChallenge(
    pollIntervalMillis: Long = REDNOTE_QR_POLL_INTERVAL_MILLIS,
): PublisherQrLoginChallenge {
    return PublisherQrLoginChallenge(
        qrContent = url,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        message = "请使用小红书 App 扫码并确认登录",
        instruction = "请使用小红书 App 扫码并确认登录",
        validityHint = "三分钟内有效",
        statusPollIntervalMillis = pollIntervalMillis,
    )
}

internal fun RednoteQrStatusSnapshot.resolveStatus(): RednoteQrCodeStatus {
    val detail = message?.trim().orEmpty()
    if (looksLikeRiskControl(code, detail)) {
        return RednoteQrCodeStatus.FAILED
    }
    if (success == false && code != null && code != 0L) {
        return when {
            looksLikeExpired(detail) -> RednoteQrCodeStatus.EXPIRED
            looksLikeCanceled(detail) -> RednoteQrCodeStatus.CANCELED
            else -> RednoteQrCodeStatus.FAILED
        }
    }
    return when (codeStatus) {
        0 -> RednoteQrCodeStatus.WAITING
        1 -> RednoteQrCodeStatus.SCANNED
        2 -> RednoteQrCodeStatus.SUCCESS
        3 -> RednoteQrCodeStatus.EXPIRED
        else -> when {
            looksLikeExpired(detail) -> RednoteQrCodeStatus.EXPIRED
            looksLikeCanceled(detail) -> RednoteQrCodeStatus.CANCELED
            codeStatus == null && (success == false || (code != null && code != 0L)) ->
                RednoteQrCodeStatus.FAILED
            else -> RednoteQrCodeStatus.WAITING
        }
    }
}

internal fun RednoteQrCodeStatus.toPublisherLoginResult(
    detail: String? = null,
    accountUserId: String? = null,
): PublisherLoginResult {
    val message = detail?.trim()?.takeIf { it.isNotEmpty() }
    return when (this) {
        RednoteQrCodeStatus.WAITING -> PublisherLoginResult(
            status = PublisherLoginStatus.PENDING,
            message = message ?: "等待小红书 App 扫码",
        )
        RednoteQrCodeStatus.SCANNED -> PublisherLoginResult(
            status = PublisherLoginStatus.PENDING,
            message = message ?: "已扫码，请在手机上确认登录",
        )
        RednoteQrCodeStatus.SUCCESS -> PublisherLoginResult(
            status = PublisherLoginStatus.SUCCESS,
            message = message ?: "小红书扫码登录成功",
            account = accountUserId?.takeIf { it.isNotBlank() }?.let {
                PublisherLoginAccount(userId = it)
            },
        )
        RednoteQrCodeStatus.EXPIRED -> PublisherLoginResult(
            status = PublisherLoginStatus.EXPIRED,
            message = message ?: "小红书登录二维码已过期，请重新获取",
        )
        RednoteQrCodeStatus.CANCELED -> PublisherLoginResult(
            status = PublisherLoginStatus.CANCELED,
            message = message ?: "已取消小红书扫码登录",
        )
        RednoteQrCodeStatus.FAILED -> PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = message ?: "小红书扫码登录失败",
        )
    }
}

internal fun RednoteQrLoginInfo.toCookiePairs(): Map<String, String> {
    val values = linkedMapOf<String, String>()
    session?.trim()?.takeIf { it.isNotEmpty() }?.let { values["web_session"] = it }
    secureSession?.trim()?.takeIf { it.isNotEmpty() }?.let { values["secure_session"] = it }
    return values
}

private fun looksLikeExpired(message: String): Boolean {
    val value = message.lowercase()
    return value.contains("过期") || value.contains("expire") || value.contains("timeout")
}

private fun looksLikeCanceled(message: String): Boolean {
    val value = message.lowercase()
    return value.contains("取消") || value.contains("cancel")
}

private fun requireApiSuccess(root: JsonObject, operation: String) {
    val success = root.boolean("success")
    val code = root.long("code")
    val message = root.string("msg", "message")?.trim().orEmpty()
    if (looksLikeRiskControl(code, message)) {
        throw RednoteBlockedException(
            message.ifBlank { "小红书请求疑似被风控，已停止继续尝试。请稍后再试。" },
        )
    }
    if (success == false || (code != null && code != 0L)) {
        throw RednoteApiException(
            message.ifBlank { operation },
        )
    }
}
