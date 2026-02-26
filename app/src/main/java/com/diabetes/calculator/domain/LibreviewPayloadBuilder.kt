package com.diabetes.calculator.domain

import com.diabetes.calculator.data.model.LibreviewDeviceData
import com.diabetes.calculator.data.model.LibreviewConnectedDevices
import com.diabetes.calculator.data.model.LibreviewExtendedProperties
import com.diabetes.calculator.data.model.LibreviewFoodEntry
import com.diabetes.calculator.data.model.LibreviewInsulinEntry
import com.diabetes.calculator.data.model.LibreviewMeasurementLog
import com.diabetes.calculator.data.model.LibreviewMeasurementsRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LibreviewPayloadOperation {
    UPSERT,
    DELETE
}

data class LibreviewPayloadBuildResult(
    val request: LibreviewMeasurementsRequest,
    val payloadHash: String
)

object LibreviewPayloadBuilder {
    private val utcFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneId.of("UTC"))
    private val localFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .withZone(ZoneId.systemDefault())

    fun hashPayload(
        channel: String,
        operation: LibreviewPayloadOperation,
        recordNumber: Long,
        eventTimestampMillis: Long,
        amountValue: Float
    ): String {
        val seed = "$channel|${operation.name}|$recordNumber|$eventTimestampMillis|$amountValue"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun buildCarbsPayload(
        userToken: String,
        recordNumber: Long,
        eventTimestampMillis: Long,
        carbsGrams: Float,
        operation: LibreviewPayloadOperation,
        connectedInsulinDevices: List<String> = emptyList(),
        gatewayType: String = "FSLibreLink.Android"
    ): LibreviewPayloadBuildResult {
        val safeCarbs = carbsGrams.coerceAtLeast(0f)
        val payloadCarbs = if (operation == LibreviewPayloadOperation.DELETE) 0f else safeCarbs
        val entry = LibreviewFoodEntry(
            foodType = inferMealType(safeCarbs, eventTimestampMillis),
            gramsCarbs = payloadCarbs,
            extendedProperties = LibreviewExtendedProperties(
                factoryTimestamp = formatUtc(eventTimestampMillis),
                action = if (operation == LibreviewPayloadOperation.DELETE) "deleted" else null
            ),
            recordNumber = recordNumber,
            timestamp = formatLocal(eventTimestampMillis)
        )
        val request = LibreviewMeasurementsRequest(
            deviceData = LibreviewDeviceData(
                connectedDevices = LibreviewConnectedDevices(
                    insulinDevices = normalizeConnectedInsulinDevices(connectedInsulinDevices)
                ),
                measurementLog = LibreviewMeasurementLog(foodEntries = listOf(entry))
            ),
            userToken = userToken,
            gatewayType = gatewayType
        )
        return LibreviewPayloadBuildResult(
            request = request,
            payloadHash = hashPayload(
                channel = "CARBS",
                operation = operation,
                recordNumber = recordNumber,
                eventTimestampMillis = eventTimestampMillis,
                amountValue = payloadCarbs
            )
        )
    }

    fun buildInsulinPayload(
        userToken: String,
        recordNumber: Long,
        eventTimestampMillis: Long,
        units: Float,
        operation: LibreviewPayloadOperation,
        connectedInsulinDevices: List<String> = emptyList(),
        gatewayType: String = "FSLibreLink.Android"
    ): LibreviewPayloadBuildResult {
        val safeUnits = units.coerceAtLeast(0f)
        val payloadUnits = if (operation == LibreviewPayloadOperation.DELETE) 0f else safeUnits
        val entry = LibreviewInsulinEntry(
            insulinType = "RapidActing",
            units = payloadUnits,
            extendedProperties = LibreviewExtendedProperties(
                factoryTimestamp = formatUtc(eventTimestampMillis),
                action = if (operation == LibreviewPayloadOperation.DELETE) "deleted" else null
            ),
            recordNumber = recordNumber,
            timestamp = formatLocal(eventTimestampMillis)
        )
        val request = LibreviewMeasurementsRequest(
            deviceData = LibreviewDeviceData(
                connectedDevices = LibreviewConnectedDevices(
                    insulinDevices = normalizeConnectedInsulinDevices(connectedInsulinDevices)
                ),
                measurementLog = LibreviewMeasurementLog(insulinEntries = listOf(entry))
            ),
            userToken = userToken,
            gatewayType = gatewayType
        )
        return LibreviewPayloadBuildResult(
            request = request,
            payloadHash = hashPayload(
                channel = "NFC_INSULIN",
                operation = operation,
                recordNumber = recordNumber,
                eventTimestampMillis = eventTimestampMillis,
                amountValue = payloadUnits
            )
        )
    }

    fun formatUtc(eventTimestampMillis: Long): String {
        return utcFormatter.format(Instant.ofEpochMilli(eventTimestampMillis))
    }

    fun formatLocal(eventTimestampMillis: Long): String {
        return localFormatter.format(Instant.ofEpochMilli(eventTimestampMillis))
    }

    private fun normalizeConnectedInsulinDevices(
        connectedInsulinDevices: List<String>
    ): List<String> {
        return connectedInsulinDevices
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun inferMealType(carbs: Float, eventTimestampMillis: Long): String {
        if (carbs <= 50f) return "Snack"
        val hour = Instant.ofEpochMilli(eventTimestampMillis)
            .atZone(ZoneId.systemDefault())
            .hour
        return when (hour) {
            in 4..10 -> "Breakfast"
            in 11..15 -> "Lunch"
            in 16..21 -> "Dinner"
            else -> "Snack"
        }
    }
}
