package com.incubator4.dynamic.rednote

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SM4-variant block cipher (ECB) and xxHash32, ported from the MIT-licensed
 * pure-Python implementation in https://github.com/Cloxl/xhshow (`core/xrap.py`,
 * `utils/hash.py`). Used only by `RednoteXrap` to build the `x-rap-param` header.
 *
 * Large constant tables are stored as hex strings and parsed once at class init
 * to keep the source compact.
 */
internal object RednoteXrapCipher {

    private const val MASK_32: Int = -1 // 0xFFFFFFFF as signed

    /** xxHash32 (little-endian input), matching xhshow `utils/hash.py`. */
    fun xxh32(data: ByteArray, seed: Int = 0): Int {
        val prime1 = 0x9E3779B1.toInt()
        val prime2 = 0x85EBCA77.toInt()
        val prime3 = 0xC2B2AE3D.toInt()
        val prime4 = 0x27D4EB2F.toInt()
        val prime5 = 0x165667B1.toInt()

        val length = data.size
        var pos = 0
        var digest: Int

        if (length >= 16) {
            var acc1 = (seed + prime1 + prime2) and MASK_32
            var acc2 = (seed + prime2) and MASK_32
            var acc3 = seed and MASK_32
            var acc4 = (seed - prime1) and MASK_32
            val end = length - 16
            while (pos <= end) {
                acc1 = rotl32((acc1 + readU32Le(data, pos) * prime2) and MASK_32, 13) * prime1 and MASK_32
                acc2 = rotl32((acc2 + readU32Le(data, pos + 4) * prime2) and MASK_32, 13) * prime1 and MASK_32
                acc3 = rotl32((acc3 + readU32Le(data, pos + 8) * prime2) and MASK_32, 13) * prime1 and MASK_32
                acc4 = rotl32((acc4 + readU32Le(data, pos + 12) * prime2) and MASK_32, 13) * prime1 and MASK_32
                pos += 16
            }
            digest = (rotl32(acc1, 1) + rotl32(acc2, 7) + rotl32(acc3, 12) + rotl32(acc4, 18)) and MASK_32
        } else {
            digest = (seed + prime5) and MASK_32
        }

        digest = (digest + length) and MASK_32
        while (pos + 4 <= length) {
            digest = rotl32((digest + readU32Le(data, pos) * prime3) and MASK_32, 17) * prime4 and MASK_32
            pos += 4
        }
        while (pos < length) {
            digest = rotl32((digest + (data[pos].toInt() and 0xFF) * prime5) and MASK_32, 11) * prime1 and MASK_32
            pos += 1
        }
        digest = digest xor (digest ushr 15)
        digest = digest * prime2 and MASK_32
        digest = digest xor (digest ushr 13)
        digest = digest * prime3 and MASK_32
        digest = digest xor (digest ushr 16)
        return digest and MASK_32
    }

    /** Encrypt 16-byte blocks in ECB mode; final partial block is zero-padded. */
    fun encryptBlocks(src: ByteArray): ByteArray {
        val out = ByteArray(((src.size + 15) / 16) * 16)
        var i = 0
        while (i < src.size) {
            val block = encryptBlock16(src.copyOfRange(i, minOf(i + 16, src.size)))
            System.arraycopy(block, 0, out, i, 16)
            i += 16
        }
        return out
    }

