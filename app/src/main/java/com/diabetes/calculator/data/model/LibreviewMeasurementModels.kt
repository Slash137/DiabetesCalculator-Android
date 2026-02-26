package com.diabetes.calculator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibreviewMeasurementsRequest(
    @SerialName("DeviceData")
    val deviceData: LibreviewDeviceData,
    @SerialName("UserToken")
    val userToken: String,
    @SerialName("Domain")
    val domain: String = "Libreview",
    @SerialName("GatewayType")
    val gatewayType: String = "FSLibreLink.Android"
)

@Serializable
data class LibreviewDeviceData(
    @SerialName("connectedDevices")
    val connectedDevices: LibreviewConnectedDevices = LibreviewConnectedDevices(),
    @SerialName("measurementLog")
    val measurementLog: LibreviewMeasurementLog
)

@Serializable
data class LibreviewConnectedDevices(
    @SerialName("insulinDevices")
    val insulinDevices: List<String> = emptyList()
)

@Serializable
data class LibreviewMeasurementLog(
    @SerialName("bloodGlucoseEntries")
    val bloodGlucoseEntries: List<String> = emptyList(),
    @SerialName("currentGlucoseEntries")
    val currentGlucoseEntries: List<String> = emptyList(),
    @SerialName("foodEntries")
    val foodEntries: List<LibreviewFoodEntry> = emptyList(),
    @SerialName("genericEntries")
    val genericEntries: List<String> = emptyList(),
    @SerialName("insulinEntries")
    val insulinEntries: List<LibreviewInsulinEntry> = emptyList(),
    @SerialName("ketoneEntries")
    val ketoneEntries: List<String> = emptyList(),
    @SerialName("scheduledContinuousGlucoseEntries")
    val scheduledContinuousGlucoseEntries: List<String> = emptyList(),
    @SerialName("unscheduledContinuousGlucoseEntries")
    val unscheduledContinuousGlucoseEntries: List<String> = emptyList()
)

@Serializable
data class LibreviewFoodEntry(
    @SerialName("foodType")
    val foodType: String,
    @SerialName("gramsCarbs")
    val gramsCarbs: Float,
    @SerialName("extendedProperties")
    val extendedProperties: LibreviewExtendedProperties,
    @SerialName("recordNumber")
    val recordNumber: Long,
    @SerialName("timestamp")
    val timestamp: String
)

@Serializable
data class LibreviewInsulinEntry(
    @SerialName("insulinType")
    val insulinType: String,
    @SerialName("units")
    val units: Float,
    @SerialName("extendedProperties")
    val extendedProperties: LibreviewExtendedProperties,
    @SerialName("recordNumber")
    val recordNumber: Long,
    @SerialName("timestamp")
    val timestamp: String
)

@Serializable
data class LibreviewExtendedProperties(
    @SerialName("factoryTimestamp")
    val factoryTimestamp: String,
    @SerialName("linkedGlucoseRecordNumber")
    val linkedGlucoseRecordNumber: String = "0",
    @SerialName("action")
    val action: String? = null
)

@Serializable
data class LibreviewPostMeasurementsResponse(
    val status: Int? = null,
    val reason: String? = null
)

@Serializable
data class LibreviewRemoteEntry(
    val channel: String,
    val recordNumber: Long,
    val eventTimestampMillis: Long? = null,
    val amountValue: Float = 0f,
    val timestampRaw: String? = null,
    val deviceSerial: String? = null,
    val deviceId: String? = null,
    val gatewayType: String? = null,
    val sourceTag: String? = null
)

@Serializable
data class LibreviewMeasurementsPageResponse(
    val entries: List<LibreviewRemoteEntry> = emptyList(),
    val nextCursor: String? = null,
    val endpointId: String? = null
)
