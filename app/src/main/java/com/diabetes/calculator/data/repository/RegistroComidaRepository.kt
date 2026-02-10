package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.dao.RegistroComidaDao
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.RegistroComida
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

    suspend fun sumHidratosInRange(start: Long, end: Long): Float =
        dao.sumHidratosInRange(start, end)

    suspend fun sumRacionesInRange(start: Long, end: Long): Float =
        dao.sumRacionesInRange(start, end)

    suspend fun sumInsulinaInRange(start: Long, end: Long): Float =
        dao.sumInsulinaInRange(start, end)

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
