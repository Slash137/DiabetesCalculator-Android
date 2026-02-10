package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.AlimentoDao
import com.diabetes.calculator.data.entity.Alimento
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio para alimentos.
 * Abstrae el acceso a datos del DAO.
 */
class AlimentoRepository(private val dao: AlimentoDao) {
    
    /**
     * Obtiene todos los alimentos de forma reactiva.
     */
    val alimentos: Flow<List<Alimento>> = dao.getAll()
    
    /**
     * Obtiene todos los alimentos de forma síncrona.
     */
    suspend fun getAllSync(): List<Alimento> = dao.getAllSync()
    
    /**
     * Obtiene un alimento por su ID.
     */
    suspend fun getById(id: Int): Alimento? = dao.getById(id)
    
    /**
     * Busca alimentos por nombre.
     */
    fun searchByName(query: String): Flow<List<Alimento>> = dao.searchByName(query)
    
    /**
     * Añade un nuevo alimento.
     */
    suspend fun insert(alimento: Alimento) = dao.insert(alimento)
    
    /**
     * Actualiza un alimento existente.
     */
    suspend fun update(alimento: Alimento) = dao.update(alimento)
    
    /**
     * Elimina un alimento.
     */
    suspend fun delete(alimento: Alimento) = dao.delete(alimento)
}
