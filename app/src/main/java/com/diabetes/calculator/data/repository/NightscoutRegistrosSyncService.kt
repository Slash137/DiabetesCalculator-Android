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
        private const val ALIAS_MATCH_MAX_MILLIS = 2L * 60L * 1000L
        private const val ALIAS_MATCH_MAX_UNITS = 0.2f
    }

    suspend fun sync(
        profile: UsuarioProfile,
        fromMillis: Long,
        toMillis: Long,
        ignoreTombstones: Boolean,
        enqueueAllLocalRecords: Boolean = false,
        enqueueFromMillis: Long? = null
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

        if (enqueueAllLocalRecords || enqueueFromMillis != null) {
            enqueueLocalRecordsForUpload(
                now = now,
                toMillis = toMillis,
                enqueueAllLocalRecords = enqueueAllLocalRecords,
                enqueueFromMillis = enqueueFromMillis
            )
        }

        return uploadPendingLocals(
            profile = profile,
            now = now
        )
    }

    private suspend fun enqueueLocalRecordsForUpload(
        now: Long,
        toMillis: Long,
        enqueueAllLocalRecords: Boolean,
        enqueueFromMillis: Long?
    ) {
        val candidatos = if (enqueueAllLocalRecords) {
            registroRepository.getAllRegistrosRaw()
        } else {
            val from = enqueueFromMillis ?: return
            registroRepository.getRegistrosInRangeRaw(from = from, to = toMillis)
        }

        candidatos.forEach { registro ->
            if (registro.origenRegistro != OrigenRegistro.LOCAL.value) return@forEach
            if (registro.hidratosTotales <= 0f) return@forEach
            val currentStatus = queueRepository
                .getByRegistroId(registro.id)
                ?.let { RegistroNightscoutSyncStatus.fromValue(it.status) }
            if (currentStatus == RegistroNightscoutSyncStatus.SYNCED_UPLOAD) return@forEach
            queueRepository.upsertPending(registro.id, now)
        }
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

        val toleranceMinutes = profile.nightscoutLinkOffsetMinutes.coerceIn(0, 180)
        val toleranceUnits = profile.nightscoutLinkOffsetUnits.coerceIn(0f, 5f)
        val toleranceMillis = toleranceMinutes * 60_000L
        val aliasMatchMillis = minOf(toleranceMillis, ALIAS_MATCH_MAX_MILLIS)
        val aliasMatchUnits = minOf(toleranceUnits, ALIAS_MATCH_MAX_UNITS)
        val remotes = fetchRemoteCandidates(
            baseUrl = url,
            token = token,
            fromMillis = fromMillis,
            toMillis = toMillis
        )

        var localRegistros = registroRepository.getRegistrosInRangeRaw(
            from = fromMillis - toleranceMillis,
            to = toMillis + toleranceMillis
        )
        val omittedWithLink = localRegistros.filter { registro ->
            registro.origenRegistro == OrigenRegistro.LOCAL.value &&
                registro.dosisEstado == EstadoDosis.OMITIDA.value &&
                !registro.nightscoutTreatmentId.isNullOrBlank()
        }
        if (omittedWithLink.isNotEmpty()) {
            omittedWithLink.forEach { registro ->
                registro.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { treatmentId ->
                    tombstoneRepository.add(treatmentId, now)
                }
                registroRepository.clearNightscoutLink(registro.id)
            }
            localRegistros = registroRepository.getRegistrosInRangeRaw(
                from = fromMillis - toleranceMillis,
                to = toMillis + toleranceMillis
            )
        }
        if (remotes.isEmpty()) {
            cleanupImportedNightscoutAliases(
                localRegistros = localRegistros,
                toleranceMillis = aliasMatchMillis,
                toleranceUnits = aliasMatchUnits,
                now = now
            )
            return
        }
        val localByTreatmentId = localRegistros
            .mapNotNull { registro ->
                if (
                    registro.origenRegistro == OrigenRegistro.LOCAL.value &&
                    registro.dosisEstado == EstadoDosis.OMITIDA.value
                ) {
                    return@mapNotNull null
                }
                registro.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { it to registro }
            }
            .toMap()

        val localCandidates = localRegistros
            .asSequence()
            .filter { it.nightscoutTreatmentId.isNullOrBlank() }
            .filter { it.origenRegistro == OrigenRegistro.LOCAL.value }
            .filter { it.dosisEstado != EstadoDosis.OMITIDA.value }
            .map {
                LocalInjectionCandidate(
                    registroId = it.id,
                    timestampMillis = resolveEffectiveTimestamp(it),
                    units = resolveFinalLocalDoseUnits(it),
                    dcid = it.nightscoutSyncDcid
                )
            }
            .toMutableList()
        val linkedLocalCandidates = localRegistros
            .asSequence()
            .filter { it.origenRegistro == OrigenRegistro.LOCAL.value }
            .filter { !it.nightscoutTreatmentId.isNullOrBlank() }
            .filter { it.dosisEstado != EstadoDosis.OMITIDA.value }
            .map {
                LocalInjectionCandidate(
                    registroId = it.id,
                    timestampMillis = resolveEffectiveTimestamp(it),
                    units = resolveFinalLocalDoseUnits(it),
                    dcid = it.nightscoutSyncDcid
                )
            }
            .toMutableList()
        val linkedLocalTreatmentById = localRegistros
            .asSequence()
            .filter { it.origenRegistro == OrigenRegistro.LOCAL.value }
            .filter { !it.nightscoutTreatmentId.isNullOrBlank() }
            .filter { it.dosisEstado != EstadoDosis.OMITIDA.value }
            .associate { it.id to it.nightscoutTreatmentId.orEmpty() }
            .toMutableMap()
        val linkedInThisRun = mutableSetOf<Int>()

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
            if (!remote.dcid.isNullOrBlank()) {
                tombstoneRepository.add(remote.treatmentId, now)
                continue
            }
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
            remotes = remotesWithoutDcidMatch,
            maxDeltaMinutes = toleranceMinutes,
            maxDeltaUnits = toleranceUnits
        )
        val findImportedDuplicateIdForRemote: (
            RemoteInjectionCandidate,
            Int?,
            Long,
            Float
        ) -> Int? = { remote, excludeId, maxDeltaMillis, maxDeltaUnits ->
            localRegistros
                .asSequence()
                .filter { registro ->
                    registro.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value &&
                        registro.id != excludeId
                }
                .mapNotNull { registro ->
                    val deltaMillis = abs(resolveEffectiveTimestamp(registro) - remote.timestampMillis)
                    val deltaUnits = abs((registro.unidadesInsulinaRemota ?: registro.unidadesInsulina) - remote.units)
                    if (deltaMillis <= maxDeltaMillis && deltaUnits <= maxDeltaUnits) {
                        Triple(registro.id, deltaMillis, deltaUnits)
                    } else {
                        null
                    }
                }
                .minWithOrNull(compareBy<Triple<Int, Long, Float>> { it.second }.thenBy { it.third })
                ?.first
        }

        dcidMatches.forEach { (local, remote) ->
            val linked = linkLocalWithRemote(
                registroId = local.registroId,
                remote = remote,
                reconciledAt = now,
                importedDuplicateId = localByTreatmentId[remote.treatmentId]
                    ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                    ?.id ?: findImportedDuplicateIdForRemote(
                    remote,
                    local.registroId,
                    aliasMatchMillis,
                    aliasMatchUnits
                ),
                baseUrl = url,
                token = token,
                now = now
            )
            if (linked) {
                queueRepository.markSyncedNoUpload(local.registroId, now)
                if (linkedLocalCandidates.none { it.registroId == local.registroId }) {
                    linkedLocalCandidates += local
                }
                linkedLocalTreatmentById[local.registroId] = remote.treatmentId
                linkedInThisRun += local.registroId
            } else {
                queueRepository.markFailed(local.registroId, "Conflicto al enlazar con Nightscout", now)
            }
        }

        reconcile.matches.forEach { match ->
            val linked = linkLocalWithRemote(
                registroId = match.local.registroId,
                remote = match.remote,
                reconciledAt = now,
                importedDuplicateId = localByTreatmentId[match.remote.treatmentId]
                    ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                    ?.id ?: findImportedDuplicateIdForRemote(
                    match.remote,
                    match.local.registroId,
                    aliasMatchMillis,
                    aliasMatchUnits
                ),
                baseUrl = url,
                token = token,
                now = now
            )
            if (linked) {
                queueRepository.markSyncedNoUpload(match.local.registroId, now)
                if (linkedLocalCandidates.none { it.registroId == match.local.registroId }) {
                    linkedLocalCandidates += match.local
                }
                linkedLocalTreatmentById[match.local.registroId] = match.remote.treatmentId
                linkedInThisRun += match.local.registroId
            } else {
                queueRepository.markFailed(match.local.registroId, "Conflicto al enlazar con Nightscout", now)
            }
        }

        reconcile.unmatchedRemotes.forEach { remote ->
            val currentOwner = registroRepository.getByNightscoutTreatmentId(remote.treatmentId)
            if (currentOwner != null) {
                if (currentOwner.origenRegistro != OrigenRegistro.NIGHTSCOUT_IMPORT.value) {
                    fillMissingGlucoseFromNightscout(
                        registroId = currentOwner.id,
                        remoteDoseMillis = remote.timestampMillis,
                        baseUrl = url,
                        token = token,
                        now = now
                    )
                    return@forEach
                }
            }

            val nearLocal = localCandidates.firstOrNull { local ->
                !linkedInThisRun.contains(local.registroId) &&
                abs(local.timestampMillis - remote.timestampMillis) <= toleranceMillis &&
                    abs(local.units - remote.units) <= toleranceUnits
            }
            if (nearLocal != null) {
                val linked = linkLocalWithRemote(
                    registroId = nearLocal.registroId,
                    remote = remote,
                    reconciledAt = now,
                    importedDuplicateId = localByTreatmentId[remote.treatmentId]
                        ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                        ?.id ?: findImportedDuplicateIdForRemote(
                        remote,
                        nearLocal.registroId,
                        aliasMatchMillis,
                        aliasMatchUnits
                    ),
                    baseUrl = url,
                    token = token,
                    now = now
                )
                if (linked) {
                    queueRepository.markSyncedNoUpload(nearLocal.registroId, now)
                    if (linkedLocalCandidates.none { it.registroId == nearLocal.registroId }) {
                        linkedLocalCandidates += nearLocal
                    }
                    linkedLocalTreatmentById[nearLocal.registroId] = remote.treatmentId
                    linkedInThisRun += nearLocal.registroId
                } else {
                    queueRepository.markFailed(nearLocal.registroId, "Conflicto al enlazar con Nightscout", now)
                }
                return@forEach
            }

            val nearLinkedLocal = linkedLocalCandidates.firstOrNull { local ->
                abs(local.timestampMillis - remote.timestampMillis) <= toleranceMillis &&
                    abs(local.units - remote.units) <= toleranceUnits
            }
            if (nearLinkedLocal != null) {
                val currentLinkedTreatmentId = linkedLocalTreatmentById[nearLinkedLocal.registroId]
                val remoteIsEntry = remote.treatmentId.startsWith("entry:")
                val linkedIsEntry = currentLinkedTreatmentId?.startsWith("entry:") == true
                val linkedLooksAppGenerated = !nearLinkedLocal.dcid.isNullOrBlank()

                val shouldRelink = currentLinkedTreatmentId.isNullOrBlank() ||
                    currentLinkedTreatmentId == remote.treatmentId ||
                    (remoteIsEntry && !linkedIsEntry && linkedLooksAppGenerated) ||
                    (remoteIsEntry && !linkedIsEntry) ||
                    (linkedLooksAppGenerated && currentLinkedTreatmentId != remote.treatmentId)

                if (shouldRelink) {
                    val linked = linkLocalWithRemote(
                        registroId = nearLinkedLocal.registroId,
                        remote = remote,
                        reconciledAt = now,
                        importedDuplicateId = localByTreatmentId[remote.treatmentId]
                            ?.takeIf { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
                            ?.id ?: findImportedDuplicateIdForRemote(
                            remote,
                            nearLinkedLocal.registroId,
                            toleranceMillis,
                            toleranceUnits
                        ),
                        baseUrl = url,
                        token = token,
                        now = now
                    )
                    if (linked) {
                        queueRepository.markSyncedNoUpload(nearLinkedLocal.registroId, now)
                        linkedLocalTreatmentById[nearLinkedLocal.registroId] = remote.treatmentId
                        linkedInThisRun += nearLinkedLocal.registroId
                    } else {
                        queueRepository.markFailed(
                            nearLinkedLocal.registroId,
                            "Conflicto al enlazar con Nightscout",
                            now
                        )
                    }
                    return@forEach
                }
            }

            val nearImported = localRegistros.firstOrNull { registro ->
                registro.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value &&
                    abs(resolveEffectiveTimestamp(registro) - remote.timestampMillis) <= aliasMatchMillis &&
                    abs((registro.unidadesInsulinaRemota ?: registro.unidadesInsulina) - remote.units) <= aliasMatchUnits
            }
            if (nearImported != null) return@forEach

            importRemoteAsRegistro(
                remote = remote,
                now = now,
                baseUrl = url,
                token = token
            )
        }

        cleanupImportedNightscoutAliases(
            localRegistros = registroRepository.getRegistrosInRangeRaw(
                from = fromMillis - toleranceMillis,
                to = toMillis + toleranceMillis
            ),
            toleranceMillis = aliasMatchMillis,
            toleranceUnits = aliasMatchUnits,
            now = now
        )
    }

    private suspend fun uploadPendingLocals(
        profile: UsuarioProfile,
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

        if (registro.origenRegistro != OrigenRegistro.LOCAL.value) {
            queueRepository.markSyncedNoUpload(registro.id, now)
            return
        }

        if (registro.hidratosTotales <= 0f) {
            queueRepository.markSyncedNoUpload(registro.id, now)
            return
        }

        try {
            val effectiveTime = resolveEffectiveTimestamp(registro)
            val dcid = registro.nightscoutSyncDcid ?: buildDcid(registro.id, effectiveTime)
            if (registro.nightscoutSyncDcid.isNullOrBlank()) {
                registroRepository.updateNightscoutSyncDcid(registro.id, dcid)
            }
            val notes = buildMealUploadNotes(registro.notas, dcid)
            val request = NightscoutCreateTreatmentRequest(
                eventType = "Carb Correction",
                insulin = 0f,
                carbs = registro.hidratosTotales,
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
                aroundMillis = effectiveTime,
                dcid = dcid
            )

            if (treatmentId.isNullOrBlank()) {
                val err = nightscoutRepository.lastErrorMessage ?: "Nightscout no devolvió _id"
                queueRepository.markFailed(registro.id, err, now)
                return
            }

            queueRepository.markSyncedUpload(registro.id, now)
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() }
                ?: nightscoutRepository.lastErrorMessage
                ?: "Error de sincronización"
            queueRepository.markFailed(registro.id, message, now)
        }
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

    private suspend fun cleanupImportedNightscoutAliases(
        localRegistros: List<RegistroComida>,
        toleranceMillis: Long,
        toleranceUnits: Float,
        now: Long
    ) {
        // Legacy cleanup: remove imported records that likely came from old app-uploaded doses
        // (we tagged uploads with a dcid in notes).
        localRegistros
            .asSequence()
            .filter { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
            .filter { !it.nightscoutSyncDcid.isNullOrBlank() }
            .forEach { imported ->
                registroRepository.deleteById(imported.id)
                imported.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { treatmentId ->
                    tombstoneRepository.add(treatmentId, now)
                }
            }

        val localAfterLegacyCleanup = registroRepository.getRegistrosInRangeRaw(
            from = localRegistros.minOfOrNull { it.fecha } ?: 0L,
            to = localRegistros.maxOfOrNull { it.fecha } ?: 0L
        )

        val linkedLocals = localAfterLegacyCleanup.filter { registro ->
            registro.origenRegistro == OrigenRegistro.LOCAL.value &&
                !registro.nightscoutTreatmentId.isNullOrBlank() &&
                !registro.nightscoutTreatmentId.startsWith("entry:")
        }

        val importedAliases = localAfterLegacyCleanup.filter { registro ->
            registro.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value &&
                registro.nightscoutTreatmentId?.startsWith("entry:") == true
        }
        if (linkedLocals.isNotEmpty() && importedAliases.isNotEmpty()) {
            importedAliases.forEach { imported ->
                val importedUnits = imported.unidadesInsulinaRemota ?: imported.unidadesInsulina
                val importedTimestamp = resolveEffectiveTimestamp(imported)
                val duplicateOfLinkedLocal = linkedLocals.any { local ->
                    val localUnits = local.unidadesInsulinaRemota ?: local.unidadesInsulina
                    val localTimestamp = resolveEffectiveTimestamp(local)
                    abs(localTimestamp - importedTimestamp) <= toleranceMillis &&
                        abs(localUnits - importedUnits) <= toleranceUnits
                }
                if (!duplicateOfLinkedLocal) return@forEach

                registroRepository.deleteById(imported.id)
                imported.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { treatmentId ->
                    tombstoneRepository.add(treatmentId, now)
                }
            }
        }

        val importedByTimestamp = localAfterLegacyCleanup
            .asSequence()
            .filter { it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value }
            .filter { !it.nightscoutTreatmentId.isNullOrBlank() }
            .sortedBy { resolveEffectiveTimestamp(it) }
            .toList()

        val alreadyDeleted = mutableSetOf<Int>()
        importedByTimestamp.forEachIndexed { index, base ->
            if (alreadyDeleted.contains(base.id)) return@forEachIndexed
            val baseTimestamp = resolveEffectiveTimestamp(base)
            val baseUnits = base.unidadesInsulinaRemota ?: base.unidadesInsulina

            val cluster = mutableListOf(base)
            for (nextIndex in index + 1 until importedByTimestamp.size) {
                val candidate = importedByTimestamp[nextIndex]
                if (alreadyDeleted.contains(candidate.id)) continue
                val candidateTimestamp = resolveEffectiveTimestamp(candidate)
                if (candidateTimestamp - baseTimestamp > toleranceMillis) break
                val candidateUnits = candidate.unidadesInsulinaRemota ?: candidate.unidadesInsulina
                if (
                    abs(candidateTimestamp - baseTimestamp) <= toleranceMillis &&
                    abs(candidateUnits - baseUnits) <= toleranceUnits
                ) {
                    cluster += candidate
                }
            }
            if (cluster.size <= 1) return@forEachIndexed

            val keeper = cluster.sortedWith(
                compareBy<RegistroComida> { if (it.nightscoutSyncDcid.isNullOrBlank()) 0 else 1 }
                    .thenBy { if (it.nightscoutTreatmentId.orEmpty().startsWith("entry:")) 0 else 1 }
                    .thenBy { resolveEffectiveTimestamp(it) }
            ).first()
            cluster
                .filter { it.id != keeper.id }
                .forEach { duplicate ->
                    registroRepository.deleteById(duplicate.id)
                    alreadyDeleted += duplicate.id
                    duplicate.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { treatmentId ->
                        tombstoneRepository.add(treatmentId, now)
                    }
                }
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
    ): Boolean {
        val registroBeforeLink = registroRepository.getRegistroRawById(registroId)
        val previousTreatmentId = registroBeforeLink
            ?.nightscoutTreatmentId
            ?.takeIf { it.isNotBlank() }
        // If this Nightscout treatment was previously imported as a standalone external record,
        // remove that duplicate first so the local record can take over the unique treatment id.
        val existingOwner = registroRepository.getByNightscoutTreatmentId(remote.treatmentId)
        val importedConflictId = existingOwner
            ?.takeIf {
                it.id != registroId && it.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value
            }
            ?.id
        val duplicateIdToDelete = listOf(importedDuplicateId, importedConflictId)
            .filterNotNull()
            .firstOrNull { it != registroId }
        if (duplicateIdToDelete != null) {
            val duplicate = registroRepository.getRegistroRawById(duplicateIdToDelete)
            registroRepository.deleteById(duplicateIdToDelete)
            val duplicateTreatmentId = duplicate?.nightscoutTreatmentId?.takeIf { it.isNotBlank() }
            if (duplicateTreatmentId != null && duplicateTreatmentId != remote.treatmentId) {
                tombstoneRepository.add(duplicateTreatmentId, now)
            }
        }

        val conflictAfterCleanup = registroRepository.getByNightscoutTreatmentId(remote.treatmentId)
        if (conflictAfterCleanup != null && conflictAfterCleanup.id != registroId) {
            return false
        }

        val updated = registroRepository.updateNightscoutLink(
            registroId = registroId,
            treatmentId = remote.treatmentId,
            unidadesInsulinaRemota = remote.units,
            reconciliadoAt = reconciledAt,
            dcid = remote.dcid
        )
        if (updated <= 0) return false

        if (
            previousTreatmentId != null &&
            previousTreatmentId != remote.treatmentId &&
            previousTreatmentId.startsWith("entry:")
        ) {
            tombstoneRepository.add(previousTreatmentId, now)
        }
        if (
            previousTreatmentId != null &&
            previousTreatmentId != remote.treatmentId &&
            !previousTreatmentId.startsWith("entry:") &&
            remote.treatmentId.startsWith("entry:")
        ) {
            tombstoneRepository.add(previousTreatmentId, now)
        }

        fillMissingGlucoseFromNightscout(
            registroId = registroId,
            remoteDoseMillis = remote.timestampMillis,
            baseUrl = baseUrl,
            token = token,
            now = now
        )
        return true
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
        if (isAppGeneratedTreatment(treatment)) return null
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
        if (isAppGeneratedEntry(entry)) return null
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
                candidates.minWithOrNull(
                    compareBy<RemoteInjectionCandidate>(
                        { if (it.dcid.isNullOrBlank()) 0 else 1 },
                        { if (it.treatmentId.startsWith("entry:")) 0 else 1 },
                        { it.timestampMillis },
                        { it.treatmentId }
                    )
                ) ?: candidates.first()
            }

        return merged.sortedBy { it.timestampMillis }
    }

    private suspend fun findRemoteTreatmentIdByDcid(
        baseUrl: String,
        token: String?,
        aroundMillis: Long,
        dcid: String
    ): String? {
        val toleranceMillis = NightscoutReconciliation.MAX_DELTA_MINUTES * 60_000L
        val from = aroundMillis - toleranceMillis
        val to = aroundMillis + toleranceMillis
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
            sameDcid && !id.isBlank()
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

    private fun buildMealUploadNotes(existing: String?, dcid: String): String {
        val cleanExisting = existing?.trim().orEmpty()
        return if (cleanExisting.isBlank()) {
            "[DiabetesCalculator meal] dcid=$dcid"
        } else {
            "[DiabetesCalculator meal] dcid=$dcid · $cleanExisting"
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

    private fun isAppGeneratedTreatment(treatment: NightscoutTreatment): Boolean {
        val enteredBy = treatment.enteredBy
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (enteredBy.contains("diabetescalculator")) return true

        val notes = treatment.notes
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return notes.contains("[diabetescalculator") || notes.contains("dcid=")
    }

    private fun isAppGeneratedEntry(entry: NightscoutRawEntry): Boolean {
        val notes = entry.notes
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return notes.contains("[diabetescalculator") || notes.contains("dcid=")
    }
}
