package com.diabetes.calculator.data.model

import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.PlantillaComida
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.UsuarioProfile
import kotlinx.serialization.Serializable

/**
 * Modelo de datos para la exportación e importación (copia de seguridad).
 * Contiene todo el estado de la aplicación.
 */
@Serializable
data class BackupData(
    val perfil: UsuarioProfile?,
    val alimentos: List<Alimento>,
    val registros: List<RegistroComida>,
    val items: List<AlimentoEnRegistro> = emptyList(),
    val plantillas: List<PlantillaComida> = emptyList(),
    val plantillaItems: List<PlantillaItem> = emptyList()
)
