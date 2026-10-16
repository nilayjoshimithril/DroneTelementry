package com.guardianangel.mithrildefense.data.remote

import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** App-wide session signals (e.g. forced logout on HTTP 401). */
class SessionEvents {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    suspend fun notifyUnauthorized() {
        AppLog.w(TAG, "Session unauthorized — notifying UI to return to login")
        _unauthorized.emit(Unit)
    }

    companion object {
        private const val TAG = "SessionEvents"
    }
}
