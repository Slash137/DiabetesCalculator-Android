package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.dao.RegistroComidaDao
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.domain.ACTIVE_INSULIN_DURATION_MINUTES
import com.diabetes.calculator.domain.ActiveInsulinCalculator
import com.diabetes.calculator.domain.ActiveInsulinDoseEvent
import com.diabetes.calculator.domain.ActiveInsulinSnapshot
import com.diabetes.calculator.domain.LocalInjectionCandidate
import com.diabetes.calculator.domain.NightscoutReconciliation
import com.diabetes.calculator.domain.RemoteInjectionCandidate
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow

internal fun mergeHybridIobCandidates(
    localCandidates: List<LocalInjectionCandidate>,
    remoteCandidates: List<RemoteInjectionCandidate>,
    maxDeltaMinutes: Int = 10,
    maxDeltaUnits: Float = 0.3f
): List<ActiveInsulinDoseEvent> {
    if (remoteCandidates.isEmpty()) {
        return localCandidates.map {
            ActiveInsulinDoseEvent(
                units = it.units,
                eventMillis = it.timestampMillis
            )
        }
    }
    val reconcile = NightscoutReconciliation.reconcile(
        locals = localCandidates,
        remotes = remoteCandidates,
        maxDeltaMinutes = maxDeltaMinutes,
        maxDeltaUnits = maxDeltaUnits
    )
    val authoritativeRemotes = buildList {
        addAll(reconcile.matches.map { it.remote })
        addAll(reconcile.unmatchedRemotes)
    }.distinctBy { it.treatmentId }
    val provisionalLocals = reconcile.unmatchedLocals

    return buildList {
        authoritativeRemotes.forEach { remote ->
            add(
                ActiveInsulinDoseEvent(
                    units = remote.units,
                    eventMillis = remote.timestampMillis
                )
            )
        }
        provisionalLocals.forEach { local ->
            add(
                ActiveInsulinDoseEvent(
                    units = local.units,
                    eventMillis = local.timestampMillis
                )
            )
        }
    }
}

/**
 * Repositorio para la gestión de registros de comida.
 * Soporta registros compuestos (múltiples alimentos).
 */
class RegistroComidaRepository(private val dao: RegistroComidaDao) {

    /**
     * Obtiene todos los registros con sus items.
     */
    val allRegistros: Flow<List<RegistroComidaConItems>> = dao.getAllWithItems()
    val nightscoutImportCount: Flow<Int> = dao.observeCountByOrigen(OrigenRegistro.NIGHTSCOUT_IMPORT.value)

    /**
     * Busca registros por texto (nombre de alimento o notas).
     */
    fun searchRegistros(query: String): Flow<List<RegistroComidaConItems>> = dao.search(query)

    /**
     * Inserta un registro de comida completo.
     */
    suspend fun insertRegistroCompleto(registro: RegistroComida, items: List<AlimentoEnRegistro>): Int {
        return dao.insertRegistroCompleto(registro, items)
    }

    suspend fun insertRegistro(registro: RegistroComida): Int {
        return dao.insertRegistro(registro).toInt()
    }

    /**
     * Actualiza la glucosa 2h después en un registro.
     */
    suspend fun updateGlucosaDespues2h(registroId: Int, glucosa: Int) {
        dao.updateGlucosaDespues2h(registroId, glucosa)
    }

    /**
     * Actualiza la glucosa antes en un registro.
     */
    suspend fun updateGlucosaAntes(registroId: Int, glucosa: Int) {
        dao.updateGlucosaAntes(registroId, glucosa)
    }

    suspend fun updateDosisEstado(
        registroId: Int,
        estado: EstadoDosis,
        confirmadaAt: Long? = if (estado == EstadoDosis.APLICADA) System.currentTimeMillis() else null
    ) {
        dao.updateDosisEstado(
            registroId = registroId,
            estado = estado.value,
            confirmadaAt = confirmadaAt
        )
    }

