package com.guardianangel.dronetelementry.presentation.onboarding

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * No login UI: goes straight to the Microsoft AD page. Shows only the splash backdrop while
 * the page opens and while the token is exchanged.
 */
class LoginFragment : Fragment(R.layout.fragment_splash) {

    private val viewModel: OnboardingViewModel by viewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<ImageView>(R.id.app_background)?.applyMithrilBlurredBackground()
        viewModel.startLoginIfNeeded(requireActivity())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { dest ->
                    when (dest) {
                        OnboardingNav.Home -> findNavController().navigate(R.id.action_login_to_home)
                        OnboardingNav.Exit -> requireActivity().finish()
                        is OnboardingNav.Failed -> showFailure(dest.message)
                    }
                }
            }
        }
    }

    private fun showFailure(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sign_in_failed)
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton(R.string.close) { _, _ -> requireActivity().finish() }
            .setPositiveButton(R.string.retry) { _, _ -> viewModel.signIn(requireActivity()) }
            .show()
    }
}
