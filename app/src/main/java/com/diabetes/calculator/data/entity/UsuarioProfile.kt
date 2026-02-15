package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad que representa el perfil del usuario.
 * Almacena la configuración personal para los cálculos de insulina.
 *
 * @property id Identificador único del perfil
 * @property nombre Nombre del usuario
 * @property gramosPorRacion Gramos de hidratos de carbono que equivalen a 1 ración (ej: 10g = 1 ración)
 * @property ratioInsulina Unidades de insulina rápida por cada ración de hidratos
 * @property objetivoHidratosDia Objetivo diario de hidratos (g)
 * @property objetivoRacionesDia Objetivo diario de raciones
 * @property objetivoInsulinaDia Objetivo diario de insulina (U)
 * @property glucosaObjetivoMgdl Glucosa objetivo para corrección de dosis (mg/dL)
 * @property factorCorreccionMgdlPorU Cuántos mg/dL corrige 1U de insulina rápida
 * @property aplicarCorreccionPorDefecto Si la corrección por glucosa se aplica por defecto en nueva comida
 * @property recordatorio2hActivo Activar recordatorio manual a las 2 h
 * @property fechaCreacion Timestamp de creación del perfil
 */
@Entity(tableName = "usuario_profile")
@Serializable
data class UsuarioProfile(
    @PrimaryKey(autoGenerate = false)
    val id: Int = 1,
    val nombre: String,
    val gramosPorRacion: Float,
    val ratioInsulina: Float,
    val objetivoHidratosDia: Float? = null,
    val objetivoRacionesDia: Float? = null,
    val objetivoInsulinaDia: Float? = null,
    val glucosaObjetivoMgdl: Int? = null,
    val factorCorreccionMgdlPorU: Float? = null,
    val aplicarCorreccionPorDefecto: Boolean = true,
    val recordatorio2hActivo: Boolean = false,
    val nightscoutUrl: String? = null,
    val nightscoutToken: String? = null,
    val nightscoutSyncRegistrosActivo: Boolean = false,
    val nightscoutSyncBackfillDoneAt: Long? = null,
    val nightscoutLinkOffsetMinutes: Int = 15,
    val nightscoutLinkOffsetUnits: Float = 0.5f,
    val factorHoraMadrugada: Float = 1.0f,
    val factorHoraManana: Float = 1.0f,
    val factorHoraTarde: Float = 1.0f,
    val factorHoraNoche: Float = 1.0f,
    val factorEstresLeve: Float = 1.10f,
    val factorEstresModerado: Float = 1.20f,
    val factorEstresAlto: Float = 1.30f,
    val factorEnfermedadLeve: Float = 1.10f,
    val factorEnfermedadModerada: Float = 1.20f,
    val factorEnfermedadAlta: Float = 1.30f,
    val cicloHormonalActivo: Boolean = false,
    val factorCicloMenstruacion: Float = 0.95f,
    val factorCicloFolicular: Float = 1.00f,
    val factorCicloOvulacion: Float = 1.05f,
    val factorCicloLutea: Float = 1.15f,
    val factorEjercicioSuave: Float = 0.90f,
    val factorEjercicioModerado: Float = 0.80f,
    val factorEjercicioIntenso: Float = 0.70f,
    val fechaCreacion: Long = System.currentTimeMillis()
)