    suspend fun updateDosisCorreccion(
        registroId: Int,
        conCorreccion: Boolean?
    ) {
        dao.updateDosisCorreccion(registroId, conCorreccion)
    }

    suspend fun sumHidratosInRange(start: Long, end: Long): Float =
        dao.sumHidratosInRange(start, end)

    suspend fun sumRacionesInRange(start: Long, end: Long): Float =
        dao.sumRacionesInRange(start, end)

    suspend fun sumInsulinaInRange(start: Long, end: Long): Float =
        dao.sumInsulinaInRange(start, end)

    suspend fun getAppliedDosesInWindow(
        fromMillis: Long,
        toMillis: Long
    ): List<RegistroComida> = dao.getAppliedDosesInWindow(fromMillis, toMillis)

    suspend fun getActiveInsulinSnapshot(
        nowMillis: Long,
        nightscoutRepository: NightscoutRepository? = null,
        nightscoutUrl: String? = null,
        nightscoutToken: String? = null
    ): ActiveInsulinSnapshot {
        val fromMillis = nowMillis - (ACTIVE_INSULIN_DURATION_MINUTES * 60_000L)
        val localDoses = dao.getAppliedDosesInWindow(fromMillis, nowMillis)
        val localEvents = localDoses.mapNotNull { registro ->
            val units = resolveIobLocalDoseUnits(registro)
            if (!units.isFinite() || units <= 0f) return@mapNotNull null
            ActiveInsulinDoseEvent(
                units = units,
                eventMillis = registro.dosisConfirmadaAt ?: registro.fecha
            )
        }

        val baseUrl = nightscoutUrl?.trim().orEmpty()
        if (nightscoutRepository == null || baseUrl.isBlank()) {
            return ActiveInsulinCalculator.calculateFromEvents(localEvents, nowMillis)
        }

        val remoteCandidates = fetchRemoteCandidates(
            nightscoutRepository = nightscoutRepository,
            nightscoutUrl = baseUrl,
            nightscoutToken = nightscoutToken,
            fromMillis = fromMillis,
            toMillis = nowMillis
        )
        if (remoteCandidates.isEmpty()) {
            return ActiveInsulinCalculator.calculateFromEvents(localEvents, nowMillis)
        }

        val localCandidates = localDoses.mapNotNull { registro ->
            val units = resolveIobLocalDoseUnits(registro)
            if (!units.isFinite() || units <= 0f) return@mapNotNull null
            LocalInjectionCandidate(
                registroId = registro.id,
                timestampMillis = registro.dosisConfirmadaAt ?: registro.fecha,
                units = units,
                dcid = registro.nightscoutSyncDcid
            )
        }

        val mergedHybridEvents = mergeHybridIobCandidates(
            localCandidates = localCandidates,
            remoteCandidates = remoteCandidates,
            maxDeltaMinutes = HYBRID_IOB_MATCH_DELTA_MINUTES,
            maxDeltaUnits = HYBRID_IOB_MATCH_DELTA_UNITS
        )
        return ActiveInsulinCalculator.calculateFromEvents(mergedHybridEvents, nowMillis)
    }

    suspend fun getRegistroRawById(id: Int): RegistroComida? = dao.getRegistroRawById(id)

    suspend fun getByNightscoutTreatmentId(treatmentId: String): RegistroComida? =
        dao.getByNightscoutTreatmentId(treatmentId)

    suspend fun getRegistrosInRangeRaw(from: Long, to: Long): List<RegistroComida> =
        dao.getRegistrosInRangeRaw(from, to)

    suspend fun updateNightscoutLink(
        registroId: Int,
        treatmentId: String?,
        unidadesInsulinaRemota: Float?,
        reconciliadoAt: Long?,
        dcid: String?
    ): Int {
        return dao.updateNightscoutLink(
            registroId = registroId,
            treatmentId = treatmentId,
            unidadesInsulinaRemota = unidadesInsulinaRemota,
            reconciliadoAt = reconciliadoAt,
            dcid = dcid
        )
    }

