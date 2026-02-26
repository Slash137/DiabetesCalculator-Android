package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Entity(
    tableName = "libreview_record_catalog",
    primaryKeys = ["channel", "recordNumber"],
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["sourceRegistroId"]),
        Index(value = ["channel", "updatedAt"])
    ]
)
@Serializable
data class LibreviewRecordCatalog(
    val channel: String,
    val recordNumber: Long,
    val sourceRegistroId: Int? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastOperation: String? = null,
    val payloadHash: String? = null
)

