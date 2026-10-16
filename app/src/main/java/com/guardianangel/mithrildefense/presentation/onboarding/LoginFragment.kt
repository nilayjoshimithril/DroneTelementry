package com.guardianangel.mithrildefense.presentation.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.guardianangel.mithrildefense.R
import com.guardianangel.mithrildefense.databinding.FragmentLoginBinding
import com.guardianangel.mithrildefense.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.launch

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by viewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLoginBinding.bind(view)
        binding.appBackground.applyMithrilBlurredBackground()

        binding.inviteCode.doAfterTextChanged {
            viewModel.onInviteCodeChanged(it?.toString().orEmpty())
        }
        binding.btnContinue.setOnClickListener { viewModel.submitInviteCode() }
        binding.btnMicrosoft.setOnClickListener {
            viewModel.continueLogin(requireActivity())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.progress.isVisible = state.loading
                        binding.btnContinue.isEnabled = !state.loading
                        binding.btnMicrosoft.isEnabled = !state.loading
                        binding.districtPanel.isVisible = state.district != null
                        state.district?.let { district ->
                            binding.districtName.text = district.name
                            binding.districtMeta.text = listOfNotNull(
                                district.city.takeIf { it.isNotBlank() },
                                district.state.takeIf { it.isNotBlank() },
                            ).joinToString(", ")
                        }
                        binding.error.isVisible = !state.error.isNullOrBlank()
                        binding.error.text = state.error
                    }
                }
                launch {
                    viewModel.nav.collect { dest ->
                        when (dest) {
                            OnboardingNav.Home ->
                                findNavController().navigate(R.id.action_login_to_home)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
