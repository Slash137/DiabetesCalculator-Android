package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Entity(
    tableName = "registro_libreview_sync",
    primaryKeys = ["registroId", "channel"],
    indices = [
        Index(value = ["status"]),
        Index(value = ["updatedAt"]),
        Index(value = ["channel"])
    ]
)
@Serializable
data class RegistroLibreviewSync(
    val registroId: Int,
    val channel: String,
    val operation: String = RegistroLibreviewSyncOperation.UPSERT.value,
    val status: String = RegistroLibreviewSyncStatus.PENDING.value,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val recordNumber: Long? = null,
    val eventTimestampMillis: Long? = null,
    val amountValue: Float? = null,
    val payloadHash: String? = null
)

enum class RegistroLibreviewSyncChannel(val value: String) {
    CARBS("CARBS"),
    NFC_INSULIN("NFC_INSULIN");

    companion object {
        fun fromValue(value: String?): RegistroLibreviewSyncChannel? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

enum class RegistroLibreviewSyncOperation(val value: String) {
    UPSERT("UPSERT"),
    DELETE("DELETE");

    companion object {
        fun fromValue(value: String?): RegistroLibreviewSyncOperation {
            return entries.firstOrNull { it.value == value } ?: UPSERT
        }
    }
}

enum class RegistroLibreviewSyncStatus(val value: String) {
    PENDING("PENDING"),
    FAILED("FAILED"),
    SYNCED_UPLOAD("SYNCED_UPLOAD"),
    SYNCED_NO_UPLOAD("SYNCED_NO_UPLOAD");

    companion object {
        fun fromValue(value: String?): RegistroLibreviewSyncStatus {
            return entries.firstOrNull { it.value == value } ?: PENDING
        }
    }
}
