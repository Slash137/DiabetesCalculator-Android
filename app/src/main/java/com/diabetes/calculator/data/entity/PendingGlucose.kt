package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Tarea pendiente de sincronización de glucosa con Nightscout.
 */
@Entity(
    tableName = "pending_glucose",
    indices = [
        Index(value = ["registroId"]),
        Index(value = ["tipo"])
    ]
)
@Serializable
data class PendingGlucose(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val registroId: Int,
    val tipo: String,
    val targetMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null
)

object PendingGlucoseTipo {
    const val ANTES = "ANTES"
    const val DESPUES_2H = "DESPUES_2H"
}
