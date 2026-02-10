package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad que representa un alimento específico dentro de un registro de comida.
 * Permite que una comida esté compuesta por varios alimentos.
 *
 * @property id Identificador único
 * @property registroId ID del registro de comida al que pertenece
 * @property alimentoId ID del alimento seleccionado
 * @property gramosConsumidos Cantidad consumida de este alimento
 * @property hidratosCalculados Hidratos aportados por este alimento en esta cantidad
 */
@Entity(
    tableName = "alimento_en_registro",
    foreignKeys = [
        ForeignKey(
            entity = RegistroComida::class,
            parentColumns = ["id"],
            childColumns = ["registroId"],
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
        Index(value = ["registroId"]),
        Index(value = ["alimentoId"])
    ]
)
@Serializable
data class AlimentoEnRegistro(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val registroId: Int,
    val alimentoId: Int,
    val gramosConsumidos: Float,
    val hidratosCalculados: Float
)
