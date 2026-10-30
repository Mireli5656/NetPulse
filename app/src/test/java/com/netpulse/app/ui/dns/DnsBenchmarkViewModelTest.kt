@file:OptIn(ExperimentalCoroutinesApi::class)

package com.netpulse.app.ui.dns

import com.netpulse.app.domain.DnsProvider
import com.netpulse.app.network.DnsBenchmark
import com.netpulse.app.network.DnsQuerier
import com.netpulse.app.network.DnsQueryResult
import com.netpulse.app.network.DnsRecordType
import com.netpulse.app.network.DnsResponse
import com.netpulse.app.network.DnsTestType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DnsBenchmarkViewModelTest {

    private val delta = 0.0001

    private val cloudflare = provider("cloudflare")
    private val google = provider("google")
    private val quad9 = provider("quad9")
    private val controlD = provider("controld")

    private lateinit var resolver: FakeResolver
    private lateinit var viewModel: DnsBenchmarkViewModel

    private val state: DnsBenchmarkUiState
        get() = viewModel.uiState.value

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resolver = FakeResolver()
        viewModel = DnsBenchmarkViewModel(
            DnsBenchmark(resolver, queryIntervalMs = 0L, warmupQueries = 1, timeoutMs = 1000L)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- initial state and progress ----

    @Test
    fun initialState_listsAllProvidersWaiting() {
        assertFalse(state.isRunning)
        assertEquals(DnsProvider.ALL.map { it.id }, state.rows.map { it.provider.id })
        assertTrue(state.rows.all { it.status == DnsRowStatus.WAITING && it.result == null })
        assertFalse(state.allFinished)
        assertFalse(state.noServerAnswered)
        assertNull(state.fastest)
        assertEquals(DnsTestType.CACHED, state.testType)
    }

    @Test
    fun start_marksFirstProviderTestingAndOthersWaiting() {
        val gate = resolver.hold(cloudflare.ip)

        viewModel.start()

        assertTrue(state.isRunning)
        assertEquals(DnsRowStatus.TESTING, state.rows[0].status)
        assertTrue(state.rows.drop(1).all { it.status == DnsRowStatus.WAITING })

        gate.complete(Unit)

        assertFalse(state.isRunning)
        assertTrue(state.rows.all { it.status == DnsRowStatus.DONE })
    }

    @Test
    fun rows_advanceOneProviderAtATime() {
        val gate = resolver.hold(google.ip)

        viewModel.start()

        assertEquals(
            listOf(
                DnsRowStatus.DONE,
                DnsRowStatus.TESTING,
                DnsRowStatus.WAITING,
                DnsRowStatus.WAITING
            ),
            state.rows.map { it.status }
        )
        assertEquals(cloudflare.id, state.rows[0].provider.id)
        assertEquals(5.0, state.rows[0].result!!.avgMs!!, delta)
        assertNull("fastest must stay unknown mid-run", state.fastest)

        gate.complete(Unit)

        assertTrue(state.allFinished)
    }

    // ---- results ----

    @Test
    fun finishedRun_sortsByAverageAndExposesFastest() {
        resolver.respond = { ip ->
            okAnswer(
                when (ip) {
                    cloudflare.ip -> 30.0
                    google.ip -> 10.0
                    quad9.ip -> 20.0
                    else -> 40.0
                }
            )
        }

        viewModel.start()

        assertEquals(
            listOf("google", "quad9", "cloudflare", "controld"),
            state.rows.map { it.provider.id }
        )
        assertEquals("google", state.fastest!!.provider.id)
        assertEquals(10.0, state.fastest!!.result!!.avgMs!!, delta)
        assertTrue(state.allFinished)
        assertFalse(state.isRunning)
    }

    @Test
    fun unreachableProvider_isSortedLastAndNeverFastest() {
        resolver.respond = { ip ->
            if (ip == cloudflare.ip) DnsQueryResult.Timeout else okAnswer(10.0)
        }

        viewModel.start()

        val last = state.rows.last()
        assertEquals("cloudflare", last.provider.id)
        assertNull(last.result!!.avgMs)
        assertEquals(0, last.result!!.successes)
        assertEquals("google", state.fastest!!.provider.id)
        assertFalse(state.noServerAnswered)
    }

    @Test
    fun noServerAnswered_whenEveryQueryTimesOut() {
        resolver.respond = { DnsQueryResult.Timeout }

        viewModel.start()

        assertTrue(state.allFinished)
        assertTrue(state.noServerAnswered)
        assertNull(state.fastest)
        assertFalse(state.failed)
        assertFalse(state.isRunning)
        assertTrue(state.rows.all { it.result!!.attempts == DnsBenchmark.DEFAULT_QUERIES })
    }

    // ---- stop and repeated starts ----

    @Test
    fun stop_keepsFinishedResultsAndResetsTheRest() {
        resolver.hold(quad9.ip)

        viewModel.start()
        viewModel.stop()

        assertFalse(state.isRunning)
        assertEquals(
            listOf("cloudflare", "google", "quad9", "controld"),
            state.rows.map { it.provider.id }
        )
        assertEquals(
            listOf(
                DnsRowStatus.DONE,
                DnsRowStatus.DONE,
                DnsRowStatus.WAITING,
                DnsRowStatus.WAITING
            ),
            state.rows.map { it.status }
        )
        assertTrue(state.rows[0].result != null && state.rows[1].result != null)
        assertNull(state.rows[2].result)
        assertFalse(state.allFinished)
        assertNull(state.fastest)
    }

    @Test
    fun startWhileRunning_isIgnored() {
        val gate = resolver.hold(cloudflare.ip)

        viewModel.start()
        viewModel.start()

        assertEquals(1, resolver.queries.size)

        gate.complete(Unit)
        assertTrue(state.allFinished)
    }

    @Test
    fun testType_cannotChangeWhileRunning() {
        val gate = resolver.hold(cloudflare.ip)

        viewModel.start()
        viewModel.onTestTypeChanged(DnsTestType.UNCACHED)
        assertEquals(DnsTestType.CACHED, state.testType)

        gate.complete(Unit)
        viewModel.onTestTypeChanged(DnsTestType.UNCACHED)
        assertEquals(DnsTestType.UNCACHED, state.testType)
    }

    @Test
    fun uncachedRun_warmsUpWithCachedNameThenUsesUniqueNames() {
        viewModel.onTestTypeChanged(DnsTestType.UNCACHED)

        viewModel.start()

        val perProvider = 1 + DnsBenchmark.DEFAULT_QUERIES
        assertEquals(4 * perProvider, resolver.queries.size)

        val names = resolver.queries.map { it.second }
        assertEquals(4, names.count { it == DnsBenchmark.CACHED_NAME })
        assertEquals(1 + 4 * DnsBenchmark.DEFAULT_QUERIES, names.toSet().size)
    }

    // ---- extra providers ----

    @Test
    fun extraProviders_areAddedAndDuplicateAddressesRemoved() {
        val system = DnsProvider(id = "system", name = "System DNS", ip = "192.0.2.53")
        val duplicate = DnsProvider(id = "dup", name = "Duplicate", ip = google.ip)

        viewModel.start(listOf(system, duplicate))

        val ids = state.rows.map { it.provider.id }
        assertEquals(5, ids.size)
        assertTrue("system" in ids)
        assertTrue("google" in ids)
        assertFalse("dup" in ids)
        assertTrue(state.allFinished)
    }

    // ---- errors ----

    @Test
    fun unexpectedError_setsFailedAndStopsRunning() {
        resolver.respond = { throw RuntimeException("boom") }

        viewModel.start()

        assertTrue(state.failed)
        assertFalse(state.isRunning)
        assertTrue(state.rows.none { it.status == DnsRowStatus.TESTING })
    }

    @Test
    fun nextStart_clearsFailedFlag() {
        resolver.respond = { throw RuntimeException("boom") }
        viewModel.start()
        assertTrue(state.failed)

        resolver.respond = { okAnswer(5.0) }
        viewModel.start()

        assertFalse(state.failed)
        assertTrue(state.allFinished)
    }

    // ---- helpers ----

    private fun provider(id: String): DnsProvider = DnsProvider.ALL.first { it.id == id }
}

/** A fake resolver. Queries to a held server wait until its gate is completed. */
private class FakeResolver : DnsQuerier {

    /** Every query as (server address, name). */
    val queries = mutableListOf<Pair<String, String>>()

    var respond: (serverIp: String) -> DnsQueryResult = { okAnswer(5.0) }

    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    fun hold(serverIp: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gates[serverIp] = it }

    override suspend fun query(
        serverIp: String,
        name: String,
        type: DnsRecordType,
        timeoutMs: Long
    ): DnsQueryResult {
        queries.add(serverIp to name)
        gates[serverIp]?.await()
        return respond(serverIp)
    }
}

private fun okAnswer(rttMs: Double): DnsQueryResult = DnsQueryResult.Answer(
    rttMs = rttMs,
    response = DnsResponse(
        id = 1,
        rcode = DnsResponse.RCODE_NO_ERROR,
        truncated = false,
        addresses = emptyList(),
        texts = emptyList()
    )
)