    suspend fun updateNightscoutSyncDcid(
        registroId: Int,
        dcid: String?
    ) {
        dao.updateNightscoutSyncDcid(registroId, dcid)
    }

    suspend fun clearNightscoutLink(registroId: Int) {
        dao.clearNightscoutLink(registroId)
    }

    suspend fun updateDoseForLink(
        registroId: Int,
        unidades: Float,
        confirmadaAt: Long?
    ) = dao.updateDoseForLink(registroId, unidades, confirmadaAt)

    /**
     * Elimina un registro.
     */
    suspend fun delete(registro: RegistroComida) = dao.delete(registro)

    /**
     * Elimina un registro por ID.
     */
    suspend fun deleteById(id: Int) = dao.deleteById(id)

    /**
     * Elimina todos los registros.
     */
    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Obtiene registros raw para backup.
     */
    suspend fun getAllRegistrosRaw(): List<RegistroComida> = dao.getAllRegistrosRaw()

    private suspend fun fetchRemoteCandidates(
        nightscoutRepository: NightscoutRepository,
        nightscoutUrl: String,
        nightscoutToken: String?,
        fromMillis: Long,
        toMillis: Long
    ): List<RemoteInjectionCandidate> {
        val treatments = nightscoutRepository.getTreatmentsInRangeAll(
            baseUrl = nightscoutUrl,
            token = nightscoutToken,
            fromMillis = fromMillis,
            toMillis = toMillis
        ).mapNotNull { treatment ->
            val timestamp = nightscoutRepository.resolveTreatmentMillis(treatment)
                ?: return@mapNotNull null
            val units = nightscoutRepository.resolveTreatmentInsulinUnits(treatment)
                ?: return@mapNotNull null
            if (!units.isFinite() || units <= 0f || timestamp !in fromMillis..toMillis) return@mapNotNull null
            val id = treatment.id?.takeIf { it.isNotBlank() }
                ?: "treat:${timestamp}:${String.format("%.2f", units)}"
            RemoteInjectionCandidate(
                treatmentId = id,
                timestampMillis = timestamp,
                units = units
            )
        }

        val rawEntries = nightscoutRepository.getFastInsulinEntriesInRangeAll(
            baseUrl = nightscoutUrl,
            token = nightscoutToken,
            fromMillis = fromMillis,
            toMillis = toMillis
        ).mapNotNull { entry ->
            val timestamp = nightscoutRepository.resolveEntryMillis(entry) ?: return@mapNotNull null
            val units = nightscoutRepository.resolveEntryInsulinUnits(entry) ?: return@mapNotNull null
            if (!units.isFinite() || units <= 0f || timestamp !in fromMillis..toMillis) return@mapNotNull null
            val id = entry.id?.takeIf { it.isNotBlank() }
                ?: "entry:${timestamp}:${String.format("%.2f", units)}"
            RemoteInjectionCandidate(
                treatmentId = id,
                timestampMillis = timestamp,
                units = units
            )
        }

        if (treatments.isEmpty()) return rawEntries.distinctBy { it.treatmentId }
        val merged = treatments.toMutableList()
        rawEntries.forEach { raw ->
            val duplicate = treatments.any { treatment ->
                abs(treatment.timestampMillis - raw.timestampMillis) <= (HYBRID_IOB_MATCH_DELTA_MINUTES * 60_000L) &&
                    abs(treatment.units - raw.units) <= HYBRID_IOB_MATCH_DELTA_UNITS
            }
            if (!duplicate) merged += raw
        }
        return merged.distinctBy { it.treatmentId }
    }

    private fun resolveIobLocalDoseUnits(registro: RegistroComida): Float {
        return registro.unidadesInsulina
            .takeIf { it.isFinite() && it > 0f }
            ?: 0f
    }

    companion object {
        private const val HYBRID_IOB_MATCH_DELTA_MINUTES = 10
        private const val HYBRID_IOB_MATCH_DELTA_UNITS = 0.3f
    }
}
