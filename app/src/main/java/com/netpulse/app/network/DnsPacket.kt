package com.netpulse.app.network

import java.io.ByteArrayOutputStream

enum class DnsRecordType(val code: Int) {
    A(1),
    TXT(16)
}

data class DnsResponse(
    val id: Int,
    val rcode: Int,
    val truncated: Boolean,
    val addresses: List<String>,
    val texts: List<String>
) {
    val isNoError: Boolean get() = rcode == RCODE_NO_ERROR

    companion object {
        const val RCODE_NO_ERROR = 0
        const val RCODE_SERVER_FAILURE = 2
        const val RCODE_NAME_ERROR = 3
    }
}

/** Builds DNS queries and parses DNS responses (RFC 1035) without any network access. */
object DnsPacket {

    private const val HEADER_SIZE = 12
    private const val FLAGS_RECURSION_DESIRED = 0x0100
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_TRUNCATED = 0x0200
    private const val CLASS_IN = 1

    private val LABEL_REGEX = Regex("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$")

    fun isValidName(name: String): Boolean {
        val trimmed = name.trimEnd('.')
        if (trimmed.isEmpty() || trimmed.length > 253) return false
        return trimmed.split('.').all { LABEL_REGEX.matches(it) }
    }

    fun buildQuery(id: Int, name: String, type: DnsRecordType): ByteArray {
        require(isValidName(name)) { "Invalid DNS name" }

        val out = ByteArrayOutputStream()
        out.writeShort(id and 0xFFFF)
        out.writeShort(FLAGS_RECURSION_DESIRED)
        out.writeShort(1) // questions
        out.writeShort(0) // answers
        out.writeShort(0) // authority records
        out.writeShort(0) // additional records

        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)

        out.writeShort(type.code)
        out.writeShort(CLASS_IN)
        return out.toByteArray()
    }

    /** Returns null if the packet is malformed or is not a DNS response. */
    fun parseResponse(data: ByteArray, length: Int = data.size): DnsResponse? {
        if (length < HEADER_SIZE || length > data.size) return null

        val id = readShort(data, 0)
        val flags = readShort(data, 2)
        if ((flags and FLAG_RESPONSE) == 0) return null

        val rcode = flags and 0x000F
        val truncated = (flags and FLAG_TRUNCATED) != 0
        val questionCount = readShort(data, 4)
        val answerCount = readShort(data, 6)

        var pos = HEADER_SIZE

        repeat(questionCount) {
            pos = skipName(data, length, pos)
            if (pos < 0) return null
            pos += 4 // type + class
            if (pos > length) return null
        }

        val addresses = mutableListOf<String>()
        val texts = mutableListOf<String>()

        repeat(answerCount) {
            pos = skipName(data, length, pos)
            if (pos < 0 || pos + 10 > length) return null

            val type = readShort(data, pos)
            val rdLength = readShort(data, pos + 8)
            val rdStart = pos + 10
            val rdEnd = rdStart + rdLength
            if (rdEnd > length) return null

            when (type) {
                DnsRecordType.A.code -> {
                    if (rdLength == 4) addresses.add(formatIpv4(data, rdStart))
                }
                DnsRecordType.TXT.code -> {
                    val text = parseTxt(data, rdStart, rdEnd) ?: return null
                    texts.add(text)
                }
            }
            pos = rdEnd
        }

        return DnsResponse(
            id = id,
            rcode = rcode,
            truncated = truncated,
            addresses = addresses,
            texts = texts
        )
    }

    /** Returns the offset just after the name, or -1 if the name is malformed. */
    private fun skipName(data: ByteArray, limit: Int, start: Int): Int {
        var pos = start
        while (pos < limit) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> return if (pos + 2 <= limit) pos + 2 else -1
                (len and 0xC0) != 0 -> return -1
                else -> pos += 1 + len
            }
        }
        return -1
    }

    private fun parseTxt(data: ByteArray, start: Int, end: Int): String? {
        val builder = StringBuilder()
        var pos = start
        while (pos < end) {
            val len = data[pos].toInt() and 0xFF
            pos++
            if (pos + len > end) return null
            builder.append(String(data, pos, len, Charsets.UTF_8))
            pos += len
        }
        return builder.toString()
    }

    private fun formatIpv4(data: ByteArray, offset: Int): String =
        (0 until 4).joinToString(".") { (data[offset + it].toInt() and 0xFF).toString() }

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
}
