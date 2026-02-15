package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "nightscout_treatment_tombstone")
@Serializable
data class NightscoutTreatmentTombstone(
    @PrimaryKey
    val treatmentId: String,
    val deletedAt: Long = System.currentTimeMillis()
)
