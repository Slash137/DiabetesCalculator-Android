package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad que representa un registro de comida consumida.
 * Guarda el cálculo realizado para una comida específica.
 *
 * @property id Identificador único del registro
 * @property alimentoId Referencia al alimento consumido (FK)
 * @property gramosConsumidos Cantidad en gramos del alimento consumido
 * @property hidratosTotales Hidratos de carbono totales calculados
 * @property racionesCalculadas Número de raciones de hidratos
 * @property unidadesInsulina Unidades de insulina rápida calculadas (redondeadas a 0.5)
 * @property ratioInsulinaHc Relación de insulina por gramo de HC usada en el cálculo (U/g)
 * @property fecha Timestamp del registro
 * @property glucosaAntesMgdl Glucosa medida al guardar el registro (mg/dL)
 * @property glucosaDespues2hMgdl Glucosa medida 2h después (mg/dL)
 * @property dosisEstado Estado de aplicación de la dosis recomendada
 * @property dosisConCorreccion Si la dosis aplicada se hizo con corrección por glucosa en tiempo real
 * @property unidadesCorreccionSugerida Unidades de corrección sugeridas por glucosa al crear el registro
 * @property factorCorreccionMgdlPorUUsado Factor de corrección usado al crear el registro (mg/dL por U)
 * @property dosisConfirmadaAt Timestamp real de aplicación si se confirmó
 */
@Entity(tableName = "registro_comida")
@Serializable
data class RegistroComida(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val hidratosTotales: Float,
    val racionesCalculadas: Float,
    val unidadesInsulina: Float,
    val ratioInsulinaHc: Float? = null,
    val fecha: Long = System.currentTimeMillis(),
    val notas: String? = null,
    val glucosaAntesMgdl: Int? = null,
    val glucosaDespues2hMgdl: Int? = null,
    val dosisEstado: String = EstadoDosis.PENDIENTE.value,
    val dosisConCorreccion: Boolean? = null,
    val unidadesCorreccionSugerida: Float? = null,
    val factorCorreccionMgdlPorUUsado: Float? = null,
    val dosisConfirmadaAt: Long? = null
)
