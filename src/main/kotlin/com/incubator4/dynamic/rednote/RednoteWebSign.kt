package com.incubator4.dynamic.rednote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Web API request headers expected by Xiaohongshu edith endpoints.
 *
 * Legacy `XYS`-style helper adapted from the MIT-licensed code in
 * https://github.com/ReaJason/xhs (Copyright (c) 2023 ReaJason).
 *
 * `XYW_` signing for data-fetching APIs follows the MIT-licensed algorithm in
 * https://github.com/Cloxl/xhshow (see issue #104 / PR #105).
 */
internal data class RednoteWebSignHeaders(
    val xS: String,
    val xT: String,
    val xSCommon: String,
    val xB3TraceId: String,
    val xXrayTraceId: String,
    val xMns: String = "unload",
    val xyDirection: String,
)

internal data class RednoteGuestIdentity(
    val a1: String,
    val webId: String,
)

internal fun generateRednoteGuestIdentity(random: Random = Random.Default): RednoteGuestIdentity {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val randomTail = buildString(30) {
        repeat(30) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
    val prefix = java.lang.Long.toHexString(System.currentTimeMillis()) + randomTail + "50" + "000"
    val crc = CRC32().also { it.update(prefix.toByteArray(StandardCharsets.UTF_8)) }.value
    val a1 = (prefix + crc.toString()).take(52)
    val webId = md5Hex(a1)
    return RednoteGuestIdentity(a1 = a1, webId = webId)
}

/**
 * Legacy signing used by non-data endpoints such as QR login.
 */
internal fun buildRednoteWebSign(
    uri: String,
    jsonBody: String? = null,
    a1: String = "",
    b1: String = "",
    userId: String? = null,
    epochMillis: Long = System.currentTimeMillis(),
    random: kotlin.random.Random = kotlin.random.Random.Default,
): RednoteWebSignHeaders {
    val xT = epochMillis.toString()
    val payload = jsonBody.orEmpty()
    val raw = "${xT}test${uri}${payload}"
    val md5 = md5Hex(raw)
    val xS = encodeXs(md5)
    val effectiveB1 = b1.ifBlank { RednoteFingerprint.generateB1(random, epochMillis) }
    return RednoteWebSignHeaders(
        xS = xS,
        xT = xT,
        xSCommon = buildXsCommon(a1 = a1, b1 = effectiveB1),
        xB3TraceId = generateB3TraceId(random),
        xXrayTraceId = generateXrayTraceId(epochMillis, random),
        xyDirection = shardingKey(userId, random).toString(),
    )
}

/**
 * `XYW_` signing required by data-fetching APIs (`user_posted`, `otherinfo`, `feed`, …)
 * that reject the older signature format with HTTP 406.
 *
 * [contentString] must be the same URI(+query) or URI+JSON body string that the
 * request will actually send (xhshow `_build_content_string`).
 */
internal fun buildRednoteXywSign(
    contentString: String,
    a1: String,
    b1: String = "",
    appId: String = XYW_APP_ID,
    userId: String? = null,
    epochMillis: Long = System.currentTimeMillis(),
    random: kotlin.random.Random = kotlin.random.Random.Default,
): RednoteWebSignHeaders {
    val xT = epochMillis.toString()
    val xS = signXyw(
        contentString = contentString,
        a1 = a1,
        timestampMs = xT,
        appId = appId,
    )
    val effectiveB1 = b1.ifBlank { RednoteFingerprint.generateB1(random, epochMillis) }
    return RednoteWebSignHeaders(
        xS = xS,
        xT = xT,
        xSCommon = buildXsCommon(a1 = a1, b1 = effectiveB1),
        xB3TraceId = generateB3TraceId(random),
        xXrayTraceId = generateXrayTraceId(epochMillis, random),
        xyDirection = shardingKey(userId, random).toString(),
    )
}

/**
 * Build the GET content string used both for XYW signing and as the request query,
 * matching xhshow's `_build_content_string` / `urllib.parse.quote(..., safe=",")`.
 */
internal fun buildRednoteGetContentString(apiPath: String, params: Map<String, String?>): String {
    if (params.isEmpty()) return apiPath
    val query = params.entries.joinToString("&") { (key, value) ->
        "$key=${encodeRednoteSignQueryValue(value.orEmpty())}"
    }
    return "$apiPath?$query"
}

internal fun encodeRednoteSignQueryValue(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    return buildString(bytes.size * 3) {
        bytes.forEach { byte ->
            val code = byte.toInt() and 0xFF
            if (isRednoteSignQuerySafe(code)) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX_DIGITS[code ushr 4])
                append(HEX_DIGITS[code and 0xF])
            }
        }
    }
}

