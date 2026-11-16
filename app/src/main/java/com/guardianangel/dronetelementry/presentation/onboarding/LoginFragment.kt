package com.guardianangel.dronetelementry.presentation.onboarding

import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.databinding.FragmentLoginBinding
import com.guardianangel.dronetelementry.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.launch

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by viewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLoginBinding.bind(view)
        binding.appBackground.applyMithrilBlurredBackground()

        // Mithril invite field: letters/digits only, forced uppercase, 6 max.
        binding.inviteCode.filters = arrayOf(
            InputFilter.AllCaps(),
            InputFilter.LengthFilter(OnboardingViewModel.INVITE_CODE_LENGTH),
            InputFilter { source, start, end, _, _, _ ->
                val kept = source.subSequence(start, end).filter(Char::isLetterOrDigit)
                if (kept.length == end - start) null else kept
            },
        )
        binding.inviteCode.doAfterTextChanged {
            viewModel.onInviteCodeChanged(it?.toString().orEmpty())
        }
        binding.inviteCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && binding.btnContinue.isEnabled) {
                binding.btnContinue.performClick()
                true
            } else {
                false
            }
        }
        // Continue = the old "Sign in" action (OAuth) for the resolved district.
        binding.btnContinue.setOnClickListener {
            hideKeyboard()
            viewModel.continueLogin(requireActivity())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.progress.isVisible = state.loading
                        binding.btnContinue.isEnabled = !state.loading && state.district != null
                        binding.btnContinue.alpha = if (binding.btnContinue.isEnabled) 1f else 0.6f
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

    private fun hideKeyboard() {
        binding.inviteCode.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.inviteCode.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
