package com.diabetes.calculator.data.dao

import androidx.room.*
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.domain.LibreviewPayloadBuilder
import com.diabetes.calculator.domain.LibreviewPayloadOperation
import com.diabetes.calculator.domain.LibreviewRecordNumber
import kotlinx.coroutines.flow.Flow

/**
 * Clase para representar un registro completo con todos sus alimentos.
 */
data class RegistroComidaConItems(
    @Embedded val registro: RegistroComida,
    @Relation(
        entity = AlimentoEnRegistro::class,
        parentColumn = "id",
        entityColumn = "registroId"
    )
    val items: List<ItemConAlimento>
)

data class ItemConAlimento(
    @Embedded val item: AlimentoEnRegistro,
    @Relation(
        parentColumn = "alimentoId",
        entityColumn = "id"
    )
    val alimento: Alimento
)

data class LegacyLibreviewDeleteLink(
    val channel: RegistroLibreviewSyncChannel,
    val recordNumber: Long,
    val eventTimestampMillis: Long,
    val payloadHash: String?
)

data class NfcCanonicalizationResult(
    val updatedRegistro: RegistroComida,
    val invalidatedNightscoutTreatmentId: String?,
    val legacyDeletes: List<LegacyLibreviewDeleteLink>
)

@Dao
interface RegistroComidaDao {
    
    @Transaction
    @Query("SELECT * FROM registro_comida ORDER BY fecha DESC")
    fun getAllWithItems(): Flow<List<RegistroComidaConItems>>
    
    @Transaction
    @Query("SELECT * FROM registro_comida WHERE id = :id")
    suspend fun getById(id: Int): RegistroComidaConItems?

    @Query("SELECT * FROM registro_comida WHERE id = :id")
    suspend fun getRegistroRawById(id: Int): RegistroComida?

    @Query("SELECT * FROM registro_comida WHERE nightscoutTreatmentId = :treatmentId LIMIT 1")
    suspend fun getByNightscoutTreatmentId(treatmentId: String): RegistroComida?

    @Query("SELECT * FROM registro_comida WHERE nightscoutSyncDcid = :dcid LIMIT 1")
    suspend fun getByNightscoutSyncDcid(dcid: String): RegistroComida?

    @Query("SELECT * FROM registro_comida WHERE fecha BETWEEN :from AND :to ORDER BY fecha ASC")
    suspend fun getRegistrosInRangeRaw(from: Long, to: Long): List<RegistroComida>

    @Query(
        """
        SELECT MIN(COALESCE(dosisConfirmadaAt, fecha))
        FROM registro_comida
        WHERE (unidadesInsulina > 0 OR hidratosTotales > 0)
        """
    )
    suspend fun getOldestUploadableTimestamp(): Long?

    @Query(
        """
        SELECT MIN(COALESCE(dosisConfirmadaAt, fecha))
        FROM registro_comida
        WHERE origenRegistro = 'LOCAL'
          AND (
              (hidratosTotales > 0 AND libreviewCarbsRecordNumber IS NULL)
              OR
              (dosisEstado = 'applied' AND unidadesInsulina > 0 AND libreviewInsulinRecordNumber IS NULL)
          )
        """
    )
    suspend fun getOldestPendingLibreviewTimestamp(): Long?

    @Query(
        """
        SELECT *
        FROM registro_comida
        WHERE dosisEstado = 'applied'
          AND COALESCE(dosisConfirmadaAt, fecha) BETWEEN :fromMillis AND :toMillis
        ORDER BY COALESCE(dosisConfirmadaAt, fecha) DESC
        """
    )
    suspend fun getAppliedDosesInWindow(
        fromMillis: Long,
        toMillis: Long
    ): List<RegistroComida>

