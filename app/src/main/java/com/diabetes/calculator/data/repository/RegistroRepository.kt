package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.dao.RegistroComidaDao
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio para registros de comida.
 * Abstrae el acceso a datos del DAO.
 */
class RegistroRepository(private val dao: RegistroComidaDao) {

    /**
     * Obtiene todos los registros con sus items.
     */
    val registros: Flow<List<RegistroComidaConItems>> = dao.getAllWithItems()

    /**
     * Inserta un nuevo registro de comida completo.
     */
    suspend fun insertRegistroCompleto(registro: RegistroComida, items: List<AlimentoEnRegistro>) {
        dao.insertRegistroCompleto(registro, items)
    }

    /**
     * Elimina un registro por su ID.
     */
    suspend fun deleteById(id: Int) = dao.deleteById(id)

    /**
     * Obtiene registros por búsqueda.
     */
    fun searchRegistros(query: String): Flow<List<RegistroComidaConItems>> = dao.search(query)
}
