package com.diabetes.calculator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NightscoutRawEntry(
    @SerialName("_id")
    val id: String? = null,
    val date: JsonElement? = null,
    val dateString: String? = null,
    val type: String? = null,
    val eventType: String? = null,
    val insulin: JsonElement? = null,
    val insulinInjections: JsonElement? = null,
    val insulinType: String? = null,
    val notes: String? = null
)
