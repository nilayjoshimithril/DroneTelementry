package com.guardianangel.dronetelementry.presentation.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.databinding.FragmentZoomPreviewBinding
import com.guardianangel.dronetelementry.domain.interact.MapInteract
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.qualifier.named
import kotlin.math.abs
import kotlin.math.roundToInt

/** Live preview of the default map zoom on one campus; Save applies it to every campus. */
class ZoomPreviewFragment : Fragment(R.layout.fragment_zoom_preview) {

    private var _binding: FragmentZoomPreviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ZoomPreviewViewModel by viewModel()
    private val tileClient: OkHttpClient by inject(named("tileClient"))
    private var appliedLayerKey: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentZoomPreviewBinding.bind(view)
        appliedLayerKey = null

        val args = requireArguments()
        val campusId = args.getString(ARG_CAMPUS_ID).orEmpty()
        binding.campusName.text = args.getString(ARG_CAMPUS_NAME).orEmpty().ifBlank { campusId }
        binding.campusName.isVisible = binding.campusName.text.isNotBlank()
        viewModel.load(campusId)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setZoom(value)
        }
        binding.btnReset.setOnClickListener { viewModel.resetZoom() }
        binding.btnSave.setOnClickListener { viewModel.save() }
        binding.zoomHint.text = getString(
            R.string.zoom_default_hint,
            getString(R.string.zoom_value, MapInteract.DEFAULT_MAP_ZOOM),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            ZoomPreviewEvent.Saved -> {
                                Toast.makeText(requireContext(), R.string.zoom_saved, Toast.LENGTH_SHORT).show()
                                findNavController().navigateUp()
                            }
                            is ZoomPreviewEvent.SaveFailed ->
                                Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: ZoomPreviewUiState) {
        val mosaic = state.mosaic
        val showMap = !state.loading && mosaic != null
        binding.progress.isVisible = state.loading
        binding.mosaicMap.isVisible = showMap
        binding.controls.isVisible = showMap

        val problem = !state.loading && (state.mapUnavailable || state.error != null)
        binding.messagePanel.isVisible = problem
        if (problem) {
            if (state.mapUnavailable) {
                binding.messageTitle.setText(R.string.map_not_available)
                binding.messageBody.setText(R.string.map_not_available_zoom)
                binding.btnMessageAction.setText(R.string.choose_another_campus)
                binding.btnMessageAction.setOnClickListener { findNavController().navigateUp() }
            } else {
                binding.messageTitle.setText(R.string.map_not_available)
                binding.messageBody.text = state.error
                binding.btnMessageAction.setText(R.string.retry)
                binding.btnMessageAction.setOnClickListener { viewModel.retry() }
            }
        }

        if (mosaic != null && appliedLayerKey != mosaic.layerKey) {
            appliedLayerKey = mosaic.layerKey
            binding.mosaicMap.defaultZoom = state.zoom
            binding.mosaicMap.configure(
                cols = mosaic.cols,
                rows = mosaic.rows,
                tileWidth = mosaic.tileWidth,
                tileHeight = mosaic.tileHeight,
                tileList = mosaic.tiles,
                overviewUrl = mosaic.overviewUrl,
                client = tileClient,
                scope = viewLifecycleOwner.lifecycleScope,
            )
        } else if (mosaic != null && abs(binding.mosaicMap.defaultZoom - state.zoom) > 0.001f) {
            binding.mosaicMap.previewZoom(state.zoom)
        }

        val sliderValue = snapToSlider(state.zoom)
        if (binding.zoomSlider.value != sliderValue) binding.zoomSlider.value = sliderValue
        binding.zoomValue.text = requireContext().formatZoomLevel(state.zoom)
        binding.btnReset.isEnabled = abs(state.zoom - MapInteract.DEFAULT_MAP_ZOOM) > 0.001f
        binding.btnSave.isEnabled = !state.saving
    }

    /** Slider rejects values off its step grid, so snap saved zooms onto it. */
    private fun snapToSlider(zoom: Float): Float {
        val slider = binding.zoomSlider
        val step = slider.stepSize.takeIf { it > 0f } ?: return zoom
        val snapped = slider.valueFrom + ((zoom - slider.valueFrom) / step).roundToInt() * step
        return snapped.coerceIn(slider.valueFrom, slider.valueTo)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appliedLayerKey = null
        _binding = null
    }

    companion object {
        const val ARG_CAMPUS_ID = "campusId"
        const val ARG_CAMPUS_NAME = "campusName"
    }
}
