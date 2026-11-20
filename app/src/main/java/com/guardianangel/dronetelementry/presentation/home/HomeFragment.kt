package com.guardianangel.dronetelementry.presentation.home

import android.content.res.Configuration
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.guardianangel.dronetelementry.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianangel.dronetelementry.data.remote.IncidentEndedNotice
import com.guardianangel.dronetelementry.data.remote.SessionEvents
import com.guardianangel.dronetelementry.databinding.FragmentHomeBinding
import com.guardianangel.dronetelementry.presentation.map.MapSessionActivity
import com.guardianangel.dronetelementry.ui.applyMithrilBlurredBackground
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModel()
    private val sessionEvents: SessionEvents by inject()

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
        // Log out moved to Settings (with its confirmation dialog).
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }

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
                            // Mithril HomeDialogs "Alarm Ended": the map screen closed because
                            // the incident was resolved / cancelled.
                            sessionEvents.incidentEnded.filterNotNull().collect { notice ->
                                sessionEvents.consumeIncidentEnded()
                                viewModel.refresh(showLoader = false)
                                showIncidentEndedDialog(notice)
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
                                            startedAt = dest.startedAt,
                                        )
                                    }
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

    private fun showIncidentEndedDialog(notice: IncidentEndedNotice) {
        val ctx = requireContext()
        val muted = ColorUtils.setAlphaComponent(ctx.getColor(R.color.md_primary), 0x99)
        val message = SpannableStringBuilder()
        fun mutedText(text: String) {
            val start = message.length
            message.append(text)
            message.setSpan(ForegroundColorSpan(muted), start, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun campus() {
            val start = message.length
            message.append(notice.campusName)
            message.setSpan(StyleSpan(Typeface.BOLD), start, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Same copy as Mithril buildIncidentEndedMessage.
        when {
            notice.isFalseAlarm && notice.isLawEnforcement -> {
                mutedText("The alert at "); campus(); mutedText(" has been cancelled")
            }
            notice.isFalseAlarm -> {
                mutedText("FALSE ALARM. "); campus()
                mutedText(" is no longer being monitored by Campus Guardian Angel.")
            }
            notice.isLawEnforcement -> {
                mutedText("The incident at "); campus()
                mutedText(" is no longer being monitored by Campus Guardian Angel. ")
            }
            else -> {
                mutedText("The incident at "); campus()
                mutedText(
                    " is no longer being monitored by Campus Guardian Angel. " +
                        "All future information will be communicated by School administration or law enforcement.",
                )
            }
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.alarm_ended)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(
                if (notice.isLawEnforcement) R.string.continue_action else R.string.dismiss,
                null,
            )
            .show()
        // Close on its own if the user doesn't. Tied to the view lifecycle so it never touches
        // a dismissed dialog or a destroyed fragment.
        val autoDismiss = viewLifecycleOwner.lifecycleScope.launch {
            delay(INCIDENT_ENDED_AUTO_DISMISS_MS)
            if (dialog.isShowing) dialog.dismiss()
        }
        dialog.setOnDismissListener { autoDismiss.cancel() }
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

    companion object {
        private const val INCIDENT_ENDED_AUTO_DISMISS_MS = 5_000L
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
