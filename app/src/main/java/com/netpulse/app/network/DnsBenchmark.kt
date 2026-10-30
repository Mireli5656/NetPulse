package com.netpulse.app.network

import com.netpulse.app.domain.DnsBenchmarkResult
import com.netpulse.app.domain.DnsProvider
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class DnsTestType {
    /** A popular name the resolver has just cached: measures its fast path. */
    CACHED,

    /** A unique name nobody has asked for: measures a fresh lookup through the resolver. */
    UNCACHED
}

data class DnsBenchmarkEntry(
    val testType: DnsTestType,
    val result: DnsBenchmarkResult
)

/**
 * Runs DNS response-time tests against several resolvers, one after another.
 * Warm-up queries come first and are never counted: they wake the Wi-Fi radio
 * and fill the resolver cache.
 */
class DnsBenchmark(
    private val querier: DnsQuerier,
    private val queryIntervalMs: Long = DEFAULT_QUERY_INTERVAL_MS,
    private val warmupQueries: Int = DEFAULT_WARMUP_QUERIES,
    private val timeoutMs: Long = DnsEngine.DEFAULT_TIMEOUT_MS
) {

    /** Emits one entry per provider as soon as that provider is finished. */
    fun run(
        providers: List<DnsProvider>,
        testType: DnsTestType,
        queriesPerProvider: Int = DEFAULT_QUERIES
    ): Flow<DnsBenchmarkEntry> {
        require(queriesPerProvider in 1..MAX_QUERIES) {
            "Queries per provider out of range: $queriesPerProvider"
        }

        return flow {
            for (provider in providers) {
                val result = benchmarkProvider(provider, testType, queriesPerProvider)
                emit(DnsBenchmarkEntry(testType, result))
            }
        }
    }

    private suspend fun benchmarkProvider(
        provider: DnsProvider,
        testType: DnsTestType,
        queries: Int
    ): DnsBenchmarkResult {
        if (!DnsEngine.isValidServerIp(provider.ip)) {
            return summarize(provider, queries, emptyList())
        }

        // Warm-up: timings are discarded.
        var warmupAnswers = 0
        repeat(warmupQueries) {
            val startedNs = System.nanoTime()
            val result = querier.query(provider.ip, CACHED_NAME, DnsRecordType.A, timeoutMs)
            if (result is DnsQueryResult.Answer && result.isUsable) warmupAnswers++
            paceSince(startedNs)
        }
        if (warmupQueries > 0 && warmupAnswers == 0) {
            // The resolver never answered: do not wait through every timeout.
            return summarize(provider, queries, emptyList())
        }

        val timings = ArrayList<Double>(queries)
        repeat(queries) {
            val startedNs = System.nanoTime()
            val name = if (testType == DnsTestType.CACHED) CACHED_NAME else uniqueName()
            val result = querier.query(provider.ip, name, DnsRecordType.A, timeoutMs)
            if (result is DnsQueryResult.Answer && result.isUsable) timings.add(result.rttMs)
            paceSince(startedNs)
        }
        return summarize(provider, queries, timings)
    }

    /** Keeps a steady rhythm: the interval counts from the start of one query to the next. */
    private suspend fun paceSince(startedNs: Long) {
        val elapsedMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLI
        val remainingMs = queryIntervalMs - elapsedMs
        if (remainingMs > 0) delay(remainingMs)
    }

    private fun uniqueName(): String {
        val label = UUID.randomUUID().toString().replace("-", "").take(UNIQUE_LABEL_LENGTH)
        return "$label.$UNCACHED_BASE_DOMAIN"
    }

    companion object {
        const val DEFAULT_QUERIES = 10
        const val MAX_QUERIES = 50
        const val DEFAULT_WARMUP_QUERIES = 3
        const val DEFAULT_QUERY_INTERVAL_MS = 200L

        /** Reserved example domain, operated for documentation and testing. */
        const val CACHED_NAME = "example.com"
        const val UNCACHED_BASE_DOMAIN = "example.com"

        private const val UNIQUE_LABEL_LENGTH = 16
        private const val NANOS_PER_MILLI = 1_000_000L

        internal fun summarize(
            provider: DnsProvider,
            attempts: Int,
            timingsMs: List<Double>
        ): DnsBenchmarkResult = DnsBenchmarkResult(
            provider = provider,
            attempts = attempts,
            successes = timingsMs.size,
            minMs = timingsMs.minOrNull(),
            avgMs = if (timingsMs.isEmpty()) null else timingsMs.average(),
            maxMs = timingsMs.maxOrNull()
        )
    }
}
