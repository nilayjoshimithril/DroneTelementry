package com.guardianangel.dronetelementry.presentation.map

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.data.remote.SessionEvents
import com.guardianangel.dronetelementry.databinding.ActivityMapSessionBinding
import com.guardianangel.dronetelementry.domain.model.DroneMapPose
import com.guardianangel.dronetelementry.utils.AppLog
import com.guardianangel.dronetelementry.utils.ServerClock
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import java.time.Instant
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.qualifier.named

class MapSessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapSessionBinding
    private val viewModel: MapSessionViewModel by viewModel()
    private val tileClient: OkHttpClient by inject(named("tileClient"))
    private val sessionEvents: SessionEvents by inject()

    private val droneAdapter = DroneListAdapter { id -> viewModel.toggleDroneExpanded(id) }
    private val buildingAdapter = BuildingAdapter { item ->
        viewModel.selectBuilding(item.id)
    }
    private var appliedLayerKey: String? = null
    private var floorAdapter: ArrayAdapter<String>? = null
    private var suppressFloorCallback = false
    private var lastListDataKey: String? = null
    private var lastExpandedId: String? = null
    private var latestDrones: Map<String, DroneMapPose> = emptyMap()
    private val droneLayoutManager by lazy { LinearLayoutManager(this) }
    /** User-collapsed drone list — hides the panel so the map gets the full screen. */
    private var dronePanelCollapsed = false
    private var chevronTarget: Float? = null
    private var leaveDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMapSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dronePanelCollapsed = savedInstanceState?.getBoolean(KEY_DRONE_PANEL_COLLAPSED) ?: false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.droneList.layoutManager = droneLayoutManager
        binding.droneList.adapter = droneAdapter
        binding.droneList.setHasFixedSize(false)
        binding.droneList.itemAnimator = null
        (binding.droneList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.buildingList.layoutManager = LinearLayoutManager(this)
        binding.buildingList.adapter = buildingAdapter
        binding.mosaicMap.onDroneTapped = { id -> viewModel.toggleDroneExpanded(id, fromMap = true) }
        binding.mosaicMap.onUserInteraction = { viewModel.onMapUserInteraction() }
        // Mithril onAnnotationClick(THREAT) → performLocateThreat(shouldFocus = true).
        binding.mosaicMap.onThreatTapped = { id -> viewModel.locateThreat(id) }
        binding.btnLocateThreat.setOnClickListener { onLocateThreatClicked() }
        binding.btnNavView.setOnClickListener { viewModel.toggleNavigationView() }

        floorAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf(),
        )
        binding.floorDropdown.setAdapter(floorAdapter)
        binding.floorDropdown.setOnItemClickListener { _, _, position, _ ->
            if (suppressFloorCallback) return@setOnItemClickListener
            val floors = viewModel.state.value.floors
            floors.getOrNull(position)?.let { viewModel.selectFloor(it.id) }
        }

        binding.threatBanner.setOnClickListener {
            viewModel.locateThreat()
            viewModel.dismissThreatBanner()
        }
        binding.threatBannerDismiss.setOnClickListener { viewModel.dismissThreatBanner() }

        binding.btnLeave.setOnClickListener { confirmLeave() }
        binding.btnToggleDrones.setOnClickListener { setDronePanelCollapsed(!dronePanelCollapsed) }
        binding.btnBack.setOnClickListener { confirmLeave() }
        binding.btnPickerBack?.setOnClickListener { handleBack() }
        binding.buildingHeader.setOnClickListener { viewModel.openBuildingPicker() }

        val unavailable = binding.mapUnavailablePanel
        unavailable.btnUnavailableBack.setOnClickListener { leaveToHome() }
        unavailable.btnUnavailableHome.setOnClickListener { leaveToHome() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { runIncidentTimer() }
                launch {
                    viewModel.state.collect { state ->
                        val mapGone = state.mapUnavailable
                        unavailable.root.isVisible = mapGone
                        unavailable.unavailableCampusName.text = state.campusName

                        binding.progress.isVisible = state.loading && !mapGone
                        binding.error.isVisible = !mapGone && !state.error.isNullOrBlank()
                        binding.error.text = state.error
                        binding.socketStatus.text = state.statusMessage

                        val showPicker = !mapGone && state.showBuildingPicker
                        val showMap = !mapGone && !showPicker && state.error.isNullOrBlank()
                        binding.buildingPicker.isVisible = showPicker
                        binding.mapChrome.isVisible = showMap
                        binding.mosaicMap.isVisible = showMap
                        applyDronePanel(mapGone = mapGone, showMap = showMap)

                        binding.pickerCampusName.text = state.campusName
                        binding.incidentCampusName.text = state.campusName
                        binding.buildingLabel.text = state.selectedBuildingName
                        buildingAdapter.submit(state.buildings)

                        updateFloorDropdown(state)
                        refreshDroneUi(state.selectedDroneId)

                        if (!mapGone) {
                            binding.mosaicMap.defaultZoom = state.defaultZoom
                            state.mosaic?.let { mosaic ->
                                if (appliedLayerKey != mosaic.layerKey) {
                                    appliedLayerKey = mosaic.layerKey
                                    binding.mosaicMap.configure(
                                        cols = mosaic.cols,
                                        rows = mosaic.rows,
                                        tileWidth = mosaic.tileWidth,
                                        tileHeight = mosaic.tileHeight,
                                        tileList = mosaic.tiles,
                                        overviewUrl = mosaic.overviewUrl,
                                        client = tileClient,
                                        scope = lifecycleScope,
                                    )
                                }
                            }
                            binding.mosaicMap.updateAnnotations(emptyList())
                            // After configure() so a fly-to waits for the new layer's fit.
                            binding.mosaicMap.setCameraFollow(state.cameraFollow)
                        }

                        binding.btnNavView.isVisible = showMap
                        binding.btnNavView.isSelected = state.navigationView
                        binding.btnNavView.imageTintList = ColorStateList.valueOf(
                            getColor(if (state.navigationView) R.color.md_turquoise else R.color.white),
                        )
                        binding.mosaicMap.setNavigationView(state.navigationView)

                        binding.locateThreatContainer.isVisible = showMap
                        binding.btnLocateThreat.isEnabled = state.threatCount > 0
                        binding.btnLocateThreat.imageAlpha = if (state.threatCount > 0) 255 else 90
                        binding.threatCountBadge.isVisible = state.threatCount > 0
                        binding.threatCountBadge.text = state.threatCount.toString()

                        val banner = state.threatBanner
                        binding.threatBanner.isVisible = showMap && banner != null
                        if (banner != null) {
                            binding.threatBannerText.text = getString(
                                R.string.threat_banner_text,
                                ordinal(banner.threatCount),
                            )
                        }
                    }
                }
                launch {
                    // Mithril: incident resolved / false alarm → leave the session; Home shows
                    // the "Alarm Ended" dialog.
                    viewModel.incidentEnded.collect { notice ->
                        sessionEvents.postIncidentEnded(notice)
                        finish()
                    }
                }
                launch {
                    viewModel.droneRoster.collect { roster ->
                        droneAdapter.submitRoster(roster)
                    }
                }
                launch {
                    viewModel.currentUserIdFlow.collect { droneAdapter.setCurrentUserId(it) }
                }
                launch {
                    viewModel.overlays.collect { overlays ->
                        binding.mosaicMap.updateOverlays(overlays.pathways, overlays.threats)
                    }
                }
                launch {
                    viewModel.drones.collect { drones ->
                        latestDrones = drones
                        refreshDroneUi(viewModel.state.value.selectedDroneId)
                    }
                }
                launch {
                    viewModel.focusDrone.collect { droneId ->
                        scrollToDrone(droneId)
                    }
                }
                launch {
                    // Not consumed here: MainActivity consumes it and opens login after we finish.
                    sessionEvents.unauthorized.filter { it }.collect {
                        AppLog.w(TAG, "Session ended — closing map session")
                        finish()
                    }
                }
            }
        }
    }

    private fun refreshDroneUi(expandedId: String?) {
        val drones = latestDrones
        binding.mosaicMap.updateDrones(drones, expandedId)
        // Keep the hint visible even while a row is expanded.
        binding.droneSelectHint?.isVisible = drones.isNotEmpty()

        // Rebuild list only when stable fields change — not every heading/pose tick.
        val dataKey = drones.values
            .sortedBy { it.droneDisplayId }
            .joinToString("|") {
                "${it.droneDisplayId}:${it.state}:${it.batteryPercent}:${it.motorStatus}:" +
                    "${"%.1f".format(it.positionZ)}:${"%.2f".format(it.confidence)}:" +
                    "${it.escStatus}:${it.batteryUnitStatus}:${it.halowConnectivityStatus}:" +
                    "${it.gyroStatus}:${it.accStatus}:${it.barometerStatus}:${it.droneIp}"
            }
        if (dataKey != lastListDataKey) {
            lastListDataKey = dataKey
            droneAdapter.submitData(drones.values.toList())
            // Re-apply after full rebind so expand state is never lost.
            droneAdapter.setExpandedId(expandedId)
            lastExpandedId = expandedId
        } else if (expandedId != lastExpandedId) {
            val previouslyExpanded = lastExpandedId
            lastExpandedId = expandedId
            droneAdapter.setExpandedId(expandedId)
            // Keep expanded details on-screen (avoids text sitting under Leave / clipped).
            if (expandedId != null && expandedId != previouslyExpanded) {
                scrollToDrone(expandedId, alignToTop = true)
            }
        }
    }

    private fun scrollToDrone(droneId: String, alignToTop: Boolean = false) {
        val index = droneAdapter.indexOf(droneId)
        if (index < 0) {
            binding.droneList.post {
                val retry = droneAdapter.indexOf(droneId)
                if (retry >= 0) {
                    if (alignToTop) {
                        droneLayoutManager.scrollToPositionWithOffset(retry, 0)
                    } else {
                        binding.droneList.smoothScrollToPosition(retry)
                    }
                }
            }
            return
        }
        binding.droneList.post {
            if (alignToTop) {
                droneLayoutManager.scrollToPositionWithOffset(index, 0)
            } else {
                binding.droneList.smoothScrollToPosition(index)
            }
        }
    }

    /**
     * Mithril locate-threat button: a single threat is focused directly; with several, the
     * "Select Threat to Locate" list opens (current focus checked; picking it again unfocuses).
     */
    private fun onLocateThreatClicked() {
        val threats = viewModel.threatListItems()
        when {
            threats.isEmpty() -> return
            threats.size == 1 -> viewModel.locateThreat(threats.first().id)
            else -> showThreatSelectionDialog(threats)
        }
    }

    private fun showThreatSelectionDialog(threats: List<ThreatListItem>) {
        val followedId = viewModel.state.value.followedThreatId
        val hint = getColor(R.color.md_hint)
        val labels = threats.map { item ->
            SpannableStringBuilder(item.name).apply {
                append('\n')
                val start = length
                append(item.location)
                setSpan(ForegroundColorSpan(hint), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_threat_to_locate)
            .setSingleChoiceItems(labels, threats.indexOfFirst { it.id == followedId }) { dialog, which ->
                dialog.dismiss()
                threats.getOrNull(which)?.let { viewModel.onThreatSelected(it.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Mithril ThreatBanner ordinal: 1st, 2nd, 3rd, 11th… */
    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    private fun setDronePanelCollapsed(collapsed: Boolean) {
        if (dronePanelCollapsed == collapsed) return
        dronePanelCollapsed = collapsed
        val state = viewModel.state.value
        val mapGone = state.mapUnavailable
        val showMap = !mapGone && !state.showBuildingPicker && state.error.isNullOrBlank()
        applyDronePanel(mapGone = mapGone, showMap = showMap, animate = true)
    }

    private fun applyDronePanel(mapGone: Boolean, showMap: Boolean, animate: Boolean = false) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panelVisible = !mapGone && !dronePanelCollapsed
        if (animate && binding.sidePanel.isVisible != panelVisible) {
            // Panel slides over the (fixed, full-screen) map; chrome and map buttons glide to
            // their new positions instead of jumping.
            val duration = 280L
            val easing = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            val transition = TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(Slide(if (landscape) Gravity.END else Gravity.BOTTOM).addTarget(binding.sidePanel))
                .addTransition(
                    ChangeBounds()
                        .excludeTarget(binding.mosaicMap, true)
                        .excludeTarget(binding.sidePanel, true)
                        .excludeChildren(binding.sidePanel, true),
                )
                .setDuration(duration)
                .setInterpolator(easing)
            TransitionManager.beginDelayedTransition(binding.root, transition)
        }
        binding.sidePanel.isVisible = panelVisible
        binding.btnToggleDrones.isVisible = showMap
        // Arrow points where the panel goes: landscape panel is on the right, portrait at the bottom.
        val chevron = when {
            landscape && dronePanelCollapsed -> 90f   // ← bring it back in from the right
            landscape -> -90f                         // → push it out to the right
            dronePanelCollapsed -> 180f               // ↑ bring it back up
            else -> 0f                                // ↓ push it down
        }
        // Only touch the arrow when its target changes, so frequent state updates don't cut the
        // rotation animation short.
        if (chevron != chevronTarget) {
            chevronTarget = chevron
            binding.toggleDronesChevron.animate().cancel()
            if (animate) {
                binding.toggleDronesChevron.animate().rotation(chevron).setDuration(280L).start()
            } else {
                binding.toggleDronesChevron.rotation = chevron
            }
        }
        binding.btnToggleDrones.contentDescription = getString(
            if (dronePanelCollapsed) R.string.expand_drones else R.string.collapse_drones,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DRONE_PANEL_COLLAPSED, dronePanelCollapsed)
    }

    /**
     * System back / picker back: close the building picker and return to the map first;
     * otherwise ask before leaving the incident map.
     */
    private fun handleBack() {
        if (viewModel.state.value.mapUnavailable) {
            leaveToHome()
            return
        }
        if (viewModel.state.value.showBuildingPicker && viewModel.closeBuildingPicker()) return
        confirmLeave()
    }

    /** Back / Leave on the map: confirm before dropping out of the live incident view. */
    private fun confirmLeave() {
        if (leaveDialog?.isShowing == true) return
        leaveDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leave_map_title)
            .setMessage(R.string.leave_map_message)
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.leave_session) { _, _ -> leaveToHome() }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    // Leave in the app's orange accent; Cancel stays neutral.
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.md_orange))
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.md_hint))
                }
                dialog.setOnDismissListener { leaveDialog = null }
                dialog.show()
            }
    }

    /** Header "Running HH:MM:SS" since the incident's CreatedAt; hidden when it's unknown. */
    private suspend fun runIncidentTimer() {
        val raw = intent.getStringExtra(EXTRA_STARTED_AT)?.trim().orEmpty()
        val startMs = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        if (startMs == null) {
            if (raw.isNotEmpty()) AppLog.w(TAG, "Unparseable incident CreatedAt=$raw")
            binding.incidentRunningRow.isVisible = false
            return
        }
        binding.incidentRunningRow.isVisible = true
        while (true) {
            // Server clock, not the device clock: a phone set to the wrong date would
            // otherwise put the start in the future and freeze the timer at 00:00:00.
            val elapsedMs = (ServerClock.nowMs() - startMs).coerceAtLeast(0L)
            binding.incidentRunningTime.text =
                getString(R.string.incident_running, formatElapsed(elapsedMs / 1000))
            // Tick on the second boundary so the clock never skips or stutters.
            delay(1000L - elapsedMs % 1000L)
        }
    }

    private fun formatElapsed(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    }

    private fun leaveToHome() {
        finish()
    }

    private fun updateFloorDropdown(state: MapSessionUiState) {
        val labels = state.floors.map { it.label }
        floorAdapter?.clear()
        floorAdapter?.addAll(labels)
        floorAdapter?.notifyDataSetChanged()

        suppressFloorCallback = true
        binding.floorDropdown.setText(state.selectedFloorLabel, false)
        suppressFloorCallback = false
        binding.floorDropdownLayout.isVisible = state.floors.isNotEmpty()
    }

    override fun onDestroy() {
        leaveDialog?.dismiss()
        leaveDialog = null
        appliedLayerKey = null
        floorAdapter = null
        if (::binding.isInitialized) {
            binding.mosaicMap.onDroneTapped = null
            binding.mosaicMap.onThreatTapped = null
            binding.mosaicMap.onUserInteraction = null
        }
        lastListDataKey = null
        lastExpandedId = null
        latestDrones = emptyMap()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MapSessionActivity"
        private const val KEY_DRONE_PANEL_COLLAPSED = "drone_panel_collapsed"

        const val EXTRA_INCIDENT_ID = "incidentId"
        const val EXTRA_CAMPUS_ID = "campusId"
        const val EXTRA_WS_TICKET = "wsTicket"
        const val EXTRA_STARTED_AT = "startedAt"

        fun start(
            context: Context,
            incidentId: String,
            campusId: String,
            wsTicket: String,
            startedAt: String?,
        ) {
            context.startActivity(
                Intent(context, MapSessionActivity::class.java).apply {
                    putExtra(EXTRA_INCIDENT_ID, incidentId)
                    putExtra(EXTRA_CAMPUS_ID, campusId)
                    putExtra(EXTRA_WS_TICKET, wsTicket)
                    putExtra(EXTRA_STARTED_AT, startedAt)
                },
            )
        }
    }
}
