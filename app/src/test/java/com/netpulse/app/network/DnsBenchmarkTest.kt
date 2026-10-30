package com.netpulse.app.network

import com.netpulse.app.domain.DnsProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsBenchmarkTest {

    private val delta = 0.0001
    private val provider = DnsProvider(id = "test", name = "Test", ip = "192.0.2.1")

    private fun benchmarkOf(querier: DnsQuerier, warmup: Int = 3) = DnsBenchmark(
        querier = querier,
        queryIntervalMs = 0L,
        warmupQueries = warmup,
        timeoutMs = 1234L
    )

    private fun runOne(
        benchmark: DnsBenchmark,
        type: DnsTestType,
        queries: Int,
        target: DnsProvider = provider
    ): DnsBenchmarkEntry = runBlocking {
        benchmark.run(listOf(target), type, queries).toList().single()
    }

    // ---- warm-up and timing ----

    @Test
    fun warmupTimings_areDiscarded() {
        val querier = ScriptedQuerier { call, _ ->
            if (call <= 3) answerOf(500.0) else answerOf(10.0 * (call - 3))
        }

        val result = runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 4).result

        assertEquals(7, querier.queries.size)
        assertEquals(4, result.attempts)
        assertEquals(4, result.successes)
        assertEquals(0, result.failures)
        assertEquals(10.0, result.minMs!!, delta)
        assertEquals(25.0, result.avgMs!!, delta)
        assertEquals(40.0, result.maxMs!!, delta)
    }

    @Test
    fun allQueries_useConfiguredServerTimeoutAndARecord() {
        val querier = ScriptedQuerier { _, _ -> answerOf(5.0) }

        runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 2)

        assertTrue(
            querier.queries.all {
                it.serverIp == "192.0.2.1" &&
                    it.timeoutMs == 1234L &&
                    it.type == DnsRecordType.A
            }
        )
    }

    // ---- cached and uncached names ----

    @Test
    fun cachedTest_usesTheSameNameForEveryQuery() {
        val querier = ScriptedQuerier { _, _ -> answerOf(5.0) }

        runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 5)

        assertEquals(8, querier.queries.size)
        assertTrue(querier.queries.all { it.name == DnsBenchmark.CACHED_NAME })
    }

    @Test
    fun uncachedTest_warmsUpWithCachedNameThenUsesUniqueNames() {
        val querier = ScriptedQuerier { _, _ -> answerOf(5.0) }

        runOne(benchmarkOf(querier), DnsTestType.UNCACHED, queries = 5)

        val all = querier.queries
        assertEquals(8, all.size)
        assertTrue(all.take(3).all { it.name == DnsBenchmark.CACHED_NAME })

        val measured = all.drop(3).map { it.name }
        assertEquals(5, measured.toSet().size)
        for (name in measured) {
            assertTrue(name.endsWith("." + DnsBenchmark.UNCACHED_BASE_DOMAIN))
            assertNotEquals(DnsBenchmark.CACHED_NAME, name)
            assertEquals(16, name.substringBefore('.').length)
            assertTrue(DnsPacket.isValidName(name))
        }
    }

    // ---- failures ----

    @Test
    fun unreachableProvider_stopsAfterWarmup() {
        val querier = ScriptedQuerier { _, _ -> DnsQueryResult.Timeout }

        val result = runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 10).result

        assertEquals(3, querier.queries.size)
        assertEquals(10, result.attempts)
        assertEquals(0, result.successes)
        assertEquals(10, result.failures)
        assertNull(result.minMs)
        assertNull(result.avgMs)
        assertNull(result.maxMs)
    }

    @Test
    fun partialFailures_areCountedAndIgnoredInStatistics() {
        val querier = ScriptedQuerier { call, _ ->
            when (call) {
                in 1..3 -> answerOf(1.0)
                4 -> answerOf(10.0)
                5 -> DnsQueryResult.Timeout
                6 -> answerOf(30.0)
                else -> DnsQueryResult.NetworkError
            }
        }

        val result = runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 4).result

        assertEquals(4, result.attempts)
        assertEquals(2, result.successes)
        assertEquals(2, result.failures)
        assertEquals(10.0, result.minMs!!, delta)
        assertEquals(20.0, result.avgMs!!, delta)
        assertEquals(30.0, result.maxMs!!, delta)
    }

    @Test
    fun serverFailure_isNotCountedAsSuccess() {
        val querier = ScriptedQuerier { call, _ ->
            when (call) {
                in 1..3 -> answerOf(1.0)
                4 -> answerOf(5.0, rcode = DnsResponse.RCODE_SERVER_FAILURE)
                else -> answerOf(8.0)
            }
        }

        val result = runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 2).result

        assertEquals(1, result.successes)
        assertEquals(1, result.failures)
        assertEquals(8.0, result.minMs!!, delta)
        assertEquals(8.0, result.avgMs!!, delta)
        assertEquals(8.0, result.maxMs!!, delta)
    }

    @Test
    fun nxdomain_isCountedAsSuccess() {
        val querier = ScriptedQuerier { _, _ ->
            answerOf(12.0, rcode = DnsResponse.RCODE_NAME_ERROR)
        }

        val result = runOne(benchmarkOf(querier), DnsTestType.UNCACHED, queries = 3).result

        assertEquals(3, result.successes)
        assertEquals(12.0, result.avgMs!!, delta)
    }

    @Test
    fun noWarmup_measuresEveryQueryEvenWhenAllTimeOut() {
        val querier = ScriptedQuerier { _, _ -> DnsQueryResult.Timeout }

        val result = runOne(
            benchmarkOf(querier, warmup = 0),
            DnsTestType.CACHED,
            queries = 3
        ).result

        assertEquals(3, querier.queries.size)
        assertEquals(3, result.attempts)
        assertEquals(0, result.successes)
    }

    @Test
    fun invalidProviderAddress_sendsNoQueries() {
        val querier = ScriptedQuerier { _, _ -> answerOf(5.0) }
        val bad = DnsProvider(id = "bad", name = "Bad", ip = "not-an-ip")

        val result = runOne(benchmarkOf(querier), DnsTestType.CACHED, queries = 4, target = bad).result

        assertEquals(0, querier.queries.size)
        assertEquals(4, result.attempts)
        assertEquals(0, result.successes)
        assertNull(result.avgMs)
    }

    // ---- several providers ----

    @Test
    fun providers_areTestedOneAfterAnotherAndReportedInOrder() {
        val querier = ScriptedQuerier { _, _ -> answerOf(5.0) }
        val second = DnsProvider(id = "second", name = "Second", ip = "192.0.2.2")

        val entries = runBlocking {
            benchmarkOf(querier).run(listOf(provider, second), DnsTestType.UNCACHED, 2).toList()
        }

        assertEquals(listOf("test", "second"), entries.map { it.result.provider.id })
        assertTrue(entries.all { it.testType == DnsTestType.UNCACHED })
        assertEquals(
            List(5) { "192.0.2.1" } + List(5) { "192.0.2.2" },
            querier.queries.map { it.serverIp }
        )
    }

    // ---- input limits ----

    @Test(expected = IllegalArgumentException::class)
    fun zeroQueries_throws() {
        benchmarkOf(ScriptedQuerier { _, _ -> answerOf(1.0) })
            .run(listOf(provider), DnsTestType.CACHED, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyQueries_throws() {
        benchmarkOf(ScriptedQuerier { _, _ -> answerOf(1.0) })
            .run(listOf(provider), DnsTestType.CACHED, DnsBenchmark.MAX_QUERIES + 1)
    }

    // ---- summary ----

    @Test
    fun summarize_withoutTimings_hasNoStatistics() {
        val result = DnsBenchmark.summarize(provider, 5, emptyList())

        assertEquals(5, result.attempts)
        assertEquals(0, result.successes)
        assertNull(result.minMs)
        assertNull(result.avgMs)
        assertNull(result.maxMs)
    }

    @Test
    fun summarize_computesMinAvgMax() {
        val result = DnsBenchmark.summarize(provider, 4, listOf(30.0, 10.0, 20.0))

        assertEquals(3, result.successes)
        assertEquals(1, result.failures)
        assertEquals(10.0, result.minMs!!, delta)
        assertEquals(20.0, result.avgMs!!, delta)
        assertEquals(30.0, result.maxMs!!, delta)
    }
}

// ---- test helpers ----

internal data class RecordedDnsQuery(
    val serverIp: String,
    val name: String,
    val type: DnsRecordType,
    val timeoutMs: Long
)

/** A fake resolver: [respond] gets the 1-based call number and the query, and decides the result. */
internal class ScriptedQuerier(
    private val respond: (callNumber: Int, query: RecordedDnsQuery) -> DnsQueryResult
) : DnsQuerier {

    val queries = mutableListOf<RecordedDnsQuery>()

    override suspend fun query(
        serverIp: String,
        name: String,
        type: DnsRecordType,
        timeoutMs: Long
    ): DnsQueryResult {
        val recorded = RecordedDnsQuery(serverIp, name, type, timeoutMs)
        queries.add(recorded)
        return respond(queries.size, recorded)
    }
}

internal fun answerOf(
    rttMs: Double,
    rcode: Int = DnsResponse.RCODE_NO_ERROR
): DnsQueryResult.Answer = DnsQueryResult.Answer(
    rttMs = rttMs,
    response = DnsResponse(
        id = 1,
        rcode = rcode,
        truncated = false,
        addresses = emptyList(),
        texts = emptyList()
    )
)
