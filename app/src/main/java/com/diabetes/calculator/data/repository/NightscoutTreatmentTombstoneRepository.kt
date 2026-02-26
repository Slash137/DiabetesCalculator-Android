package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.NightscoutTreatmentTombstoneDao
import com.diabetes.calculator.data.entity.NightscoutTreatmentTombstone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NightscoutTreatmentTombstoneRepository(
    private val dao: NightscoutTreatmentTombstoneDao
) {
    suspend fun add(treatmentId: String, deletedAt: Long = System.currentTimeMillis()) {
        if (treatmentId.isBlank()) return
        dao.upsert(
            NightscoutTreatmentTombstone(
                treatmentId = treatmentId,
                deletedAt = deletedAt
            )
        )
    }

    suspend fun exists(treatmentId: String): Boolean {
        if (treatmentId.isBlank()) return false
        return dao.exists(treatmentId)
    }

    suspend fun delete(treatmentId: String) = dao.deleteByTreatmentId(treatmentId)

    suspend fun getAllTreatmentIds(): Set<String> = dao.getAllTreatmentIds().toSet()

    val allTreatmentIds: Flow<Set<String>> = dao.observeAllTreatmentIds().map { it.toSet() }
}
