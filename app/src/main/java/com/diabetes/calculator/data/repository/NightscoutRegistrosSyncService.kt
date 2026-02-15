package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroNightscoutSync
import com.diabetes.calculator.data.entity.RegistroNightscoutSyncStatus
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.model.NightscoutCreateTreatmentRequest
import com.diabetes.calculator.data.model.NightscoutRawEntry
import com.diabetes.calculator.data.model.NightscoutTreatment
import com.diabetes.calculator.domain.LocalInjectionCandidate
import com.diabetes.calculator.domain.NightscoutReconciliation
import com.diabetes.calculator.domain.RemoteInjectionCandidate
import com.diabetes.calculator.util.NightscoutRetryPolicy
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

data class NightscoutSyncRunResult(
    val processedPending: Int = 0,
    val failedPending: Int = 0,
    val maxFailedAttempts: Int = 0
)

class NightscoutRegistrosSyncService(
    private val registroRepository: RegistroComidaRepository,
    private val queueRepository: RegistroNightscoutSyncRepository,
    private val tombstoneRepository: NightscoutTreatmentTombstoneRepository,
    private val nightscoutRepository: NightscoutRepository
) {
    companion object {
        private const val GLUCOSE_TOLERANCE_MINUTES = 15
        private const val TWO_HOURS_MILLIS = 2L * 60L * 60L * 1000L
    }

    suspend fun sync(
        profile: UsuarioProfile,
        fromMillis: Long,
        toMillis: Long,
        ignoreTombstones: Boolean
    ): NightscoutSyncRunResult {
        val url = profile.nightscoutUrl?.trim().orEmpty()
        if (url.isBlank()) return NightscoutSyncRunResult()

        val now = System.currentTimeMillis()
        importAndReconcileRemote(
            profile = profile,
            fromMillis = fromMillis,
            toMillis = toMillis,
            ignoreTombstones = ignoreTombstones,
            now = now
        )

        return uploadPendingLocals(
            profile = profile,
            ignoreTombstones = ignoreTombstones,
            now = now
        )
    }

    private suspend fun importAndReconcileRemote(
        profile: UsuarioProfile,
        fromMillis: Long,
        toMillis: Long,
        ignoreTombstones: Boolean,
        now: Long
    ) {
        val url = profile.nightscoutUrl?.trim().orEmpty()
        val token = profile.nightscoutToken
        if (url.isBlank()) return

        val toleranceMillis = NightscoutReconciliation.MAX_DELTA_MINUTES * 60_000L
        val remotes = fetchRemoteCandidates(
            baseUrl = url,
            token = token,
            fromMillis = fromMillis,
            toMillis = toMillis
        )

        if (remotes.isEmpty()) return

        val localRegistros = registroRepository.getRegistrosInRangeRaw(
            from = fromMillis - toleranceMillis,
            to = toMillis + toleranceMillis
        )
        val localByTreatmentId = localRegistros
            .mapNotNull { registro ->
                registro.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { it to registro }
            }
            .toMap()

        val localCandidates = localRegistros
            .asSequence()
            .filter { it.nightscoutTreatmentId.isNullOrBlank() }
            .filter { it.origenRegistro == OrigenRegistro.LOCAL.value }
            .map {
                LocalInjectionCandidate(
                    registroId = it.id,
                    timestampMillis = resolveEffectiveTimestamp(it),
                    units = resolveFinalLocalDoseUnits(it),
                    dcid = it.nightscoutSyncDcid
                )
            }
            .toMutableList()

        val pendingRemotes = mutableListOf<RemoteInjectionCandidate>()
        for (remote in remotes) {
            val existing = localByTreatmentId[remote.treatmentId]
            if (existing != null) {
                fillMissingGlucoseFromNightscout(
                    registroId = existing.id,
                    remoteDoseMillis = remote.timestampMillis,
                    baseUrl = url,
                    token = token,
                    now = now
                )
                if (existing.origenRegistro != OrigenRegistro.NIGHTSCOUT_IMPORT.value) continue
            }
            if (!ignoreTombstones && tombstoneRepository.exists(remote.treatmentId)) continue
            pendingRemotes += remote
        }

        if (pendingRemotes.isEmpty()) return

        // Reconciliación directa por dcid antes de aplicar tolerancias.
        val localByDcid = localCandidates
            .mapNotNull { local -> local.dcid?.let { it to local } }
            .toMap()
        val dcidMatches = mutableListOf<Pair<LocalInjectionCandidate, RemoteInjectionCandidate>>()
        val remotesWithoutDcidMatch = mutableListOf<RemoteInjectionCandidate>()

        pendingRemotes.forEach { remote ->
            val local = remote.dcid?.let { localByDcid[it] }
            if (local != null) {
                dcidMatches += local to remote
            } else {
                remotesWithoutDcidMatch += remote
            }
        }

        val matchedLocalIdsByDcid = dcidMatches.map { it.first.registroId }.toSet()
        val localsForTolerance = localCandidates.filterNot { matchedLocalIdsByDcid.contains(it.registroId) }
        val reconcile = NightscoutReconciliation.reconcile(
            locals = localsForTolerance,
            remotes = remotesWithoutDcidMatch
        )

        dcidMatches.forEach { (local, remote) ->
            linkLocalWithRemote(
                registroId = local.registroId,
                remote = remote,
                reconciledAt = now,
                importedDuplicateId = localByTreatmentId[remote.treatmentId]
                    ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                    ?.id,
                baseUrl = url,
                token = token,
                now = now
            )
            queueRepository.markSyncedNoUpload(local.registroId, now)
        }

        reconcile.matches.forEach { match ->
            linkLocalWithRemote(
                registroId = match.local.registroId,
                remote = match.remote,
                reconciledAt = now,
                importedDuplicateId = localByTreatmentId[match.remote.treatmentId]
                    ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                    ?.id,
                baseUrl = url,
                token = token,
                now = now
            )
            queueRepository.markSyncedNoUpload(match.local.registroId, now)
        }

        reconcile.unmatchedRemotes.forEach { remote ->
            if (localByTreatmentId.containsKey(remote.treatmentId)) return@forEach
            importRemoteAsRegistro(
                remote = remote,
                now = now,
                baseUrl = url,
                token = token
            )
        }
    }

    private suspend fun uploadPendingLocals(
        profile: UsuarioProfile,
        ignoreTombstones: Boolean,
        now: Long
    ): NightscoutSyncRunResult {
        val url = profile.nightscoutUrl?.trim().orEmpty()
        val token = profile.nightscoutToken
        if (url.isBlank()) return NightscoutSyncRunResult()

        val pending = queueRepository.getPendingOrFailed()
        if (pending.isEmpty()) return NightscoutSyncRunResult()

        pending.forEach { item ->
            processQueueItem(
                item = item,
                baseUrl = url,
                token = token,
                ignoreTombstones = ignoreTombstones,
                now = now
            )
        }

        val stillPendingOrFailed = queueRepository.getPendingOrFailed()
        val failed = stillPendingOrFailed.filter {
            RegistroNightscoutSyncStatus.fromValue(it.status) == RegistroNightscoutSyncStatus.FAILED
        }
        return NightscoutSyncRunResult(
            processedPending = pending.size,
            failedPending = failed.size,
            maxFailedAttempts = failed.maxOfOrNull { it.attempts } ?: 0
        )
    }

    private suspend fun processQueueItem(
        item: RegistroNightscoutSync,
        baseUrl: String,
        token: String?,
        ignoreTombstones: Boolean,
        now: Long
    ) {
        val status = RegistroNightscoutSyncStatus.fromValue(item.status)
        if (status == RegistroNightscoutSyncStatus.FAILED) {
            val delayMillis = NightscoutRetryPolicy.nextDelayMinutes(item.attempts) * 60_000L
            if (now - item.updatedAt < delayMillis) return
        }

        val registro = registroRepository.getRegistroRawById(item.registroId)
        if (registro == null) {
            queueRepository.deleteByRegistroId(item.registroId)
            return
        }

        if (!registro.nightscoutTreatmentId.isNullOrBlank()) {
            queueRepository.markSyncedNoUpload(registro.id, now)
            return
        }

        if (registro.origenRegistro != OrigenRegistro.LOCAL.value) {
            queueRepository.markSyncedNoUpload(registro.id, now)
            return
        }

        val localFinalDose = resolveFinalLocalDoseUnits(registro)
        if (localFinalDose <= 0f) {
            queueRepository.markSyncedNoUpload(registro.id, now)
            return
        }

        try {
            val match = findRemoteMatchForLocal(
                local = registro,
                baseUrl = baseUrl,
                token = token,
                ignoreTombstones = ignoreTombstones
            )
            if (match != null) {
                linkLocalWithRemote(
                    registroId = registro.id,
                    remote = match,
                    reconciledAt = now,
                    importedDuplicateId = registroRepository
                        .getByNightscoutTreatmentId(match.treatmentId)
                        ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                        ?.id,
                    baseUrl = baseUrl,
                    token = token,
                    now = now
                )
                queueRepository.markSyncedNoUpload(registro.id, now)
                return
            }

            val effectiveTime = resolveEffectiveTimestamp(registro)
            val dcid = registro.nightscoutSyncDcid ?: buildDcid(registro.id, effectiveTime)
            val notes = buildUploadNotes(registro.notas, dcid)
            val request = NightscoutCreateTreatmentRequest(
                eventType = "Correction Bolus",
                insulin = localFinalDose,
                createdAt = Instant.ofEpochMilli(effectiveTime).toString(),
                notes = notes
            )
            val created = nightscoutRepository.createTreatment(
                baseUrl = baseUrl,
                token = token,
                request = request
            )

            val treatmentId = created?.id ?: findRemoteTreatmentIdByDcid(
                baseUrl = baseUrl,
                token = token,
                local = registro,
                dcid = dcid
            )

            if (treatmentId.isNullOrBlank()) {
                val err = nightscoutRepository.lastErrorMessage ?: "Nightscout no devolvió _id"
                queueRepository.markFailed(registro.id, err, now)
                return
            }

            val remote = RemoteInjectionCandidate(
                treatmentId = treatmentId,
                timestampMillis = created?.let { nightscoutRepository.resolveTreatmentMillis(it) } ?: effectiveTime,
                units = created?.let { nightscoutRepository.resolveTreatmentInsulinUnits(it) }
                    ?: localFinalDose,
                dcid = dcid
            )
            linkLocalWithRemote(
                registroId = registro.id,
                remote = remote,
                reconciledAt = now,
                importedDuplicateId = registroRepository
                    .getByNightscoutTreatmentId(remote.treatmentId)
                    ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                    ?.id,
                baseUrl = baseUrl,
                token = token,
                now = now
            )
            queueRepository.markSyncedUpload(registro.id, now)
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() }
                ?: nightscoutRepository.lastErrorMessage
                ?: "Error de sincronización"
            queueRepository.markFailed(registro.id, message, now)
        }
    }

    private suspend fun findRemoteMatchForLocal(
        local: RegistroComida,
        baseUrl: String,
        token: String?,
        ignoreTombstones: Boolean
    ): RemoteInjectionCandidate? {
        val toleranceMillis = NightscoutReconciliation.MAX_DELTA_MINUTES * 60_000L
        val effectiveTime = resolveEffectiveTimestamp(local)
        val from = effectiveTime - toleranceMillis
        val to = effectiveTime + toleranceMillis
        val remoteCandidates = fetchRemoteCandidates(
            baseUrl = baseUrl,
            token = token,
            fromMillis = from,
            toMillis = to
        )

        val remotes = mutableListOf<RemoteInjectionCandidate>()
        for (candidate in remoteCandidates) {
            if (!ignoreTombstones && tombstoneRepository.exists(candidate.treatmentId)) continue
            remotes += candidate
        }

        if (remotes.isEmpty()) return null

        val localCandidate = LocalInjectionCandidate(
            registroId = local.id,
            timestampMillis = resolveEffectiveTimestamp(local),
            units = resolveFinalLocalDoseUnits(local),
            dcid = local.nightscoutSyncDcid
        )
        val result = NightscoutReconciliation.reconcile(
            locals = listOf(localCandidate),
            remotes = remotes
        )
        return result.matches.firstOrNull()?.remote
    }

    private suspend fun importRemoteAsRegistro(
        remote: RemoteInjectionCandidate,
        now: Long,
        baseUrl: String,
        token: String?
    ) {
        if (registroRepository.getByNightscoutTreatmentId(remote.treatmentId) != null) return
        val prefix = "[Nightscout/Novopen]"
        val registro = RegistroComida(
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = remote.units,
            fecha = remote.timestampMillis,
            notas = "$prefix Dosis importada",
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value,
            nightscoutTreatmentId = remote.treatmentId,
            unidadesInsulinaRemota = remote.units,
            nightscoutReconciliadoAt = now,
            nightscoutSyncDcid = remote.dcid
        )
        runCatching {
            val registroId = registroRepository.insertRegistro(registro)
            fillMissingGlucoseFromNightscout(
                registroId = registroId,
                remoteDoseMillis = remote.timestampMillis,
                baseUrl = baseUrl,
                token = token,
                now = now
            )
        }
    }

    private suspend fun linkLocalWithRemote(
        registroId: Int,
        remote: RemoteInjectionCandidate,
        reconciledAt: Long,
        importedDuplicateId: Int? = null,
        baseUrl: String,
        token: String?,
        now: Long
    ) {
        // If this Nightscout treatment was previously imported as a standalone external record,
        // remove that duplicate first so the local record can take over the unique treatment id.
        if (importedDuplicateId != null && importedDuplicateId != registroId) {
            registroRepository.deleteById(importedDuplicateId)
        }
        registroRepository.updateNightscoutLink(
            registroId = registroId,
            treatmentId = remote.treatmentId,
            unidadesInsulinaRemota = remote.units,
            reconciliadoAt = reconciledAt,
            dcid = remote.dcid
        )
        fillMissingGlucoseFromNightscout(
            registroId = registroId,
            remoteDoseMillis = remote.timestampMillis,
            baseUrl = baseUrl,
            token = token,
            now = now
        )
    }

    private suspend fun fillMissingGlucoseFromNightscout(
        registroId: Int,
        remoteDoseMillis: Long,
        baseUrl: String,
        token: String?,
        now: Long
    ) {
        val registro = registroRepository.getRegistroRawById(registroId) ?: return
        val shouldFetchBefore = registro.glucosaAntesMgdl == null
        val shouldFetchAfter = registro.glucosaDespues2hMgdl == null &&
            now >= (remoteDoseMillis + TWO_HOURS_MILLIS - GLUCOSE_TOLERANCE_MINUTES * 60_000L)

        if (!shouldFetchBefore && !shouldFetchAfter) return

        val before = if (shouldFetchBefore) {
            nightscoutRepository.getGlucoseClosestTo(
                baseUrl = baseUrl,
                token = token,
                targetMillis = remoteDoseMillis,
                toleranceMinutes = GLUCOSE_TOLERANCE_MINUTES
            )?.sgv
        } else {
            null
        }

        val after2h = if (shouldFetchAfter) {
            nightscoutRepository.getGlucoseClosestTo(
                baseUrl = baseUrl,
                token = token,
                targetMillis = remoteDoseMillis + TWO_HOURS_MILLIS,
                toleranceMinutes = GLUCOSE_TOLERANCE_MINUTES
            )?.sgv
        } else {
            null
        }

        if (before != null) {
            registroRepository.updateGlucosaAntes(registroId, before)
        }
        if (after2h != null) {
            registroRepository.updateGlucosaDespues2h(registroId, after2h)
        }
    }

    private fun treatmentToCandidate(treatment: NightscoutTreatment): RemoteInjectionCandidate? {
        val id = treatment.id?.takeIf { it.isNotBlank() } ?: return null
        val units = nightscoutRepository.resolveTreatmentInsulinUnits(treatment) ?: return null
        if (units <= 0f || units.isNaN()) return null
        val timestamp = nightscoutRepository.resolveTreatmentMillis(treatment) ?: return null
        val dcid = extractDcid(treatment.notes)
        return RemoteInjectionCandidate(
            treatmentId = id,
            timestampMillis = timestamp,
            units = units,
            dcid = dcid
        )
    }

    private fun entryToCandidate(entry: NightscoutRawEntry): RemoteInjectionCandidate? {
        val rawId = entry.id?.takeIf { it.isNotBlank() } ?: return null
        val id = "entry:$rawId"
        val units = nightscoutRepository.resolveEntryInsulinUnits(entry) ?: return null
        if (units <= 0f || units.isNaN()) return null
        val timestamp = nightscoutRepository.resolveEntryMillis(entry) ?: return null
        val dcid = extractDcid(entry.notes)
        return RemoteInjectionCandidate(
            treatmentId = id,
            timestampMillis = timestamp,
            units = units,
            dcid = dcid
        )
    }

    private suspend fun fetchRemoteCandidates(
        baseUrl: String,
        token: String?,
        fromMillis: Long,
        toMillis: Long
    ): List<RemoteInjectionCandidate> {
        val fromEntries = nightscoutRepository.getFastInsulinEntriesInRangeAll(
            baseUrl = baseUrl,
            token = token,
            fromMillis = fromMillis,
            toMillis = toMillis
        ).mapNotNull { entryToCandidate(it) }

        val fromTreatments = nightscoutRepository.getTreatmentsInRangeAll(
            baseUrl = baseUrl,
            token = token,
            fromMillis = fromMillis,
            toMillis = toMillis
        ).mapNotNull { treatmentToCandidate(it) }

        val merged = (fromEntries + fromTreatments)
            .distinctBy { it.treatmentId }
            .groupBy { remote ->
                val minute = remote.timestampMillis / 60_000L
                val units = String.format(Locale.US, "%.2f", remote.units)
                "$minute|$units"
            }
            .map { (_, candidates) ->
                candidates.firstOrNull { it.treatmentId.startsWith("entry:") }
                    ?: candidates.first()
            }

        return merged.sortedBy { it.timestampMillis }
    }

    private suspend fun findRemoteTreatmentIdByDcid(
        baseUrl: String,
        token: String?,
        local: RegistroComida,
        dcid: String
    ): String? {
        val toleranceMillis = NightscoutReconciliation.MAX_DELTA_MINUTES * 60_000L
        val effectiveTime = resolveEffectiveTimestamp(local)
        val from = effectiveTime - toleranceMillis
        val to = effectiveTime + toleranceMillis
        val remotes = nightscoutRepository.getTreatmentsInRangeAll(
            baseUrl = baseUrl,
            token = token,
            fromMillis = from,
            toMillis = to
        )
        val candidate = remotes.firstOrNull { treatment ->
            val id = treatment.id ?: return@firstOrNull false
            val notes = treatment.notes.orEmpty()
            val sameDcid = notes.contains("dcid=$dcid")
            if (!sameDcid) return@firstOrNull false
            val insulin = nightscoutRepository.resolveTreatmentInsulinUnits(treatment)
                ?: return@firstOrNull false
            abs(insulin - resolveFinalLocalDoseUnits(local)) <= NightscoutReconciliation.MAX_DELTA_UNITS &&
                !id.isBlank()
        }
        return candidate?.id
    }

    /**
     * Dosis final efectiva usada para reconciliación/subida.
     * Si el registro quedó marcado sin corrección, derivamos la dosis final desde hidratos+ratio
     * (incluyendo factor contextual aplicado) para evitar comparar contra una dosis con corrección.
     */
    private fun resolveFinalLocalDoseUnits(registro: RegistroComida): Float {
        val stored = registro.unidadesInsulina.takeIf { it.isFinite() } ?: 0f
        // Once a dose is confirmed, treat stored units as the source of truth (supports manual link tuning).
        if (registro.dosisConfirmadaAt != null) {
            return stored.coerceAtLeast(0f)
        }
        if (registro.dosisConCorreccion != false) {
            return stored.coerceAtLeast(0f)
        }

        val ratioInsulinaHc = registro.ratioInsulinaHc
        val hidratos = registro.hidratosTotales
        if (ratioInsulinaHc == null || !ratioInsulinaHc.isFinite() || ratioInsulinaHc <= 0f || hidratos <= 0f) {
            return stored.coerceAtLeast(0f)
        }

        val factorContexto = registro.factorContextoTotalAplicado
            ?.takeIf { it.isFinite() && it > 0f }
            ?: 1f

        val sinCorreccion = roundToHalf((hidratos * ratioInsulinaHc * factorContexto).coerceAtLeast(0f))
        return sinCorreccion.takeIf { it.isFinite() } ?: stored.coerceAtLeast(0f)
    }

    private fun resolveEffectiveTimestamp(registro: RegistroComida): Long {
        return registro.dosisConfirmadaAt ?: registro.fecha
    }

    private fun roundToHalf(value: Float): Float = round(value * 2f) / 2f

    private fun buildUploadNotes(existing: String?, dcid: String): String {
        val cleanExisting = existing?.trim().orEmpty()
        return if (cleanExisting.isBlank()) {
            "[DiabetesCalculator] dcid=$dcid"
        } else {
            "[DiabetesCalculator] dcid=$dcid · $cleanExisting"
        }
    }

    private fun buildDcid(registroId: Int, timestampMillis: Long): String {
        return "reg-$registroId-$timestampMillis"
    }

    private fun extractDcid(notes: String?): String? {
        if (notes.isNullOrBlank()) return null
        val marker = "dcid="
        val index = notes.indexOf(marker)
        if (index == -1) return null
        val start = index + marker.length
        val tail = notes.substring(start)
        val token = tail.takeWhile { ch ->
            ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.'
        }
        return token.takeIf { it.isNotBlank() }
    }
}
