package com.netpulse.app.ui.dns

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.netpulse.app.R
import com.netpulse.app.databinding.ItemDnsRowBinding

/** One list entry: a provider row plus whether it is the fastest one of a finished run. */
data class DnsRowItem(
    val row: DnsRow,
    val isFastest: Boolean
)

class DnsRowAdapter : ListAdapter<DnsRowItem, DnsRowAdapter.ViewHolder>(DiffCallback) {

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun submitRows(rows: List<DnsRow>, fastestIp: String?) {
        submitList(rows.map { DnsRowItem(it, it.provider.ip == fastestIp) }) {
            // The list sits inside the page's ScrollView and never scrolls on its own.
            // When rows move (the final sorting), it could keep following the moved row
            // and hide the rows above, so always show it from the first row.
            recyclerView?.scrollToPosition(0)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemDnsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemDnsRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DnsRowItem) {
            val context = binding.root.context
            val row = item.row
            val result = row.result

            binding.dnsName.text = row.provider.name
            binding.dnsIp.text = row.provider.ip
            binding.dnsFastestBadge.isVisible = item.isFastest
            binding.dnsProgress.isVisible = row.status == DnsRowStatus.TESTING

            val avgMs = result?.avgMs
            when {
                row.status == DnsRowStatus.WAITING ->
                    showStatus(context.getString(R.string.dns_status_waiting))

                row.status == DnsRowStatus.TESTING ->
                    showStatus(context.getString(R.string.dns_status_testing))

                avgMs == null ->
                    showStatus(context.getString(R.string.dns_status_no_answer))

                else -> {
                    binding.dnsAvg.text = context.getString(R.string.format_ms, avgMs)
                    binding.dnsAvg.setTextSize(TypedValue.COMPLEX_UNIT_SP, RESULT_TEXT_SP)
                    binding.dnsAvg.alpha = 1f
                }
            }

            val minMs = result?.minMs
            val maxMs = result?.maxMs
            if (row.status == DnsRowStatus.DONE && minMs != null && maxMs != null) {
                binding.dnsRange.text = context.getString(R.string.dns_row_range, minMs, maxMs)
                binding.dnsRange.isVisible = true
            } else {
                binding.dnsRange.isVisible = false
            }

            if (row.status == DnsRowStatus.DONE && result != null && result.failures > 0) {
                binding.dnsFailed.text =
                    context.getString(R.string.dns_row_failed, result.failures, result.attempts)
                binding.dnsFailed.isVisible = true
            } else {
                binding.dnsFailed.isVisible = false
            }
        }

        private fun showStatus(text: String) {
            binding.dnsAvg.text = text
            binding.dnsAvg.setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_TEXT_SP)
            binding.dnsAvg.alpha = STATUS_ALPHA
        }

        private companion object {
            const val RESULT_TEXT_SP = 22f
            const val STATUS_TEXT_SP = 14f
            const val STATUS_ALPHA = 0.7f
        }
    }
}

private object DiffCallback : DiffUtil.ItemCallback<DnsRowItem>() {

    override fun areItemsTheSame(oldItem: DnsRowItem, newItem: DnsRowItem): Boolean =
        oldItem.row.provider.ip == newItem.row.provider.ip

    override fun areContentsTheSame(oldItem: DnsRowItem, newItem: DnsRowItem): Boolean =
        oldItem == newItem
}
