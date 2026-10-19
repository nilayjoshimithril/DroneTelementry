package com.guardianangel.mithrildefense.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import com.guardianangel.mithrildefense.R
import com.guardianangel.mithrildefense.data.model.MapTileDto
import com.guardianangel.mithrildefense.domain.model.DroneMapPose
import com.guardianangel.mithrildefense.utils.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class MapAnnotationMarker(
    val id: String,
    val type: String,
    val label: String,
    val pixelX: Float,
    val pixelY: Float,
    val iconRes: Int = 0,
    val showLabel: Boolean = false,
)

/**
 * Mosaic floor-plan: low-res overview first, hi-res tiles after zoom (Mithril LOD),
 * plus color pathways, annotations, threat markers + drone markers.
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

    private val tiles = ConcurrentHashMap<String, Bitmap>()
    private var tileJobs = mutableListOf<Job>()
    private var overviewJob: Job? = null
    private var httpClient: OkHttpClient? = null
    private var loadScope: CoroutineScope? = null

    private var pendingTiles: List<MapTileDto> = emptyList()
    private var overviewUrl: String? = null
    @Volatile private var overviewBitmap: Bitmap? = null
    private var hasOverview = false
    private var tilesRequested = false
    private var minScale = 1f

    private val drawMatrix = Matrix()
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f

    @Volatile
    private var dronePoses: Map<String, DroneMapPose> = emptyMap()
    @Volatile
    private var selectedDroneId: String? = null
    @Volatile
    private var annotations: List<MapAnnotationMarker> = emptyList()
    @Volatile
    private var pathways: List<Pair<PathwayRender, Path>> = emptyList()
    @Volatile
    private var threats: List<ThreatMarker> = emptyList()
    /** World-pixel point to center on once the pending [fitToView] has run. */
    private var pendingFocus: PointF? = null
    private var fitPending = false

    var onDroneTapped: ((String) -> Unit)? = null

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overviewPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dronePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF891C")
        style = Paint.Style.FILL
    }
    private val droneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0EEAC6")
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
        color = Color.parseColor("#0EEAC6")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
    private val iconCache = ConcurrentHashMap<Int, Bitmap>()
    private val headingArcRect = RectF()
    private val droneBodyRect = RectF()
    private val invertMatrix = Matrix()
    private val touchPoint = FloatArray(2)

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val overviewDst = RectF()
    private val overviewSrc = Rect()

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
        pendingTiles = tileList
        this.overviewUrl = overviewUrl?.takeIf { it.isNotBlank() }
        hasOverview = this.overviewUrl != null
        tilesRequested = false
        AppLog.i(
            logTag,
            "configure mosaic ${this.cols}x${this.rows} " +
                "tile=${this.tileWidth}x${this.tileHeight} urls=${tileList.size} " +
                "overview=${!this.overviewUrl.isNullOrBlank()}",
        )
        clearTiles()
        clearOverview()
        pendingFocus = null
        fitPending = true
        if (hasOverview) {
            loadOverview(this.overviewUrl!!)
        } else {
            // No overview — load tiles immediately (fit still gates minScale for zoom UX).
            requestTilesIfNeeded(force = true)
        }
        post { fitToView() }
    }

    fun updateDrones(poses: Map<String, DroneMapPose>, selectedId: String? = selectedDroneId) {
        dronePoses = poses
        selectedDroneId = selectedId
        postInvalidateOnAnimation()
    }

    fun setSelectedDrone(id: String?) {
        if (selectedDroneId == id) return
        selectedDroneId = id
        postInvalidateOnAnimation()
    }

    fun updateAnnotations(markers: List<MapAnnotationMarker>) {
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
        invalidate()
    }

    /** Center (and zoom in if at fit) on a world-pixel point — Mithril locate threat. */
    fun focusOn(pixelX: Float, pixelY: Float) {
        pendingFocus = PointF(pixelX, pixelY)
        if (!fitPending && width > 0 && height > 0) applyPendingFocus()
    }

    private fun applyPendingFocus() {
        val target = pendingFocus ?: return
        pendingFocus = null
        scale = scale.coerceAtLeast(minScale * FOCUS_ZOOM_MULTIPLIER).coerceAtMost(8f)
        transX = width / 2f - target.x * scale
        transY = height / 2f - target.y * scale
        requestTilesIfNeeded()
        invalidate()
    }

    private fun clearTiles() {
        tileJobs.forEach { it.cancel() }
        tileJobs.clear()
        tiles.values.forEach { it.recycle() }
        tiles.clear()
    }

    private fun clearOverview() {
        overviewJob?.cancel()
        overviewJob = null
        overviewBitmap?.recycle()
        overviewBitmap = null
    }

    private fun loadOverview(url: String) {
        val client = httpClient ?: return
        val scope = loadScope ?: return
        overviewJob = scope.launch(Dispatchers.IO) {
            val bitmap = downloadBitmap(client, url)
            if (bitmap == null) {
                AppLog.w(logTag, "Overview download failed url=$url — falling back to tiles")
                hasOverview = false
                withContext(Dispatchers.Main) {
                    requestTilesIfNeeded(force = true)
                    invalidate()
                }
                return@launch
            }
            AppLog.i(logTag, "Overview loaded ${bitmap.width}x${bitmap.height}")
            overviewBitmap = bitmap
            withContext(Dispatchers.Main) { invalidate() }
        }
    }

    private fun requestTilesIfNeeded(force: Boolean = false) {
        if (tilesRequested) return
        if (pendingTiles.isEmpty()) return
        val threshold = minScale * TILE_LOAD_SCALE_MULTIPLIER
        if (!force && hasOverview && scale < threshold) {
            AppLog.d(
                logTag,
                "Tiles deferred until zoom scale=$scale threshold=$threshold minScale=$minScale",
            )
            return
        }
        tilesRequested = true
        AppLog.i(logTag, "Loading hi-res tiles count=${pendingTiles.size} force=$force")
        loadTiles(pendingTiles)
    }

    private fun loadTiles(tileList: List<MapTileDto>) {
        val client = httpClient ?: return
        val scope = loadScope ?: return
        val loaded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val total = tileList.size
        tileList.forEach { tile ->
            val key = "${tile.col}_${tile.row}"
            val job = scope.launch(Dispatchers.IO) {
                val bitmap = downloadBitmap(client, tile.url)
                if (bitmap == null) {
                    failed.incrementAndGet()
                    AppLog.w(logTag, "Tile download failed key=$key url=${tile.url}")
                    return@launch
                }
                tiles[key] = bitmap
                val done = loaded.incrementAndGet()
                if (done == 1 || done == total) {
                    AppLog.d(logTag, "Tiles loaded=$done/$total failed=${failed.get()}")
                }
                withContext(Dispatchers.Main) { invalidate() }
            }
            tileJobs.add(job)
        }
    }

    private fun downloadBitmap(client: OkHttpClient, url: String): Bitmap? {
        return runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use {
                if (!it.isSuccessful) return null
                val bytes = it.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    private fun fitToView() {
        if (width == 0 || height == 0) return
        fitPending = false
        val sx = width / worldWidth
        val sy = height / worldHeight
        scale = min(sx, sy) * 0.95f
        minScale = scale
        transX = (width - worldWidth * scale) / 2f
        transY = (height - worldHeight * scale) / 2f
        invalidate()
        // Stay on overview at fit; tiles load after zoom-in.
        requestTilesIfNeeded(force = !hasOverview)
        applyPendingFocus()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0E0F13"))
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(transX, transY)
        canvas.save()
        canvas.concat(drawMatrix)

        // Layer 1: low-res overview stretched to full mosaic world.
        overviewBitmap?.let { bmp ->
            overviewSrc.set(0, 0, bmp.width, bmp.height)
            overviewDst.set(0f, 0f, worldWidth, worldHeight)
            canvas.drawBitmap(bmp, overviewSrc, overviewDst, overviewPaint)
        }

        // Layer 2: hi-res tiles (after zoom / no overview).
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val bmp = tiles["${col}_$row"] ?: continue
                val left = col * tileWidth.toFloat()
                val top = row * tileHeight.toFloat()
                canvas.drawBitmap(bmp, left, top, tilePaint)
            }
        }

        // Pathways under markers; clipped so splines past the mosaic edge don't bleed out.
        if (pathways.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, 0f, worldWidth, worldHeight)
            pathways.forEach { (render, path) -> drawPathway(canvas, render, path) }
            canvas.restore()
        }
        annotations.forEach { marker -> drawAnnotation(canvas, marker) }
        threats.forEach { threat -> drawThreat(canvas, threat) }
        dronePoses.values.forEach { pose -> drawDrone(canvas, pose) }
        canvas.restore()
    }

    private fun drawPathway(canvas: Canvas, render: PathwayRender, path: Path) {
        if (render.points.size < 4) return
        pathwayPaint.color = render.colorArgb
        // Mithril: thickness * unrealToPixels in world px, never thinner than 4 screen px.
        pathwayPaint.strokeWidth = render.strokeWorldPx.coerceAtLeast(4f / scale)
        canvas.drawPath(path, pathwayPaint)
    }

    private fun drawThreat(canvas: Canvas, threat: ThreatMarker) {
        val inv = 1f / scale.coerceAtLeast(0.35f)
        val half = 24f * inv
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
        threatLabelPaint.textSize = 16f * inv
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
            val dst = RectF(
                marker.pixelX - half,
                marker.pixelY - half,
                marker.pixelX + half,
                marker.pixelY + half,
            )
            canvas.drawBitmap(bmp, null, dst, iconPaint)
        } else {
            dronePaint.color = Color.parseColor("#297FFF")
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
        val inv = 1f / scale.coerceAtLeast(0.35f)
        val bodyHalf = 14f * inv
        val bodyCorner = bodyHalf * 0.35f
        val ringR = bodyHalf * 1.55f
        val selected = pose.droneDisplayId == selectedDroneId
        val isDemo = pose.droneDisplayId.startsWith("DEMO", ignoreCase = true)
        dronePaint.color = if (isDemo) Color.parseColor("#297FFF") else Color.parseColor("#FF891C")

        if (selected) {
            val selHalf = ringR + 6f * inv
            val selCorner = selHalf * 0.35f
            selectionRingPaint.strokeWidth = 3.2f * inv
            droneBodyRect.set(
                pose.pixelX - selHalf,
                pose.pixelY - selHalf,
                pose.pixelX + selHalf,
                pose.pixelY + selHalf,
            )
            canvas.drawRoundRect(droneBodyRect, selCorner, selCorner, selectionRingPaint)
        }

        // Soft outer guide ring around the icon (circle keeps heading readable).
        headingRingPaint.strokeWidth = 2.2f * inv
        headingRingPaint.color = Color.parseColor("#66FFFFFF")
        canvas.drawCircle(pose.pixelX, pose.pixelY, ringR, headingRingPaint)

        // Direction arc + arrow tip on the heading side.
        val headingDeg = ((pose.headingDegrees % 360f) + 360f) % 360f
        val canvasHeading = headingDeg - 90f
        headingArcRect.set(
            pose.pixelX - ringR,
            pose.pixelY - ringR,
            pose.pixelX + ringR,
            pose.pixelY + ringR,
        )
        headingRingPaint.color = Color.parseColor("#0EEAC6")
        headingRingPaint.strokeWidth = 3.4f * inv
        canvas.drawArc(headingArcRect, canvasHeading - 28f, 56f, false, headingRingPaint)

        val headingRad = Math.toRadians(canvasHeading.toDouble())
        val tipDist = ringR + 7f * inv
        val tipX = pose.pixelX + (tipDist * cos(headingRad)).toFloat()
        val tipY = pose.pixelY + (tipDist * sin(headingRad)).toFloat()
        val leftRad = Math.toRadians((canvasHeading - 90.0))
        val rightRad = Math.toRadians((canvasHeading + 90.0))
        val baseDist = ringR - 1.5f * inv
        val baseHalf = 5.5f * inv
        val bx = pose.pixelX + (baseDist * cos(headingRad)).toFloat()
        val by = pose.pixelY + (baseDist * sin(headingRad)).toFloat()
        val arrow = Path().apply {
            moveTo(tipX, tipY)
            lineTo(
                bx + (baseHalf * cos(leftRad)).toFloat(),
                by + (baseHalf * sin(leftRad)).toFloat(),
            )
            lineTo(
                bx + (baseHalf * cos(rightRad)).toFloat(),
                by + (baseHalf * sin(rightRad)).toFloat(),
            )
            close()
        }
        canvas.drawPath(arrow, headingArrowPaint)

        // Rounded-square marker body + white drone glyph.
        val strokeHalf = bodyHalf + 1.5f * inv
        val strokeCorner = strokeHalf * 0.35f
        droneStroke.strokeWidth = 2.4f * inv
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
            canvas.drawBitmap(bmp, null, droneBodyRect, iconPaint)
        }

        labelPaint.textSize = 22f * inv
        canvas.drawText(
            pose.droneDisplayId,
            pose.pixelX + ringR + 8f * inv,
            pose.pixelY - 2f * inv,
            labelPaint,
        )
    }

    private fun droneAtScreen(screenX: Float, screenY: Float): String? {
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(transX, transY)
        if (!drawMatrix.invert(invertMatrix)) return null
        touchPoint[0] = screenX
        touchPoint[1] = screenY
        invertMatrix.mapPoints(touchPoint)
        val worldX = touchPoint[0]
        val worldY = touchPoint[1]
        val hitR = 28f / scale.coerceAtLeast(0.35f)
        var bestId: String? = null
        var bestDist = hitR
        dronePoses.values.forEach { pose ->
            val d = hypot((pose.pixelX - worldX).toDouble(), (pose.pixelY - worldY).toDouble()).toFloat()
            if (d <= bestDist) {
                bestDist = d
                bestId = pose.droneDisplayId
            }
        }
        return bestId
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val focusX = detector.focusX
            val focusY = detector.focusY
            val newScale = (scale * factor).coerceIn(0.05f, 8f)
            val ratio = newScale / scale
            transX = focusX - (focusX - transX) * ratio
            transY = focusY - (focusY - transY) * ratio
            scale = newScale
            requestTilesIfNeeded()
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val id = droneAtScreen(e.x, e.y) ?: return false
            onDroneTapped?.invoke(id)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (scaleDetector.isInProgress) return false
            transX -= distanceX
            transY -= distanceY
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            fitToView()
            return true
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearTiles()
        clearOverview()
    }

    companion object {
        /** Mithril: fetch/draw tiles once zoomed past fit * 1.35. */
        private const val TILE_LOAD_SCALE_MULTIPLIER = 1.35f
        /** Locate threat zooms to at least fit * 2.5 so the marker's surroundings are readable. */
        private const val FOCUS_ZOOM_MULTIPLIER = 2.5f
    }
}
