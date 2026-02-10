package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Plantilla de comida con un conjunto de alimentos predefinidos.
 */
@Entity(tableName = "plantilla_comida")
@Serializable
data class PlantillaComida(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val nombre: String,
    val fechaCreacion: Long = System.currentTimeMillis()
)
