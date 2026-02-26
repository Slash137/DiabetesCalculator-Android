package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.LibreviewRecordCatalogDao
import com.diabetes.calculator.data.entity.LibreviewRecordCatalog
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation

data class LibreviewRecordKey(
    val channel: RegistroLibreviewSyncChannel,
    val recordNumber: Long
)

class LibreviewRecordCatalogRepository(
    private val dao: LibreviewRecordCatalogDao
) {
    suspend fun upsert(
        channel: RegistroLibreviewSyncChannel,
        recordNumber: Long,
        sourceRegistroId: Int?,
        now: Long,
        operation: RegistroLibreviewSyncOperation?,
        payloadHash: String?
    ) {
        dao.upsert(
            LibreviewRecordCatalog(
                channel = channel.value,
                recordNumber = recordNumber,
                sourceRegistroId = sourceRegistroId,
                firstSeenAt = now,
                updatedAt = now,
                lastOperation = operation?.value,
                payloadHash = payloadHash
            )
        )
    }

    suspend fun upsertAll(items: List<LibreviewRecordCatalog>) {
        if (items.isEmpty()) return
        dao.upsertAll(items)
    }

    suspend fun getKnownKeys(): Set<LibreviewRecordKey> {
        return dao.getAllKeys()
            .mapNotNull { key ->
                val channel = RegistroLibreviewSyncChannel.fromValue(key.channel) ?: return@mapNotNull null
                LibreviewRecordKey(channel = channel, recordNumber = key.recordNumber)
            }
            .toSet()
    }

    suspend fun getKnownKeysUpdatedSince(sinceMillis: Long): Set<LibreviewRecordKey> {
        return dao.getKeysUpdatedSince(sinceMillis)
            .mapNotNull { key ->
                val channel = RegistroLibreviewSyncChannel.fromValue(key.channel) ?: return@mapNotNull null
                LibreviewRecordKey(channel = channel, recordNumber = key.recordNumber)
            }
            .toSet()
    }

    suspend fun getAttributableKnownKeys(): Set<LibreviewRecordKey> {
        return dao.getAttributableKeys()
            .mapNotNull { key ->
                val channel = RegistroLibreviewSyncChannel.fromValue(key.channel) ?: return@mapNotNull null
                LibreviewRecordKey(channel = channel, recordNumber = key.recordNumber)
            }
            .toSet()
    }

    suspend fun getAttributableKnownKeysUpdatedSince(sinceMillis: Long): Set<LibreviewRecordKey> {
        return dao.getAttributableKeysUpdatedSince(sinceMillis)
            .mapNotNull { key ->
                val channel = RegistroLibreviewSyncChannel.fromValue(key.channel) ?: return@mapNotNull null
                LibreviewRecordKey(channel = channel, recordNumber = key.recordNumber)
            }
            .toSet()
    }
}
