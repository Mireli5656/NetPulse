package com.netpulse.app.domain

import kotlin.math.abs

/** One ping attempt. rttMs == null means the packet was lost or timed out. */
data class PingSample(
    val sequence: Int,
    val rttMs: Double?
)

data class PingStats(
    val sent: Int,
    val received: Int,
    val lastMs: Double?,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMs: Double?,
    val lossPercent: Double
) {
    companion object {
        val EMPTY = PingStats(
            sent = 0,
            received = 0,
            lastMs = null,
            minMs = null,
            avgMs = null,
            maxMs = null,
            jitterMs = null,
            lossPercent = 0.0
        )
    }
}

object PingStatsCalculator {

    /**
     * Jitter is the mean absolute difference between consecutive
     * successful round-trip times.
     */
    fun calculate(samples: List<PingSample>): PingStats {
        if (samples.isEmpty()) return PingStats.EMPTY

        val rtts = samples.mapNotNull { it.rttMs }
        val sent = samples.size
        val received = rtts.size
        val lossPercent = (sent - received) * 100.0 / sent

        if (rtts.isEmpty()) {
            return PingStats.EMPTY.copy(sent = sent, lossPercent = lossPercent)
        }

        val jitter = if (rtts.size < 2) {
            0.0
        } else {
            rtts.zipWithNext { a, b -> abs(b - a) }.average()
        }

        return PingStats(
            sent = sent,
            received = received,
            lastMs = rtts.last(),
            minMs = rtts.min(),
            avgMs = rtts.average(),
            maxMs = rtts.max(),
            jitterMs = jitter,
            lossPercent = lossPercent
        )
    }
}
