package com.incubator4.dynamic.rednote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Browser fingerprint `b1` generator, aligned with the MIT-licensed algorithm in
 * https://github.com/Cloxl/xhshow (`generators/fingerprint.py`).
 *
 * `b1` feeds the `x-s-common` header (`x8`) and its CRC32 checksum (`x9`).
 * The full xhshow fingerprint samples many browser fields; here we emit a
 * stable, plausible PC fingerprint so the server-validated `x8`/`x9` pair is
 * internally consistent. Only the subset consumed by `generate_b1` is modeled.
 */
internal object RednoteFingerprint {

    private const val B1_SECRET_KEY: String = "xhswebmplfbt"

    /**
     * Generate a `b1` string from a freshly sampled fingerprint.
     */
    fun generateB1(random: Random = Random.Default, nowMillis: Long = System.currentTimeMillis()): String {
        val fingerprint = sampleFingerprint(random, nowMillis)
        return generateB1(fingerprint)
    }

    /**
     * Encode a fingerprint dict into the `b1` base64 string, mirroring xhshow
     * `FingerprintGenerator.generate_b1`.
     */
    private fun generateB1(fp: Map<String, Any>): String {
        val b1Fp = linkedMapOf<String, Any>()
        for (key in B1_FIELDS) {
            b1Fp[key] = fp[key] ?: ""
        }
        val json = compactJsonObject(b1Fp)
        val cipherBytes = arc4Encrypt(B1_SECRET_KEY.toByteArray(StandardCharsets.UTF_8), json.toByteArray(StandardCharsets.UTF_8))
        // xhshow decodes the ciphertext as latin1, then urllib.quote with safe="!*'()~_-"
        val latin1 = String(cipherBytes, StandardCharsets.ISO_8859_1)
        val quoted = urlQuoteB1(latin1)
        // xhshow: split by '%', take first two hex chars as a byte, rest as ord(char)
        val bytes = ArrayList<Int>()
        val parts = quoted.split('%')
        for ((index, part) in parts.withIndex()) {
            if (index == 0) {
                // before the first '%' the chars are literal (already safe chars)
                for (ch in part) bytes.add(ch.code and 0xFF)
                continue
            }
            if (part.length >= 2) {
                bytes.add(part.substring(0, 2).toInt(16))
                for (i in 2 until part.length) bytes.add(part[i].code and 0xFF)
            }
        }
        return customBase64Encode(bytes.toIntArray())
    }

    private fun sampleFingerprint(random: Random, nowMillis: Long): Map<String, Any> {
        val vendor = "Google Inc. (Intel)"
        val renderer = "ANGLE (Intel, Intel(R) UHD Graphics 630 (0x00003E9B) Direct3D11 vs_5_0 ps_5_0, D3D11)"
        val webglHash = md5Hex(randomBytesHex(random, 32))
        val canvasHash = "cdf0e539f0d6b7f0e8f2c1a3b9e7f4d1"
        val screen = "1920;1080"
        val screenAvail = "1920;1040"
        val fp = linkedMapOf<String, Any>()
        fp["x1"] = DESKTOP_USER_AGENT
        fp["x2"] = "false"
        fp["x3"] = "zh-CN"
        fp["x4"] = "24"
        fp["x5"] = "8"
        fp["x6"] = "24"
        fp["x7"] = "$vendor,$renderer"
        fp["x8"] = "8"
        fp["x9"] = screen
        fp["x10"] = screenAvail
        fp["x11"] = "-480"
        fp["x12"] = "Asia/Shanghai"
        fp["x13"] = "false"
        fp["x14"] = "false"
        fp["x15"] = "false"
        fp["x16"] = "false"
        fp["x17"] = "false"
        fp["x18"] = "un"
        fp["x19"] = "Win32"
        fp["x20"] = ""
        fp["x21"] = "PDF Viewer,Chrome PDF Viewer,Chromium PDF Viewer,Microsoft Edge PDF Viewer,WebKit built-in PDF"
        fp["x22"] = webglHash
        fp["x23"] = "false"
        fp["x24"] = "false"
        fp["x25"] = "false"
        fp["x26"] = "false"
        fp["x27"] = "false"
        fp["x28"] = "0,false,false"
        fp["x29"] = "4,7,8"
        fp["x30"] = "swf object not loaded"
        fp["x33"] = "0"
        fp["x34"] = "0"
        fp["x35"] = "0"
        fp["x36"] = "${random.nextInt(1, 21)}"
        fp["x37"] = "0|0|0|0|0|0|0|0|0|1|0|0|0|0|0|0|0|0|1|0|0|0|0|0"
        fp["x38"] = "0|0|1|0|1|0|0|0|0|0|1|0|1|0|1|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0"
        fp["x39"] = 0
        fp["x40"] = "0"
        fp["x41"] = "0"
        fp["x42"] = "3.4.4"
        fp["x43"] = canvasHash
        fp["x44"] = nowMillis.toString()
        fp["x45"] = "__SEC_CAV__1-1-1-1-1|__SEC_WSA__|"
        fp["x46"] = "false"
        fp["x47"] = "1|0|0|0|0|0"
        fp["x48"] = ""
        fp["x49"] = "{list:[],type:}"
        fp["x50"] = ""
        fp["x51"] = ""
        fp["x52"] = ""
        fp["x82"] = "_0x17a2|_0x1954"
        return fp
    }

