package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.PlantillaConItems
import com.diabetes.calculator.data.dao.PlantillaDao
import com.diabetes.calculator.data.entity.PlantillaComida
import com.diabetes.calculator.data.entity.PlantillaItem
import kotlinx.coroutines.flow.Flow

class PlantillaRepository(private val dao: PlantillaDao) {

    val plantillas: Flow<List<PlantillaConItems>> = dao.getAllPlantillas()

    suspend fun insertPlantilla(nombre: String, items: List<PlantillaItem>): Int {
        val plantilla = PlantillaComida(nombre = nombre.trim())
        return dao.insertPlantillaCompleta(plantilla, items)
    }

    suspend fun deletePlantilla(id: Int) = dao.deletePlantilla(id)
}
