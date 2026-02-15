package com.diabetes.calculator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NightscoutTreatment(
    @SerialName("_id")
    val id: String? = null,
    val eventType: String? = null,
    val insulin: JsonElement? = null,
    val carbs: Float? = null,
    val notes: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val timestamp: String? = null,
    val mills: JsonElement? = null,
    val date: JsonElement? = null,
    val insulinInjections: JsonElement? = null,
    val enteredBy: String? = null
)

@Serializable
data class NightscoutCreateTreatmentRequest(
    val eventType: String,
    val insulin: Float,
    val carbs: Float? = null,
    @SerialName("created_at")
    val createdAt: String,
    val notes: String? = null,
    val enteredBy: String = "DiabetesCalculator"
)
