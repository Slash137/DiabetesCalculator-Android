package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroLibreviewSyncDao
import com.diabetes.calculator.data.entity.RegistroLibreviewSync
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class LibreviewRegistrosSyncSummary(
    val pendingCount: Int = 0,
    val pendingUpsertCount: Int = 0,
    val pendingDeleteCount: Int = 0,
    val failedCount: Int = 0,
    val failedUpsertCount: Int = 0,
    val failedDeleteCount: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastErrorMessage: String? = null
)

class RegistroLibreviewSyncRepository(
    private val dao: RegistroLibreviewSyncDao
) {
    private val pendingCounts: Flow<Triple<Int, Int, Int>> = combine(
        dao.observePendingCount(),
        dao.observePendingCountByOperation(RegistroLibreviewSyncOperation.UPSERT.value),
        dao.observePendingCountByOperation(RegistroLibreviewSyncOperation.DELETE.value)
    ) { pending, pendingUpserts, pendingDeletes ->
        Triple(pending, pendingUpserts, pendingDeletes)
    }

    private val failedCounts: Flow<Triple<Int, Int, Int>> = combine(
        dao.observeFailedCount(),
        dao.observeFailedCountByOperation(RegistroLibreviewSyncOperation.UPSERT.value),
        dao.observeFailedCountByOperation(RegistroLibreviewSyncOperation.DELETE.value)
    ) { failed, failedUpserts, failedDeletes ->
        Triple(failed, failedUpserts, failedDeletes)
    }

    private val timingSummary: Flow<Triple<Long?, Long?, String?>> = combine(
        dao.observeLastSuccessAt(),
        dao.observeLastErrorAt(),
        dao.observeLastErrorMessage()
    ) { lastSuccess, lastErrorAt, lastErrorMessage ->
        Triple(lastSuccess, lastErrorAt, lastErrorMessage)
    }

    val summary: Flow<LibreviewRegistrosSyncSummary> = combine(
        pendingCounts,
        failedCounts,
        timingSummary
    ) { pending, failed, timing ->
        LibreviewRegistrosSyncSummary(
            pendingCount = pending.first,
            pendingUpsertCount = pending.second,
            pendingDeleteCount = pending.third,
            failedCount = failed.first,
            failedUpsertCount = failed.second,
            failedDeleteCount = failed.third,
            lastSuccessAt = timing.first,
            lastErrorAt = timing.second,
            lastErrorMessage = timing.third
        )
    }

    val failedRegistroIds: Flow<List<Int>> = dao.observeFailedRegistroIds()

    suspend fun upsertPending(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel,
        operation: RegistroLibreviewSyncOperation = RegistroLibreviewSyncOperation.UPSERT,
        now: Long = System.currentTimeMillis(),
        recordNumber: Long? = null,
        eventTimestampMillis: Long? = null,
        amountValue: Float? = null,
        payloadHash: String? = null
    ) {
        val current = dao.getByRegistroAndChannel(registroId, channel.value)
        dao.upsert(
            RegistroLibreviewSync(
                registroId = registroId,
                channel = channel.value,
                operation = operation.value,
                status = RegistroLibreviewSyncStatus.PENDING.value,
                attempts = 0,
                lastError = if (current?.operation == operation.value) current.lastError else null,
                updatedAt = now,
                recordNumber = recordNumber ?: current?.recordNumber,
                eventTimestampMillis = eventTimestampMillis ?: current?.eventTimestampMillis,
                amountValue = amountValue ?: current?.amountValue,
                payloadHash = payloadHash ?: current?.payloadHash
            )
        )
    }

    suspend fun getByRegistroAndChannel(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel
    ): RegistroLibreviewSync? = dao.getByRegistroAndChannel(registroId, channel.value)

    suspend fun markSyncedUpload(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel,
        now: Long = System.currentTimeMillis()
    ) {
        val current = dao.getByRegistroAndChannel(registroId, channel.value) ?: return
        dao.upsert(
            current.copy(
                status = RegistroLibreviewSyncStatus.SYNCED_UPLOAD.value,
                lastError = null,
                updatedAt = now
            )
        )
    }

    suspend fun markSyncedNoUpload(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel,
        now: Long = System.currentTimeMillis()
    ) {
        val current = dao.getByRegistroAndChannel(registroId, channel.value) ?: return
        dao.upsert(
            current.copy(
                status = RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD.value,
                lastError = null,
                updatedAt = now
            )
        )
    }

    suspend fun markFailed(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel,
        error: String,
        now: Long = System.currentTimeMillis()
    ) {
        val current = dao.getByRegistroAndChannel(registroId, channel.value) ?: return
        dao.upsert(
            current.copy(
                status = RegistroLibreviewSyncStatus.FAILED.value,
                attempts = current.attempts + 1,
                lastError = error,
                updatedAt = now
            )
        )
    }

    suspend fun getPendingOrFailed(): List<RegistroLibreviewSync> = dao.getPendingOrFailed()

    suspend fun getPendingOrFailedPrioritizingDeletes(): List<RegistroLibreviewSync> =
        dao.getPendingOrFailedPrioritizingDeletes()

    suspend fun deleteByRegistroId(registroId: Int) = dao.deleteByRegistroId(registroId)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun deleteByRegistroAndChannel(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel
    ) = dao.deleteByRegistroAndChannel(registroId, channel.value)

    suspend fun deleteByRegistroAndChannelValue(
        registroId: Int,
        channel: String
    ) = dao.deleteByRegistroAndChannel(registroId, channel)

    suspend fun deleteByOperation(operation: RegistroLibreviewSyncOperation) {
        dao.deleteByOperation(operation.value)
    }

    suspend fun deleteDeleteOperationsByRecordNumber(
        channel: RegistroLibreviewSyncChannel,
        recordNumber: Long
    ) = dao.deleteDeleteOperationsByChannelAndRecordNumber(channel.value, recordNumber)

    suspend fun countByOperationAndStatusSince(
        operation: RegistroLibreviewSyncOperation,
        status: RegistroLibreviewSyncStatus,
        sinceMillis: Long
    ): Int {
        return dao.countByOperationAndStatusSince(
            operation = operation.value,
            status = status.value,
            sinceMillis = sinceMillis
        )
    }

    suspend fun countByOperationAndStatusSinceForKeys(
        operation: RegistroLibreviewSyncOperation,
        status: RegistroLibreviewSyncStatus,
        sinceMillis: Long,
        keys: Set<Pair<String, Long>>
    ): Int {
        if (keys.isEmpty()) return 0
        return dao.countByOperationAndStatusSinceGroupedByKey(
            operation = operation.value,
            status = status.value,
            sinceMillis = sinceMillis
        ).sumOf { keyCount ->
            val recordNumber = keyCount.recordNumber ?: return@sumOf 0
            if (keys.contains(keyCount.channel to recordNumber)) keyCount.total else 0
        }
    }
}