private fun isRednoteSignQuerySafe(code: Int): Boolean {
    val ch = code.toChar()
    return ch in 'A'..'Z' ||
        ch in 'a'..'z' ||
        ch in '0'..'9' ||
        ch == '-' ||
        ch == '.' ||
        ch == '_' ||
        ch == '~' ||
        ch == ','
}

private fun signXyw(
    contentString: String,
    a1: String,
    timestampMs: String,
    appId: String,
): String {
    val payloadHex = buildXywPayloadHex(
        fullUri = contentString,
        a1 = a1,
        timestampMs = timestampMs,
    )
    val envelope = compactJsonObject(
        linkedMapOf(
            "signSvn" to XYW_SIGN_SVN,
            "signType" to XYW_SIGN_TYPE,
            "appId" to appId,
            "signVersion" to XYW_SIGN_VERSION,
            "payload" to payloadHex,
        ),
    )
    val encoded = Base64.getEncoder().encodeToString(envelope.toByteArray(StandardCharsets.UTF_8))
    return XYW_PREFIX + encoded
}

internal fun buildXywPayloadHex(
    fullUri: String,
    a1: String,
    timestampMs: String,
    envFlags: String = XYW_ENV_FLAGS_DEFAULT,
): String {
    val x1 = md5Hex("url=$fullUri")
    val message = "x1=$x1;x2=$envFlags;x3=$a1;x4=$timestampMs;"
        .toByteArray(StandardCharsets.UTF_8)
    val plaintext = Base64.getEncoder().encode(message)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(XYW_AES_KEY, "AES"),
        IvParameterSpec(XYW_AES_IV),
    )
    return cipher.doFinal(plaintext).joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        "${HEX_DIGITS[value ushr 4]}${HEX_DIGITS[value and 0xF]}"
    }.lowercase(Locale.ROOT)
}

/**
 * `x-s-common` header, aligned with xhshow `XsCommonSigner`.
 *
 * Unlike the legacy ReaJason/xhs layout, xhshow leaves `x6`/`x7` empty and
 * derives `x9` from `CRC32.js(b1)` instead of `mrc(xT+xS)`. Version fields
 * track the current xhs-pc-web build (`x1=4.3.5`, `x4=4.86.0`).
 */
private fun buildXsCommon(
    a1: String,
    b1: String,
): String {
    val common = compactJsonObject(
        linkedMapOf(
            "s0" to 5,
            "s1" to "",
            "x0" to "1",
            "x1" to "4.3.5",
            "x2" to "Windows",
            "x3" to "xhs-pc-web",
            "x4" to "4.86.0",
            "x5" to a1,
            "x6" to "",
            "x7" to "",
            "x8" to b1,
            "x9" to crc32JsSignedInt(b1),
            "x10" to 0,
            "x11" to "normal",
        ),
    )
    return customBase64Encode(encodeUtf8(common))
}

private const val XYW_PREFIX: String = "XYW_"
private const val XYW_APP_ID: String = "xhs-pc-web"
private const val XYW_SIGN_SVN: String = "56"
private const val XYW_SIGN_TYPE: String = "x2"
private const val XYW_SIGN_VERSION: String = "1"
private const val XYW_ENV_FLAGS_DEFAULT: String = "0|0|0|1|0|0|1|0|0|0|1|0|0|0|0|1|0|0|1"
private val XYW_AES_KEY: ByteArray = "7cc4adla5ay0701v".toByteArray(StandardCharsets.UTF_8)
private val XYW_AES_IV: ByteArray = "4uzjr7mbsibcaldp".toByteArray(StandardCharsets.UTF_8)
private val HEX_DIGITS: CharArray = "0123456789ABCDEF".toCharArray()

