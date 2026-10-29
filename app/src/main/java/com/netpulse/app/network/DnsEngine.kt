package com.netpulse.app.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Outcome of one DNS query. */
sealed interface DnsQueryResult {

    /** A valid response with a matching ID arrived in time. */
    data class Answer(val rttMs: Double, val response: DnsResponse) : DnsQueryResult {
        /** NOERROR and NXDOMAIN are both real answers, so both give a meaningful timing. */
        val isUsable: Boolean
            get() = response.rcode == DnsResponse.RCODE_NO_ERROR ||
                response.rcode == DnsResponse.RCODE_NAME_ERROR
    }

    /** No valid response arrived before the deadline. */
    data object Timeout : DnsQueryResult

    /** The query could not be sent (no network, unreachable server, socket error). */
    data object NetworkError : DnsQueryResult
}

/** Sends single DNS queries. An interface so that callers can be tested with fakes. */
interface DnsQuerier {
    suspend fun query(
        serverIp: String,
        name: String,
        type: DnsRecordType = DnsRecordType.A,
        timeoutMs: Long = DnsEngine.DEFAULT_TIMEOUT_MS
    ): DnsQueryResult
}

/**
 * Measures real DNS response time with a UDP query, separately from ICMP ping.
 * The time covers only the span from sending the query to receiving the answer.
 */
class DnsEngine(private val port: Int = DNS_PORT) : DnsQuerier {

    override suspend fun query(
        serverIp: String,
        name: String,
        type: DnsRecordType,
        timeoutMs: Long
    ): DnsQueryResult {
        require(isValidServerIp(serverIp)) { "Invalid DNS server address: $serverIp" }
        require(DnsPacket.isValidName(name)) { "Invalid DNS name: $name" }
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "Timeout out of range: $timeoutMs ms"
        }

        return withContext(Dispatchers.IO) { queryBlocking(serverIp, name, type, timeoutMs) }
    }

    private fun CoroutineScope.queryBlocking(
        serverIp: String,
        name: String,
        type: DnsRecordType,
        timeoutMs: Long
    ): DnsQueryResult {
        val queryId = random.nextInt(ID_RANGE)
        val queryBytes = DnsPacket.buildQuery(queryId, name, type)

        return try {
            DatagramSocket().use { socket ->
                // A connected UDP socket only accepts datagrams from the server's address and port.
                socket.connect(InetAddress.getByName(serverIp), port)

                val startNs = System.nanoTime()
                socket.send(DatagramPacket(queryBytes, queryBytes.size))
                awaitAnswer(socket, queryId, startNs, startNs + timeoutMs * NANOS_PER_MILLI)
            }
        } catch (e: IOException) {
            DnsQueryResult.NetworkError
        }
    }

    private fun CoroutineScope.awaitAnswer(
        socket: DatagramSocket,
        queryId: Int,
        startNs: Long,
        deadlineNs: Long
    ): DnsQueryResult {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        val reply = DatagramPacket(buffer, buffer.size)

        while (true) {
            // Wait in short slices so that cancelling the coroutine takes effect quickly.
            ensureActive()
            val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MILLI
            if (remainingMs <= 0) return DnsQueryResult.Timeout
            socket.soTimeout = minOf(remainingMs, SLICE_MS).toInt()

            try {
                reply.length = buffer.size
                socket.receive(reply)
            } catch (e: SocketTimeoutException) {
                continue
            }
            val endNs = System.nanoTime()

            val response = DnsPacket.parseResponse(buffer, reply.length)
            if (response != null && response.id == queryId) {
                return DnsQueryResult.Answer((endNs - startNs) / NANOS_PER_MILLI_DOUBLE, response)
            }
            // Stray or malformed packet: ignore it and keep waiting.
        }
    }

    companion object {
        const val DNS_PORT = 53
        const val DEFAULT_TIMEOUT_MS = 2000L
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 10_000L

        private const val ID_RANGE = 0x10000
        private const val RECEIVE_BUFFER_SIZE = 1500
        private const val SLICE_MS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NANOS_PER_MILLI_DOUBLE = 1_000_000.0

        private val random = SecureRandom()

        private val IPV4_REGEX =
            Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
        private val IPV6_REGEX = Regex("^[0-9A-Fa-f:]{2,39}$")

        /** Only IP literals are accepted, so that no extra DNS lookup can happen. */
        fun isValidServerIp(ip: String): Boolean =
            IPV4_REGEX.matches(ip) || (ip.count { it == ':' } >= 2 && IPV6_REGEX.matches(ip))
    }
}
