package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "libreview_repair_run",
    indices = [
        Index(value = ["status"]),
        Index(value = ["phase"]),
        Index(value = ["updatedAt"])
    ]
)
@Serializable
data class LibreviewRepairRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val status: String = LibreviewRepairRunStatus.STARTED.value,
    val phase: String = LibreviewRepairPhase.DISCOVERY.value,
    val canonicalRecords: Int = 0,
    val knownManagedCount: Int = 0,
    val unknownOverlapCount: Int = 0,
    val foreignCount: Int = 0,
    val deletePlanned: Int = 0,
    val deleteSucceeded: Int = 0,
    val deleteFailedTolerated: Int = 0,
    val upsertPlanned: Int = 0,
    val upsertSucceeded: Int = 0,
    val upsertFailed: Int = 0,
    val blockedReason: String? = null,
    val snapshotJson: String? = null,
    val reportJson: String? = null
)

enum class LibreviewRepairPhase(val value: String) {
    DISCOVERY("DISCOVERY"),
    WIPE_ONLY("WIPE_ONLY"),
    UPSERT_ONLY("UPSERT_ONLY"),
    VERIFY("VERIFY");

    companion object {
        fun fromValue(value: String?): LibreviewRepairPhase {
            return entries.firstOrNull { it.value == value } ?: DISCOVERY
        }
    }
}

enum class LibreviewRepairRunStatus(val value: String) {
    STARTED("STARTED"),
    IN_PROGRESS("IN_PROGRESS"),
    BLOCKED("BLOCKED"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    companion object {
        fun fromValue(value: String?): LibreviewRepairRunStatus {
            return entries.firstOrNull { it.value == value } ?: STARTED
        }
    }
}

