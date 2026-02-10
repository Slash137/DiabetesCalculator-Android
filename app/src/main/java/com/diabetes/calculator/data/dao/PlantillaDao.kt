package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.PlantillaComida
import com.diabetes.calculator.data.entity.PlantillaItem
import kotlinx.coroutines.flow.Flow

data class PlantillaItemConAlimento(
    @Embedded val item: PlantillaItem,
    @Relation(
        parentColumn = "alimentoId",
        entityColumn = "id"
    )
    val alimento: Alimento
)

data class PlantillaConItems(
    @Embedded val plantilla: PlantillaComida,
    @Relation(
        entity = PlantillaItem::class,
        parentColumn = "id",
        entityColumn = "plantillaId"
    )
    val items: List<PlantillaItemConAlimento>
)

@Dao
interface PlantillaDao {
    @Transaction
    @Query("SELECT * FROM plantilla_comida ORDER BY fechaCreacion DESC")
    fun getAllPlantillas(): Flow<List<PlantillaConItems>>

    @Query("SELECT * FROM plantilla_comida")
    suspend fun getAllRaw(): List<PlantillaComida>

    @Query("SELECT * FROM plantilla_item")
    suspend fun getAllItemsRaw(): List<PlantillaItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlantilla(plantilla: PlantillaComida): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PlantillaItem>)

    @Transaction
    suspend fun insertPlantillaCompleta(
        plantilla: PlantillaComida,
        items: List<PlantillaItem>
    ): Int {
        val plantillaId = insertPlantilla(plantilla).toInt()
        val itemsConId = items.map { it.copy(plantillaId = plantillaId) }
        insertItems(itemsConId)
        return plantillaId
    }

    @Query("DELETE FROM plantilla_comida WHERE id = :id")
    suspend fun deletePlantilla(id: Int)

    @Query("DELETE FROM plantilla_item WHERE plantillaId = :plantillaId")
    suspend fun deleteItemsByPlantilla(plantillaId: Int)
}
