package com.diabetes.calculator.data.dao

import androidx.room.*
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import kotlinx.coroutines.flow.Flow

/**
 * Clase para representar un registro completo con todos sus alimentos.
 */
data class RegistroComidaConItems(
    @Embedded val registro: RegistroComida,
    @Relation(
        entity = AlimentoEnRegistro::class,
        parentColumn = "id",
        entityColumn = "registroId"
    )
    val items: List<ItemConAlimento>
)

data class ItemConAlimento(
    @Embedded val item: AlimentoEnRegistro,
    @Relation(
        parentColumn = "alimentoId",
        entityColumn = "id"
    )
    val alimento: Alimento
)

@Dao
interface RegistroComidaDao {
    
    @Transaction
    @Query("SELECT * FROM registro_comida ORDER BY fecha DESC")
    fun getAllWithItems(): Flow<List<RegistroComidaConItems>>
    
    @Transaction
    @Query("SELECT * FROM registro_comida WHERE id = :id")
    suspend fun getById(id: Int): RegistroComidaConItems?
    
    @Insert
    suspend fun insertRegistro(registro: RegistroComida): Long
    
    @Insert
    suspend fun insertItems(items: List<AlimentoEnRegistro>)
    
    @Transaction
    suspend fun insertRegistroCompleto(registro: RegistroComida, items: List<AlimentoEnRegistro>): Int {
        val id = insertRegistro(registro)
        val itemsConId = items.map { it.copy(registroId = id.toInt()) }
        insertItems(itemsConId)
        return id.toInt()
    }
    
    @Delete
    suspend fun delete(registro: RegistroComida)
    
    @Query("DELETE FROM registro_comida WHERE id = :id")
    suspend fun deleteById(id: Int)
    
    @Query("DELETE FROM registro_comida")
    suspend fun deleteAll()

    @Query("SELECT * FROM registro_comida")
    suspend fun getAllRegistrosRaw(): List<RegistroComida>

    @Query("SELECT * FROM alimento_en_registro")
    suspend fun getAllItemsRaw(): List<AlimentoEnRegistro>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: AlimentoEnRegistro)

    @Query("""
        UPDATE registro_comida
        SET glucosaDespues2hMgdl = :glucosa
        WHERE id = :registroId AND glucosaDespues2hMgdl IS NULL
    """)
    suspend fun updateGlucosaDespues2h(registroId: Int, glucosa: Int)

    @Query("""
        UPDATE registro_comida
        SET glucosaAntesMgdl = :glucosa
        WHERE id = :registroId AND glucosaAntesMgdl IS NULL
    """)
    suspend fun updateGlucosaAntes(registroId: Int, glucosa: Int)

    @Query(
        """
        UPDATE registro_comida
        SET dosisEstado = :estado,
            dosisConfirmadaAt = CASE
                WHEN :estado = 'applied' THEN :confirmadaAt
                ELSE NULL
            END
        WHERE id = :registroId
    """
    )
    suspend fun updateDosisEstado(
        registroId: Int,
        estado: String,
        confirmadaAt: Long?
    )

    @Query("SELECT IFNULL(SUM(hidratosTotales), 0) FROM registro_comida WHERE fecha BETWEEN :start AND :end")
    suspend fun sumHidratosInRange(start: Long, end: Long): Float

    @Query("SELECT IFNULL(SUM(racionesCalculadas), 0) FROM registro_comida WHERE fecha BETWEEN :start AND :end")
    suspend fun sumRacionesInRange(start: Long, end: Long): Float

    @Query("SELECT IFNULL(SUM(unidadesInsulina), 0) FROM registro_comida WHERE fecha BETWEEN :start AND :end")
    suspend fun sumInsulinaInRange(start: Long, end: Long): Float
    
    // Métodos para el historial filtrado
    @Transaction
    @Query("""
        SELECT DISTINCT r.* FROM registro_comida r
        LEFT JOIN alimento_en_registro i ON r.id = i.registroId
        LEFT JOIN alimentos a ON i.alimentoId = a.id
        WHERE a.nombre LIKE '%' || :query || '%' OR r.notas LIKE '%' || :query || '%'
        ORDER BY r.fecha DESC
    """)
    fun search(query: String): Flow<List<RegistroComidaConItems>>
}
