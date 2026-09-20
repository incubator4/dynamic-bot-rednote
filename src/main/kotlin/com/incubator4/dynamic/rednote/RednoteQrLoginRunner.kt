package com.incubator4.dynamic.rednote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge

internal suspend fun runRednoteQrLogin(
    onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
    onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    createChallenge: suspend () -> RednoteQrCodeChallenge,
    pollStatus: suspend (qrId: String, code: String) -> RednoteQrStatusSnapshot,
    applyLoginInfo: (RednoteQrLoginInfo) -> Unit,
    verifyLogin: suspend () -> PublisherLoginResult,
    completeLogin: (suspend (qrId: String, code: String, confirmedUserId: String?) -> RednoteQrLoginInfo)? = null,
    pollIntervalMs: Long = REDNOTE_QR_POLL_INTERVAL_MILLIS,
    timeoutMs: Long = REDNOTE_QR_TIMEOUT_MILLIS,
    delayMillis: suspend (Long) -> Unit = { delay(it) },
    nowMillis: () -> Long = { System.currentTimeMillis() },
): PublisherLoginResult {
    val boundedPollInterval = pollIntervalMs.coerceAtLeast(1_000)
    val challenge = createChallenge()
    onQrCode(challenge.toPublisherChallenge(boundedPollInterval))
    onStatusChanged(RednoteQrCodeStatus.WAITING.toPublisherLoginResult())

    val deadline = nowMillis() + timeoutMs.coerceAtLeast(1_000)
    var lastStatus = RednoteQrCodeStatus.WAITING
    var consecutiveErrors = 0
    while (nowMillis() < deadline) {
        delayMillis(boundedPollInterval)
        val snapshot = try {
            pollStatus(challenge.qrId, challenge.code)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RednoteBlockedException) {
            throw error
        } catch (error: Throwable) {
            consecutiveErrors += 1
            if (consecutiveErrors >= REDNOTE_QR_POLL_ERROR_LIMIT) {
                throw error
            }
            continue
        }
        consecutiveErrors = 0
        val status = snapshot.resolveStatus()
        if (status != lastStatus) {
            lastStatus = status
            onStatusChanged(
                status.toPublisherLoginResult(
                    detail = snapshot.message,
                    accountUserId = snapshot.confirmedUserId(),
                ),
            )
        }
        when (status) {
            RednoteQrCodeStatus.SUCCESS -> {
                val confirmedUserId = snapshot.confirmedUserId()
                val loginInfo = if (completeLogin != null) {
                    completeLogin(challenge.qrId, challenge.code, confirmedUserId)
                } else {
                    snapshot.loginInfo ?: RednoteQrLoginInfo(userId = confirmedUserId)
                }
                applyLoginInfo(loginInfo)
                val verified = verifyLogin()
                if (verified.status == PublisherLoginStatus.SUCCESS) {
                    return verified
                }
                val failed = PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = verified.message.ifBlank {
                        "小红书扫码登录成功，但登录态校验未通过"
                    },
                )
                onStatusChanged(failed)
                return failed
            }
            RednoteQrCodeStatus.EXPIRED,
            RednoteQrCodeStatus.CANCELED,
            RednoteQrCodeStatus.FAILED,
            -> return status.toPublisherLoginResult(detail = snapshot.message)
            RednoteQrCodeStatus.WAITING,
            RednoteQrCodeStatus.SCANNED,
            -> Unit
        }
    }
    return RednoteQrCodeStatus.EXPIRED.toPublisherLoginResult()
}

internal suspend fun safeRunRednoteQrLogin(
    block: suspend () -> PublisherLoginResult,
): PublisherLoginResult {
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RednoteBlockedException) {
        PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = error.message ?: "小红书扫码登录疑似被风控",
        )
    } catch (error: Throwable) {
        PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = error.message ?: "小红书扫码登录失败",
        )
    }
}
