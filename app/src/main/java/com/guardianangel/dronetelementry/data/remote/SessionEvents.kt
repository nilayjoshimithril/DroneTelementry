package com.guardianangel.dronetelementry.data.remote

import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /**
     * Pending "session ended, go to login". A StateFlow (not a one-shot event) so it survives
     * while MainActivity is stopped behind the map screen: the map finishes, then MainActivity
     * picks it up on restart and consumes it.
     */
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    /*
     * Incident auto-join state. Lives here (app singleton) so it survives Home being recreated
     * and the round trip to the map screen.
     *
     * Rule: whenever exactly one incident is ongoing (at app start, when one appears on an empty
     * list, or when others end and one is left), join it — unless the user already joined that
     * incident (auto or manual). Leaving it and finding it still ongoing must not pull them back.
     */
    private val joinedIncidentIds = mutableSetOf<String>()

    /** Fresh session (sign-in): forget joined incidents. */
    fun armAutoJoin() = synchronized(this) {
        joinedIncidentIds.clear()
    }

    /** Remember that the user joined [incidentId] so it is never auto-joined again. */
    fun markIncidentJoined(incidentId: String) = synchronized(this) {
        joinedIncidentIds += incidentId
    }

    /**
     * Feed every successful ongoing-incident load. Returns the id to auto-join, or null.
     * The returned incident is marked joined.
     */
    fun takeAutoJoin(ongoingIds: List<String>): String? = synchronized(this) {
        val only = ongoingIds.singleOrNull() ?: return null
        if (only in joinedIncidentIds) return null
        joinedIncidentIds += only
        only
    }

    fun consumeUnauthorized() {
        _unauthorized.value = false
    }

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

    fun notifyUnauthorized() {
        AppLog.w(TAG, "Session unauthorized — notifying UI to return to login")
        _unauthorized.value = true
    }

    companion object {
        private const val TAG = "SessionEvents"
    }
}
