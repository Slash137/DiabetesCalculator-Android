package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.diabetes.calculator.data.entity.RegistroNightscoutSync
import kotlinx.coroutines.flow.Flow

@Dao
interface RegistroNightscoutSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RegistroNightscoutSync)

    @Update
    suspend fun update(item: RegistroNightscoutSync)

    @Query("SELECT * FROM registro_nightscout_sync WHERE registroId = :registroId LIMIT 1")
    suspend fun getByRegistroId(registroId: Int): RegistroNightscoutSync?

    @Query(
        """
        SELECT * FROM registro_nightscout_sync
        WHERE status = 'PENDING' OR status = 'FAILED'
        ORDER BY updatedAt ASC
        """
    )
    suspend fun getPendingOrFailed(): List<RegistroNightscoutSync>

    @Query("DELETE FROM registro_nightscout_sync WHERE registroId = :registroId")
    suspend fun deleteByRegistroId(registroId: Int)

    @Query("SELECT COUNT(*) FROM registro_nightscout_sync WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM registro_nightscout_sync WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT MAX(updatedAt) FROM registro_nightscout_sync WHERE status IN ('SYNCED_UPLOAD', 'SYNCED_NO_UPLOAD')")
    fun observeLastSuccessAt(): Flow<Long?>

    @Query("SELECT MAX(updatedAt) FROM registro_nightscout_sync WHERE status = 'FAILED'")
    fun observeLastErrorAt(): Flow<Long?>

    @Query(
        """
        SELECT lastError FROM registro_nightscout_sync
        WHERE status = 'FAILED'
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    fun observeLastErrorMessage(): Flow<String?>
}
