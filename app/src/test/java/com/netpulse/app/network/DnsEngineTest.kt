package com.netpulse.app.network

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsEngineTest {

    private val loopback = "127.0.0.1"

    private fun runQuery(
        server: FakeDnsServer,
        type: DnsRecordType = DnsRecordType.A,
        timeoutMs: Long = 1000L
    ): DnsQueryResult = runBlocking {
        DnsEngine(server.port).query(loopback, "example.com", type, timeoutMs)
    }

    private fun expectAnswer(result: DnsQueryResult): DnsQueryResult.Answer {
        assertTrue("Expected an answer but got $result", result is DnsQueryResult.Answer)
        return result as DnsQueryResult.Answer
    }

    // ---- answers ----

    @Test
    fun aRecord_isReturnedWithTiming() {
        FakeDnsServer { q -> listOf(response(q, answer = aRecord(192, 0, 2, 1))) }.use { server ->
            val answer = expectAnswer(runQuery(server))

            assertEquals(listOf("192.0.2.1"), answer.response.addresses)
            assertTrue(answer.isUsable)
            assertTrue("rtt was ${answer.rttMs}", answer.rttMs >= 0.0 && answer.rttMs < 1000.0)
        }
    }

    @Test
    fun txtQuery_returnsText() {
        FakeDnsServer { q ->
            if (typeOf(q) == DnsRecordType.TXT.code) {
                listOf(response(q, answer = txtRecord("203.0.113.7")))
            } else {
                emptyList()
            }
        }.use { server ->
            val answer = expectAnswer(runQuery(server, DnsRecordType.TXT))

            assertEquals(listOf("203.0.113.7"), answer.response.texts)
        }
    }

    @Test
    fun nxdomain_isUsableAnswerWithoutAddresses() {
        FakeDnsServer { q -> listOf(response(q, rcode = 3)) }.use { server ->
            val answer = expectAnswer(runQuery(server))

            assertEquals(DnsResponse.RCODE_NAME_ERROR, answer.response.rcode)
            assertTrue(answer.isUsable)
            assertTrue(answer.response.addresses.isEmpty())
        }
    }

    @Test
    fun serverFailure_isAnswerButNotUsable() {
        FakeDnsServer { q -> listOf(response(q, rcode = 2)) }.use { server ->
            val answer = expectAnswer(runQuery(server))

            assertEquals(DnsResponse.RCODE_SERVER_FAILURE, answer.response.rcode)
            assertFalse(answer.isUsable)
        }
    }

    @Test
    fun wrongId_isIgnoredAndCorrectAnswerIsUsed() {
        FakeDnsServer { q ->
            val wrongId = (idOf(q) + 1) and 0xFFFF
            listOf(
                response(q, id = wrongId, answer = aRecord(10, 0, 0, 1)),
                response(q, answer = aRecord(192, 0, 2, 1))
            )
        }.use { server ->
            val answer = expectAnswer(runQuery(server))

            assertEquals(listOf("192.0.2.1"), answer.response.addresses)
        }
    }

    // ---- timing ----

    @Test
    fun rtt_reflectsServerDelay() {
        FakeDnsServer { q ->
            Thread.sleep(150)
            listOf(response(q, answer = aRecord(192, 0, 2, 1)))
        }.use { server ->
            val answer = expectAnswer(runQuery(server))

            assertTrue(
                "rtt was ${answer.rttMs}",
                answer.rttMs >= 140.0 && answer.rttMs < 1500.0
            )
        }
    }

    // ---- failures ----

    @Test
    fun silentServer_timesOut() {
        FakeDnsServer { emptyList() }.use { server ->
            val startNs = System.nanoTime()
            val result = runQuery(server, timeoutMs = 300L)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            assertEquals(DnsQueryResult.Timeout, result)
            assertTrue("elapsed $elapsedMs ms", elapsedMs >= 250 && elapsedMs < 3000)
        }
    }

    @Test
    fun closedPort_doesNotThrow() {
        val deadPort = FakeDnsServer { emptyList() }.use { it.port }
        val result = runBlocking {
            DnsEngine(deadPort).query(loopback, "example.com", DnsRecordType.A, 300L)
        }

        assertTrue(
            "unexpected result $result",
            result is DnsQueryResult.NetworkError || result is DnsQueryResult.Timeout
        )
    }

    @Test
    fun cancellation_stopsQuicklyInsteadOfWaitingForTimeout() {
        FakeDnsServer { emptyList() }.use { server ->
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    DnsEngine(server.port).query(loopback, "example.com", timeoutMs = 5000L)
                }
                delay(300)

                val startNs = System.nanoTime()
                job.cancelAndJoin()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

                assertTrue("cancel took $elapsedMs ms", elapsedMs < 1000)
            }
        }
    }

    // ---- input validation ----

    @Test
    fun isValidServerIp_acceptsIpLiteralsOnly() {
        assertTrue(DnsEngine.isValidServerIp("1.1.1.1"))
        assertTrue(DnsEngine.isValidServerIp("76.76.2.0"))
        assertTrue(DnsEngine.isValidServerIp("255.255.255.255"))
        assertTrue(DnsEngine.isValidServerIp("2606:4700:4700::1111"))

        assertFalse(DnsEngine.isValidServerIp(""))
        assertFalse(DnsEngine.isValidServerIp("example.com"))
        assertFalse(DnsEngine.isValidServerIp("256.1.1.1"))
        assertFalse(DnsEngine.isValidServerIp("1.1.1"))
        assertFalse(DnsEngine.isValidServerIp("1.1.1.1.1"))
        assertFalse(DnsEngine.isValidServerIp("01.1.1.1"))
        assertFalse(DnsEngine.isValidServerIp("1.1.1.1 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidServer_throws() {
        runBlocking { DnsEngine().query("not-an-ip", "example.com") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidName_throws() {
        runBlocking { DnsEngine().query("1.1.1.1", "bad name") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeoutOutOfRange_throws() {
        runBlocking { DnsEngine().query("1.1.1.1", "example.com", timeoutMs = 10L) }
    }
}

// ---- fake DNS server and packet helpers ----

/**
 * A tiny DNS server on the loopback interface. For every query it receives,
 * [respond] returns the datagrams to send back (none means "stay silent").
 */
private class FakeDnsServer(
    private val respond: (query: ByteArray) -> List<ByteArray>
) : Closeable {

    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort

    init {
        Thread {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            while (!socket.isClosed) {
                try {
                    packet.length = buffer.size
                    socket.receive(packet)
                    val query = buffer.copyOf(packet.length)
                    for (reply in respond(query)) {
                        socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    }
                } catch (e: IOException) {
                    // The socket was closed: the loop ends.
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        socket.close()
    }
}

private fun idOf(query: ByteArray): Int =
    ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)

/** The question section of a query: the name followed by type and class. */
private fun questionOf(query: ByteArray): ByteArray {
    var pos = 12
    while (query[pos].toInt() != 0) pos += (query[pos].toInt() and 0xFF) + 1
    pos += 1 + 4
    return query.copyOfRange(12, pos)
}

private fun typeOf(query: ByteArray): Int {
    val question = questionOf(query)
    val i = question.size - 4
    return ((question[i].toInt() and 0xFF) shl 8) or (question[i + 1].toInt() and 0xFF)
}

private fun response(
    query: ByteArray,
    id: Int = idOf(query),
    rcode: Int = 0,
    answer: ByteArray? = null
): ByteArray {
    val flags = 0x8180 or rcode
    val answerCount = if (answer != null) 1 else 0
    val header = byteArrayOf(
        (id shr 8).toByte(), id.toByte(),
        (flags shr 8).toByte(), flags.toByte(),
        0, 1,
        0, answerCount.toByte(),
        0, 0,
        0, 0
    )
    return header + questionOf(query) + (answer ?: ByteArray(0))
}

private fun aRecord(a: Int, b: Int, c: Int, d: Int): ByteArray =
    byteArrayOf(
        0xC0.toByte(), 0x0C,
        0, 1,
        0, 1,
        0, 0, 0, 60,
        0, 4,
        a.toByte(), b.toByte(), c.toByte(), d.toByte()
    )

private fun txtRecord(text: String): ByteArray {
    val data = text.toByteArray(Charsets.US_ASCII)
    val rdata = byteArrayOf(data.size.toByte()) + data
    return byteArrayOf(
        0xC0.toByte(), 0x0C,
        0, 16,
        0, 1,
        0, 0, 0, 60,
        0, rdata.size.toByte()
    ) + rdata
}
