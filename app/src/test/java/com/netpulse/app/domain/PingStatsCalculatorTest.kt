package com.netpulse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingStatsCalculatorTest {

    private val delta = 0.0001

    private fun samples(vararg rtts: Double?): List<PingSample> =
        rtts.mapIndexed { index, rtt -> PingSample(index + 1, rtt) }

    @Test
    fun emptyList_returnsEmptyStats() {
        val stats = PingStatsCalculator.calculate(emptyList())

        assertEquals(PingStats.EMPTY, stats)
    }

    @Test
    fun allReceived_computesMinAvgMaxAndJitter() {
        val stats = PingStatsCalculator.calculate(samples(10.0, 20.0, 30.0))

        assertEquals(3, stats.sent)
        assertEquals(3, stats.received)
        assertEquals(0.0, stats.lossPercent, delta)
        assertEquals(10.0, stats.minMs!!, delta)
        assertEquals(20.0, stats.avgMs!!, delta)
        assertEquals(30.0, stats.maxMs!!, delta)
        assertEquals(30.0, stats.lastMs!!, delta)
        assertEquals(10.0, stats.jitterMs!!, delta)
    }

    @Test
    fun withLostPackets_computesLossAndIgnoresLostInStats() {
        val stats = PingStatsCalculator.calculate(samples(10.0, null, 30.0, null))

        assertEquals(4, stats.sent)
        assertEquals(2, stats.received)
        assertEquals(50.0, stats.lossPercent, delta)
        assertEquals(10.0, stats.minMs!!, delta)
        assertEquals(20.0, stats.avgMs!!, delta)
        assertEquals(30.0, stats.maxMs!!, delta)
        assertEquals(30.0, stats.lastMs!!, delta)
        assertEquals(20.0, stats.jitterMs!!, delta)
    }

    @Test
    fun allLost_reportsFullLossAndNoLatency() {
        val stats = PingStatsCalculator.calculate(samples(null, null))

        assertEquals(2, stats.sent)
        assertEquals(0, stats.received)
        assertEquals(100.0, stats.lossPercent, delta)
        assertNull(stats.minMs)
        assertNull(stats.avgMs)
        assertNull(stats.maxMs)
        assertNull(stats.lastMs)
        assertNull(stats.jitterMs)
    }

    @Test
    fun singleSample_hasZeroJitter() {
        val stats = PingStatsCalculator.calculate(samples(15.0))

        assertEquals(1, stats.received)
        assertEquals(15.0, stats.minMs!!, delta)
        assertEquals(15.0, stats.avgMs!!, delta)
        assertEquals(15.0, stats.maxMs!!, delta)
        assertEquals(0.0, stats.jitterMs!!, delta)
    }
}
