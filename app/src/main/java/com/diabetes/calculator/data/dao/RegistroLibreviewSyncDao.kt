package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diabetes.calculator.data.entity.RegistroLibreviewSync
import kotlinx.coroutines.flow.Flow

data class LibreviewSyncOperationKeyCount(
    val channel: String,
    val recordNumber: Long?,
    val total: Int
)

@Dao
interface RegistroLibreviewSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RegistroLibreviewSync)

    @Query(
        """
        SELECT * FROM registro_libreview_sync
        WHERE registroId = :registroId AND channel = :channel
        LIMIT 1
        """
    )
    suspend fun getByRegistroAndChannel(registroId: Int, channel: String): RegistroLibreviewSync?

    @Query(
        """
        SELECT * FROM registro_libreview_sync
        WHERE status = 'PENDING' OR status = 'FAILED'
        ORDER BY updatedAt ASC
        """
    )
    suspend fun getPendingOrFailed(): List<RegistroLibreviewSync>

    @Query(
        """
        SELECT * FROM registro_libreview_sync
        WHERE status = 'PENDING' OR status = 'FAILED'
        ORDER BY
            CASE WHEN operation = 'DELETE' THEN 0 ELSE 1 END ASC,
            updatedAt ASC
        """
    )
    suspend fun getPendingOrFailedPrioritizingDeletes(): List<RegistroLibreviewSync>

    @Query("DELETE FROM registro_libreview_sync WHERE registroId = :registroId")
    suspend fun deleteByRegistroId(registroId: Int)

    @Query("DELETE FROM registro_libreview_sync")
    suspend fun deleteAll()

    @Query("DELETE FROM registro_libreview_sync WHERE registroId = :registroId AND channel = :channel")
    suspend fun deleteByRegistroAndChannel(registroId: Int, channel: String)

    @Query(
        """
        DELETE FROM registro_libreview_sync
        WHERE channel = :channel
          AND operation = 'DELETE'
          AND recordNumber = :recordNumber
        """
    )
    suspend fun deleteDeleteOperationsByChannelAndRecordNumber(channel: String, recordNumber: Long)

    @Query(
        """
        SELECT COUNT(*)
        FROM registro_libreview_sync
        WHERE operation = :operation
          AND status = :status
          AND updatedAt >= :sinceMillis
        """
    )
    suspend fun countByOperationAndStatusSince(
        operation: String,
        status: String,
        sinceMillis: Long
    ): Int

    @Query(
        """
        SELECT channel, recordNumber, COUNT(*) AS total
        FROM registro_libreview_sync
        WHERE operation = :operation
          AND status = :status
          AND updatedAt >= :sinceMillis
        GROUP BY channel, recordNumber
        """
    )
    suspend fun countByOperationAndStatusSinceGroupedByKey(
        operation: String,
        status: String,
        sinceMillis: Long
    ): List<LibreviewSyncOperationKeyCount>

    @Query("SELECT COUNT(*) FROM registro_libreview_sync WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM registro_libreview_sync WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*)
        FROM registro_libreview_sync
        WHERE status = 'PENDING'
          AND operation = :operation
        """
    )
    fun observePendingCountByOperation(operation: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*)
        FROM registro_libreview_sync
        WHERE status = 'FAILED'
          AND operation = :operation
        """
    )
    fun observeFailedCountByOperation(operation: String): Flow<Int>

    @Query("SELECT MAX(updatedAt) FROM registro_libreview_sync WHERE status IN ('SYNCED_UPLOAD', 'SYNCED_NO_UPLOAD')")
    fun observeLastSuccessAt(): Flow<Long?>

    @Query("SELECT MAX(updatedAt) FROM registro_libreview_sync WHERE status = 'FAILED'")
    fun observeLastErrorAt(): Flow<Long?>

    @Query(
        """
        SELECT lastError FROM registro_libreview_sync
        WHERE status = 'FAILED'
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    fun observeLastErrorMessage(): Flow<String?>

    @Query(
        """
        SELECT DISTINCT registroId
        FROM registro_libreview_sync
        WHERE status = 'FAILED'
        """
    )
    fun observeFailedRegistroIds(): Flow<List<Int>>

    @Query("DELETE FROM registro_libreview_sync WHERE operation = :operation")
    suspend fun deleteByOperation(operation: String)
}
