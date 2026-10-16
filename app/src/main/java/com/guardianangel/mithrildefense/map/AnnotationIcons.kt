package com.guardianangel.mithrildefense.map

import com.guardianangel.mithrildefense.R

/** Mithril filter / marker type keys (normalized uppercase, no separators). */
object AnnotationTypes {
    const val SECURITY_CAMERA = "SECURITYCAMERA"
    const val CAMERA = "CAMERA"
    const val DOOR = "DOOR"
    const val STAIRS = "STAIRS"
    const val EXIT = "EXITLOCATION"
    const val FIRE = "FIREEXTINGUISHER"
    const val FIRST_AID = "FIRSTAID"

    val FILTERABLE = listOf(
        SECURITY_CAMERA to ("Security Cameras" to R.drawable.ic_ann_camera),
        DOOR to ("Doors" to R.drawable.ic_ann_door),
        STAIRS to ("Stairs" to R.drawable.ic_ann_stairs),
        EXIT to ("Emergency Exit" to R.drawable.ic_ann_exit),
        FIRE to ("Fire Extinguisher" to R.drawable.ic_ann_fire),
        FIRST_AID to ("Med Kit" to R.drawable.ic_ann_med),
    )

    fun normalize(type: String): String =
        type.uppercase().replace("_", "").replace(" ", "").replace("-", "")

    fun iconRes(type: String): Int {
        // Exact type keys match MithrilMapComponent painter routing.
        return when (normalize(type)) {
            SECURITY_CAMERA, CAMERA -> R.drawable.ic_ann_camera
            DOOR -> R.drawable.ic_ann_door
            STAIRS -> R.drawable.ic_ann_stairs
            EXIT -> R.drawable.ic_ann_exit
            FIRE -> R.drawable.ic_ann_fire
            FIRST_AID -> R.drawable.ic_ann_med
            else -> R.drawable.ic_ann_default
        }
    }

    fun isHidden(type: String, hidden: Set<String>): Boolean {
        val t = normalize(type)
        if (t in hidden) return true
        // Hiding Security Cameras also hides CAMERA alias (Mithril).
        if ((SECURITY_CAMERA in hidden || CAMERA in hidden) &&
            (t == SECURITY_CAMERA || t == CAMERA)
        ) {
            return true
        }
        return false
    }
}
