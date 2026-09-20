package com.incubator4.dynamic.rednote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * `x-rap-param` risk-control header, aligned with the MIT-licensed pure-Python
 * algorithm in https://github.com/Cloxl/xhshow (`core/xrap.py`).
 *
 * Required by edith endpoints that perform write/read risk control beyond the
 * `x-s` signature — notably `feed`, search, and note publishing. Without it
 * these endpoints reject the request with HTTP 406 even when `x-s` is valid.
 *
 * The header is a base64 envelope wrapping a gzip-compressed, SM4-variant
 * encrypted TLV body whose hash binds the request API path + body to the
 * caller session. The SM4 cipher and xxHash32 live in [RednoteXrapCipher].
 */
internal object RednoteXrap {

    private const val SDK_VERSION: Int = 10300

    fun build(
        api: String,
        bodyJson: String,
        random: Random = Random.Default,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val rawBody = buildBodyStructure(api, bodyJson, random, nowMillis)
        val compressed = gzipCompress(rawBody)
        return packEnvelope(compressed, random)
    }

    private fun buildBodyStructure(
        api: String,
        bodyJson: String,
        random: Random,
        nowMillis: Long,
    ): ByteArray {
        val hashInput = (api + bodyJson).toByteArray(StandardCharsets.UTF_8)
        val ts = nowMillis
        val sessionKey = randomAscii(random, 16).toByteArray(StandardCharsets.US_ASCII)
        val xorByte = random.nextInt(1, 256)
        val randNonce = random.nextInt()
        val trace = DEFAULT_INTERACTION_TRACE
        val env = DEFAULT_ENVIRONMENT_SNAPSHOT

        val buf = ByteArrayOutputStream()
        buf.write(fieldU64(0x03E8, ts))
        buf.write(fieldU32(0x03E9, randNonce))
        buf.write(fieldBlob(0x03EA, sessionKey))
        buf.write(fieldU32(0x03EB, RednoteXrapCipher.xxh32(hashInput)))
        // Boolean capability flags: tags 1051..1065, then 1070, then 1066..1069
        for (tag in 1051..1065) buf.write(fieldByte(tag, 0))
        buf.write(fieldByte(1070, 0))
        for (tag in 1066..1069) buf.write(fieldByte(tag, 0))
        buf.write(fieldU32(1100, 0))
        for (tag in 1071..1073) buf.write(fieldByte(tag, 0))
        buf.write(fieldU32(1075, 0x564))
        buf.write(fieldU32(1076, 0x2C))
        buf.write(fieldU64(1077, maxOf(0L, ts - 0x434)))
        buf.write(fieldBlob(1078, trace))
        buf.write(fieldU32(1082, 0))
        buf.write(fieldU32(1084, 0))
        buf.write(fieldU32(1085, 0))
        buf.write(fieldU32(1086, 100))
        buf.write(fieldU64(1087, maxOf(0L, ts - 0x2D7)))
        buf.write(fieldBlob(1088, env))
        buf.write(fieldU32(1090, 0))
        buf.write(fieldU32(1097, 0))
        buf.write(fieldU32(1092, 0x566))
        buf.write(fieldU32(1094, 0x519))
        buf.write(fieldU64(1095, maxOf(0L, ts - 0x218C)))
        buf.write(fieldU32(1093, 0))
        buf.write(fieldByte(1096, 0))
        buf.write(fieldBlob(1091, byteArrayOf(0, 0, -1, -1)))
        for (tag in 1151..1156) buf.write(fieldByte(tag, 0))

        val raw = buf.toByteArray()
        // First 16 bytes cleartext; rest XOR-obfuscated with xorByte.
        val out = ByteArray(raw.size)
        System.arraycopy(raw, 0, out, 0, minOf(16, raw.size))
        for (i in 16 until raw.size) out[i] = (raw[i].toInt() xor xorByte).toByte()
        return out
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        val result = out.toByteArray()
        // Patch the OS byte (byte 9 of the gzip header) to 0x03 to match the browser runtime.
        if (result.size >= 10) result[9] = 0x03
        return result
    }

