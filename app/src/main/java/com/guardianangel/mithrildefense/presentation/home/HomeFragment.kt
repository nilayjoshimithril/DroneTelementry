package com.guardianangel.mithrildefense.presentation.home

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.guardianangel.mithrildefense.R
import com.guardianangel.mithrildefense.databinding.FragmentHomeBinding
import com.guardianangel.mithrildefense.presentation.map.MapSessionActivity
import com.guardianangel.mithrildefense.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModel()

    private val adapter = IncidentAdapter { incident ->
        viewModel.join(incident)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)
        binding.appBackground.applyMithrilBlurredBackground()
        binding.incidentList.layoutManager = incidentLayoutManager()
        binding.incidentList.adapter = adapter
        binding.btnRefresh.setOnClickListener { viewModel.refresh(showLoader = true) }
        binding.btnLogout.setOnClickListener { viewModel.logout() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.startIncidentPolling()
                try {
                    // coroutineScope keeps this block suspended until collectors are cancelled
                    // (lifecycle < STARTED). A plain try/finally around launch{} would run
                    // finally immediately and stop polling right after start.
                    coroutineScope {
                        launch {
                            var lastError: String? = null
                            viewModel.state.collect { state ->
                                binding.progress.isVisible = state.loading && state.incidents.isEmpty()
                                binding.empty.isVisible = !state.loading && state.incidents.isEmpty()
                                adapter.submit(state.incidents, state.joiningId)
                                val err = state.error
                                if (!err.isNullOrBlank() && err != lastError) {
                                    lastError = err
                                    Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        launch {
                            viewModel.nav.collect { dest ->
                                when (dest) {
                                    is HomeNav.MapSession -> {
                                        viewModel.stopIncidentPolling()
                                        MapSessionActivity.start(
                                            requireContext(),
                                            incidentId = dest.incidentId,
                                            campusId = dest.campusId,
                                            wsTicket = dest.wsTicket,
                                        )
                                    }
                                    HomeNav.Login -> findNavController().navigate(R.id.action_home_to_login)
                                }
                            }
                        }
                    }
                } finally {
                    viewModel.stopIncidentPolling()
                }
            }
        }
    }

    private fun incidentLayoutManager() =
        if (isTablet()) {
            GridLayoutManager(requireContext(), 2)
        } else {
            LinearLayoutManager(requireContext())
        }

    private fun isTablet(): Boolean {
        val sw = resources.configuration.smallestScreenWidthDp
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return sw >= 600 || (landscape && resources.configuration.screenWidthDp >= 840)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
