package com.diabetes.calculator.data.model

import kotlinx.serialization.Serializable

/**
 * Modelo para las entradas de glucosa de Nightscout (SGV - Sensor Glucose Value).
 */
@Serializable
data class NightscoutEntry(
    val _id: String,
    val sgv: Int,
    val date: Long,
    val dateString: String,
    val direction: String? = null, // "Flat", "FortyFiveUp", "SingleUp", etc.
    val type: String
)