    @Query(
        """
        SELECT *
        FROM registro_comida
        WHERE dosisEstado = 'applied'
          AND (
                origenRegistro = 'NIGHTSCOUT_IMPORT'
                OR (nightscoutTreatmentId IS NOT NULL AND nightscoutTreatmentId != '')
          )
          AND COALESCE(dosisConfirmadaAt, fecha) BETWEEN :fromMillis AND :toMillis
        ORDER BY COALESCE(dosisConfirmadaAt, fecha) DESC
        """
    )
    suspend fun getReliableAppliedDosesInWindow(
        fromMillis: Long,
        toMillis: Long
    ): List<RegistroComida>
    
    @Insert
    suspend fun insertRegistro(registro: RegistroComida): Long
    
    @Insert
    suspend fun insertItems(items: List<AlimentoEnRegistro>)
    
    @Transaction
    suspend fun insertRegistroCompleto(registro: RegistroComida, items: List<AlimentoEnRegistro>): Int {
        val id = insertRegistro(registro)
        val itemsConId = items.map { it.copy(registroId = id.toInt()) }
        insertItems(itemsConId)
        return id.toInt()
    }
    
    @Delete
    suspend fun delete(registro: RegistroComida)
    
    @Query("DELETE FROM registro_comida WHERE id = :id")
    suspend fun deleteById(id: Int)
    
    @Query("DELETE FROM registro_comida")
    suspend fun deleteAll()

    @Query("SELECT * FROM registro_comida")
    suspend fun getAllRegistrosRaw(): List<RegistroComida>

    @Query(
        """
        UPDATE registro_comida
        SET nightscoutTreatmentId = :treatmentId,
            unidadesInsulinaRemota = :unidadesInsulinaRemota,
            nightscoutReconciliadoAt = :reconciliadoAt,
            nightscoutSyncDcid = :dcid
        WHERE id = :registroId
          AND (
            :treatmentId IS NULL OR NOT EXISTS (
                SELECT 1
                FROM registro_comida other
                WHERE other.nightscoutTreatmentId = :treatmentId
                  AND other.id != :registroId
            )
          )
        """
    )
    suspend fun updateNightscoutLink(
        registroId: Int,
        treatmentId: String?,
        unidadesInsulinaRemota: Float?,
        reconciliadoAt: Long?,
        dcid: String?
    ): Int

    @Query(
        """
        UPDATE registro_comida
        SET nightscoutSyncDcid = :dcid
        WHERE id = :registroId
        """
    )
    suspend fun updateNightscoutSyncDcid(
        registroId: Int,
        dcid: String?
    )

    @Query(
        """
        UPDATE registro_comida
        SET nightscoutTreatmentId = NULL,
            unidadesInsulinaRemota = NULL,
            nightscoutReconciliadoAt = NULL
        WHERE id = :registroId
        """
    )
    suspend fun clearNightscoutLink(registroId: Int)

    @Query(
        """
        UPDATE registro_comida
        SET libreviewCarbsRecordNumber = :recordNumber,
            libreviewCarbsPayloadHash = :payloadHash,
            libreviewReconciliadoAt = :reconciliadoAt
        WHERE id = :registroId
        """
    )
    suspend fun updateLibreviewCarbsLink(
        registroId: Int,
        recordNumber: Long,
        payloadHash: String?,
        reconciliadoAt: Long?
    )

    @Query(
        """
        UPDATE registro_comida
        SET libreviewInsulinRecordNumber = :recordNumber,
            libreviewInsulinPayloadHash = :payloadHash,
            libreviewReconciliadoAt = :reconciliadoAt
        WHERE id = :registroId
        """
    )
    suspend fun updateLibreviewInsulinLink(
        registroId: Int,
        recordNumber: Long,
        payloadHash: String?,
        reconciliadoAt: Long?
    )

    @Query(
        """
        UPDATE registro_comida
        SET libreviewCarbsRecordNumber = NULL,
            libreviewCarbsPayloadHash = NULL,
            libreviewReconciliadoAt = :reconciliadoAt
        WHERE id = :registroId
        """
    )
    suspend fun clearLibreviewCarbsLink(
        registroId: Int,
        reconciliadoAt: Long? = null
    )

