package com.guardianangel.dronetelementry.map

import android.animation.Animator
import android.animation.TimeAnimator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.PathInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.data.model.MapTileDto
import com.guardianangel.dronetelementry.domain.model.DroneMapPose
import com.guardianangel.dronetelementry.presentation.map.CameraFollow
import com.guardianangel.dronetelementry.utils.AppLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class MapAnnotationMarker(
    val id: String,
    val type: String,
    val label: String,
    val pixelX: Float,
    val pixelY: Float,
    val iconRes: Int = 0,
    val showLabel: Boolean = false,
)

/** One mosaic cell at one decode size bucket (see [decodeTargetForLod]). */
private data class TileKey(val col: Int, val row: Int, val lod: Int)

/**
 * Mosaic floor-plan: low-res overview first, hi-res tiles after zoom (Mithril LOD),
 * plus color pathways, annotations, threat markers + drone markers.
 *
 * Tile pipeline (Mithril "Optimize map performance, memory usage, and tile loading pipeline"):
 * only on-screen tiles (+1 cell) are loaded, nearest to the centre first, decoded at a size that
 * matches their on-screen width, and kept in a byte-capped LRU that never evicts on-screen tiles.
 * Large campuses (big outdoor grids, many buildings/floors) therefore cost memory proportional
 * to the screen, not to the map.
 */
class MosaicMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val logTag = "MosaicMap"

    private var worldWidth = 1f
    private var worldHeight = 1f
    private var tileWidth = 1920
    private var tileHeight = 1080
    private var cols = 1
    private var rows = 1

    // ---- Tile pipeline (main thread only) ----
    /** "col_row" → URL for the active layer. */
    private var tileUrls: Map<String, String> = emptyMap()
    /** Access-ordered LRU of decoded tiles. */
    private val tileCache = LinkedHashMap<TileKey, Bitmap>(64, 0.75f, true)
    private var tileCacheBytes = 0L
    private val loadingJobs = HashMap<TileKey, Job>()
    private val failedTiles = HashMap<TileKey, Long>()
    /** On-screen cells (no buffer) at [tileLod], from the last scheduling pass: never evicted. */
    private val onScreenTiles = HashSet<TileKey>()
    /** Decode bucket currently drawn/loaded. Coarser applies at once, sharper after a settle. */
    private var tileLod = 0
    private var lastLodScale = 0f
    private var scheduleQueued = false
    private var lastScheduleAt = 0L
    /** Bumped on every [configure] so loads for a previous layer never land in this one. */
    private var layerGeneration = 0
    private val visibleRange = IntArray(4)

    private var overviewJob: Job? = null
    private var httpClient: OkHttpClient? = null
    private var loadScope: CoroutineScope? = null

    private var overviewUrl: String? = null
    private var overviewBitmap: Bitmap? = null
    private var hasOverview = false
    private var minScale = 1f

    private val drawMatrix = Matrix()
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f

    /** Drone markers as drawn this frame (interpolated by [droneSmoother] between updates). */
    @Volatile
    private var dronePoses: Map<String, DroneMapPose> = emptyMap()
    private val droneSmoother = DroneMotionSmoother()
    /** Ticks every frame while a drone marker is gliding; idle otherwise. */
    private var droneGlideAnimator: TimeAnimator? = null
    @Volatile
    private var selectedDroneId: String? = null
    @Volatile
    private var annotations: List<MapAnnotationMarker> = emptyList()
    @Volatile
    private var pathways: List<Pair<PathwayRender, Path>> = emptyList()
    @Volatile
    private var threats: List<ThreatMarker> = emptyList()
    private var fitPending = false

    /** Marker the camera keeps centred (Mithril followedThreatId; drones use the same follow). */
    private var follow: CameraFollow? = null
    /** A fly-to for [follow] is owed: runs as soon as the layer is fitted and the marker is known. */
    private var followFlyToPending = false
    /** Layer was reconfigured under an active follow: open it zoomed on the marker (no fly-to). */
    private var followSnapPending = false
    private var cameraAnimator: ValueAnimator? = null
    private val isCameraAnimating: Boolean get() = cameraAnimator != null

    // ---- Navigation view (Google Maps driving-style 3D camera) ----
    /** 0 = top-down, 1 = fully tilted to [NAV_TILT_DEG]; animated by [navAnimator]. */
    private var navT = 0f
    /** Map rotation in degrees (clockwise heading that points straight up on screen). */
    private var bearing = 0f
    private var navEnabled = false
    private var navAnimator: ValueAnimator? = null
    /** Zoom before entering the navigation view, restored when leaving it. */
    private var preNavScale: Float? = null
    /** Screen-space part of the camera: rotate by -[bearing] then tilt, both about the anchor. */
    private val viewMatrix = Matrix()
    private val tiltMatrix = Matrix()
    private val tiltValues = FloatArray(9)
    private val screenPt = FloatArray(2)
    private val prePt = FloatArray(2)
    private val visibleWorld = RectF()
    private val preClip = RectF()

    /** Zoom a layer opens at, as a multiple of "fit the whole layer" (1 = fit). */
    var defaultZoom = 1f
        set(value) {
            field = value.coerceAtLeast(1f)
        }

    /**
     * Settings preview: apply [level] to the current layer immediately, keeping the point under
     * the screen centre fixed, and use it for later fits too.
     */
    fun previewZoom(level: Float) {
        defaultZoom = level
        if (fitPending || width == 0 || height == 0) return
        stopCameraAnimation()
        val center = centerWorld()
        scale = (minScale * defaultZoom).coerceIn(minScale, MAX_SCALE)
        setCenterWorld(center)
    }

    var onDroneTapped: ((String) -> Unit)? = null
    /** Tap on a threat marker — Mithril onAnnotationClick(THREAT) → performLocateThreat. */
    var onThreatTapped: ((String) -> Unit)? = null
    /** User pan / pinch / double-tap — the camera follow has been released. */
    var onUserInteraction: (() -> Unit)? = null

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overviewPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dronePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DRONE_COLOR
        style = Paint.Style.FILL
    }
    private val droneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val headingRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val headingArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(0x66, 0xFF, 0xFF, 0xFF)
    }
    private val labelRect = RectF()
    /** Parsed `pilot_hex_color` values (null = unparseable), so labels don't re-parse per frame. */
    private val pilotColorCache = HashMap<String, Int?>()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Drone glyph recoloured dark, for light pilot colours where the white glyph would vanish. */
    private val iconDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(DRONE_ON_LIGHT_COLOR, PorterDuff.Mode.SRC_IN)
    }
    private val pathwayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val threatLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val threatLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }
    private val threatLabelRect = RectF()
    private val threatHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40EF4141")
        style = Paint.Style.FILL
    }
    private val threatHaloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4141")
        style = Paint.Style.STROKE
    }
    private val navArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NAV_ARROW_COLOR
        style = Paint.Style.FILL
    }
    private val navArrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val navShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.FILL
    }
    private val iconCache = ConcurrentHashMap<Int, Bitmap>()
    private val headingArcRect = RectF()
    private val droneBodyRect = RectF()
    private val annotationRect = RectF()
    private val tileDst = RectF()
    private val arrowPath = Path()

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val overviewDst = RectF()
    private val overviewSrc = Rect()

    private val scheduleRunnable = Runnable {
        scheduleQueued = false
        lastScheduleAt = SystemClock.uptimeMillis()
        scheduleTilesNow()
    }
    private val lodSettleRunnable = Runnable {
        tileLod = lodForScale(scale)
        scheduleTiles()
        invalidate()
    }

    /** OS low-memory signal (Mithril MapMemoryPressure): keep only on-screen tiles. */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) post { dropOffscreenTiles() }
        }
        override fun onLowMemory() {
            post { dropOffscreenTiles() }
        }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    fun configure(
        cols: Int,
        rows: Int,
        tileWidth: Int,
        tileHeight: Int,
        tileList: List<MapTileDto>,
        overviewUrl: String?,
        client: OkHttpClient,
        scope: CoroutineScope,
    ) {
        this.cols = cols.coerceAtLeast(1)
        this.rows = rows.coerceAtLeast(1)
        this.tileWidth = tileWidth.coerceAtLeast(1)
        this.tileHeight = tileHeight.coerceAtLeast(1)
        worldWidth = this.cols * this.tileWidth.toFloat()
        worldHeight = this.rows * this.tileHeight.toFloat()
        httpClient = client
        loadScope = scope
        tileUrls = tileList.associate { "${it.col}_${it.row}" to it.url.replace(" ", "") }
        this.overviewUrl = overviewUrl?.takeIf { it.isNotBlank() }
        hasOverview = this.overviewUrl != null
        AppLog.i(
            logTag,
            "configure mosaic ${this.cols}x${this.rows} " +
                "tile=${this.tileWidth}x${this.tileHeight} urls=${tileList.size} " +
                "overview=${!this.overviewUrl.isNullOrBlank()}",
        )
        clearTiles()
        clearOverview()
        stopCameraAnimation()
        // New layer = new pixel space: markers must snap, not slide across from the old floor.
        stopDroneGlide()
        droneSmoother.reset()
        // Mithril floor switch: the remounted map opens on the followed marker. A fly-to still
        // owed (locate across layers) wins and animates in from the fitted view instead.
        followSnapPending = follow != null && !followFlyToPending
        fitPending = true
        if (hasOverview) loadOverview(this.overviewUrl!!)
        post { fitToView() }
    }

    fun updateDrones(poses: Map<String, DroneMapPose>, selectedId: String? = selectedDroneId) {
        selectedDroneId = selectedId
        droneSmoother.setTargets(poses, SystemClock.uptimeMillis())
        stepDroneGlide()
        if (droneSmoother.isAnimating(SystemClock.uptimeMillis())) startDroneGlide()
    }

    /** Advance gliding markers to this frame; the follow camera rides the drawn position. */
    private fun stepDroneGlide() {
        dronePoses = droneSmoother.current(SystemClock.uptimeMillis())
        if (follow?.kind == CameraFollow.Kind.DRONE) applyFollowCamera()
        postInvalidateOnAnimation()
    }

    private fun startDroneGlide() {
        if (droneGlideAnimator != null) return
        droneGlideAnimator = TimeAnimator().apply {
            setTimeListener { _, _, _ ->
                stepDroneGlide()
                if (!droneSmoother.isAnimating(SystemClock.uptimeMillis())) stopDroneGlide()
            }
            start()
        }
    }

    private fun stopDroneGlide() {
        droneGlideAnimator?.let {
            droneGlideAnimator = null
            it.cancel()
        }
    }

    fun setSelectedDrone(id: String?) {
        if (selectedDroneId == id) return
        selectedDroneId = id
        postInvalidateOnAnimation()
    }

    fun updateAnnotations(markers: List<MapAnnotationMarker>) {
        if (markers.isEmpty() && annotations.isEmpty()) return
        annotations = markers
        AppLog.d(logTag, "annotations on map count=${markers.size}")
        invalidate()
    }

    fun updateOverlays(pathwayList: List<PathwayRender>, threatList: List<ThreatMarker>) {
        // Paths are rebuilt only when overlays change, not every drone animation frame.
        pathways = pathwayList.map { render ->
            val path = Path()
            val pts = render.points
            if (pts.size >= 4) {
                path.moveTo(pts[0], pts[1])
                var i = 2
                while (i + 1 < pts.size) {
                    path.lineTo(pts[i], pts[i + 1])
                    i += 2
                }
            }
            render to path
        }
        threats = threatList
        if (follow?.kind == CameraFollow.Kind.THREAT) applyFollowCamera()
        invalidate()
    }

    /**
     * Keep [target]'s marker centred. A new target (or a new nonce for the same one, i.e. the user
     * located it again) flies to it; afterwards every marker update re-centres the camera.
     */
    fun setCameraFollow(target: CameraFollow?) {
        if (target == follow) return
        follow = target
        followSnapPending = false
        followFlyToPending = target != null
        if (target == null) {
            stopCameraAnimation()
            return
        }
        applyFollowCamera()
    }

    /** World position of the followed marker as it is drawn right now, or null if not on this layer. */
    private fun followTargetPos(): PointF? {
        val target = follow ?: return null
        return when (target.kind) {
            CameraFollow.Kind.THREAT ->
                threats.firstOrNull { it.id == target.id }?.let { PointF(it.pixelX, it.pixelY) }
            CameraFollow.Kind.DRONE ->
                (dronePoses[target.id] ?: dronePoses.values.firstOrNull { it.droneDisplayId == target.id })
                    ?.let { PointF(it.pixelX, it.pixelY) }
        }
    }

    /**
     * FOLLOW CAMERA (Mithril MithrilMapComponent): runs synchronously in the same call that
     * swaps in the new marker positions, so the camera moves in the same frame as the marker.
     * Re-centring from a later callback would draw the marker moved a frame or more before the
     * camera catches up, which reads as the map lagging on fast moves.
     */
    private fun applyFollowCamera() {
        if (follow == null || fitPending || width == 0 || height == 0) return
        val pos = followTargetPos() ?: return
        steerBearingToFollowedDrone()
        when {
            followFlyToPending -> {
                followFlyToPending = false
                followSnapPending = false
                animateCenterAndScale(pos, focusScale(), liveTarget = ::followTargetPos)
            }
            followSnapPending -> {
                followSnapPending = false
                scale = focusScale()
                setCenterWorld(pos)
            }
            // A running fly-to tracks the marker itself (live target).
            !isCameraAnimating -> setCenterWorld(pos)
        }
    }

    /**
     * Navigation view: ease the map rotation toward the followed drone's heading so its
     * direction of travel points up (Google Maps driving camera). Smoothed so jittery
     * telemetry headings don't shake the map.
     */
    private fun steerBearingToFollowedDrone() {
        if (!navEnabled) return
        val target = follow?.takeIf { it.kind == CameraFollow.Kind.DRONE } ?: return
        val pose = dronePoses[target.id]
            ?: dronePoses.values.firstOrNull { it.droneDisplayId == target.id }
            ?: return
        val delta = shortestAngle(bearing, screenHeading(pose))
        if (kotlin.math.abs(delta) < 0.05f) return
        bearing = normalizeDeg(bearing + delta * BEARING_SMOOTHING)
        updateViewMatrix()
    }

    /**
     * Telemetry yaw → map heading (0° = screen up, clockwise). Yaw is Unreal's: 0° along +X,
     * which the floor-plan transform maps to screen-right, increasing clockwise (+Y is down) —
     * so up-relative heading is yaw + 90°. Drawing raw yaw pointed every arrow 90° off.
     */
    private fun screenHeading(pose: DroneMapPose): Float =
        normalizeDeg(pose.headingDegrees + YAW_TO_SCREEN_HEADING_DEG)

    /**
     * Locate zooms to at least fit * 2.5 so the marker's surroundings are readable; in the
     * navigation view at least fit * [NAV_ZOOM_MULTIPLIER] so the tilted ground stays legible.
     */
    private fun focusScale(): Float {
        val multiplier = if (navEnabled) NAV_ZOOM_MULTIPLIER else FOCUS_ZOOM_MULTIPLIER
        return scale.coerceAtLeast(minScale * multiplier).coerceAtMost(MAX_SCALE)
    }

    /** Screen point the camera centres on: mid-screen top-down, lower third when tilted. */
    private fun anchorX(): Float = width / 2f
    private fun anchorY(): Float = height * (0.5f + (NAV_ANCHOR_Y_FRAC - 0.5f) * navT)

    private fun setCenterWorld(world: PointF) {
        transX = anchorX() - world.x * scale
        transY = anchorY() - world.y * scale
        onViewportChanged()
    }

    private fun centerWorld(): PointF =
        PointF((anchorX() - transX) / scale, (anchorY() - transY) / scale)

    /**
     * Mithril ViewportState.animateCenterAndScale: optional 250 ms zoom-out for context, then a
     * 450 ms FastOutSlowIn move to [worldPos] at [newScale]. [liveTarget], when it returns a
     * position, is read every frame and overrides [worldPos], so a marker that keeps moving
     * mid-flight is tracked and the animation ends exactly on it instead of its stale start.
     */
    private fun animateCenterAndScale(
        worldPos: PointF,
        newScale: Float,
        liveTarget: (() -> PointF?)? = null,
    ) {
        stopCameraAnimation()
        val targetScale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val startScale = scale
        val intermediateScale = (min(startScale, targetScale) * 0.7f).coerceAtLeast(minScale)

        fun moveStage() {
            val fromScale = scale
            val fromX = transX
            val fromY = transY
            startCameraAnimator(FLY_TO_MOVE_MS) { progress ->
                scale = fromScale + (targetScale - fromScale) * progress
                val target = liveTarget?.invoke() ?: worldPos
                val toX = anchorX() - target.x * targetScale
                val toY = anchorY() - target.y * targetScale
                transX = fromX + (toX - fromX) * progress
                transY = fromY + (toY - fromY) * progress
            }
        }

        if (startScale > intermediateScale * 1.1f) {
            startCameraAnimator(FLY_TO_PRE_ZOOM_MS, onEnd = ::moveStage) { progress ->
                val center = centerWorld()
                scale = startScale + (intermediateScale - startScale) * progress
                transX = anchorX() - center.x * scale
                transY = anchorY() - center.y * scale
            }
        } else {
            moveStage()
        }
    }

    private fun startCameraAnimator(
        durationMs: Long,
        onEnd: (() -> Unit)? = null,
        onFrame: (Float) -> Unit,
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = FAST_OUT_SLOW_IN
        }
        animator.addUpdateListener {
            onFrame(it.animatedValue as Float)
            onViewportChanged()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Cancelled animators were already detached by stopCameraAnimation().
                if (cameraAnimator !== animation) return
                cameraAnimator = null
                if (onEnd != null) onEnd() else applyFollowCamera()
            }
        })
        cameraAnimator = animator
        animator.start()
    }

    private fun stopCameraAnimation() {
        val animator = cameraAnimator ?: return
        cameraAnimator = null
        animator.cancel()
    }

    /** Pan / pinch / double-tap: stop any fly-to and hand the camera back to the user. */
    private fun onUserGesture() {
        stopCameraAnimation()
        if (follow != null) {
            follow = null
            followFlyToPending = false
            followSnapPending = false
            onUserInteraction?.invoke()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tile pipeline
    // ------------------------------------------------------------------------------------------

    /** Every scale/offset change: pick the decode bucket, queue a scheduling pass, redraw. */
    private fun onViewportChanged() {
        if (scale != lastLodScale) {
            lastLodScale = scale
            val target = lodForScale(scale)
            removeCallbacks(lodSettleRunnable)
            when {
                target == tileLod -> Unit
                // Zooming out (coarser) or nothing drawn yet: switch at once.
                target > tileLod || tileCache.isEmpty() -> tileLod = target
                // Zooming in: wait until the pinch settles so one gesture doesn't decode every size.
                else -> postDelayed(lodSettleRunnable, TILE_LOD_SETTLE_MS)
            }
        }
        scheduleTiles()
        invalidate()
    }

    /** Decode bucket for a tile drawn [tileWidth] * [s] screen px wide (0 = full resolution). */
    private fun lodForScale(s: Float): Int {
        val screenPx = tileWidth * s
        return when {
            screenPx > 768f -> 0
            screenPx > 384f -> 1
            screenPx > 192f -> 2
            else -> 3
        }
    }

    /**
     * Tiles only once the overview would be magnified: below that the overview already has more
     * pixels than the screen shows, so tiles add load but no detail.
     */
    private fun shouldRenderTiles(): Boolean {
        if (fitPending || tileUrls.isEmpty() || width == 0 || height == 0) return false
        if (!hasOverview) return true
        val overview = overviewBitmap ?: return false
        return scale >= minScale * TILE_LOAD_SCALE_MULTIPLIER &&
            scale >= overview.width / worldWidth
    }

    /** Fills [visibleRange] (col0, col1, row0, row1) with cells on screen + [buffer] cells. */
    private fun computeVisibleRange(buffer: Int): Boolean {
        if (!computeVisibleWorld(visibleWorld)) return false
        val left = visibleWorld.left
        val top = visibleWorld.top
        val right = visibleWorld.right
        val bottom = visibleWorld.bottom
        val c0 = (floor(left / tileWidth).toInt() - buffer).coerceAtLeast(0)
        val c1 = (floor(right / tileWidth).toInt() + buffer).coerceAtMost(cols - 1)
        val r0 = (floor(top / tileHeight).toInt() - buffer).coerceAtLeast(0)
        val r1 = (floor(bottom / tileHeight).toInt() + buffer).coerceAtMost(rows - 1)
        if (c0 > c1 || r0 > r1) return false
        visibleRange[0] = c0
        visibleRange[1] = c1
        visibleRange[2] = r0
        visibleRange[3] = r1
        return true
    }

    /** Conflated: at most one scheduling pass per [TILE_SCHEDULE_INTERVAL_MS]. */
    private fun scheduleTiles() {
        if (scheduleQueued) return
        scheduleQueued = true
        val wait = (lastScheduleAt + TILE_SCHEDULE_INTERVAL_MS - SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        postDelayed(scheduleRunnable, wait)
    }

    private fun scheduleTilesNow() {
        onScreenTiles.clear()
        if (!shouldRenderTiles()) {
            loadingJobs.values.forEach { it.cancel() }
            loadingJobs.clear()
            return
        }
        val lod = tileLod
        if (computeVisibleRange(buffer = 0)) {
            for (r in visibleRange[2]..visibleRange[3]) {
                for (c in visibleRange[0]..visibleRange[1]) onScreenTiles.add(TileKey(c, r, lod))
            }
        }
        val wanted = HashSet<TileKey>(onScreenTiles)
        // One cell of preload around the screen, only while the cache has room for it.
        if (tileCacheBytes < MAX_TILE_CACHE_BYTES * 3 / 4 && computeVisibleRange(buffer = TILE_VISIBLE_BUFFER)) {
            for (r in visibleRange[2]..visibleRange[3]) {
                for (c in visibleRange[0]..visibleRange[1]) wanted.add(TileKey(c, r, lod))
            }
        }

        // Stop loads for tiles that scrolled/zoomed away; they'd only be evicted again.
        val stale = loadingJobs.keys.filter { it !in wanted }
        stale.forEach { loadingJobs.remove(it)?.cancel() }

        val now = SystemClock.uptimeMillis()
        val needed = ArrayList<TileKey>()
        for (key in wanted) {
            if (loadingJobs.containsKey(key) || tileCache.containsKey(key)) continue
            val failedAt = failedTiles[key]
            if (failedAt != null && now - failedAt < FAILED_TILE_RETRY_DELAY_MS) continue
            if (tileUrls["${key.col}_${key.row}"] == null) continue
            needed.add(key)
        }
        val slots = MAX_QUEUED_TILE_LOADS - loadingJobs.size
        if (slots > 0 && needed.isNotEmpty()) {
            val center = centerWorld()
            needed.sortBy { key ->
                val dx = (key.col + 0.5f) * tileWidth - center.x
                val dy = (key.row + 0.5f) * tileHeight - center.y
                dx * dx + dy * dy
            }
            needed.take(slots).forEach { startTileLoad(it) }
        }
        evictTiles()
    }

    private fun startTileLoad(key: TileKey) {
        val client = httpClient ?: return
        val scope = loadScope ?: return
        val url = tileUrls["${key.col}_${key.row}"] ?: return
        val generation = layerGeneration
        val target = decodeTargetForLod(key.lod)
        // Registered before it starts and only removes its own entry, so a cancelled load can't
        // remove a newer load of the same tile.
        val job = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val bytes = fetchBytes(client, url)
                val bitmap = bytes?.let { withContext(Dispatchers.Default) { decodeSampled(it, target) } }
                if (generation != layerGeneration) return@launch
                if (bitmap == null) {
                    failedTiles[key] = SystemClock.uptimeMillis()
                    AppLog.w(logTag, "Tile load failed key=$key")
                } else {
                    failedTiles.remove(key)
                    tileCache.put(key, bitmap)?.let { tileCacheBytes -= it.allocationByteCount }
                    tileCacheBytes += bitmap.allocationByteCount
                    evictTiles()
                    invalidate()
                }
            } finally {
                if (loadingJobs[key] === self) loadingJobs.remove(key)
                // Free slot: let the scheduler queue the next-nearest tile.
                if (generation == layerGeneration) scheduleTiles()
            }
        }
        loadingJobs[key] = job
        job.start()
    }

    /**
     * Evict least-recently-used tiles that are not on screen until under the count/byte caps.
     * On-screen tiles are never evicted, so the cache can't fall into a load → evict → reload loop.
     */
    private fun evictTiles() {
        if (tileCache.size <= MAX_TILE_CACHE_COUNT && tileCacheBytes <= MAX_TILE_CACHE_BYTES) return
        val it = tileCache.entries.iterator() // LRU first
        while (it.hasNext() &&
            (tileCache.size > MAX_TILE_CACHE_COUNT || tileCacheBytes > MAX_TILE_CACHE_BYTES)
        ) {
            val entry = it.next()
            if (entry.key in onScreenTiles) continue
            tileCacheBytes -= entry.value.allocationByteCount
            it.remove()
        }
    }

    private fun dropOffscreenTiles() {
        val before = tileCache.size
        val it = tileCache.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key in onScreenTiles) continue
            tileCacheBytes -= entry.value.allocationByteCount
            it.remove()
        }
        AppLog.w(logTag, "Memory pressure: dropped ${before - tileCache.size} off-screen tiles, kept ${tileCache.size}")
    }

    private fun clearTiles() {
        layerGeneration++
        removeCallbacks(scheduleRunnable)
        removeCallbacks(lodSettleRunnable)
        scheduleQueued = false
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        // Not recycled: a hardware display list may still reference them; GC frees them.
        tileCache.clear()
        tileCacheBytes = 0L
        failedTiles.clear()
        onScreenTiles.clear()
        lastLodScale = 0f
    }

    private fun clearOverview() {
        overviewJob?.cancel()
        overviewJob = null
        overviewBitmap = null
    }

    private fun loadOverview(url: String) {
        val client = httpClient ?: return
        val scope = loadScope ?: return
        val generation = layerGeneration
        overviewJob = scope.launch(Dispatchers.Main) {
            val bitmap = fetchBytes(client, url)?.let {
                withContext(Dispatchers.Default) { decodeSampled(it, OVERVIEW_MAX_DECODE_PX) }
            }
            if (generation != layerGeneration) return@launch
            if (bitmap == null) {
                AppLog.w(logTag, "Overview download failed url=$url — falling back to tiles")
                hasOverview = false
            } else {
                AppLog.i(logTag, "Overview loaded ${bitmap.width}x${bitmap.height}")
                overviewBitmap = bitmap
            }
            scheduleTiles()
            invalidate()
        }
    }

    /** Async OkHttp fetch that cancels the HTTP call when the coroutine is cancelled. */
    private suspend fun fetchBytes(client: OkHttpClient, url: String): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = try {
                        response.use { if (it.isSuccessful) it.body?.bytes() else null }
                    } catch (_: IOException) {
                        null
                    }
                    if (cont.isActive) cont.resume(bytes)
                }
            })
        }

    /**
     * Decode with a power-of-two sample so the longer side is at least [targetPx] (0 = full
     * size). A 1920 px tile shown 300 px wide is held as a 480 px bitmap, not an 8 MB one.
     */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun fitToView() {
        if (width == 0 || height == 0) return
        fitPending = false
        val sx = width / worldWidth
        val sy = height / worldHeight
        minScale = min(sx, sy) * 0.95f
        // User's saved default zoom (Settings → Map zoom level), centred on the layer.
        scale = (minScale * defaultZoom).coerceIn(minScale, MAX_SCALE)
        transX = anchorX() - worldWidth / 2f * scale
        transY = anchorY() - worldHeight / 2f * scale
        tileLod = lodForScale(scale)
        // Stay on overview at fit; tiles load once the overview would be magnified.
        onViewportChanged()
        applyFollowCamera()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND_COLOR)
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(transX, transY)
        canvas.save()
        if (hasViewTransform()) {
            canvas.concat(viewMatrix)
            // Keep perspective geometry to what the screen shows (never near the eye plane).
            if (computePreViewBounds(preClip)) canvas.clipRect(preClip)
        }
        canvas.concat(drawMatrix)

        // Layer 1: low-res overview stretched to full mosaic world.
        overviewBitmap?.let { bmp ->
            overviewSrc.set(0, 0, bmp.width, bmp.height)
            overviewDst.set(0f, 0f, worldWidth, worldHeight)
            canvas.drawBitmap(bmp, overviewSrc, overviewDst, overviewPaint)
        }

        // Layer 2: hi-res tiles, on-screen cells only. A cell still loading at the current decode
        // size falls back to any other size already cached, so a zoom step never flashes.
        if (shouldRenderTiles() && tileCache.isNotEmpty() && computeVisibleRange(buffer = 0)) {
            val lod = tileLod
            for (row in visibleRange[2]..visibleRange[3]) {
                for (col in visibleRange[0]..visibleRange[1]) {
                    val bmp = tileCache[TileKey(col, row, lod)] ?: fallbackTile(col, row, lod) ?: continue
                    val left = col * tileWidth.toFloat()
                    val top = row * tileHeight.toFloat()
                    tileDst.set(left, top, left + tileWidth, top + tileHeight)
                    canvas.drawBitmap(bmp, null, tileDst, tilePaint)
                }
            }
        }

        // Pathways lie on the ground plane; clipped so splines past the mosaic edge don't bleed out.
        if (pathways.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, 0f, worldWidth, worldHeight)
            pathways.forEach { (render, path) -> drawPathway(canvas, render, path) }
            canvas.restore()
        }
        canvas.restore()

        // Markers are billboards: projected to the screen, then drawn upright at a constant
        // size, so they stay readable when the map is rotated/tilted (identical when top-down).
        annotations.forEach { marker ->
            billboard(canvas, marker.pixelX, marker.pixelY) { drawAnnotation(canvas, marker) }
        }
        val focusedThreatId = follow?.takeIf { it.kind == CameraFollow.Kind.THREAT }?.id
        threats.forEach { threat ->
            if (threat.id == focusedThreatId) return@forEach
            billboard(canvas, threat.pixelX, threat.pixelY) { drawThreat(canvas, threat, focused = false) }
        }
        val navDroneId = navDroneId()
        dronePoses.values.forEach { pose ->
            if (pose.droneDisplayId == navDroneId) return@forEach
            billboard(canvas, pose.pixelX, pose.pixelY) { drawDrone(canvas, pose) }
        }
        // Focused threat on top, at Mithril's 2x focus size.
        threats.firstOrNull { it.id == focusedThreatId }?.let { threat ->
            billboard(canvas, threat.pixelX, threat.pixelY) { drawThreat(canvas, threat, focused = true) }
        }
        navDroneId?.let { id ->
            val pose = dronePoses[id] ?: dronePoses.values.firstOrNull { it.droneDisplayId == id }
            if (pose != null && project(pose.pixelX, pose.pixelY, screenPt)) {
                drawNavArrow(canvas, screenPt[0], screenPt[1], screenHeading(pose) - bearing)
            }
        }
    }

    /**
     * Draws [block] (which uses world coords + 1/scale sizes) upright at the marker's projected
     * screen position. Skips markers off screen (+margin) or behind the camera.
     */
    private inline fun billboard(canvas: Canvas, worldX: Float, worldY: Float, block: () -> Unit) {
        if (!project(worldX, worldY, screenPt)) return
        val sx = screenPt[0]
        val sy = screenPt[1]
        if (sx < -OVERLAY_CULL_MARGIN_PX || sx > width + OVERLAY_CULL_MARGIN_PX ||
            sy < -OVERLAY_CULL_MARGIN_PX || sy > height + OVERLAY_CULL_MARGIN_PX
        ) {
            return
        }
        canvas.save()
        canvas.translate(sx, sy)
        canvas.scale(scale, scale)
        canvas.translate(-worldX, -worldY)
        block()
        canvas.restore()
    }

    /** Drone drawn as the navigation arrow: navigation view + camera following that drone. */
    private fun navDroneId(): String? {
        if (!navEnabled || navT < 0.5f) return null
        return follow?.takeIf { it.kind == CameraFollow.Kind.DRONE }?.id
    }

    /** Google-Maps-style navigation chevron at a screen point; [screenHeadingDeg] 0 = up. */
    private fun drawNavArrow(canvas: Canvas, sx: Float, sy: Float, screenHeadingDeg: Float) {
        val r = NAV_ARROW_RADIUS_DP * resources.displayMetrics.density
        canvas.save()
        canvas.translate(sx, sy)
        canvas.drawCircle(0f, r * 0.15f, r * 1.25f, navShadowPaint)
        canvas.rotate(screenHeadingDeg)
        arrowPath.reset()
        arrowPath.moveTo(0f, -r)
        arrowPath.lineTo(r * 0.78f, r * 0.85f)
        arrowPath.lineTo(0f, r * 0.42f)
        arrowPath.lineTo(-r * 0.78f, r * 0.85f)
        arrowPath.close()
        canvas.drawPath(arrowPath, navArrowPaint)
        navArrowStroke.strokeWidth = r * 0.12f
        canvas.drawPath(arrowPath, navArrowStroke)
        canvas.restore()
    }

    /**
     * Nearest other decode size of the same cell (sharper first). The lookup marks it recently
     * used, which is intended: it stays cached while it is what the screen shows.
     */
    private fun fallbackTile(col: Int, row: Int, lod: Int): Bitmap? {
        for (delta in 1 until LOD_COUNT) {
            val sharper = lod - delta
            if (sharper >= 0) tileCache[TileKey(col, row, sharper)]?.let { return it }
            val coarser = lod + delta
            if (coarser < LOD_COUNT) tileCache[TileKey(col, row, coarser)]?.let { return it }
        }
        return null
    }

    private fun drawPathway(canvas: Canvas, render: PathwayRender, path: Path) {
        if (render.points.size < 4) return
        pathwayPaint.color = render.colorArgb
        // Mithril: thickness * unrealToPixels in world px, never thinner than 4 screen px.
        pathwayPaint.strokeWidth = render.strokeWorldPx.coerceAtLeast(4f / scale)
        canvas.drawPath(path, pathwayPaint)
    }

    private fun drawThreat(canvas: Canvas, threat: ThreatMarker, focused: Boolean) {
        val inv = 1f / scale.coerceAtLeast(0.35f)
        // Mithril MithrilMapComponent: the followed threat is drawn at 2x the base icon size.
        val half = 24f * inv * (if (focused) FOCUSED_THREAT_SCALE else 1f)
        if (focused) {
            canvas.drawCircle(threat.pixelX, threat.pixelY, half * 1.35f, threatHaloPaint)
            threatHaloStroke.strokeWidth = 2.5f * inv
            canvas.drawCircle(threat.pixelX, threat.pixelY, half * 1.35f, threatHaloStroke)
        }
        iconBitmap(R.drawable.ic_threat_marker)?.let { bmp ->
            droneBodyRect.set(
                threat.pixelX - half,
                threat.pixelY - half,
                threat.pixelX + half,
                threat.pixelY + half,
            )
            canvas.drawBitmap(bmp, null, droneBodyRect, iconPaint)
        }
        // Mithril always labels threats (other annotations are zoom-gated).
        if (threat.label.isBlank()) return
        threatLabelPaint.textSize = (if (focused) 18f else 16f) * inv
        val textWidth = threatLabelPaint.measureText(threat.label)
        val top = threat.pixelY + half + 4f * inv
        val bottom = top + threatLabelPaint.textSize + 6f * inv
        threatLabelRect.set(
            threat.pixelX - textWidth / 2f - 4f * inv,
            top,
            threat.pixelX + textWidth / 2f + 4f * inv,
            bottom,
        )
        canvas.drawRoundRect(threatLabelRect, 3f * inv, 3f * inv, threatLabelBgPaint)
        canvas.drawText(threat.label, threat.pixelX, bottom - 5f * inv, threatLabelPaint)
    }

    private fun drawAnnotation(canvas: Canvas, marker: MapAnnotationMarker) {
        // Mithril base icon size ≈ 48 world px, counter-scaled with zoom.
        val size = 48f / scale.coerceAtLeast(0.35f)
        val half = size / 2f
        val bmp = iconBitmap(marker.iconRes)
        if (bmp != null) {
            annotationRect.set(
                marker.pixelX - half,
                marker.pixelY - half,
                marker.pixelX + half,
                marker.pixelY + half,
            )
            canvas.drawBitmap(bmp, null, annotationRect, iconPaint)
        } else {
            dronePaint.color = ANNOTATION_FALLBACK_COLOR
            canvas.drawCircle(marker.pixelX, marker.pixelY, half * 0.7f, dronePaint)
        }

        // Labels: user toggle OR auto when zoomed (Mithril defaults labels off until filter).
        val wantLabel = marker.showLabel || scale >= minScale * 1.5f
        if (wantLabel && marker.label.isNotBlank()) {
            val textScale = 1f / scale.coerceAtLeast(0.45f)
            labelPaint.textSize = 18f * textScale
            canvas.drawText(
                marker.label,
                marker.pixelX + half + 4f * textScale,
                marker.pixelY + 4f * textScale,
                labelPaint,
            )
        }
    }

    private fun iconBitmap(resId: Int): Bitmap? {
        if (resId == 0) return null
        iconCache[resId]?.let { return it }
        val drawable = AppCompatResources.getDrawable(context, resId) ?: return null
        val px = (26 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bmp = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> drawable.toBitmap(px, px)
        }
        iconCache[resId] = bmp
        return bmp
    }

    private fun drawDrone(canvas: Canvas, pose: DroneMapPose) {
        // Keep a constant on-screen size at every zoom. The old coerceAtLeast(0.35)
        // floor made markers shrink to a few pixels when the campus overview fit
        // (minScale is often ~0.05–0.15).
        val inv = 1f / scale.coerceAtLeast(0.01f)
        val bodyHalf = DRONE_BODY_HALF_SCREEN_PX * inv
        val bodyCorner = bodyHalf * 0.35f
        val ringR = bodyHalf * 1.55f
        val selected = pose.droneDisplayId == selectedDroneId
        val isDemo = pose.droneDisplayId.startsWith("DEMO", ignoreCase = true)
        // Pilot's colour when telemetry sends one, otherwise the default marker colour.
        val pilotColor = pilotColor(pose)
        dronePaint.color = pilotColor ?: if (isDemo) DEMO_DRONE_COLOR else DRONE_COLOR
        // On a light body (white, yellow, …) the white glyph and outline would blend in,
        // so switch both to a dark colour.
        val lightBody = pilotColor != null && isLightColor(pilotColor)
        droneStroke.color = if (lightBody) DRONE_ON_LIGHT_COLOR else Color.WHITE

        if (selected) {
            val selHalf = ringR + 10f * inv
            val selCorner = selHalf * 0.35f
            selectionRingPaint.strokeWidth = 5f * inv
            droneBodyRect.set(
                pose.pixelX - selHalf,
                pose.pixelY - selHalf,
                pose.pixelX + selHalf,
                pose.pixelY + selHalf,
            )
            canvas.drawRoundRect(droneBodyRect, selCorner, selCorner, selectionRingPaint)
        }

        // Soft outer guide ring around the icon (circle keeps heading readable).
        headingRingPaint.strokeWidth = 3.2f * inv
        headingRingPaint.color = HEADING_RING_COLOR
        canvas.drawCircle(pose.pixelX, pose.pixelY, ringR, headingRingPaint)

        // Direction arc + arrow tip on the heading side.
        // Billboards don't rotate with the map, so draw heading relative to the map bearing.
        val headingDeg = normalizeDeg(screenHeading(pose) - bearing)
        val canvasHeading = headingDeg - 90f
        headingArcRect.set(
            pose.pixelX - ringR,
            pose.pixelY - ringR,
            pose.pixelX + ringR,
            pose.pixelY + ringR,
        )
        headingRingPaint.color = ACCENT_COLOR
        headingRingPaint.strokeWidth = 5f * inv
        canvas.drawArc(headingArcRect, canvasHeading - 28f, 56f, false, headingRingPaint)

        val headingRad = Math.toRadians(canvasHeading.toDouble())
        val tipDist = ringR + 12f * inv
        val tipX = pose.pixelX + (tipDist * cos(headingRad)).toFloat()
        val tipY = pose.pixelY + (tipDist * sin(headingRad)).toFloat()
        val leftRad = Math.toRadians((canvasHeading - 90.0))
        val rightRad = Math.toRadians((canvasHeading + 90.0))
        val baseDist = ringR - 2.5f * inv
        val baseHalf = 10f * inv
        val bx = pose.pixelX + (baseDist * cos(headingRad)).toFloat()
        val by = pose.pixelY + (baseDist * sin(headingRad)).toFloat()
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(
            bx + (baseHalf * cos(leftRad)).toFloat(),
            by + (baseHalf * sin(leftRad)).toFloat(),
        )
        arrowPath.lineTo(
            bx + (baseHalf * cos(rightRad)).toFloat(),
            by + (baseHalf * sin(rightRad)).toFloat(),
        )
        arrowPath.close()
        canvas.drawPath(arrowPath, headingArrowPaint)

        // Rounded-square marker body + white drone glyph.
        val strokeHalf = bodyHalf + 2.8f * inv
        val strokeCorner = strokeHalf * 0.35f
        droneStroke.strokeWidth = 3.8f * inv
        droneBodyRect.set(
            pose.pixelX - strokeHalf,
            pose.pixelY - strokeHalf,
            pose.pixelX + strokeHalf,
            pose.pixelY + strokeHalf,
        )
        canvas.drawRoundRect(droneBodyRect, strokeCorner, strokeCorner, droneStroke)
        droneBodyRect.set(
            pose.pixelX - bodyHalf,
            pose.pixelY - bodyHalf,
            pose.pixelX + bodyHalf,
            pose.pixelY + bodyHalf,
        )
        canvas.drawRoundRect(droneBodyRect, bodyCorner, bodyCorner, dronePaint)

        val iconHalf = bodyHalf * 0.68f
        iconBitmap(R.drawable.ic_drone_marker)?.let { bmp ->
            droneBodyRect.set(
                pose.pixelX - iconHalf,
                pose.pixelY - iconHalf,
                pose.pixelX + iconHalf,
                pose.pixelY + iconHalf,
            )
            canvas.drawBitmap(bmp, null, droneBodyRect, if (lightBody) iconDarkPaint else iconPaint)
        }

        drawDroneLabel(canvas, pose, pose.pixelX + ringR + 12f * inv, pose.pixelY, inv)
    }

    /**
     * Pilot name / "Me" beside the marker on a rounded, semi-transparent pill so it stays legible
     * on light and dark map areas. The pill uses the pilot's colour when telemetry sends one.
     */
    private fun drawDroneLabel(canvas: Canvas, pose: DroneMapPose, left: Float, centerY: Float, inv: Float) {
        val text = pose.mapLabel
        labelPaint.textSize = 28f * inv
        val padH = 10f * inv
        val padV = 5f * inv
        val textWidth = labelPaint.measureText(text)
        val fm = labelPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        labelRect.set(
            left,
            centerY - textHeight / 2f - padV,
            left + textWidth + padH * 2f,
            centerY + textHeight / 2f + padV,
        )
        labelBgPaint.color = labelBackgroundColor(pose)
        val radius = labelRect.height() / 2f
        canvas.drawRoundRect(labelRect, radius, radius, labelBgPaint)
        labelBorderPaint.strokeWidth = 1.5f * inv
        canvas.drawRoundRect(labelRect, radius, radius, labelBorderPaint)
        // Baseline that vertically centres the text in the pill.
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, left + padH, baseline, labelPaint)
    }

    /** Parsed `pilot_hex_color` (`#RRGGBB`, `RRGGBB` or `#AARRGGBB`), or null if absent/invalid. */
    private fun pilotColor(pose: DroneMapPose): Int? {
        val raw = pose.pilotHexColor?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return pilotColorCache.getOrPut(raw) {
            runCatching { Color.parseColor(if (raw.startsWith("#")) raw else "#$raw") }
                .getOrNull()
                ?.let { it or 0xFF000000.toInt() } // marker body is always opaque
        }
    }

    /** Perceived brightness (Rec. 601 luma): true when white on top would be hard to see. */
    private fun isLightColor(color: Int): Boolean {
        val luma = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
        return luma > LIGHT_COLOR_LUMA
    }

    private fun labelBackgroundColor(pose: DroneMapPose): Int {
        val base = pilotColor(pose) ?: return LABEL_DEFAULT_BG
        // Darken a little so white text stays readable even on a light pilot colour.
        val r = (Color.red(base) * 0.8f).toInt()
        val g = (Color.green(base) * 0.8f).toInt()
        val b = (Color.blue(base) * 0.8f).toInt()
        return Color.argb(LABEL_BG_ALPHA, r, g, b)
    }

    /** Nearest threat marker under the finger, compared in screen px (focused icon is 2x). */
    private fun threatAtScreen(screenX: Float, screenY: Float): String? {
        val focusedId = follow?.takeIf { it.kind == CameraFollow.Kind.THREAT }?.id
        var bestId: String? = null
        var bestDist = Float.MAX_VALUE
        threats.forEach { threat ->
            if (!project(threat.pixelX, threat.pixelY, screenPt)) return@forEach
            val d = hypot(screenPt[0] - screenX, screenPt[1] - screenY)
            val hitR = THREAT_HIT_RADIUS_SCREEN_PX * (if (threat.id == focusedId) FOCUSED_THREAT_SCALE else 1f)
            if (d <= hitR && d < bestDist) {
                bestDist = d
                bestId = threat.id
            }
        }
        return bestId
    }

    private fun droneAtScreen(screenX: Float, screenY: Float): String? {
        val hitR = DRONE_BODY_HALF_SCREEN_PX * 2.2f
        var bestId: String? = null
        var bestDist = hitR
        dronePoses.values.forEach { pose ->
            if (!project(pose.pixelX, pose.pixelY, screenPt)) return@forEach
            val d = hypot(screenPt[0] - screenX, screenPt[1] - screenY)
            if (d <= bestDist) {
                bestDist = d
                bestId = pose.droneDisplayId
            }
        }
        return bestId
    }

    // ------------------------------------------------------------------------------------------
    // Navigation view: camera math
    // ------------------------------------------------------------------------------------------

    /**
     * Toggle the Google-Maps-style navigation camera: map tilted [NAV_TILT_DEG] in perspective,
     * camera anchored in the lower third, and — while a drone is followed — rotated so the
     * drone's heading points up, drawn as a navigation arrow. Turning it off eases back to
     * north-up top-down.
     */
    fun setNavigationView(enabled: Boolean) {
        if (enabled == navEnabled) return
        navEnabled = enabled
        navAnimator?.cancel()
        val fromT = navT
        val toT = if (enabled) 1f else 0f
        val fromBearing = bearing
        // Zoom in on entry (Google driving camera) and return to the prior zoom on exit.
        val fromScale = scale
        val toScale = if (enabled) {
            preNavScale = scale
            scale.coerceAtLeast(minScale * NAV_ZOOM_MULTIPLIER).coerceAtMost(MAX_SCALE)
        } else {
            preNavScale ?: scale
        }
        if (!enabled) preNavScale = null
        navAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NAV_TRANSITION_MS
            interpolator = FAST_OUT_SLOW_IN
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                // Keep whatever is under the anchor under it while the anchor itself moves.
                val center = centerWorld()
                navT = fromT + (toT - fromT) * p
                scale = fromScale + (toScale - fromScale) * p
                if (!enabled) bearing = normalizeDeg(fromBearing + shortestAngle(fromBearing, 0f) * p)
                updateViewMatrix()
                val followPos = if (!isCameraAnimating) followTargetPos() else null
                if (followPos != null) {
                    steerBearingToFollowedDrone()
                    setCenterWorld(followPos)
                } else {
                    setCenterWorld(center)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (navAnimator === animation) navAnimator = null
                }
            })
            start()
        }
    }

    private fun hasViewTransform(): Boolean = navT > 0.001f || kotlin.math.abs(bearing) > 0.01f

    private fun cameraDistance(): Float = height * NAV_CAMERA_DISTANCE_FACTOR

    /** Rebuilds [viewMatrix] = tilt ∘ rotate(-bearing), both about the anchor. */
    private fun updateViewMatrix() {
        val ax = anchorX()
        val ay = anchorY()
        viewMatrix.setRotate(-bearing, ax, ay)
        if (navT > 0.001f && height > 0) {
            val theta = Math.toRadians((NAV_TILT_DEG * navT).toDouble())
            tiltValues[Matrix.MSCALE_X] = 1f
            tiltValues[Matrix.MSKEW_X] = 0f
            tiltValues[Matrix.MTRANS_X] = 0f
            tiltValues[Matrix.MSKEW_Y] = 0f
            tiltValues[Matrix.MSCALE_Y] = cos(theta).toFloat()
            tiltValues[Matrix.MTRANS_Y] = 0f
            tiltValues[Matrix.MPERSP_0] = 0f
            tiltValues[Matrix.MPERSP_1] = (-sin(theta) / cameraDistance()).toFloat()
            tiltValues[Matrix.MPERSP_2] = 1f
            tiltMatrix.setValues(tiltValues)
            tiltMatrix.preTranslate(-ax, -ay)
            tiltMatrix.postTranslate(ax, ay)
            viewMatrix.postConcat(tiltMatrix)
        }
        invalidate()
    }

    /**
     * World (mosaic px) → screen px through scale/translate, rotation and perspective tilt.
     * False when the point is at/behind the camera's eye plane (not drawable).
     */
    private fun project(worldX: Float, worldY: Float, out: FloatArray): Boolean {
        var x = worldX * scale + transX
        var y = worldY * scale + transY
        if (!hasViewTransform()) {
            out[0] = x
            out[1] = y
            return true
        }
        val ax = anchorX()
        val ay = anchorY()
        // Rotate by -bearing about the anchor.
        val rad = Math.toRadians(-bearing.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val dx = x - ax
        val dy = y - ay
        x = dx * c - dy * sn
        y = dx * sn + dy * c
        // Perspective tilt about the anchor's horizontal axis.
        val theta = Math.toRadians((NAV_TILT_DEG * navT).toDouble())
        val w = 1f - (sin(theta).toFloat() / cameraDistance()) * y
        if (w <= EYE_PLANE_EPSILON) return false
        out[0] = ax + x / w
        out[1] = ay + (cos(theta).toFloat() * y) / w
        return true
    }

    /** Screen px → pre-view space (where transX/transY/scale live). False above the horizon. */
    private fun unprojectToPre(screenX: Float, screenY: Float, out: FloatArray): Boolean {
        if (!hasViewTransform()) {
            out[0] = screenX
            out[1] = screenY
            return true
        }
        val ax = anchorX()
        val ay = anchorY()
        val theta = Math.toRadians((NAV_TILT_DEG * navT).toDouble())
        val ct = cos(theta).toFloat()
        val st = sin(theta).toFloat()
        val d = cameraDistance()
        val sxr = screenX - ax
        val syr = screenY - ay
        // Inverse of Y = ct*y / (1 - st*y/d)  →  y = Y / (ct + Y*st/d).
        val denom = ct + syr * st / d
        if (denom <= EYE_PLANE_EPSILON) return false
        val y = syr / denom
        val x = sxr * (1f - st * y / d)
        // Undo the rotation (rotate by +bearing).
        val rad = Math.toRadians(bearing.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        out[0] = ax + x * c - y * sn
        out[1] = ay + x * sn + y * c
        return true
    }

    /** Pre-view-space bounds of the screen (for clipping perspective drawing). */
    private fun computePreViewBounds(out: RectF): Boolean {
        if (width == 0 || height == 0) return false
        var any = false
        out.setEmpty()
        for (corner in 0 until 4) {
            val sx = if (corner % 2 == 0) 0f else width.toFloat()
            val sy = if (corner < 2) 0f else height.toFloat()
            if (!unprojectToPre(sx, sy, prePt)) return false
            if (!any) {
                out.set(prePt[0], prePt[1], prePt[0], prePt[1])
                any = true
            } else {
                out.union(prePt[0], prePt[1])
            }
        }
        return any
    }

    /** World-space bounding box of everything on screen (wider than the screen when tilted). */
    private fun computeVisibleWorld(out: RectF): Boolean {
        if (width == 0 || height == 0 || scale <= 0f) return false
        if (!computePreViewBounds(out)) return false
        out.set(
            (out.left - transX) / scale,
            (out.top - transY) / scale,
            (out.right - transX) / scale,
            (out.bottom - transY) / scale,
        )
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewMatrix()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onUserGesture()
            val factor = detector.scaleFactor
            // Zoom about the finger's point on the (possibly tilted/rotated) map.
            if (!unprojectToPre(detector.focusX, detector.focusY, prePt)) return true
            val focusX = prePt[0]
            val focusY = prePt[1]
            val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val ratio = newScale / scale
            transX = focusX - (focusX - transX) * ratio
            transY = focusY - (focusY - transY) * ratio
            scale = newScale
            onViewportChanged()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Threats are drawn under drones; prefer the drone when both are hit.
            droneAtScreen(e.x, e.y)?.let { id ->
                onDroneTapped?.invoke(id)
                return true
            }
            val threatId = threatAtScreen(e.x, e.y) ?: return false
            onThreatTapped?.invoke(threatId)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (scaleDetector.isInProgress) return false
            onUserGesture()
            // Drag the ground point under the finger with it, also when tilted/rotated.
            if (!unprojectToPre(e2.x + distanceX, e2.y + distanceY, prePt)) return true
            val prevX = prePt[0]
            val prevY = prePt[1]
            if (!unprojectToPre(e2.x, e2.y, prePt)) return true
            transX += prePt[0] - prevX
            transY += prePt[1] - prevY
            onViewportChanged()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onUserGesture()
            fitToView()
            return true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        stopCameraAnimation()
        stopDroneGlide()
        navAnimator?.cancel()
        navAnimator = null
        clearTiles()
        clearOverview()
    }

    companion object {
        /** Mithril: tiles only once zoomed past fit * 1.35 (and the overview is magnified). */
        private const val TILE_LOAD_SCALE_MULTIPLIER = 1.35f
        /** Locate threat zooms to at least fit * 2.5 so the marker's surroundings are readable. */
        private const val FOCUS_ZOOM_MULTIPLIER = 2.5f
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 8f
        /** Mithril fly-to timings: context zoom-out, then the move to the target. */
        private const val FLY_TO_PRE_ZOOM_MS = 250L
        private const val FLY_TO_MOVE_MS = 450L
        /** Compose FastOutSlowInEasing. */
        private val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        /** Half-size of the drone body in screen pixels (constant across zoom). */
        private const val DRONE_BODY_HALF_SCREEN_PX = 36f
        private const val THREAT_HIT_RADIUS_SCREEN_PX = 40f
        /** Mithril: followed threat icon is drawn at 2x. */
        private const val FOCUSED_THREAT_SCALE = 2f

        // Navigation view (Google Maps driving camera).
        private const val NAV_TILT_DEG = 45f
        /** Navigation view zooms to at least fit * this (2D locate uses fit * 2.5). */
        private const val NAV_ZOOM_MULTIPLIER = 5f
        /** Unreal yaw (0° = +X = screen-right) → map heading (0° = screen-up). */
        private const val YAW_TO_SCREEN_HEADING_DEG = 90f
        /** Camera anchor sits ~2/3 down the screen when tilted, so more road ahead is visible. */
        private const val NAV_ANCHOR_Y_FRAC = 0.68f
        /** Eye distance as a multiple of view height — keeps the horizon above the screen. */
        private const val NAV_CAMERA_DISTANCE_FACTOR = 1.3f
        private const val NAV_TRANSITION_MS = 600L
        private const val NAV_ARROW_RADIUS_DP = 22f
        /** Per-telemetry-update easing of the map rotation toward the drone heading. */
        private const val BEARING_SMOOTHING = 0.2f
        private const val EYE_PLANE_EPSILON = 0.05f
        private val NAV_ARROW_COLOR = Color.parseColor("#297FFF")

        private fun normalizeDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /** Signed smallest rotation from [from] to [to], in (-180, 180]. */
        private fun shortestAngle(from: Float, to: Float): Float {
            val d = normalizeDeg(to - from)
            return if (d > 180f) d - 360f else d
        }

        // Tile pipeline limits (Mithril MithrilMapEngine).
        private const val MAX_TILE_CACHE_BYTES = 128L * 1024L * 1024L
        private const val MAX_TILE_CACHE_COUNT = 220
        private const val MAX_QUEUED_TILE_LOADS = 24
        private const val TILE_SCHEDULE_INTERVAL_MS = 100L
        private const val TILE_LOD_SETTLE_MS = 150L
        private const val FAILED_TILE_RETRY_DELAY_MS = 10_000L
        private const val TILE_VISIBLE_BUFFER = 1
        /** Overview is one image for the whole layer; cap it below the Canvas bitmap limit. */
        private const val OVERVIEW_MAX_DECODE_PX = 4096
        /** Screen px around the viewport in which overlays are still drawn. */
        private const val OVERLAY_CULL_MARGIN_PX = 400f

        /**
         * Decode bucket per [lodForScale]: 0 = full resolution (zoomed right in), then long side
         * of at least 768 / 384 / 192 px. Mithril caps at 768; full size is kept for bucket 0 so
         * deep zoom stays as sharp as before.
         */
        private const val LOD_COUNT = 4
        private fun decodeTargetForLod(lod: Int): Int = when (lod) {
            0 -> 0
            1 -> 768
            2 -> 384
            else -> 192
        }

        private val BACKGROUND_COLOR = Color.parseColor("#0E0F13")
        private val DRONE_COLOR = Color.parseColor("#FF891C")
        /** Drone label pill: ~75% opaque so the map shows through but text stays readable. */
        private const val LABEL_BG_ALPHA = 0xC0
        private val LABEL_DEFAULT_BG = Color.argb(LABEL_BG_ALPHA, 0x10, 0x12, 0x18)
        private val DEMO_DRONE_COLOR = Color.parseColor("#297FFF")
        /** Glyph + outline colour on light pilot colours (white, yellow, light grey…). */
        private val DRONE_ON_LIGHT_COLOR = Color.parseColor("#14161C")
        /** Luma (0–255) above which a pilot colour counts as light. */
        private const val LIGHT_COLOR_LUMA = 170f
        private val ANNOTATION_FALLBACK_COLOR = Color.parseColor("#297FFF")
        private val ACCENT_COLOR = Color.parseColor("#0EEAC6")
        private val HEADING_RING_COLOR = Color.parseColor("#66FFFFFF")
    }
}
