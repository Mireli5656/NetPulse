package com.netpulse.app.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpulse.app.domain.PingSample
import com.netpulse.app.domain.PingStats
import com.netpulse.app.domain.PingStatsCalculator
import com.netpulse.app.network.PingEngine
import com.netpulse.app.network.PingUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MonitorError {
    INVALID_HOST,
    PING_UNAVAILABLE,
    UNKNOWN
}

/** How often a ping is sent. Slower intervals let Wi-Fi power saving inflate latency. */
enum class PingInterval(val ms: Long) {
    FAST(PingEngine.MIN_INTERVAL_MS),
    NORMAL(500L),
    SLOW(1000L)
}

data class MonitorUiState(
    val host: String = MonitorViewModel.DEFAULT_HOST,
    val interval: PingInterval = PingInterval.FAST,
    val keepWifiAwake: Boolean = false,
    val isRunning: Boolean = false,
    val stats: PingStats = PingStats.EMPTY,
    val samples: List<PingSample> = emptyList(),
    val error: MonitorError? = null
)

class MonitorViewModel : ViewModel() {

    private val engine = PingEngine()

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    private var pingJob: Job? = null

    fun onHostChanged(host: String) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(host = host, error = null) }
    }

    fun onIntervalChanged(interval: PingInterval) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(interval = interval) }
    }

    fun onKeepWifiAwakeChanged(enabled: Boolean) {
        if (_uiState.value.isRunning) return
        _uiState.update { it.copy(keepWifiAwake = enabled) }
    }

    fun start() {
        if (pingJob?.isActive == true) return

        val host = _uiState.value.host.trim()
        if (!PingEngine.isValidHost(host)) {
            _uiState.update { it.copy(error = MonitorError.INVALID_HOST) }
            return
        }
        val interval = _uiState.value.interval

        _uiState.update {
            it.copy(
                host = host,
                isRunning = true,
                stats = PingStats.EMPTY,
                samples = emptyList(),
                error = null
            )
        }

        pingJob = viewModelScope.launch {
            try {
                engine.pingFlow(host, interval.ms).collect { sample ->
                    _uiState.update { state ->
                        val window = (state.samples + sample).takeLast(MAX_SAMPLES)
                        state.copy(
                            samples = window,
                            stats = PingStatsCalculator.calculate(window)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingUnavailableException) {
                _uiState.update { it.copy(error = MonitorError.PING_UNAVAILABLE) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = MonitorError.UNKNOWN) }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    fun stop() {
        pingJob?.cancel()
    }

    companion object {
        const val DEFAULT_HOST = "1.1.1.1"
        const val MAX_SAMPLES = 300
    }
}
