package com.guardianangel.dronetelementry.domain.model

/**
 * The signed-in user, as matched against a drone's pilot fields to decide "Mine".
 *
 * `pilot_email` is the primary key. `pilot_id` is used only when the drone carries no email
 * (e.g. campus roster rows, which have `pilot_id` but no `pilot_email`).
 */
data class CurrentPilot(
    val userId: String? = null,
    val email: String? = null,
) {
    fun owns(pilotEmail: String?, pilotId: String?): Boolean {
        val droneEmail = pilotEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (droneEmail != null) {
            val myEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return droneEmail.equals(myEmail, ignoreCase = true)
        }
        val droneId = pilotId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val myId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return droneId.equals(myId, ignoreCase = true)
    }

    val isKnown: Boolean
        get() = !email.isNullOrBlank() || !userId.isNullOrBlank()
}
