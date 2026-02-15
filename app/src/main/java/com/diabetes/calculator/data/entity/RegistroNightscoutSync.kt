package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "registro_nightscout_sync",
    foreignKeys = [
        ForeignKey(
            entity = RegistroComida::class,
            parentColumns = ["id"],
            childColumns = ["registroId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["updatedAt"])
    ]
)
@Serializable
data class RegistroNightscoutSync(
    @PrimaryKey
    val registroId: Int,
    val status: String = RegistroNightscoutSyncStatus.PENDING.value,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class RegistroNightscoutSyncStatus(val value: String) {
    PENDING("PENDING"),
    FAILED("FAILED"),
    SYNCED_UPLOAD("SYNCED_UPLOAD"),
    SYNCED_NO_UPLOAD("SYNCED_NO_UPLOAD");

    companion object {
        fun fromValue(value: String?): RegistroNightscoutSyncStatus {
            return entries.firstOrNull { it.value == value } ?: PENDING
        }
    }
}
