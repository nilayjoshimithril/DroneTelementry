package com.guardianangel.mithrildefense.presentation.splash

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.guardianangel.mithrildefense.R
import com.guardianangel.mithrildefense.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class SplashFragment : Fragment(R.layout.fragment_splash) {

    private val viewModel: SplashViewModel by viewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<ImageView>(R.id.app_background)?.applyMithrilBlurredBackground()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nav.collect { dest ->
                    val nav = findNavController()
                    when (dest) {
                        SplashNav.Login -> nav.navigate(R.id.action_splash_to_login)
                        SplashNav.Home -> nav.navigate(R.id.action_splash_to_home)
                    }
                }
            }
        }
    }
}
