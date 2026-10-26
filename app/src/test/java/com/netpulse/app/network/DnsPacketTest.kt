package com.netpulse.app.network

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {

    private val queryId = 0x1234
    private val okFlags = 0x8180

    // ---- helpers ----

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun name(vararg labels: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in labels) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun header(id: Int, flags: Int, questions: Int, answers: Int): ByteArray =
        bytes(
            id shr 8, id and 0xFF,
            flags shr 8, flags and 0xFF,
            questions shr 8, questions and 0xFF,
            answers shr 8, answers and 0xFF,
            0, 0, 0, 0
        )

    private val question: ByteArray =
        concat(name("example", "com"), bytes(0x00, 0x01, 0x00, 0x01))

    private fun response(flags: Int, answerCount: Int, vararg answers: ByteArray): ByteArray =
        concat(header(queryId, flags, 1, answerCount), question, *answers)

    /** Answer record whose name is a compression pointer to the question name. */
    private fun answer(type: Int, rdata: ByteArray): ByteArray = concat(
        bytes(
            0xC0, 0x0C,
            type shr 8, type and 0xFF,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            rdata.size shr 8, rdata.size and 0xFF
        ),
        rdata
    )

    private fun aAnswer(a: Int, b: Int, c: Int, d: Int): ByteArray =
        answer(1, bytes(a, b, c, d))

    private fun txtAnswer(vararg parts: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.write(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        return answer(16, out.toByteArray())
    }

    // ---- building queries ----

    @Test
    fun buildQuery_aRecord_matchesExpectedBytes() {
        val query = DnsPacket.buildQuery(queryId, "example.com", DnsRecordType.A)

        val expected = concat(
            bytes(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            name("example", "com"),
            bytes(0x00, 0x01, 0x00, 0x01)
        )
        assertEquals(29, query.size)
        assertArrayEquals(expected, query)
    }

    @Test
    fun buildQuery_txtRecord_usesTxtTypeCode() {
        val query = DnsPacket.buildQuery(queryId, "example.com", DnsRecordType.TXT)

        val tail = query.copyOfRange(query.size - 4, query.size)
        assertArrayEquals(bytes(0x00, 0x10, 0x00, 0x01), tail)
    }

    @Test
    fun buildQuery_trailingDot_isSameAsWithoutDot() {
        val withDot = DnsPacket.buildQuery(queryId, "example.com.", DnsRecordType.A)
        val withoutDot = DnsPacket.buildQuery(queryId, "example.com", DnsRecordType.A)

        assertArrayEquals(withoutDot, withDot)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildQuery_invalidName_throws() {
        DnsPacket.buildQuery(queryId, "bad name.com", DnsRecordType.A)
    }

    // ---- name validation ----

    @Test
    fun isValidName_acceptsNormalNames() {
        assertTrue(DnsPacket.isValidName("example.com"))
        assertTrue(DnsPacket.isValidName("example.com."))
        assertTrue(DnsPacket.isValidName("a-b.example.org"))
        assertTrue(DnsPacket.isValidName("_dmarc.example.com"))
        assertTrue(DnsPacket.isValidName("a".repeat(63) + ".com"))
    }

    @Test
    fun isValidName_rejectsBadNames() {
        assertFalse(DnsPacket.isValidName(""))
        assertFalse(DnsPacket.isValidName("."))
        assertFalse(DnsPacket.isValidName("a..b"))
        assertFalse(DnsPacket.isValidName("-bad.com"))
        assertFalse(DnsPacket.isValidName("bad-.com"))
        assertFalse(DnsPacket.isValidName("has space.com"))
        assertFalse(DnsPacket.isValidName("a".repeat(64) + ".com"))
        assertFalse(DnsPacket.isValidName(("a".repeat(60) + ".").repeat(5) + "com"))
    }

    // ---- parsing responses ----

    @Test
    fun parse_aRecord() {
        val packet = response(okFlags, 1, aAnswer(93, 184, 216, 34))

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertEquals(queryId, parsed!!.id)
        assertTrue(parsed.isNoError)
        assertFalse(parsed.truncated)
        assertEquals(listOf("93.184.216.34"), parsed.addresses)
        assertTrue(parsed.texts.isEmpty())
    }

    @Test
    fun parse_multipleARecords_keepsOrder() {
        val packet = response(okFlags, 2, aAnswer(1, 2, 3, 4), aAnswer(5, 6, 7, 8))

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), parsed!!.addresses)
    }

    @Test
    fun parse_cnameBeforeARecord_isSkipped() {
        val cname = answer(5, bytes(0xC0, 0x0C))
        val packet = response(okFlags, 2, cname, aAnswer(1, 2, 3, 4))

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertEquals(listOf("1.2.3.4"), parsed!!.addresses)
    }

    @Test
    fun parse_txtRecord() {
        val packet = response(okFlags, 1, txtAnswer("203.0.113.7"))

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertEquals(listOf("203.0.113.7"), parsed!!.texts)
        assertTrue(parsed.addresses.isEmpty())
    }

    @Test
    fun parse_txtWithSeveralStrings_joinsThem() {
        val packet = response(okFlags, 1, txtAnswer("abc", "def"))

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertEquals(listOf("abcdef"), parsed!!.texts)
    }

    @Test
    fun parse_nonExistentName_reportsNameError() {
        val packet = response(0x8183, 0)

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertEquals(DnsResponse.RCODE_NAME_ERROR, parsed!!.rcode)
        assertFalse(parsed.isNoError)
        assertTrue(parsed.addresses.isEmpty())
    }

    @Test
    fun parse_truncatedFlag_isReported() {
        val packet = response(0x8380, 0)

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertTrue(parsed!!.truncated)
        assertTrue(parsed.isNoError)
    }

    @Test
    fun parse_ipv6Record_isIgnoredWithoutError() {
        val packet = response(okFlags, 1, answer(28, ByteArray(16)))

        val parsed = DnsPacket.parseResponse(packet)

        assertNotNull(parsed)
        assertTrue(parsed!!.addresses.isEmpty())
        assertTrue(parsed.texts.isEmpty())
    }

    // ---- malformed input ----

    @Test
    fun parse_queryPacket_returnsNull() {
        val query = DnsPacket.buildQuery(1, "example.com", DnsRecordType.A)

        assertNull(DnsPacket.parseResponse(query))
    }

    @Test
    fun parse_tooShort_returnsNull() {
        assertNull(DnsPacket.parseResponse(bytes(1, 2, 3)))
    }

    @Test
    fun parse_lengthBeyondArray_returnsNull() {
        val packet = response(okFlags, 1, aAnswer(1, 2, 3, 4))

        assertNull(DnsPacket.parseResponse(packet, packet.size + 1))
    }

    @Test
    fun parse_respectsLengthParameter() {
        val packet = response(okFlags, 1, aAnswer(1, 2, 3, 4))
        val padded = packet + ByteArray(10)

        val parsed = DnsPacket.parseResponse(padded, packet.size)

        assertNotNull(parsed)
        assertEquals(listOf("1.2.3.4"), parsed!!.addresses)
        assertNull(DnsPacket.parseResponse(packet, packet.size - 1))
    }

    @Test
    fun parse_rdataBeyondPacket_returnsNull() {
        val brokenAnswer = bytes(
            0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 0x01, 0x02
        )
        val packet = concat(header(queryId, okFlags, 1, 1), question, brokenAnswer)

        assertNull(DnsPacket.parseResponse(packet))
    }

    @Test
    fun parse_reservedLabelBits_returnsNull() {
        val packet = concat(
            header(queryId, okFlags, 1, 0),
            bytes(0x40, 0x00, 0x00, 0x00, 0x00)
        )

        assertNull(DnsPacket.parseResponse(packet))
    }

    @Test
    fun parse_missingQuestion_returnsNull() {
        val packet = header(queryId, okFlags, 5, 0)

        assertNull(DnsPacket.parseResponse(packet))
    }
}
