package com.netpulse.app.domain

data class DnsProvider(
    val id: String,
    val name: String,
    val ip: String
) {
    companion object {
        val ALL: List<DnsProvider> = listOf(
            DnsProvider(id = "cloudflare", name = "Cloudflare", ip = "1.1.1.1"),
            DnsProvider(id = "google", name = "Google", ip = "8.8.8.8"),
            DnsProvider(id = "quad9", name = "Quad9", ip = "9.9.9.9"),
            DnsProvider(id = "controld", name = "Control D", ip = "76.76.2.0")
        )
    }
}

/** Aggregated result of benchmarking one provider with several DNS queries. */
data class DnsBenchmarkResult(
    val provider: DnsProvider,
    val attempts: Int,
    val successes: Int,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?
) {
    val failures: Int get() = attempts - successes
}