    private fun randomBytesHex(random: Random, count: Int): String {
        val sb = StringBuilder(count * 2)
        repeat(count) {
            val v = random.nextInt(256)
            sb.append(HEX_LOWER[v ushr 4])
            sb.append(HEX_LOWER[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * Quote a string for the `b1` URL path, matching Python `urllib.parse.quote(s, safe="!*'()~_-")`.
     * Everything except [A-Za-z0-9] and the safe set is percent-encoded using the
     * byte's value (latin1 code point for U+0000..U+00FF).
     */
    private fun urlQuoteB1(input: String): String {
        val out = StringBuilder(input.length * 3)
        for (ch in input) {
            val code = ch.code
            if (code in 0..255) {
                if (isB1Safe(code)) {
                    out.append(ch)
                } else {
                    out.append('%')
                    out.append(HEX_UPPER[code ushr 4])
                    out.append(HEX_UPPER[code and 0xF])
                }
            } else {
                // non-latin1: encode as UTF-8 bytes then percent-encode each byte
                val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    if (isB1Safe(v)) {
                        out.append(v.toChar())
                    } else {
                        out.append('%')
                        out.append(HEX_UPPER[v ushr 4])
                        out.append(HEX_UPPER[v and 0xF])
                    }
                }
            }
        }
        return out.toString()
    }

    private fun isB1Safe(code: Int): Boolean {
        val ch = code.toChar()
        return ch in 'A'..'Z' ||
            ch in 'a'..'z' ||
            ch in '0'..'9' ||
            ch == '!' ||
            ch == '*' ||
            ch == '\'' ||
            ch == '(' ||
            ch == ')' ||
            ch == '~' ||
            ch == '_' ||
            ch == '-'
    }

    /**
     * ARC4 (RC4) stream cipher. Java does not ship RC4, so we implement it directly.
     */
    private fun arc4Encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0
        var k = 0
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            k = (k + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[k]; s[k] = tmp
            out[n] = (data[n].toInt() xor s[(s[i] + s[k]) and 0xFF]).toByte()
        }
        return out
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(HEX_LOWER[(byte.toInt() shr 4) and 0xF])
                append(HEX_LOWER[byte.toInt() and 0xF])
            }
        }
    }

    private val B1_FIELDS: List<String> = listOf(
        "x33", "x34", "x35", "x36", "x37", "x38", "x39",
        "x42", "x43", "x44", "x45", "x46", "x48", "x49", "x50", "x51", "x52", "x82",
    )

    private val HEX_LOWER: CharArray = "0123456789abcdef".toCharArray()
    private val HEX_UPPER: CharArray = "0123456789ABCDEF".toCharArray()
}
