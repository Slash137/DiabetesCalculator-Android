package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Item de una plantilla de comida.
 */
@Entity(
    tableName = "plantilla_item",
    foreignKeys = [
        ForeignKey(
            entity = PlantillaComida::class,
            parentColumns = ["id"],
            childColumns = ["plantillaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Alimento::class,
            parentColumns = ["id"],
            childColumns = ["alimentoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["plantillaId"]),
        Index(value = ["alimentoId"])
    ]
)
@Serializable
data class PlantillaItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val plantillaId: Int,
    val alimentoId: Int,
    val gramos: Float,
    val cantidad: Float = 0f,
    val unidad: String = UnidadConsumoAlimento.GRAMOS
)
