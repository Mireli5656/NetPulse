package com.netpulse.app.ui.dns

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.netpulse.app.R
import com.netpulse.app.databinding.ActivityDnsBenchmarkBinding
import com.netpulse.app.network.DnsTestType
import kotlinx.coroutines.launch

class DnsBenchmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDnsBenchmarkBinding
    private val viewModel: DnsBenchmarkViewModel by viewModels()
    private val adapter = DnsRowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityDnsBenchmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.dnsList.adapter = adapter

        // Restore the selected lookup type (e.g. after rotation), then listen for changes.
        binding.dnsTypeGroup.check(buttonForType(viewModel.uiState.value.testType))
        binding.dnsTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                typeForButton(checkedId)?.let { viewModel.onTestTypeChanged(it) }
            }
        }

        binding.dnsToggleButton.setOnClickListener {
            if (viewModel.uiState.value.isRunning) viewModel.stop() else viewModel.start()
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

    private fun render(state: DnsBenchmarkUiState) {
        val canEdit = !state.isRunning

        binding.dnsToggleButton.setText(
            if (state.isRunning) R.string.action_stop else R.string.action_start
        )
        binding.dnsTypeCached.isEnabled = canEdit
        binding.dnsTypeUncached.isEnabled = canEdit
        binding.dnsTypeHint.setText(
            when (state.testType) {
                DnsTestType.CACHED -> R.string.dns_type_hint_cached
                DnsTestType.UNCACHED -> R.string.dns_type_hint_uncached
            }
        )

        val fastest = state.fastest
        adapter.submitRows(state.rows, fastest?.provider?.ip)

        val fastestAvg = fastest?.result?.avgMs
        when {
            fastest != null && fastestAvg != null -> {
                binding.dnsSummary.text = getString(
                    R.string.dns_summary_fastest,
                    fastest.provider.name,
                    fastestAvg
                )
                binding.dnsSummary.isVisible = true
            }

            state.noServerAnswered -> {
                binding.dnsSummary.setText(R.string.dns_summary_no_answer)
                binding.dnsSummary.isVisible = true
            }

            else -> binding.dnsSummary.isVisible = false
        }

        binding.dnsError.isVisible = state.failed
    }

    private fun typeForButton(buttonId: Int): DnsTestType? = when (buttonId) {
        R.id.dnsTypeCached -> DnsTestType.CACHED
        R.id.dnsTypeUncached -> DnsTestType.UNCACHED
        else -> null
    }

    private fun buttonForType(type: DnsTestType): Int = when (type) {
        DnsTestType.CACHED -> R.id.dnsTypeCached
        DnsTestType.UNCACHED -> R.id.dnsTypeUncached
    }
}