internal fun compactJsonObject(entries: Map<String, Any?>): String {
    if (entries.isEmpty()) return "{}"
    return buildString {
        append('{')
        entries.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append('"').append(escapeJson(key)).append('"').append(':')
            append(encodeJsonValue(value))
        }
        append('}')
    }
}

private fun encodeJsonValue(value: Any?): String {
    return when (value) {
        null -> "null"
        is Number -> value.toString()
        is Boolean -> value.toString()
        is String -> "\"${escapeJson(value)}\""
        else -> "\"${escapeJson(value.toString())}\""
    }
}

private fun escapeJson(value: String): String {
    return buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}

private fun encodeXs(md5Hex: String): String {
    val alphabet = "A4NjFqYu5wPHsO0XTdDgMa2r1ZQocVte9UJBvk6/7=yRnhISGKblCWi+LpfE8xzm3"
    val out = StringBuilder()
    var i = 0
    while (i < 32) {
        val o = md5Hex[i].code
        val g = if (i + 1 < 32) md5Hex[i + 1].code else 0
        val h = if (i + 2 < 32) md5Hex[i + 2].code else 0
        val x = ((o and 3) shl 4) or (g shr 4)
        var p = ((15 and g) shl 2) or (h shr 6)
        val v = o shr 2
        var b = if (h != 0) h and 63 else 64
        if (g == 0) {
            p = 64
            b = 64
        }
        out.append(alphabet[v]).append(alphabet[x]).append(alphabet[p]).append(alphabet[b])
        i += 3
    }
    return out.toString()
}

