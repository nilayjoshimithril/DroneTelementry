package com.guardianangel.mithrildefense.presentation.map

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
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
import com.guardianangel.mithrildefense.data.remote.SessionEvents
import com.guardianangel.mithrildefense.databinding.ActivityMapSessionBinding
import com.guardianangel.mithrildefense.domain.model.DroneMapPose
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMapSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        binding.btnLeave.setOnClickListener { leaveToHome() }
        binding.btnBack.setOnClickListener { leaveToHome() }
        binding.btnPickerBack?.setOnClickListener { leaveToHome() }
        binding.buildingHeader.setOnClickListener { viewModel.openBuildingPicker() }

        val unavailable = binding.mapUnavailablePanel
        unavailable.btnUnavailableBack.setOnClickListener { leaveToHome() }
        unavailable.btnUnavailableHome.setOnClickListener { leaveToHome() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveToHome()
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                        binding.sidePanel.isVisible = !mapGone

                        binding.pickerCampusName.text = state.campusName
                        binding.campusLabel.text = state.campusName
                        binding.buildingLabel.text = state.selectedBuildingName
                        buildingAdapter.submit(state.buildings)

                        updateFloorDropdown(state)
                        refreshDroneUi(state.selectedDroneId)

                        if (!mapGone) {
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
                        }
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
                    sessionEvents.unauthorized.collect {
                        AppLog.w(TAG, "401 received — closing map session")
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
        appliedLayerKey = null
        floorAdapter = null
        if (::binding.isInitialized) {
            binding.mosaicMap.onDroneTapped = null
        }
        lastListDataKey = null
        lastExpandedId = null
        latestDrones = emptyMap()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MapSessionActivity"

        const val EXTRA_INCIDENT_ID = "incidentId"
        const val EXTRA_CAMPUS_ID = "campusId"
        const val EXTRA_WS_TICKET = "wsTicket"

        fun start(
            context: Context,
            incidentId: String,
            campusId: String,
            wsTicket: String,
        ) {
            context.startActivity(
                Intent(context, MapSessionActivity::class.java).apply {
                    putExtra(EXTRA_INCIDENT_ID, incidentId)
                    putExtra(EXTRA_CAMPUS_ID, campusId)
                    putExtra(EXTRA_WS_TICKET, wsTicket)
                },
            )
        }
    }
}
