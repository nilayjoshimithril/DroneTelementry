package com.guardianangel.dronetelementry.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.data.model.CampusDto
import com.guardianangel.dronetelementry.databinding.FragmentZoomCampusesBinding
import com.guardianangel.dronetelementry.databinding.ItemCampusBinding
import com.guardianangel.dronetelementry.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/** Settings → Map zoom level: pick which campus map to preview the zoom on. */
class ZoomCampusListFragment : Fragment(R.layout.fragment_zoom_campuses) {

    private var _binding: FragmentZoomCampusesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModel()

    private val adapter = CampusAdapter { campus ->
        findNavController().navigate(
            R.id.action_zoom_campuses_to_preview,
            bundleOf(
                ZoomPreviewFragment.ARG_CAMPUS_ID to campus.id,
                ZoomPreviewFragment.ARG_CAMPUS_NAME to campus.name,
            ),
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentZoomCampusesBinding.bind(view)
        binding.appBackground.applyMithrilBlurredBackground()
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.campusList.layoutManager = LinearLayoutManager(requireContext())
        binding.campusList.adapter = adapter
        binding.btnRetry.setOnClickListener { viewModel.loadCampuses(force = true) }
        viewModel.loadCampuses()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    adapter.submit(state.campuses)
                    binding.progress.isVisible = state.campusesLoading && state.campuses.isEmpty()
                    val error = state.campusesError
                    val empty = state.campusesLoaded && state.campuses.isEmpty()
                    binding.messagePanel.isVisible = !state.campusesLoading && (error != null || empty)
                    binding.message.text = error ?: getString(R.string.no_campuses)
                    binding.btnRetry.isVisible = error != null || empty
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.campusList.adapter = null
        _binding = null
    }
}

private class CampusAdapter(
    private val onClick: (CampusDto) -> Unit,
) : RecyclerView.Adapter<CampusAdapter.Holder>() {

    private var items: List<CampusDto> = emptyList()

    fun submit(list: List<CampusDto>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCampusBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val campus = items[position]
        holder.binding.campusName.text = campus.name.ifBlank { campus.id }
        holder.binding.root.setOnClickListener { onClick(campus) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemCampusBinding) : RecyclerView.ViewHolder(binding.root)
}
