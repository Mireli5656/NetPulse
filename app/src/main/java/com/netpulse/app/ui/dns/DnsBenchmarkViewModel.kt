package com.netpulse.app.ui.dns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpulse.app.domain.DnsBenchmarkResult
import com.netpulse.app.domain.DnsProvider
import com.netpulse.app.network.DnsBenchmark
import com.netpulse.app.network.DnsEngine
import com.netpulse.app.network.DnsTestType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DnsRowStatus { WAITING, TESTING, DONE }

data class DnsRow(
    val provider: DnsProvider,
    val status: DnsRowStatus,
    val result: DnsBenchmarkResult? = null
)

data class DnsBenchmarkUiState(
    val testType: DnsTestType = DnsTestType.CACHED,
    val isRunning: Boolean = false,
    val rows: List<DnsRow> = DnsBenchmarkViewModel.waitingRows(DnsProvider.ALL),
    val failed: Boolean = false
) {
    val allFinished: Boolean
        get() = rows.isNotEmpty() && rows.all { it.status == DnsRowStatus.DONE }

    /** True when every provider was tested and none of them answered a single query. */
    val noServerAnswered: Boolean
        get() = allFinished && rows.all { (it.result?.successes ?: 0) == 0 }

    /** The fastest provider by average time; only known once a full run has finished. */
    val fastest: DnsRow?
        get() = rows.firstOrNull()?.takeIf {
            !isRunning && allFinished && it.result?.avgMs != null
        }
}

class DnsBenchmarkViewModel(
    private val benchmark: DnsBenchmark = DnsBenchmark(DnsEngine())
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsBenchmarkUiState())
    val uiState: StateFlow<DnsBenchmarkUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun onTestTypeChanged(type: DnsTestType) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(testType = type) }
    }

    fun start(extraProviders: List<DnsProvider> = emptyList()) {
        if (job?.isActive == true) return

        val providers = (DnsProvider.ALL + extraProviders).distinctBy { it.ip }
        val testType = _uiState.value.testType

        _uiState.update {
            it.copy(
                isRunning = true,
                failed = false,
                rows = providers.mapIndexed { index, provider ->
                    DnsRow(
                        provider = provider,
                        status = if (index == 0) DnsRowStatus.TESTING else DnsRowStatus.WAITING
                    )
                }
            )
        }

        job = viewModelScope.launch {
            try {
                benchmark.run(providers, testType).collect { entry ->
                    _uiState.update { it.withFinished(entry.result) }
                }
                _uiState.update { state ->
                    state.copy(
                        rows = state.rows.sortedBy { it.result?.avgMs ?: Double.POSITIVE_INFINITY }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(failed = true) }
            } finally {
                _uiState.update { state ->
                    state.copy(
                        isRunning = false,
                        rows = state.rows.map {
                            if (it.status == DnsRowStatus.DONE) it
                            else it.copy(status = DnsRowStatus.WAITING)
                        }
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun DnsBenchmarkUiState.withFinished(result: DnsBenchmarkResult): DnsBenchmarkUiState {
        val updated = rows.map {
            if (it.provider.ip == result.provider.ip) {
                it.copy(status = DnsRowStatus.DONE, result = result)
            } else {
                it
            }
        }
        val nextIndex = updated.indexOfFirst { it.status == DnsRowStatus.WAITING }
        val withNext = if (nextIndex >= 0) {
            updated.toMutableList().also {
                it[nextIndex] = it[nextIndex].copy(status = DnsRowStatus.TESTING)
            }
        } else {
            updated
        }
        return copy(rows = withNext)
    }

    companion object {
        fun waitingRows(providers: List<DnsProvider>): List<DnsRow> =
            providers.map { DnsRow(provider = it, status = DnsRowStatus.WAITING) }
    }
}
