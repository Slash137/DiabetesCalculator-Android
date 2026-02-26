package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.diabetes.calculator.data.entity.LibreviewRepairRun
import kotlinx.coroutines.flow.Flow

@Dao
interface LibreviewRepairRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LibreviewRepairRun): Long

    @Update
    suspend fun update(item: LibreviewRepairRun)

    @Query(
        """
        SELECT *
        FROM libreview_repair_run
        WHERE id = :id
        LIMIT 1
        """
    )
    suspend fun getById(id: Long): LibreviewRepairRun?

    @Query(
        """
        SELECT *
        FROM libreview_repair_run
        ORDER BY startedAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatest(): LibreviewRepairRun?

    @Query(
        """
        SELECT *
        FROM libreview_repair_run
        WHERE status IN ('STARTED', 'IN_PROGRESS')
        ORDER BY startedAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestActive(): LibreviewRepairRun?

    @Query(
        """
        SELECT *
        FROM libreview_repair_run
        ORDER BY startedAt DESC
        LIMIT 1
        """
    )
    fun observeLatest(): Flow<LibreviewRepairRun?>
}

