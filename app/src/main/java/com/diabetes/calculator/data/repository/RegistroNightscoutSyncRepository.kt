package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroNightscoutSyncDao
import com.diabetes.calculator.data.entity.RegistroNightscoutSync
import com.diabetes.calculator.data.entity.RegistroNightscoutSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class NightscoutRegistrosSyncSummary(
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastErrorMessage: String? = null
)

class RegistroNightscoutSyncRepository(
    private val dao: RegistroNightscoutSyncDao
) {
    val summary: Flow<NightscoutRegistrosSyncSummary> = combine(
        dao.observePendingCount(),
        dao.observeFailedCount(),
        dao.observeLastSuccessAt(),
        dao.observeLastErrorAt(),
        dao.observeLastErrorMessage()
    ) { pending, failed, lastSuccess, lastErrorAt, lastErrorMessage ->
        NightscoutRegistrosSyncSummary(
            pendingCount = pending,
            failedCount = failed,
            lastSuccessAt = lastSuccess,
            lastErrorAt = lastErrorAt,
            lastErrorMessage = lastErrorMessage
        )
    }

    suspend fun upsertPending(registroId: Int, now: Long = System.currentTimeMillis()) {
        val current = dao.getByRegistroId(registroId)
        dao.upsert(
            RegistroNightscoutSync(
                registroId = registroId,
                status = RegistroNightscoutSyncStatus.PENDING.value,
                attempts = current?.attempts ?: 0,
                lastError = current?.lastError,
                updatedAt = now
            )
        )
    }

    suspend fun getByRegistroId(registroId: Int): RegistroNightscoutSync? =
        dao.getByRegistroId(registroId)

    suspend fun markSyncedUpload(registroId: Int, now: Long = System.currentTimeMillis()) {
        val current = dao.getByRegistroId(registroId)
        dao.upsert(
            RegistroNightscoutSync(
                registroId = registroId,
                status = RegistroNightscoutSyncStatus.SYNCED_UPLOAD.value,
                attempts = current?.attempts ?: 0,
                lastError = null,
                updatedAt = now
            )
        )
    }

    suspend fun markSyncedNoUpload(registroId: Int, now: Long = System.currentTimeMillis()) {
        val current = dao.getByRegistroId(registroId)
        dao.upsert(
            RegistroNightscoutSync(
                registroId = registroId,
                status = RegistroNightscoutSyncStatus.SYNCED_NO_UPLOAD.value,
                attempts = current?.attempts ?: 0,
                lastError = null,
                updatedAt = now
            )
        )
    }

    suspend fun markFailed(
        registroId: Int,
        error: String,
        now: Long = System.currentTimeMillis()
    ) {
        val current = dao.getByRegistroId(registroId)
        val attempts = (current?.attempts ?: 0) + 1
        dao.upsert(
            RegistroNightscoutSync(
                registroId = registroId,
                status = RegistroNightscoutSyncStatus.FAILED.value,
                attempts = attempts,
                lastError = error,
                updatedAt = now
            )
        )
    }

    suspend fun getPendingOrFailed(): List<RegistroNightscoutSync> = dao.getPendingOrFailed()

    suspend fun deleteByRegistroId(registroId: Int) = dao.deleteByRegistroId(registroId)
}
