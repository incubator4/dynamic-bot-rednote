package com.incubator4.dynamic.rednote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * Web API request headers expected by Xiaohongshu edith endpoints.
 *
 * Adapted from the MIT-licensed helper in https://github.com/ReaJason/xhs (Copyright (c) 2023 ReaJason).
 */
internal data class RednoteWebSignHeaders(
    val xS: String,
    val xT: String,
    val xSCommon: String,
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

internal fun buildRednoteWebSign(
    uri: String,
    jsonBody: String? = null,
    a1: String = "",
    b1: String = "",
    epochMillis: Long = System.currentTimeMillis(),
): RednoteWebSignHeaders {
    val xT = epochMillis.toString()
    val payload = jsonBody.orEmpty()
    val raw = "${xT}test${uri}${payload}"
    val md5 = md5Hex(raw)
    val xS = encodeXs(md5)
    val common = buildString {
        append('{')
        append("\"s0\":5,")
        append("\"s1\":\"\",")
        append("\"x0\":\"1\",")
        append("\"x1\":\"3.2.0\",")
        append("\"x2\":\"Windows\",")
        append("\"x3\":\"xhs-pc-web\",")
        append("\"x4\":\"2.3.1\",")
        append("\"x5\":\"").append(escapeJson(a1)).append("\",")
        append("\"x6\":\"").append(escapeJson(xT)).append("\",")
        append("\"x7\":\"").append(escapeJson(xS)).append("\",")
        append("\"x8\":\"").append(escapeJson(b1)).append("\",")
        append("\"x9\":").append(mrc(xT + xS)).append(',')
        append("\"x10\":1")
        append('}')
    }
    return RednoteWebSignHeaders(
        xS = xS,
        xT = xT,
        xSCommon = customBase64Encode(encodeUtf8(common)),
    )
}

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

private fun customBase64Encode(bytes: IntArray): String {
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

private fun mrc(input: String): Int {
    var o = -1
    for (n in 0 until 57) {
        val mixed = (o and 255) xor input[n].code
        o = MRC_TABLE[mixed] xor (o ushr 8)
    }
    return o xor -1 xor -306674912
}

private val CUSTOM_B64: List<String> = listOf(
    "Z", "m", "s", "e", "r", "b", "B", "o", "H", "Q", "t", "N", "P", "+", "w", "O",
    "c", "z", "a", "/", "L", "p", "n", "g", "G", "8", "y", "J", "q", "4", "2", "K",
    "W", "Y", "j", "0", "D", "S", "f", "d", "i", "k", "x", "3", "V", "T", "1", "6",
    "I", "l", "U", "A", "F", "M", "9", "7", "h", "E", "C", "v", "u", "R", "X", "5",
)

private val MRC_TABLE: IntArray = intArrayOf(
    0, 1996959894, -301047508, -1727442502, 124634137, 1886057615,
    -379345611, -1637575261, 249268274, 2044508324, -522852066, -1747789432,
    162941995, 2125561021, -407360249, -1866523247, 498536548, 1789927666,
    -205950648, -2067906082, 450548861, 1843258603, -187386543, -2083289657,
    325883990, 1684777152, -43845254, -1973040660, 335633487, 1661365465,
    -99664541, -1928851979, 997073096, 1281953886, -715111964, -1570279054,
    1006888145, 1258607687, -770865667, -1526024853, 901097722, 1119000684,
    -608450090, -1396901568, 853044451, 1172266101, -589951537, -1412350631,
    651767980, 1373503546, -925412992, -1076862698, 565507253, 1454621731,
    -809855591, -1195530993, 671266974, 1594198024, -972236366, -1324619484,
    795835527, 1483230225, -1050600021, -1234817731, 1994146192, 31158534,
    -1731059524, -271249366, 1907459465, 112637215, -1614814043, -390540237,
    2013776290, 251722036, -1777751922, -519137256, 2137656763, 141376813,
    -1855689577, -429695999, 1802195444, 476864866, -2056965928, -228458418,
    1812370925, 453092731, -2113342271, -183516073, 1706088902, 314042704,
    -1950435094, -54949764, 1658658271, 366619977, -1932296973, -69972891,
    1303535960, 984961486, -1547960204, -725929758, 1256170817, 1037604311,
    -1529756563, -740887301, 1131014506, 879679996, -1385723834, -631195440,
    1141124467, 855842277, -1442165665, -586318647, 1342533948, 654459306,
    -1106571248, -921952122, 1466479909, 544179635, -1184443383, -832445281,
    1591671054, 702138776, -1328506846, -942167884, 1504918807, 783551873,
    -1212326853, -1061524307, -306674912, -1698712650, 62317068, 1957810842,
    -355121351, -1647151185, 81470997, 1943803523, -480048366, -1805370492,
    225274430, 2053790376, -468791541, -1828061283, 167816743, 2097651377,
    -267414716, -2029476910, 503444072, 1762050814, -144550051, -2140837941,
    426522225, 1852507879, -19653770, -1982649376, 282753626, 1742555852,
    -105259153, -1900089351, 397917763, 1622183637, -690576408, -1580100738,
    953729732, 1340076626, -776247311, -1497606297, 1068828381, 1219638859,
    -670225446, -1358292148, 906185462, 1090812512, -547295293, -1469587627,
    829329135, 1181335161, -882789492, -1134132454, 628085408, 1382605366,
    -871598187, -1156888829, 570562233, 1426400815, -977650754, -1296233688,
    733239954, 1555261956, -1026031705, -1244606671, 752459403, 1541320221,
    -1687895376, -328994266, 1969922972, 40735498, -1677130071, -351390145,
    1913087877, 83908371, -1782625662, -491226604, 2075208622, 213261112,
    -1831694693, -438977011, 2094854071, 198958881, -2032938284, -237706686,
    1759359992, 534414190, -2118248755, -155638181, 1873836001, 414664567,
    -2012718362, -15766928, 1711684554, 285281116, -1889165569, -127750551,
    1634467795, 376229701, -1609899400, -686959890, 1308918612, 956543938,
    -1486412191, -799009033, 1231636301, 1047427035, -1362007478, -640263460,
    1088359270, 936918000, -1447252397, -558129467, 1202900863, 817233897,
    -1111625188, -893730166, 1404277552, 615818150, -1160759803, -841546093,
    1423857449, 601450431, -1285129682, -1000256840, 1567103746, 711928724,
    -1274298825, -1022587231, 1510334235, 755167117,
)
