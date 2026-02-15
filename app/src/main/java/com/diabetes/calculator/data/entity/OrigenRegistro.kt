package com.diabetes.calculator.data.entity

enum class OrigenRegistro(val value: String) {
    LOCAL("LOCAL"),
    NIGHTSCOUT_IMPORT("NIGHTSCOUT_IMPORT");

    companion object {
        fun fromValue(value: String?): OrigenRegistro {
            return entries.firstOrNull { it.value == value } ?: LOCAL
        }
    }
}
