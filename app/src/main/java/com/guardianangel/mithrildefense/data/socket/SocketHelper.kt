package com.guardianangel.mithrildefense.data.socket

import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.model.DroneTelemetryDto
import com.guardianangel.mithrildefense.data.model.SocketEnvelopeDto
import com.guardianangel.mithrildefense.domain.model.DroneTelemetry
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SocketEvent {
    data object Connected : SocketEvent
    data class Failed(val reason: String) : SocketEvent
    data class Channel(val name: String, val rawPayload: String?) : SocketEvent
    data object Disconnected : SocketEvent
    data object Reconnecting : SocketEvent
}

/**
 * Incident WebSocket helper.
 *
 * Mithril notes mirrored here:
 * - WS tickets are single-use and expire ~30s → [urlProvider] must mint a fresh ticket
 *   on every reconnect (not only the first connect).
 * - After long incidents (~2h) the server may drop the socket; we keep retrying with
 *   backoff while the session is active ([manualClose] = false).
 */
class SocketHelper(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var urlProvider: (suspend () -> String)? = null
    private var attempt = 0
    private var telemetryLogCounter = 0
    private val manualClose = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)

    private val _events = MutableSharedFlow<SocketEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val _drones = MutableStateFlow<Map<String, DroneTelemetry>>(emptyMap())
    val drones: StateFlow<Map<String, DroneTelemetry>> = _drones.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun connect(urlProvider: suspend () -> String) {
        AppLog.i(TAG, "connect() requested")
        manualClose.set(false)
        this.urlProvider = urlProvider
        attempt = 0
        reconnectJob?.cancel()
        scope.launch { openSocket(immediate = true) }
    }

    /** Mithril [manualCloseReconnect]: reset attempt counter and reconnect with a fresh ticket. */
    fun manualReconnect() {
        if (manualClose.get() || urlProvider == null) {
            AppLog.w(TAG, "manualReconnect ignored — disconnected session")
            return
        }
        AppLog.i(TAG, "manualReconnect()")
        attempt = 0
        reconnectJob?.cancel()
        scope.launch {
            _events.emit(SocketEvent.Reconnecting)
            closeQuietly()
            openSocket(immediate = true)
        }
    }

    fun disconnect() {
        AppLog.i(TAG, "disconnect()")
        manualClose.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        urlProvider = null
        closeQuietly()
        _connected.value = false
        _drones.value = emptyMap()
    }

    fun publish(channel: String, payloadJson: String) {
        val frame = """{"channel":"$channel","payload":$payloadJson}"""
        AppLog.d(TAG, "publish channel=$channel payload=$payloadJson")
        webSocket?.send(frame)
    }

    private fun closeQuietly() {
        runCatching { webSocket?.cancel() }
        webSocket = null
    }

    private suspend fun openSocket(immediate: Boolean) {
        if (manualClose.get()) return
        val provider = urlProvider ?: return
        if (!opening.compareAndSet(false, true)) {
            AppLog.d(TAG, "openSocket skipped — already opening")
            return
        }
        try {
            if (!immediate && attempt > 0) {
                val delayMs = reconnectDelayMs(attempt)
                AppLog.w(TAG, "Reconnect #$attempt waiting ${delayMs}ms for fresh ws-ticket")
                _events.emit(SocketEvent.Reconnecting)
                delay(delayMs)
                if (manualClose.get() || urlProvider == null) return
            }

            // Always resolve URL after backoff so each attempt gets a fresh single-use ticket.
            val url = runCatching { provider() }.getOrElse {
                AppLog.e(TAG, "urlProvider failed: ${it.message}", it)
                _events.emit(SocketEvent.Failed(it.message ?: "ws ticket failed"))
                scheduleReconnect()
                return
            }
            AppLog.i(TAG, "Opening WebSocket url=${redactWsUrl(url)} attempt=$attempt")
            withContext(Dispatchers.Main.immediate) {
                closeQuietly()
            }
            val request = Request.Builder().url(url).build()
            webSocket = okHttpClient.newWebSocket(request, listener)
        } finally {
            opening.set(false)
        }
    }

    private fun scheduleReconnect() {
        if (manualClose.get() || urlProvider == null) return
        attempt++
        AppLog.w(TAG, "Scheduling reconnect attempt=$attempt")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (isActive) openSocket(immediate = false)
        }
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        // Mithril waits 30s between retries after the first; we ramp up then cap at 30s.
        return when {
            attempt <= 1 -> 0L
            attempt == 2 -> 2_000L
            attempt == 3 -> 5_000L
            attempt == 4 -> 10_000L
            else -> AppConfig.SOCKET_RECONNECT_CAP_MS
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _connected.value = true
            AppLog.i(TAG, "WS onOpen code=${response.code}")
            scope.launch { _events.emit(SocketEvent.Connected) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            scope.launch { handleMessage(bytes.utf8()) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.d(TAG, "WS onClosing code=$code reason=$reason")
            runCatching { webSocket.close(code, reason) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            AppLog.w(TAG, "WS onClosed code=$code reason=$reason")
            scope.launch { _events.emit(SocketEvent.Disconnected) }
            if (!manualClose.get()) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connected.value = false
            AppLog.e(TAG, "WS onFailure http=${response?.code} msg=${t.message}", t)
            scope.launch {
                _events.emit(SocketEvent.Failed(t.message ?: "socket failure"))
            }
            if (!manualClose.get()) scheduleReconnect()
        }
    }

    private suspend fun handleMessage(text: String) {
        val envelope = runCatching {
            json.decodeFromString<SocketEnvelopeDto>(text)
        }.getOrElse {
            AppLog.w(TAG, "Failed to parse socket frame: ${text.take(200)}", it)
            return
        }
        val channel = envelope.channel ?: run {
            AppLog.w(TAG, "Socket frame missing channel: ${text.take(200)}")
            return
        }
        when (channel) {
            "connection_success" -> {
                AppLog.i(TAG, "channel=connection_success")
                _connected.value = true
                attempt = 0
                _events.emit(SocketEvent.Connected)
            }
            "connection_failed" -> {
                AppLog.e(TAG, "channel=connection_failed payload=${envelope.payload}")
                _events.emit(SocketEvent.Failed("connection_failed"))
                // Ticket rejected — drop socket so onClosed/onFailure path opens with a fresh ticket.
                closeQuietly()
                _connected.value = false
                if (!manualClose.get()) scheduleReconnect()
            }
            "on-drone-telemetry" -> {
                parseTelemetry(envelope)
            }
            else -> {
                AppLog.d(TAG, "channel=$channel payload=${envelope.payload?.toString()?.take(300)}")
                _events.emit(SocketEvent.Channel(channel, envelope.payload?.toString()))
            }
        }
    }

    private fun parseTelemetry(envelope: SocketEnvelopeDto) {
        val payload = envelope.payload ?: return
        val list = runCatching {
            json.decodeFromJsonElement<List<DroneTelemetryDto>>(payload)
        }.getOrElse {
            runCatching {
                listOf(json.decodeFromJsonElement<DroneTelemetryDto>(payload))
            }.getOrElse {
                AppLog.w(TAG, "Failed to parse on-drone-telemetry: $payload", it)
                return
            }
        }
        if (list.isEmpty()) return
        val merged = _drones.value.toMutableMap()
        list.forEach { dto ->
            val id = dto.displayId.ifBlank { dto.deviceId }
            if (id.isBlank()) return@forEach
            val pose = dto.pose
            val position = pose.position
            val rotation = pose.rotation
            merged[id] = DroneTelemetry(
                displayId = id,
                deviceId = dto.deviceId.ifBlank { id },
                droneIp = dto.droneIp,
                state = dto.state,
                batteryPercent = dto.batteryPercent,
                deviceType = dto.deviceType,
                buildingId = dto.buildingId,
                floorId = dto.floorId,
                positionX = position.x,
                positionY = position.y,
                positionZ = position.z,
                rotationX = rotation.x,
                rotationY = rotation.y,
                rotationZ = rotation.z,
                rotationW = rotation.w,
                confidence = pose.confidence,
                capturedAt = pose.capturedAt,
                frameUrl = pose.frameUrl,
                videoDeviceStatus = dto.videoDeviceStatus,
                micDeviceStatus = dto.micDeviceStatus,
                audioDeviceStatus = dto.audioDeviceStatus,
                halowConnectivityStatus = dto.halowConnectivityStatus,
                gyroStatus = dto.gyroStatus,
                accStatus = dto.accStatus,
                barometerStatus = dto.barometerStatus,
                escStatus = dto.escStatus,
                batteryUnitStatus = dto.batteryUnitStatus,
                motorStatus = dto.motorStatus,
            )
        }
        _drones.value = merged
        telemetryLogCounter++
        if (telemetryLogCounter % 30 == 1) {
            AppLog.d(
                TAG,
                "on-drone-telemetry batch=${list.size} drones=${merged.keys} " +
                    "sample=${list.firstOrNull()?.let { "${it.displayId}@(${it.pose.position.x},${it.pose.position.y},${it.pose.position.z})" }}",
            )
        }
    }

    companion object {
        private const val TAG = "Socket"

        fun buildWsUrl(httpsBaseUrl: String, ticket: String, incidentId: String): String {
            val wssBase = httpsBaseUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                .trimEnd('/')
            val url =
                "$wssBase/incidents/ws?ticket=$ticket&incident_id=$incidentId&client_type=${AppConfig.CLIENT_TYPE_MOBILE}"
            AppLog.d(TAG, "buildWsUrl incidentId=$incidentId ticket=${AppLog.redact(ticket)}")
            return url
        }

        private fun redactWsUrl(url: String): String {
            return url.replace(Regex("ticket=[^&]+"), "ticket=***")
        }
    }
}
