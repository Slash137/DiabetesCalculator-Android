package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.PendingGlucoseDao
import com.diabetes.calculator.data.entity.PendingGlucose
import kotlinx.coroutines.flow.Flow

class PendingGlucoseRepository(private val dao: PendingGlucoseDao) {
    val pending: Flow<List<PendingGlucose>> = dao.observeAll()

    suspend fun getAll(): List<PendingGlucose> = dao.getAll()

    suspend fun insert(item: PendingGlucose) = dao.insert(item)

    suspend fun update(item: PendingGlucose) = dao.update(item)

    suspend fun deleteById(id: Int) = dao.deleteById(id)

    suspend fun deleteByRegistroAndTipo(registroId: Int, tipo: String) =
        dao.deleteByRegistroAndTipo(registroId, tipo)
}