    @Query(
        """
        UPDATE registro_comida
        SET libreviewInsulinRecordNumber = NULL,
            libreviewInsulinPayloadHash = NULL,
            libreviewReconciliadoAt = :reconciliadoAt
        WHERE id = :registroId
        """
    )
    suspend fun clearLibreviewInsulinLink(
        registroId: Int,
        reconciliadoAt: Long? = null
    )

    @Query("SELECT * FROM alimento_en_registro")
    suspend fun getAllItemsRaw(): List<AlimentoEnRegistro>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: AlimentoEnRegistro)

    @Query("""
        UPDATE registro_comida
        SET glucosaDespues2hMgdl = :glucosa
        WHERE id = :registroId AND glucosaDespues2hMgdl IS NULL
    """)
    suspend fun updateGlucosaDespues2h(registroId: Int, glucosa: Int)

    @Query("""
        UPDATE registro_comida
        SET glucosaAntesMgdl = :glucosa
        WHERE id = :registroId AND glucosaAntesMgdl IS NULL
    """)
    suspend fun updateGlucosaAntes(registroId: Int, glucosa: Int)

    @Query(
        """
        UPDATE registro_comida
        SET dosisEstado = :estado,
            dosisConCorreccion = CASE
                WHEN :estado = 'applied' THEN dosisConCorreccion
                ELSE NULL
            END,
            dosisConfirmadaAt = CASE
                WHEN :estado = 'applied' THEN :confirmadaAt
                ELSE NULL
            END
        WHERE id = :registroId
    """
    )
    suspend fun updateDosisEstado(
        registroId: Int,
        estado: String,
        confirmadaAt: Long?
    )

    @Query(
        """
        UPDATE registro_comida
        SET dosisConCorreccion = CASE
            WHEN dosisEstado = 'applied' THEN :conCorreccion
            ELSE NULL
        END
        WHERE id = :registroId
    """
    )
    suspend fun updateDosisCorreccion(
        registroId: Int,
        conCorreccion: Boolean?
    )

    @Query("""
        UPDATE registro_comida
        SET unidadesInsulina = :unidades,
            dosisConfirmadaAt = :confirmadaAt,
            nightscoutTreatmentId = NULL,
            unidadesInsulinaRemota = NULL,
            nightscoutReconciliadoAt = NULL,
            nightscoutSyncDcid = NULL,
            libreviewInsulinRecordNumber = NULL,
            libreviewInsulinPayloadHash = NULL
        WHERE id = :registroId
    """)
    suspend fun updateDoseForLink(
        registroId: Int,
        unidades: Float,
        confirmadaAt: Long?
    )

    @Query(
        """
        UPDATE registro_comida
        SET unidadesInsulina = :unidades,
            dosisEstado = 'applied',
            dosisConfirmadaAt = :confirmadaAt,
            nightscoutTreatmentId = NULL,
            unidadesInsulinaRemota = NULL,
            nightscoutReconciliadoAt = NULL,
            nightscoutSyncDcid = :dcid
        WHERE id = :registroId
    """
    )
    suspend fun applyNfcDoseCanonicalization(
        registroId: Int,
        unidades: Float,
        confirmadaAt: Long,
        dcid: String
    ): Int

    @Transaction
    suspend fun canonicalizeLocalRegistroWithNfcDose(
        registroId: Int,
        unidades: Float,
        confirmadaAt: Long,
        dcid: String,
        now: Long = System.currentTimeMillis()
    ): NfcCanonicalizationResult? {
        val before = getRegistroRawById(registroId) ?: return null
        val invalidatedNightscoutTreatmentId = before.nightscoutTreatmentId
            ?.takeIf { it.isNotBlank() }

        val canonicalRecordNumber = LibreviewRecordNumber.from(
            registroId = registroId,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = confirmadaAt
        )
        val canonicalPayloadHash = LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
            operation = LibreviewPayloadOperation.UPSERT,
            recordNumber = canonicalRecordNumber,
            eventTimestampMillis = confirmadaAt,
            amountValue = unidades.coerceAtLeast(0f)
        )
        val legacyDeletes = mutableListOf<LegacyLibreviewDeleteLink>()
        before.libreviewInsulinRecordNumber?.let { linkedRecordNumber ->
            val linkedPayloadHash = before.libreviewInsulinPayloadHash
            val obsoleteLink = linkedRecordNumber != canonicalRecordNumber ||
                linkedPayloadHash != canonicalPayloadHash
            if (obsoleteLink) {
                legacyDeletes += LegacyLibreviewDeleteLink(
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                    recordNumber = linkedRecordNumber,
                    eventTimestampMillis = resolveLegacyInsulinEventTimestamp(before, linkedRecordNumber),
                    payloadHash = linkedPayloadHash
                )
            }
        }

        val updatedRows = applyNfcDoseCanonicalization(
            registroId = registroId,
            unidades = unidades,
            confirmadaAt = confirmadaAt,
            dcid = dcid
        )
        if (updatedRows <= 0) return null

        if (legacyDeletes.isNotEmpty()) {
            clearLibreviewInsulinLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }

        val updated = getRegistroRawById(registroId) ?: return null
        return NfcCanonicalizationResult(
            updatedRegistro = updated,
            invalidatedNightscoutTreatmentId = invalidatedNightscoutTreatmentId,
            legacyDeletes = legacyDeletes
        )
    }

    @Query("SELECT IFNULL(SUM(hidratosTotales), 0) FROM registro_comida WHERE fecha BETWEEN :start AND :end")
    suspend fun sumHidratosInRange(start: Long, end: Long): Float

    @Query("SELECT IFNULL(SUM(racionesCalculadas), 0) FROM registro_comida WHERE fecha BETWEEN :start AND :end")
    suspend fun sumRacionesInRange(start: Long, end: Long): Float

    @Query("SELECT IFNULL(SUM(unidadesInsulina), 0) FROM registro_comida WHERE fecha BETWEEN :start AND :end")
    suspend fun sumInsulinaInRange(start: Long, end: Long): Float

    @Query("SELECT COUNT(*) FROM registro_comida WHERE origenRegistro = :origen")
    fun observeCountByOrigen(origen: String): Flow<Int>
    
    // Métodos para el historial filtrado
    @Transaction
    @Query("""
        SELECT DISTINCT r.* FROM registro_comida r
        LEFT JOIN alimento_en_registro i ON r.id = i.registroId
        LEFT JOIN alimentos a ON i.alimentoId = a.id
        WHERE a.nombre LIKE '%' || :query || '%' OR r.notas LIKE '%' || :query || '%'
        ORDER BY r.fecha DESC
    """)
    fun search(query: String): Flow<List<RegistroComidaConItems>>
}

private fun resolveLegacyInsulinEventTimestamp(
    registro: RegistroComida,
    recordNumber: Long
): Long {
    val canonicalTimestamp = registro.dosisConfirmadaAt ?: registro.fecha
    val legacyTimestamp = registro.fecha
    if (canonicalTimestamp == legacyTimestamp) return canonicalTimestamp

    val canonicalRecordNumber = LibreviewRecordNumber.from(
        registroId = registro.id,
        channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
        effectiveTimestamp = canonicalTimestamp
    )
    if (recordNumber == canonicalRecordNumber) return canonicalTimestamp

    val legacyRecordNumber = LibreviewRecordNumber.from(
        registroId = registro.id,
        channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
        effectiveTimestamp = legacyTimestamp
    )
    if (recordNumber == legacyRecordNumber) return legacyTimestamp

    return canonicalTimestamp
}