    private fun packEnvelope(compressed: ByteArray, random: Random): String {
        val encryptionKey = randomAscii(random, 16).toByteArray(StandardCharsets.US_ASCII)
        val salt = randomAscii(random, random.nextInt(4, 7)).toByteArray(StandardCharsets.US_ASCII)

        val xored = cyclicXor(compressed, encryptionKey)
        val cipherBlocks = RednoteXrapCipher.encryptBlocks(xored)
        val cipherBody = ByteArray(cipherBlocks.size + 4)
        System.arraycopy(cipherBlocks, 0, cipherBody, 0, cipherBlocks.size)
        writeU32Be(cipherBody, cipherBlocks.size, compressed.size)

        val encryptedSessionKey = RednoteXrapCipher.encryptBlocks(encryptionKey) + fieldU32Raw(16)
        val content = ByteArray(salt.size + encryptedSessionKey.size + cipherBody.size)
        var off = 0
        System.arraycopy(salt, 0, content, off, salt.size); off += salt.size
        System.arraycopy(encryptedSessionKey, 0, content, off, encryptedSessionKey.size); off += encryptedSessionKey.size
        System.arraycopy(cipherBody, 0, content, off, cipherBody.size)

        val contentHash = RednoteXrapCipher.xxh32(content)
        val encTime = random.nextInt(60, 241)

        val header = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN)
            .put(0x07.toByte()).put(0x24.toByte()).put(0x01.toByte()).put(salt.size.toByte())
            .putInt(1)
            .putInt(20)
            .putInt(cipherBody.size)
            .putInt(contentHash)
            .putInt(SDK_VERSION)
            .putInt(encTime)
            .put(ByteArray(8))
            .array()

        return Base64.getEncoder().encodeToString(header + content)
    }

    // --- TLV field writers (big-endian) ---

    private fun fieldByte(tag: Int, value: Int): ByteArray =
        ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN).putShort(tag.toShort()).put(value.toByte()).array()

    private fun fieldU32(tag: Int, value: Int): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).putShort(tag.toShort()).putInt(value).array()

    private fun fieldU32Raw(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun fieldU64(tag: Int, value: Long): ByteArray =
        ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).putShort(tag.toShort()).putLong(value).array()

    private fun fieldBlob(tag: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(6 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(tag.toShort())
        buf.putInt(data.size)
        buf.put(data)
        return buf.array()
    }

    private fun writeU32Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun cyclicXor(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    private fun randomAscii(random: Random, length: Int): String {
        val charset = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(length)
        repeat(length) { sb.append(charset[random.nextInt(charset.length)]) }
        return sb.toString()
    }

    private val DEFAULT_INTERACTION_TRACE: ByteArray = parseHex(
        "0002000200004700000000000001ffd9ffa8005c23fff1ffff001501ff90fff9000022fff2ffff" +
            "001501ffb800770061a5ffe3ffff002a01fffcffe70000f0ffe4ffff002a01ffd30000003e01" +
            "ffd40000003e01ffc8ffff005301ffbdfffa006901ffbefffb006901ffb1fff4007d01ffa6ff" +
            "ee009301ffa8ffef009301ff9effe900a801ff96ffe600be01ff97ffe600be01ff93ffe500d3" +
            "01ff92ffe500ea01ff92ffe4010501ff92ffe3011b01ff92ffe402d101ff93ffe502f201ff94" +
            "ffe6040501",
    )

    private val DEFAULT_ENVIRONMENT_SNAPSHOT: ByteArray = parseHex(
        "000000010000000000000000fffeffff00000000000000390001ffff00bc00000000006e0002" +
            "0001013400000000012f00000002011a00000000016100000000019f000000000247000000000279" +
            "0000000002b1",
    )

    private fun parseHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
