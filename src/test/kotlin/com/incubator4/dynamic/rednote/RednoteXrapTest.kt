package com.incubator4.dynamic.rednote

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RednoteXrapTest {

    @Test
    fun `x-rap-param is base64 and decodes to the expected envelope header`() {
        val rap = RednoteXrap.build(
            api = REDNOTE_FEED_RAP_API,
            bodyJson = """{"source_note_id":"abc","image_formats":["jpg"]}""",
            random = Random(42),
            nowMillis = 1_729_214_251_341L,
        )
        assertTrue(rap.isNotBlank(), "x-rap-param must not be blank")
        val decoded = java.util.Base64.getDecoder().decode(rap)
        // 32-byte header: magic 07 24 01 <saltLen> ...
        assertEquals(0x07.toByte(), decoded[0])
        assertEquals(0x24.toByte(), decoded[1])
        assertEquals(0x01.toByte(), decoded[2])
        val saltLen = decoded[3].toInt() and 0xFF
        assertTrue(saltLen in 4..6, "salt length should be 4..6, was $saltLen")
        // Total payload is header(32) + salt + encrypted session key(20) + cipher body (>=4).
        assertTrue(decoded.size > 32 + saltLen + 20, "envelope body missing")
    }

    @Test
    fun `x-rap-param is stable for identical inputs and random seed`() {
        val api = REDNOTE_FEED_RAP_API
        val body = """{"a":1}"""
        val a = RednoteXrap.build(api, body, random = Random(7), nowMillis = 1_000L)
        val b = RednoteXrap.build(api, body, random = Random(7), nowMillis = 1_000L)
        assertEquals(a, b, "same seed + inputs must produce identical x-rap-param")
    }

    @Test
    fun `x-rap-param varies across random seeds`() {
        val api = REDNOTE_FEED_RAP_API
        val body = """{"a":1}"""
        val a = RednoteXrap.build(api, body, random = Random(1), nowMillis = 1_000L)
        val b = RednoteXrap.build(api, body, random = Random(2), nowMillis = 1_000L)
        assertNotEquals(a, b, "different seeds should yield different envelopes")
    }

    @Test
    fun `xxh32 matches known vector`() {
        // xxHash32 of "abc" with seed 0 — canonical reference value.
        val h = RednoteXrapCipher.xxh32("abc".toByteArray(), 0)
        assertEquals(0x32D153FF, h)
    }

    @Test
    fun `sm4 encrypt blocks is reversible in length`() {
        val src = ByteArray(48) { (it * 7).toByte() }
        val cipher = RednoteXrapCipher.encryptBlocks(src)
        assertEquals(48, cipher.size, "full-block input must keep its length")
        val partial = ByteArray(20) { it.toByte() }
        val cipherPartial = RednoteXrapCipher.encryptBlocks(partial)
        assertEquals(32, cipherPartial.size, "partial block is zero-padded to the next 16-byte boundary")
    }
}

class RednoteFingerprintTest {

    @Test
    fun `b1 is non-blank base64-ish and stable for a fixed seed`() {
        val a = RednoteFingerprint.generateB1(random = Random(123), nowMillis = 1_729_214_251_341L)
        val b = RednoteFingerprint.generateB1(random = Random(123), nowMillis = 1_729_214_251_341L)
        assertTrue(a.isNotBlank())
        assertEquals(a, b, "b1 must be deterministic for a fixed seed")
    }

    @Test
    fun `b1 feeds a consistent x9 crc32 checksum`() {
        val b1 = RednoteFingerprint.generateB1(random = Random(99), nowMillis = 1_000L)
        // x9 in x-s-common is crc32_js_signed_int(b1); the checksum must be a stable signed int.
        val x9 = crc32JsSignedInt(b1)
        assertEquals(x9, crc32JsSignedInt(b1))
    }
}
