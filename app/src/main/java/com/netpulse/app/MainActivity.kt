package com.netpulse.app

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.netpulse.app.databinding.ActivityMainBinding
import com.netpulse.app.network.WifiAwakeLock
import com.netpulse.app.ui.monitor.MonitorError
import com.netpulse.app.ui.monitor.MonitorUiState
import com.netpulse.app.ui.monitor.MonitorViewModel
import com.netpulse.app.ui.monitor.PingInterval
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiLock: WifiAwakeLock
    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiLock = WifiAwakeLock(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.hostInput.setText(viewModel.uiState.value.host)
        binding.hostInput.doAfterTextChanged {
            viewModel.onHostChanged(it?.toString().orEmpty())
        }
        binding.hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) viewModel.start()
            false
        }

        // Restore the selected interval (e.g. after rotation), then listen for changes.
        binding.intervalGroup.check(buttonForInterval(viewModel.uiState.value.interval))
        binding.intervalGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                intervalForButton(checkedId)?.let { viewModel.onIntervalChanged(it) }
            }
        }

        // Restore the keep-awake choice first, then listen for changes.
        binding.keepAwakeSwitch.isChecked = viewModel.uiState.value.keepWifiAwake
        binding.keepAwakeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onKeepWifiAwakeChanged(isChecked)
        }

        binding.toggleButton.setOnClickListener {
            if (viewModel.uiState.value.isRunning) {
                viewModel.stop()
            } else {
                hideKeyboard()
                viewModel.start()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Never hold the Wi-Fi lock in the background.
        wifiLock.release()
        // Keep the test alive across rotation, but never run it in the background.
        if (!isChangingConfigurations) viewModel.stop()
    }

    private fun render(state: MonitorUiState) {
        val stats = state.stats
        val canEdit = !state.isRunning

        binding.toggleButton.setText(
            if (state.isRunning) R.string.action_stop else R.string.action_start
        )
        binding.hostInput.isEnabled = canEdit
        binding.intervalFast.isEnabled = canEdit
        binding.intervalNormal.isEnabled = canEdit
        binding.intervalSlow.isEnabled = canEdit
        binding.keepAwakeSwitch.isEnabled = canEdit

        updateWifiLock(state)

        binding.latencyValue.text = formatMs(stats.lastMs)
        binding.minValue.text = formatMs(stats.minMs)
        binding.avgValue.text = formatMs(stats.avgMs)
        binding.maxValue.text = formatMs(stats.maxMs)
        binding.jitterValue.text = formatMs(stats.jitterMs)

        if (stats.sent == 0) {
            binding.lossValue.text = getString(R.string.value_none)
            binding.packetsText.text = ""
        } else {
            binding.lossValue.text = getString(R.string.format_percent, stats.lossPercent)
            binding.packetsText.text =
                getString(R.string.format_packets, stats.sent, stats.received)
        }

        val errorRes = when (state.error) {
            MonitorError.INVALID_HOST -> R.string.error_invalid_host
            MonitorError.PING_UNAVAILABLE -> R.string.error_ping_unavailable
            MonitorError.UNKNOWN -> R.string.error_unknown
            null -> null
        }
        binding.errorText.isVisible = errorRes != null
        if (errorRes != null) binding.errorText.setText(errorRes)
    }

    /** Holds the Wi-Fi lock only while a test is running with the option enabled. */
    private fun updateWifiLock(state: MonitorUiState) {
        if (state.isRunning && state.keepWifiAwake) {
            wifiLock.acquire()
        } else {
            wifiLock.release()
        }
    }

    private fun intervalForButton(buttonId: Int): PingInterval? = when (buttonId) {
        R.id.intervalFast -> PingInterval.FAST
        R.id.intervalNormal -> PingInterval.NORMAL
        R.id.intervalSlow -> PingInterval.SLOW
        else -> null
    }

    private fun buttonForInterval(interval: PingInterval): Int = when (interval) {
        PingInterval.FAST -> R.id.intervalFast
        PingInterval.NORMAL -> R.id.intervalNormal
        PingInterval.SLOW -> R.id.intervalSlow
    }

    private fun formatMs(value: Double?): String =
        if (value == null) {
            getString(R.string.value_none)
        } else {
            getString(R.string.format_ms, value)
        }

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, binding.root)
            .hide(WindowInsetsCompat.Type.ime())
    }
}
