package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.LibreviewRepairRunDao
import com.diabetes.calculator.data.entity.LibreviewRepairPhase
import com.diabetes.calculator.data.entity.LibreviewRepairRun
import com.diabetes.calculator.data.entity.LibreviewRepairRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LibreviewRepairRunSummary(
    val runId: Long = 0,
    val phase: LibreviewRepairPhase = LibreviewRepairPhase.DISCOVERY,
    val status: LibreviewRepairRunStatus = LibreviewRepairRunStatus.STARTED,
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
    val startedAt: Long? = null,
    val updatedAt: Long? = null,
    val finishedAt: Long? = null,
    val reportJson: String? = null
)

class LibreviewRepairRunRepository(
    private val dao: LibreviewRepairRunDao
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    val latestSummary: Flow<LibreviewRepairRunSummary> = dao.observeLatest().map { run ->
        run?.toSummary() ?: LibreviewRepairRunSummary()
    }

    suspend fun startRun(
        canonicalRecords: Int,
        snapshotJson: String?,
        now: Long
    ): Long {
        return dao.insert(
            LibreviewRepairRun(
                startedAt = now,
                updatedAt = now,
                status = LibreviewRepairRunStatus.STARTED.value,
                phase = LibreviewRepairPhase.DISCOVERY.value,
                canonicalRecords = canonicalRecords,
                snapshotJson = snapshotJson
            )
        )
    }

    suspend fun getRun(runId: Long): LibreviewRepairRun? = dao.getById(runId)

    suspend fun getLatestActiveRun(): LibreviewRepairRun? = dao.getLatestActive()

    suspend fun getLatestReportJson(): String? {
        val run = dao.getLatest() ?: return null
        return run.reportJson ?: json.encodeToString(run)
    }

    suspend fun updateRun(runId: Long, mutator: (LibreviewRepairRun) -> LibreviewRepairRun) {
        val current = dao.getById(runId) ?: return
        dao.update(mutator(current))
    }

    suspend fun markPhase(
        runId: Long,
        phase: LibreviewRepairPhase,
        status: LibreviewRepairRunStatus = LibreviewRepairRunStatus.IN_PROGRESS,
        now: Long
    ) {
        updateRun(runId) { current ->
            current.copy(
                phase = phase.value,
                status = status.value,
                updatedAt = now
            )
        }
    }

    suspend fun markBlocked(
        runId: Long,
        reason: String,
        reportJson: String?,
        now: Long
    ) {
        updateRun(runId) { current ->
            current.copy(
                status = LibreviewRepairRunStatus.BLOCKED.value,
                blockedReason = reason,
                reportJson = reportJson ?: current.reportJson,
                updatedAt = now,
                finishedAt = now
            )
        }
    }

    suspend fun markFailed(
        runId: Long,
        reason: String,
        reportJson: String?,
        now: Long
    ) {
        updateRun(runId) { current ->
            current.copy(
                status = LibreviewRepairRunStatus.FAILED.value,
                blockedReason = reason,
                reportJson = reportJson ?: current.reportJson,
                updatedAt = now,
                finishedAt = now
            )
        }
    }

    suspend fun markCompleted(
        runId: Long,
        reportJson: String?,
        now: Long
    ) {
        updateRun(runId) { current ->
            current.copy(
                status = LibreviewRepairRunStatus.COMPLETED.value,
                reportJson = reportJson ?: current.reportJson,
                updatedAt = now,
                finishedAt = now
            )
        }
    }

    private fun LibreviewRepairRun.toSummary(): LibreviewRepairRunSummary {
        return LibreviewRepairRunSummary(
            runId = id,
            phase = LibreviewRepairPhase.fromValue(phase),
            status = LibreviewRepairRunStatus.fromValue(status),
            canonicalRecords = canonicalRecords,
            knownManagedCount = knownManagedCount,
            unknownOverlapCount = unknownOverlapCount,
            foreignCount = foreignCount,
            deletePlanned = deletePlanned,
            deleteSucceeded = deleteSucceeded,
            deleteFailedTolerated = deleteFailedTolerated,
            upsertPlanned = upsertPlanned,
            upsertSucceeded = upsertSucceeded,
            upsertFailed = upsertFailed,
            blockedReason = blockedReason,
            startedAt = startedAt,
            updatedAt = updatedAt,
            finishedAt = finishedAt,
            reportJson = reportJson
        )
    }
}