private fun md5Hex(value: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(((byte.toInt() shr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }.lowercase(Locale.ROOT)
}

private fun encodeUtf8(value: String): IntArray {
    return value.toByteArray(StandardCharsets.UTF_8)
        .map { byte -> byte.toInt() and 0xFF }
        .toIntArray()
}

internal fun customBase64Encode(bytes: IntArray): String {
    val lookup = CUSTOM_B64
    val length = bytes.size
    val remainder = length % 3
    val chunks = ArrayList<String>()
    val limit = 16383
    var start = 0
    val alignedEnd = length - remainder
    while (start < alignedEnd) {
        val end = minOf(alignedEnd, start + limit)
        chunks += encodeChunk(bytes, start, end, lookup)
        start += limit
    }
    if (remainder == 1) {
        val value = bytes[length - 1]
        chunks += lookup[value shr 2] + lookup[(value shl 4) and 63] + "=="
    } else if (remainder == 2) {
        val value = (bytes[length - 2] shl 8) + bytes[length - 1]
        chunks += lookup[value shr 10] + lookup[(value shr 4) and 63] + lookup[(value shl 2) and 63] + "="
    }
    return chunks.joinToString("")
}

private fun encodeChunk(bytes: IntArray, start: Int, end: Int, lookup: List<String>): String {
    val out = StringBuilder()
    var index = start
    while (index < end) {
        val packed = ((bytes[index] and 0xFF) shl 16) +
            ((bytes[index + 1] and 0xFF) shl 8) +
            (bytes[index + 2] and 0xFF)
        out.append(lookup[(packed shr 18) and 63])
            .append(lookup[(packed shr 12) and 63])
            .append(lookup[(packed shr 6) and 63])
            .append(lookup[packed and 63])
        index += 3
    }
    return out.toString()
}

private val CUSTOM_B64: List<String> = listOf(
    "Z", "m", "s", "e", "r", "b", "B", "o", "H", "Q", "t", "N", "P", "+", "w", "O",
    "c", "z", "a", "/", "L", "p", "n", "g", "G", "8", "y", "J", "q", "4", "2", "K",
    "W", "Y", "j", "0", "D", "S", "f", "d", "i", "k", "x", "3", "V", "T", "1", "6",
    "I", "l", "U", "A", "F", "M", "9", "7", "h", "E", "C", "v", "u", "R", "X", "5",
)

private val HEX_LOWER: CharArray = "0123456789abcdef".toCharArray()

/**
 * JavaScript-style CRC32, matching xhshow `CRC32.crc32_js_int`:
 * `(-1 ^ c ^ 0xEDB88320) >>> 0` then interpreted as a signed 32-bit int.
 * String input uses the lower 8 bits of each char code point (JS `charCodeAt`).
 */
internal fun crc32JsSignedInt(data: String): Int {
    val table = CRC32_TABLE
    var c = -1
    for (ch in data) {
        val b = ch.code and 0xFF
        c = table[(c and 0xFF) xor b] xor (c shr 8)
    }
    // (-1 ^ c ^ 0xEDB88320) >>> 0; Kotlin Int is already a signed 32-bit value,
    // so the unsigned-to-signed conversion Python performs is implicit here.
    return c.inv() xor 0xEDB88320.toInt()
}

private val CRC32_TABLE: IntArray = IntArray(256) { d ->
    var r = d
    repeat(8) {
        r = if ((r and 1) != 0) (r shr 1) xor 0xEDB88320.toInt() else r shr 1
    }
    r
}

/**
 * `x-b3-traceid`: 16 random lowercase hex characters (xhshow `generate_b3_trace_id`).
 */
internal fun generateB3TraceId(random: kotlin.random.Random = kotlin.random.Random.Default): String {
    val sb = StringBuilder(16)
    repeat(16) { sb.append(HEX_LOWER[random.nextInt(16)]) }
    return sb.toString()
}

/**
 * `x-xray-traceid`: 32 hex characters. First 16 encode `(timestampMs << 23) | seq`,
 * last 16 are random (xhshow `generate_xray_trace_id`).
 */
internal fun generateXrayTraceId(
    timestampMs: Long,
    random: kotlin.random.Random = kotlin.random.Random.Default,
): String {
    val seq = random.nextInt(0, 1 shl 23)
    val shifted = (timestampMs.toLong() shl 23) or seq.toLong()
    val part1 = String.format("%016x", shifted)
    val sb = StringBuilder(16)
    repeat(16) { sb.append(HEX_LOWER[random.nextInt(16)]) }
    return part1 + sb.toString()
}

/**
 * `xy-direction` sharding key, matching xhshow `get_sharding_key`:
 * MurmurHash3 x86 32 of the user id, then `(r % 100) + 1`. When no user id is
 * available, fall back to a random value in `10..100` (xhshow default).
 */
internal fun shardingKey(userId: String?, random: kotlin.random.Random = kotlin.random.Random.Default): Int {
    if (userId.isNullOrBlank()) return random.nextInt(10, 101)
    val data = userId.toByteArray(StandardCharsets.UTF_8)
    val length = data.size
    var r = 151488
    val blocks = length / 4
    for (o in 0 until blocks) {
        val i = 4 * o
        var u = (data[i].toInt() and 0xFF) or
            ((data[i + 1].toInt() and 0xFF) shl 8) or
            ((data[i + 2].toInt() and 0xFF) shl 16) or
            ((data[i + 3].toInt() and 0xFF) shl 24)
        u = imul32(u, C1)
        u = rotl32(u, 15)
        u = imul32(u, C2)
        r = r xor u
        r = rotl32(r, 13)
        r = imul32(r, 5) + -1640531527 // 0xE6546B64
    }
    val s = 4 * blocks
    var c = 0
    val rem = length % 4
    if (rem >= 3) c = c xor ((data[s + 2].toInt() and 0xFF) shl 16)
    if (rem >= 2) c = c xor ((data[s + 1].toInt() and 0xFF) shl 8)
    if (rem >= 1) {
        c = c xor (data[s].toInt() and 0xFF)
        c = imul32(c, C1)
        c = rotl32(c, 15)
        c = imul32(c, C2)
        r = r xor c
    }
    r = r xor length
    r = r and 0xFFFFFFFF.toInt()
    r = r xor (r ushr 16)
    r = imul32(r, 0x85EBCA6B.toInt()) and 0xFFFFFFFF.toInt()
    r = r xor (r ushr 13)
    r = imul32(r, 0xC2B2AE35.toInt()) and 0xFFFFFFFF.toInt()
    r = r xor (r ushr 16)
    return (r and 0x7FFFFFFF) % 100 + 1
}

private const val C1: Int = -862048943 // 0xCC9E2D51
private const val C2: Int = 461845907

private fun imul32(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

private fun rotl32(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

