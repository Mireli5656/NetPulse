package com.netpulse.app.network

import com.netpulse.app.domain.PingSample
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible

/** Thrown when the system ping tool cannot be started on this device. */
class PingUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Measures real ICMP round-trip time by running the system ping tool.
 * The returned flow runs until the collector is cancelled.
 */
class PingEngine(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    fun pingFlow(host: String): Flow<PingSample> {
        require(isValidHost(host)) { "Invalid host: $host" }

        return flow {
            var sequence = 1
            while (true) {
                val rtt = runInterruptible(Dispatchers.IO) { pingOnce(host) }
                emit(PingSample(sequence++, rtt))
                delay(intervalMs)
            }
        }
    }

    /** Returns the round-trip time in ms, or null if the packet was lost. */
    private fun pingOnce(host: String): Double? {
        val timeoutSeconds = (timeoutMs / 1000).coerceAtLeast(1)

        val process = try {
            ProcessBuilder(PING_BINARY, "-c", "1", "-W", timeoutSeconds.toString(), host)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw PingUnavailableException(
                "The system ping tool is not available on this device.", e
            )
        }

        try {
            val finished = process.waitFor(timeoutMs + PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)
            if (!finished) return null
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return parseRttMs(output)
        } finally {
            process.destroy()
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1000L
        const val DEFAULT_TIMEOUT_MS = 2000L

        private const val PROCESS_GRACE_MS = 1000L
        private const val PING_BINARY = "/system/bin/ping"

        private val HOST_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
        private val RTT_REGEX = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""")

        fun isValidHost(host: String): Boolean =
            host.length <= 253 && HOST_REGEX.matches(host)

        internal fun parseRttMs(output: String): Double? =
            RTT_REGEX.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