    private fun encryptBlock16(block: ByteArray): ByteArray {
        val padded = ByteArray(16)
        System.arraycopy(block, 0, padded, 0, minOf(block.size, 16))
        var s0 = readU32Be(padded, 0) xor ROUND_KEYS[0][0]
        var s1 = readU32Be(padded, 4) xor ROUND_KEYS[0][1]
        var s2 = readU32Be(padded, 8) xor ROUND_KEYS[0][2]
        var s3 = readU32Be(padded, 12) xor ROUND_KEYS[0][3]
        for (rnd in 1 until 10) {
            val rk = ROUND_KEYS[rnd]
            val n0 = LUT0[(s0 ushr 24) and 0xFF] xor LUT1[(s1 ushr 16) and 0xFF] xor LUT2[(s2 ushr 8) and 0xFF] xor LUT3[s3 and 0xFF] xor rk[0]
            val n1 = LUT0[(s1 ushr 24) and 0xFF] xor LUT1[(s2 ushr 16) and 0xFF] xor LUT2[(s3 ushr 8) and 0xFF] xor LUT3[s0 and 0xFF] xor rk[1]
            val n2 = LUT0[(s2 ushr 24) and 0xFF] xor LUT1[(s3 ushr 16) and 0xFF] xor LUT2[(s0 ushr 8) and 0xFF] xor LUT3[s1 and 0xFF] xor rk[2]
            val n3 = LUT0[(s3 ushr 24) and 0xFF] xor LUT1[(s0 ushr 16) and 0xFF] xor LUT2[(s1 ushr 8) and 0xFF] xor LUT3[s2 and 0xFF] xor rk[3]
            s0 = n0; s1 = n1; s2 = n2; s3 = n3
        }
        val state = intArrayOf(s0, s1, s2, s3)
        val out = ByteArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val idx = (state[(row + col) and 3] ushr (24 - 8 * col)) and 0xFF
                out[4 * row + col] = (SBOX[idx].toInt() and 0xFF xor (LAST_ROUND_KEY_BYTES[4 * row + col].toInt() and 0xFF)).toByte()
            }
        }
        return out
    }

    private fun readU32Be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun readU32Le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun rotl32(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

    // --- GF(2^8) T-tables built from the custom S-box ---

    private fun gfDouble(x: Int): Int {
        val v = x shl 1
        return if (v and 0x100 != 0) (v xor 0x11B) and 0xFF else v and 0xFF
    }

    private fun gfMul(x: Int, n: Int): Int = when (n) {
        1 -> x
        2 -> gfDouble(x)
        3 -> gfDouble(x) xor x
        else -> throw IllegalArgumentException("unsupported GF multiplier: $n")
    }

    private val SBOX: ByteArray = parseHexBytes(
        "7A0158E0504E02791D4B53DA6B48D452" +
            "ED7712211415EC1018E5B9F10C08FC7D" +
            "F9CDB5C8E637268756BAB82BADF068F7" +
            "8B8DD35E364D2E923182F229703D2DD7" +
            "B640B243448078D20D494A09636C073A" +
            "9ED506C6E162F4342459A9572A003E17" +
            "2C0A1A42FA93BEDCF5B36A13E803C797" +
            "BB737686E3467247D0054C387C1F81AB" +
            "7551EBF33274118F84899C71227E9DCF" +
            "3F9169653C6D96A2989933399ACAC39F" +
            "A0BCE4A3A4547FA7A8046F5DACB727AF" +
            "B02841AEB46E0B1BDF8E30B1FE906160" +
            "C0CB5C0EEF1683EA20E9C955C44585CC" +
            "1EAA678A7B35D619D8D9C2DB94DD1CDE" +
            "A6FFF8BF5B5A0FE7C1BDD166C525EE8C" +
            "E25F88A13BA5F6CE952F6423FBFD4F9B",
    )

    private val LUT0: IntArray
    private val LUT1: IntArray
    private val LUT2: IntArray
    private val LUT3: IntArray

    init {
        val c0 = IntArray(256); val c1 = IntArray(256); val c2 = IntArray(256); val c3 = IntArray(256)
        for (i in 0 until 256) {
            val s = SBOX[i].toInt() and 0xFF
            val a = gfMul(s, 2); val d = gfMul(s, 3)
            c0[i] = ((a shl 24) or (s shl 16) or (s shl 8) or d) and MASK_32
            c1[i] = ((d shl 24) or (a shl 16) or (s shl 8) or s) and MASK_32
            c2[i] = ((s shl 24) or (d shl 16) or (a shl 8) or s) and MASK_32
            c3[i] = ((s shl 24) or (s shl 16) or (d shl 8) or a) and MASK_32
        }
        LUT0 = c0; LUT1 = c1; LUT2 = c2; LUT3 = c3
    }

    private val ROUND_KEYS: Array<IntArray> = parseRoundKeys(
        "6B714931445463774B5839305A744179" +
            "89314C98CD652FEF863D16DFDC4957A6" +
            "C205330C0F601CE3895D0A3C55145D9A" +
            "D205006EDD651C8D543816B1012C4B2B" +
            "770C2B6FAA6937E2FE512153FF7D6A78" +
            "7866FBF4D20FCC162C5EED45D323873D" +
            "90E9C67E42E60A686EB8E72DBD9B6010" +
            "E9C52BEEAB232186C59BC6AB7800A6BB" +
            "13BA9A3EB899BBB87D027D130502DBA8" +
            "50613270E8F889C895FAF4DB90F82F73",
    )

    private val LAST_ROUND_KEY: IntArray = parseRoundKeys("F396B44F1B6E3D878E94C95C1E6CE62F")[0]

    private val LAST_ROUND_KEY_BYTES: ByteArray = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        .putInt(LAST_ROUND_KEY[0]).putInt(LAST_ROUND_KEY[1]).putInt(LAST_ROUND_KEY[2]).putInt(LAST_ROUND_KEY[3])
        .array()

    private fun parseRoundKeys(hex: String): Array<IntArray> {
        val bytes = parseHexBytes(hex)
        require(bytes.size % 16 == 0)
        return Array(bytes.size / 16) { row ->
            intArrayOf(
                readU32Be(bytes, row * 16),
                readU32Be(bytes, row * 16 + 4),
                readU32Be(bytes, row * 16 + 8),
                readU32Be(bytes, row * 16 + 12),
            )
        }
    }

    private fun parseHexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
