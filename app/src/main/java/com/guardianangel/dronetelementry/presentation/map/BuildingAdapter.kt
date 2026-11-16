package com.guardianangel.dronetelementry.presentation.map

import android.graphics.Color
import android.view.LayoutInflater
import androidx.core.view.isVisible
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.guardianangel.dronetelementry.databinding.ItemBuildingBinding

private val THREAT_BG = Color.parseColor("#0FEF4141")
private val THREAT_STROKE = Color.parseColor("#F42424")
private val NORMAL_BG = Color.parseColor("#0FFFFFFF")
private val NORMAL_STROKE = Color.parseColor("#33FFFFFF")

class BuildingAdapter(
    private val onClick: (BuildingListItem) -> Unit,
) : RecyclerView.Adapter<BuildingAdapter.VH>() {

    private var items: List<BuildingListItem> = emptyList()

    fun submit(list: List<BuildingListItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBuildingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(
        private val binding: ItemBuildingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BuildingListItem) {
            binding.buildingName.text = item.name
            binding.buildingMeta.text = if (item.id == "outdoor") {
                "Campus overview"
            } else {
                binding.root.context.resources.getQuantityString(
                    com.guardianangel.dronetelementry.R.plurals.floor_count,
                    item.floorCount,
                    item.floorCount,
                )
            }
            // Mithril BuildingItem CRITICAL: red tint + border and a "Threat Identified" badge.
            binding.threatBadge.isVisible = item.hasThreat
            val card = binding.root
            if (item.hasThreat) {
                card.setCardBackgroundColor(THREAT_BG)
                card.strokeColor = THREAT_STROKE
            } else {
                card.setCardBackgroundColor(NORMAL_BG)
                card.strokeColor = NORMAL_STROKE
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
