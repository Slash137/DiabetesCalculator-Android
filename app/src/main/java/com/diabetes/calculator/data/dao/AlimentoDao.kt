package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.diabetes.calculator.data.entity.Alimento
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones CRUD de alimentos.
 */
@Dao
interface AlimentoDao {
    
    /**
     * Obtiene todos los alimentos ordenados alfabéticamente.
     */
    @Query("SELECT * FROM alimentos ORDER BY nombre ASC")
    fun getAll(): Flow<List<Alimento>>
    
    /**
     * Obtiene todos los alimentos de forma síncrona (para selectores).
     */
    @Query("SELECT * FROM alimentos ORDER BY nombre ASC")
    suspend fun getAllSync(): List<Alimento>
    
    /**
     * Obtiene un alimento por su ID.
     */
    @Query("SELECT * FROM alimentos WHERE id = :id")
    suspend fun getById(id: Int): Alimento?
    
    /**
     * Busca alimentos por nombre.
     */
    @Query("SELECT * FROM alimentos WHERE nombre LIKE '%' || :query || '%' ORDER BY nombre ASC")
    fun searchByName(query: String): Flow<List<Alimento>>

    /**
     * Actualiza un alimento por nombre (para mantener seed data sin duplicados).
     */
    @Query("""
        UPDATE alimentos
        SET hidratosPor100g = :hidratos,
            fuente = :fuente,
            nota = :nota,
            tipoMedicionPrincipal = CASE
                WHEN tipoMedicionPrincipal IS NULL OR tipoMedicionPrincipal = '' THEN :tipoMedicionPrincipal
                ELSE tipoMedicionPrincipal
            END,
            estadoFisico = CASE
                WHEN estadoFisico IS NULL OR estadoFisico = '' THEN :estadoFisico
                ELSE estadoFisico
            END,
            hidratosPor100ml = COALESCE(hidratosPor100ml, :hidratosPor100ml),
            unidadNombre = COALESCE(unidadNombre, :unidadNombre),
            gramosPorUnidad = COALESCE(gramosPorUnidad, :gramosPorUnidad),
            mlPorUnidad = COALESCE(mlPorUnidad, :mlPorUnidad)
        WHERE nombre = :nombre
    """)
    suspend fun updateByNombre(
        nombre: String,
        hidratos: Float,
        fuente: String,
        nota: String?,
        tipoMedicionPrincipal: String,
        estadoFisico: String,
        hidratosPor100ml: Float?,
        unidadNombre: String?,
        gramosPorUnidad: Float?,
        mlPorUnidad: Float?
    ): Int
    
    /**
     * Inserta un nuevo alimento.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alimento: Alimento)
    
    /**
     * Inserta múltiples alimentos (para seed data).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alimentos: List<Alimento>)

    /**
     * Inserta un alimento (usado para restauración).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlimento(alimento: Alimento)
    
    /**
     * Actualiza un alimento existente.
     */
    @Update
    suspend fun update(alimento: Alimento)
    
    /**
     * Elimina un alimento.
     */
    @Delete
    suspend fun delete(alimento: Alimento)
    
    /**
     * Obtiene el número total de alimentos.
     */
    @Query("SELECT COUNT(*) FROM alimentos")
    suspend fun getCount(): Int
}
