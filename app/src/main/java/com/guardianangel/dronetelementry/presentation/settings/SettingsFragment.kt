package com.guardianangel.dronetelementry.presentation.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianangel.dronetelementry.BuildConfig
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.databinding.FragmentSettingsBinding
import com.guardianangel.dronetelementry.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSettingsBinding.bind(view)
        binding.appBackground.applyMithrilBlurredBackground()

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.rowMapZoom.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_zoom_campuses)
        }
        binding.rowLogout.setOnClickListener { confirmLogout() }
        binding.appVersion.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.userName.text = state.userName
                        binding.userEmail.text = state.userEmail
                        binding.zoomCurrent.text = getString(
                            R.string.zoom_current,
                            requireContext().formatZoomLevel(state.zoomLevel),
                        )
                    }
                }
                launch {
                    viewModel.nav.collect { dest ->
                        when (dest) {
                            SettingsNav.Login ->
                                findNavController().navigate(R.id.action_global_to_login)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The zoom preview may have saved a new level.
        viewModel.refreshZoom()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.logout_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yes_log_out) { _, _ -> viewModel.logout() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
