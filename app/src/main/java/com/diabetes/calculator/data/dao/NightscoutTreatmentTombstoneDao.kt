package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diabetes.calculator.data.entity.NightscoutTreatmentTombstone

@Dao
interface NightscoutTreatmentTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NightscoutTreatmentTombstone)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM nightscout_treatment_tombstone
            WHERE treatmentId = :treatmentId
        )
        """
    )
    suspend fun exists(treatmentId: String): Boolean

    @Query("DELETE FROM nightscout_treatment_tombstone WHERE treatmentId = :treatmentId")
    suspend fun deleteByTreatmentId(treatmentId: String)
}
