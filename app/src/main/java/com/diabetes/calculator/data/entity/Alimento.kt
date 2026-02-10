package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad que representa un alimento con su contenido en hidratos de carbono.
 *
 * @property id Identificador único del alimento
 * @property nombre Nombre descriptivo del alimento (ej: "Arroz blanco hervido")
 * @property hidratosPor100g Gramos de hidratos de carbono por cada 100g del alimento
 * @property fuente Origen de la información nutricional ("librito", "manual", "personal")
 * @property nota Nota opcional (ej: "peso cocido aprox.")
 */
@Serializable
@Entity(tableName = "alimentos")
data class Alimento(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val nombre: String,
    val hidratosPor100g: Float,
    val fuente: String,
    val nota: String? = null
)
