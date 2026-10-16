package com.guardianangel.mithrildefense.presentation.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.guardianangel.mithrildefense.R
import com.guardianangel.mithrildefense.data.model.OngoingIncidentDto
import com.guardianangel.mithrildefense.databinding.ItemIncidentBinding
import com.guardianangel.mithrildefense.utils.to12HourFormat

class IncidentAdapter(
    private val onJoin: (OngoingIncidentDto) -> Unit,
) : RecyclerView.Adapter<IncidentAdapter.Holder>() {

    private var items: List<OngoingIncidentDto> = emptyList()
    private var joiningId: String? = null

    fun submit(list: List<OngoingIncidentDto>, joiningId: String?) {
        items = list
        this.joiningId = joiningId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemIncidentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemIncidentBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: OngoingIncidentDto) {
            val ctx = binding.root.context
            val isLockdown = item.status.equals("IN_PROGRESS", ignoreCase = true)
            val stroke = ContextCompat.getColor(
                ctx,
                if (isLockdown) R.color.md_red else R.color.md_orange,
            )
            binding.incidentCard.strokeColor = stroke
            binding.incidentCard.setCardBackgroundColor(
                ContextCompat.getColor(
                    ctx,
                    if (isLockdown) R.color.md_card_lockdown else R.color.md_card_verification,
                ),
            )

            binding.campusName.text = item.campusName
                ?.takeIf { it.isNotBlank() }
                ?: "Campus ${item.campusId.take(8)}"

            binding.statusChip.text = if (isLockdown) {
                ctx.getString(R.string.status_lockdown)
            } else {
                ctx.getString(R.string.status_verification)
            }
            binding.statusChip.setTextColor(stroke)

            binding.drillChip.isVisible = item.isDrill == true

            val droneCount = item.droneIds?.size ?: 0
            binding.droneCount.text = ctx.resources.getQuantityString(
                R.plurals.drone_count,
                droneCount,
                droneCount,
            )

            val campusNo = item.campusNumber?.let { "#$it" }.orEmpty()
            val status = item.status.orEmpty().replace('_', ' ')
            binding.incidentMeta.text = listOfNotNull(
                campusNo.takeIf { it.isNotBlank() }?.let { "Campus $it" },
                status.takeIf { it.isNotBlank() },
                item.districtName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")

            val time = item.createdAt.to12HourFormat()
            binding.incidentTime.isVisible = time.isNotBlank()
            binding.incidentTime.text = time

            val joining = joiningId == item.id
            binding.btnJoin.isEnabled = joiningId == null
            binding.btnJoin.text = if (joining) {
                ctx.getString(R.string.joining)
            } else {
                ctx.getString(R.string.join)
            }
            binding.btnJoin.setOnClickListener { onJoin(item) }
            binding.root.setOnClickListener {
                if (joiningId == null) onJoin(item)
            }
        }
    }
}
