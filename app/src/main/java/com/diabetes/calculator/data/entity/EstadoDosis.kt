package com.diabetes.calculator.data.entity

/**
 * Estado de administración de la dosis recomendada para un registro.
 */
enum class EstadoDosis(
    val value: String,
    val label: String
) {
    PENDIENTE("pending", "Pendiente"),
    APLICADA("applied", "Aplicada"),
    OMITIDA("skipped", "No aplicada");

    companion object {
        fun fromValue(value: String?): EstadoDosis {
            return values().firstOrNull { it.value == value } ?: PENDIENTE
        }
    }
}
