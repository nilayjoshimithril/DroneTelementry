package com.guardianangel.mithrildefense.presentation.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.guardianangel.mithrildefense.databinding.ItemBuildingBinding

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
                    com.guardianangel.mithrildefense.R.plurals.floor_count,
                    item.floorCount,
                    item.floorCount,
                )
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
