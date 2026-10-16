package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DroneTelemetryDto(
    @SerialName("display_id") val displayId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("drone_ip") val droneIp: String? = null,
    val pose: DronePoseDto = DronePoseDto(),
    @SerialName("device_type") val deviceType: String? = null,
    val state: String? = null,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("floor_id") val floorId: String? = null,
    @SerialName("battery_percent") val batteryPercent: Int = 0,
    @SerialName("video_device_status") val videoDeviceStatus: String? = null,
    @SerialName("mic_device_status") val micDeviceStatus: String? = null,
    @SerialName("audio_device_status") val audioDeviceStatus: String? = null,
    @SerialName("halow_connectivity_status") val halowConnectivityStatus: String? = null,
    @SerialName("gyro_status") val gyroStatus: String? = null,
    @SerialName("acc_status") val accStatus: String? = null,
    @SerialName("barometer_status") val barometerStatus: String? = null,
    @SerialName("esc_status") val escStatus: String? = null,
    @SerialName("battery_unit_status") val batteryUnitStatus: String? = null,
    @SerialName("motor_status") val motorStatus: String? = null,
)
