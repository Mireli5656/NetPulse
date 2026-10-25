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
import com.netpulse.app.ui.monitor.MonitorError
import com.netpulse.app.ui.monitor.MonitorUiState
import com.netpulse.app.ui.monitor.MonitorViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        // Keep the test alive across rotation, but never run it in the background.
        if (!isChangingConfigurations) viewModel.stop()
    }

    private fun render(state: MonitorUiState) {
        val stats = state.stats

        binding.toggleButton.setText(
            if (state.isRunning) R.string.action_stop else R.string.action_start
        )
        binding.hostInput.isEnabled = !state.isRunning

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
