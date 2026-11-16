package com.guardianangel.dronetelementry.data.remote

import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mithril InComingCallHelper.presentIncidentEnded payload — shown on Home after leaving the map. */
data class IncidentEndedNotice(
    val incidentId: String,
    val campusName: String,
    /** RESOLVED or FALSE_ALARM. */
    val status: String,
    val isLawEnforcement: Boolean,
) {
    val isFalseAlarm: Boolean get() = status.equals("FALSE_ALARM", ignoreCase = true)
}

/** App-wide session signals (e.g. forced logout on HTTP 401). */
class SessionEvents {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    /**
     * Pending "Alarm Ended" dialog. A StateFlow (not a one-shot event) so Home still shows it
     * if it only starts collecting after the map screen has finished.
     */
    private val _incidentEnded = MutableStateFlow<IncidentEndedNotice?>(null)
    val incidentEnded: StateFlow<IncidentEndedNotice?> = _incidentEnded.asStateFlow()

    fun postIncidentEnded(notice: IncidentEndedNotice) {
        AppLog.i(TAG, "Incident ${notice.incidentId} ended status=${notice.status}")
        _incidentEnded.value = notice
    }

    fun consumeIncidentEnded() {
        _incidentEnded.value = null
    }

    suspend fun notifyUnauthorized() {
        AppLog.w(TAG, "Session unauthorized — notifying UI to return to login")
        _unauthorized.emit(Unit)
    }

    companion object {
        private const val TAG = "SessionEvents"
    }
}
