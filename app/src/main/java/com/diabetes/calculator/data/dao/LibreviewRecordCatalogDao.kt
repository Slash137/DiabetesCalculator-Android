package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diabetes.calculator.data.entity.LibreviewRecordCatalog
import kotlinx.coroutines.flow.Flow

data class LibreviewCatalogKey(
    val channel: String,
    val recordNumber: Long
)

@Dao
interface LibreviewRecordCatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibreviewRecordCatalog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibreviewRecordCatalog>)

    @Query(
        """
        SELECT channel, recordNumber
        FROM libreview_record_catalog
        """
    )
    suspend fun getAllKeys(): List<LibreviewCatalogKey>

    @Query(
        """
        SELECT channel, recordNumber
        FROM libreview_record_catalog
        WHERE updatedAt >= :sinceMillis
        """
    )
    suspend fun getKeysUpdatedSince(sinceMillis: Long): List<LibreviewCatalogKey>

    @Query(
        """
        SELECT c.channel, c.recordNumber
        FROM libreview_record_catalog c
        INNER JOIN registro_comida r
            ON r.id = c.sourceRegistroId
        WHERE r.origenRegistro IN ('LOCAL', 'NIGHTSCOUT_IMPORT')
        """
    )
    suspend fun getAttributableKeys(): List<LibreviewCatalogKey>

    @Query(
        """
        SELECT c.channel, c.recordNumber
        FROM libreview_record_catalog c
        INNER JOIN registro_comida r
            ON r.id = c.sourceRegistroId
        WHERE c.updatedAt >= :sinceMillis
          AND r.origenRegistro IN ('LOCAL', 'NIGHTSCOUT_IMPORT')
        """
    )
    suspend fun getAttributableKeysUpdatedSince(sinceMillis: Long): List<LibreviewCatalogKey>

    @Query(
        """
        SELECT *
        FROM libreview_record_catalog
        WHERE channel = :channel
        """
    )
    suspend fun getByChannel(channel: String): List<LibreviewRecordCatalog>

    @Query(
        """
        SELECT *
        FROM libreview_record_catalog
        ORDER BY updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<LibreviewRecordCatalog>>
}
