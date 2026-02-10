package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.diabetes.calculator.data.entity.PendingGlucose
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingGlucoseDao {
    @Query("SELECT * FROM pending_glucose ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingGlucose>>

    @Query("SELECT * FROM pending_glucose ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingGlucose>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingGlucose): Long

    @Update
    suspend fun update(item: PendingGlucose)

    @Query("DELETE FROM pending_glucose WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM pending_glucose WHERE registroId = :registroId AND tipo = :tipo")
    suspend fun deleteByRegistroAndTipo(registroId: Int, tipo: String)
}
