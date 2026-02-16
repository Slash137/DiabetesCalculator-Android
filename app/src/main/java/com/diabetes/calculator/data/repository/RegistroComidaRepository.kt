package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.dao.RegistroComidaDao
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.domain.ACTIVE_INSULIN_DURATION_MINUTES
import com.diabetes.calculator.domain.ActiveInsulinCalculator
import com.diabetes.calculator.domain.ActiveInsulinSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio para la gestión de registros de comida.
 * Soporta registros compuestos (múltiples alimentos).
 */
class RegistroComidaRepository(private val dao: RegistroComidaDao) {

    /**
     * Obtiene todos los registros con sus items.
     */
    val allRegistros: Flow<List<RegistroComidaConItems>> = dao.getAllWithItems()
    val nightscoutImportCount: Flow<Int> = dao.observeCountByOrigen(OrigenRegistro.NIGHTSCOUT_IMPORT.value)

    /**
     * Busca registros por texto (nombre de alimento o notas).
     */
    fun searchRegistros(query: String): Flow<List<RegistroComidaConItems>> = dao.search(query)

    /**
     * Inserta un registro de comida completo.
     */
    suspend fun insertRegistroCompleto(registro: RegistroComida, items: List<AlimentoEnRegistro>): Int {
        return dao.insertRegistroCompleto(registro, items)
    }

    suspend fun insertRegistro(registro: RegistroComida): Int {
        return dao.insertRegistro(registro).toInt()
    }

    /**
     * Actualiza la glucosa 2h después en un registro.
     */
    suspend fun updateGlucosaDespues2h(registroId: Int, glucosa: Int) {
        dao.updateGlucosaDespues2h(registroId, glucosa)
    }

    /**
     * Actualiza la glucosa antes en un registro.
     */
    suspend fun updateGlucosaAntes(registroId: Int, glucosa: Int) {
        dao.updateGlucosaAntes(registroId, glucosa)
    }

    suspend fun updateDosisEstado(
        registroId: Int,
        estado: EstadoDosis,
        confirmadaAt: Long? = if (estado == EstadoDosis.APLICADA) System.currentTimeMillis() else null
    ) {
        dao.updateDosisEstado(
            registroId = registroId,
            estado = estado.value,
            confirmadaAt = confirmadaAt
        )
    }

    suspend fun updateDosisCorreccion(
        registroId: Int,
        conCorreccion: Boolean?
    ) {
        dao.updateDosisCorreccion(registroId, conCorreccion)
    }

    suspend fun sumHidratosInRange(start: Long, end: Long): Float =
        dao.sumHidratosInRange(start, end)

    suspend fun sumRacionesInRange(start: Long, end: Long): Float =
        dao.sumRacionesInRange(start, end)

    suspend fun sumInsulinaInRange(start: Long, end: Long): Float =
        dao.sumInsulinaInRange(start, end)

    suspend fun getActiveInsulinSnapshot(nowMillis: Long): ActiveInsulinSnapshot {
        val fromMillis = nowMillis - (ACTIVE_INSULIN_DURATION_MINUTES * 60_000L)
        val dosis = dao.getReliableAppliedDosesInWindow(fromMillis, nowMillis)
        return ActiveInsulinCalculator.calculate(dosis, nowMillis)
    }

    suspend fun getRegistroRawById(id: Int): RegistroComida? = dao.getRegistroRawById(id)

    suspend fun getByNightscoutTreatmentId(treatmentId: String): RegistroComida? =
        dao.getByNightscoutTreatmentId(treatmentId)

    suspend fun getRegistrosInRangeRaw(from: Long, to: Long): List<RegistroComida> =
        dao.getRegistrosInRangeRaw(from, to)

    suspend fun updateNightscoutLink(
        registroId: Int,
        treatmentId: String?,
        unidadesInsulinaRemota: Float?,
        reconciliadoAt: Long?,
        dcid: String?
    ): Int {
        return dao.updateNightscoutLink(
            registroId = registroId,
            treatmentId = treatmentId,
            unidadesInsulinaRemota = unidadesInsulinaRemota,
            reconciliadoAt = reconciliadoAt,
            dcid = dcid
        )
    }

    suspend fun updateNightscoutSyncDcid(
        registroId: Int,
        dcid: String?
    ) {
        dao.updateNightscoutSyncDcid(registroId, dcid)
    }

    suspend fun clearNightscoutLink(registroId: Int) {
        dao.clearNightscoutLink(registroId)
    }

    suspend fun updateDoseForLink(
        registroId: Int,
        unidades: Float,
        confirmadaAt: Long?
    ) = dao.updateDoseForLink(registroId, unidades, confirmadaAt)

    /**
     * Elimina un registro.
     */
    suspend fun delete(registro: RegistroComida) = dao.delete(registro)

    /**
     * Elimina un registro por ID.
     */
    suspend fun deleteById(id: Int) = dao.deleteById(id)

    /**
     * Elimina todos los registros.
     */
    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Obtiene registros raw para backup.
     */
    suspend fun getAllRegistrosRaw(): List<RegistroComida> = dao.getAllRegistrosRaw()
}
