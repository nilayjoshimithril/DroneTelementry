package com.guardianangel.dronetelementry.presentation.map

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.data.model.CampusDroneDto
import com.guardianangel.dronetelementry.databinding.ItemDroneBinding
import com.guardianangel.dronetelementry.domain.model.DroneMapPose

/** KINETIC_STRIKE → "Kinetic Strike". */
internal fun formatEnumLabel(raw: String): String =
    raw.trim().split('_', ' ').filter { it.isNotBlank() }
        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

class DroneListAdapter(
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<DroneListAdapter.Holder>() {

    private var items: List<DroneMapPose> = emptyList()
    private var expandedId: String? = null
    /** Campus roster by display_id (`campuses/drones/`), merged into each live row. */
    private var roster: Map<String, CampusDroneDto> = emptyMap()
    fun submitRoster(map: Map<String, CampusDroneDto>) {
        if (map == roster) return
        roster = map
        notifyDataSetChanged()
    }

    fun submitData(list: List<DroneMapPose>) {
        items = list.sortedBy { it.droneDisplayId }
        // Drop expansion if that drone left the list.
        if (expandedId != null && items.none { it.droneDisplayId == expandedId }) {
            expandedId = null
        }
        notifyDataSetChanged()
    }

    /** Expand one row (or none). Only one item can be open at a time. */
    fun setExpandedId(id: String?) {
        val previous = expandedId
        if (previous == id) return
        expandedId = id
        val oldIndex = previous?.let { indexOf(it) } ?: -1
        val newIndex = id?.let { indexOf(it) } ?: -1
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
    }

    fun indexOf(droneId: String): Int =
        items.indexOfFirst { it.droneDisplayId == droneId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDroneBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val rosterDrone = roster[item.droneDisplayId]
        holder.bind(
            item = item,
            rosterDrone = rosterDrone,
            isMine = item.isMine,
            expanded = item.droneDisplayId == expandedId,
            onToggle = onToggle,
        )
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemDroneBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: DroneMapPose,
            rosterDrone: CampusDroneDto?,
            isMine: Boolean,
            expanded: Boolean,
            onToggle: (String) -> Unit,
        ) {
            // Same label as on the map: "Me (name)" / "Me" / pilot name / display id.
            binding.droneId.text = item.mapLabel
            // The title already says "Me", so the badge would only repeat it.
            binding.droneMineBadge.isVisible = false
            // When the title isn't the display id, keep the id visible on the meta line.
            val idPrefix = item.droneDisplayId.takeIf { item.mapLabel != it }
            // Roster type (e.g. "Dazzler") in front of the live state line.
            val type = rosterDrone?.type?.let(::formatEnumLabel)
            binding.droneMeta.text = listOfNotNull(idPrefix, type, item.listMetaLine()).joinToString(" · ")
            binding.droneBattery.text = "${item.batteryPercent}%"
            binding.droneBattery.applyToneColor(batteryTone(item.batteryPercent))
            binding.droneCard.setBackgroundResource(
                if (expanded) R.drawable.bg_drone_selected else R.drawable.bg_frosted_card,
            )
            val dotColor = ContextCompat.getColor(
                binding.root.context,
                batteryTone(item.batteryPercent).textColor(),
            )
            binding.batteryDot.background.mutate().setTint(dotColor)
            binding.expandChevron.animate().cancel()
            binding.expandChevron.rotation = if (expanded) 180f else 0f
            binding.droneDetailPanel.isVisible = expanded

            if (expanded) {
                bindExpandedDetails(item, rosterDrone)
            }

            // Force wrap_content remasure so expanded text is not clipped by old item height.
            binding.root.layoutParams = binding.root.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            binding.root.requestLayout()

            // Single click target — avoids parent+child double-toggle (expand then collapse).
            binding.droneCard.isClickable = false
            binding.droneCard.setOnClickListener(null)
            binding.droneDetailPanel.isClickable = false
            binding.droneDetailPanel.setOnClickListener(null)
            binding.droneHeader.isClickable = true
            binding.droneHeader.isFocusable = true
            binding.droneHeader.setOnClickListener {
                onToggle(item.droneDisplayId)
            }
        }

        private fun bindExpandedDetails(pose: DroneMapPose, rosterDrone: CampusDroneDto?) {
            bindStateChip(binding.detailState, pose.state)
            binding.detailBatteryBar.progress = pose.batteryPercent.coerceIn(0, 100)
            binding.detailBatteryBar.applyBatteryTint(pose.batteryPercent)
            binding.detailBatteryText.text = "${pose.batteryPercent}%"
            binding.detailBatteryText.applyToneColor(batteryTone(pose.batteryPercent))
            binding.detailHeading.text = formatHeading(pose.headingDegrees)
            binding.detailAltitude.text = formatAltitude(pose.positionZ)
            binding.detailConfidence.text = formatConfidence(pose.confidence)
            binding.detailPoseAge.text = formatPoseAge(pose.capturedAt)

            binding.detailMotor.bindStatusPill("Motor", pose.motorStatus)
            binding.detailEsc.bindStatusPill("ESC", pose.escStatus)
            binding.detailBatteryUnit.bindStatusPill("Batt", pose.batteryUnitStatus)
            binding.detailLink.bindStatusPill("Link", pose.halowConnectivityStatus)

            binding.detailSensors.text = buildString {
                append("Gyro ${displayStatus(pose.gyroStatus)}")
                append(" · Acc ${displayStatus(pose.accStatus)}")
                append(" · Baro ${displayStatus(pose.barometerStatus)}")
                val video = pose.videoDeviceStatus?.trim().orEmpty()
                if (video.isNotEmpty()) append(" · Cam $video")
            }
            // IP + roster facts (status, weapons, flight hours) under the live details.
            val extra = buildList {
                pose.droneIp?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                rosterDrone?.status?.let { add("Roster ${formatEnumLabel(it)}") }
                rosterDrone?.weapons.orEmpty()
                    .mapNotNull { w -> w.type?.let { "${formatEnumLabel(it)} ${w.roundsRemaining ?: 0}/${w.totalRounds ?: 0}" } }
                    .takeIf { it.isNotEmpty() }
                    ?.let { add("Weapons " + it.joinToString(", ")) }
                rosterDrone?.flightHours?.let { add("Flight ${"%.1f".format(it)} h") }
            }
            binding.detailIp.isVisible = extra.isNotEmpty()
            binding.detailIp.text = extra.joinToString("\n")
        }

        private fun bindStateChip(view: TextView, state: String?) {
            val label = state?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
            val tone = statusTone(state)
            view.text = label
            view.setBackgroundResource(tone.pillBackground())
            view.setTextColor(ContextCompat.getColor(view.context, tone.textColor()))
        }
    }
}
