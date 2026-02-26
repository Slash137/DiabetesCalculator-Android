package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.LegacyLibreviewDeleteLink
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation
import com.diabetes.calculator.data.entity.RegistroNightscoutSync
import com.diabetes.calculator.data.entity.RegistroNightscoutSyncStatus
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.model.NightscoutCreateTreatmentRequest
import com.diabetes.calculator.data.model.NightscoutRawEntry
import com.diabetes.calculator.data.model.NightscoutTreatment
import com.diabetes.calculator.domain.LocalInjectionCandidate
import com.diabetes.calculator.domain.NightscoutReconciliation
import com.diabetes.calculator.domain.RemoteInjectionCandidate
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.util.NightscoutRetryPolicy
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.round

data class NightscoutSyncRunResult(
    val processedPending: Int = 0,
    val failedPending: Int = 0,
    val maxFailedAttempts: Int = 0
)

internal fun shouldUploadDoseOnlyLocalToNightscout(
    registro: RegistroComida,
    effectiveUnits: Float
): Boolean {
    if (EstadoDosis.fromValue(registro.dosisEstado) != EstadoDosis.APLICADA) return false
    if (!effectiveUnits.isFinite() || effectiveUnits <= 0f) return false
    return registro.hidratosTotales <= 0f && registro.racionesCalculadas <= 0f
}

internal fun scoreLocalKeeper(registro: RegistroComida): Int {
    var score = 0
    if (registro.hidratosTotales > 0f || registro.racionesCalculadas > 0f) score += 1_000
    if (!isNovoPenNfcRegistroLocal(registro)) score += 400
    if (registro.libreviewInsulinRecordNumber != null || registro.libreviewCarbsRecordNumber != null) score += 200
    if (!registro.nightscoutTreatmentId.isNullOrBlank()) score += 100
    if (registro.glucosaAntesMgdl != null || registro.glucosaDespues2hMgdl != null) score += 50
    return score
}

internal fun choosePreferredLocalKeeper(cluster: List<RegistroComida>): RegistroComida {
    return cluster
        .sortedWith(
            compareByDescending<RegistroComida> { scoreLocalKeeper(it) }
                .thenBy { it.id }
        )
        .first()
}

internal fun resolvePreferredTreatmentIdForCluster(
    cluster: List<RegistroComida>,
    keeper: RegistroComida
): String? {
    return keeper.nightscoutTreatmentId?.takeIf { it.isNotBlank() }
        ?: cluster
            .asSequence()
            .filter { it.id != keeper.id }
            .sortedWith(
                compareByDescending<RegistroComida> { scoreLocalKeeper(it) }
                    .thenBy { it.id }
            )
            .mapNotNull { it.nightscoutTreatmentId?.takeIf { id -> id.isNotBlank() } }
            .firstOrNull()
}

internal fun hasDuplicatePair(
    registro: RegistroComida,
    candidates: List<RegistroComida>,
    toleranceMillis: Long,
    toleranceUnits: Float
): Boolean {
    if (candidates.isEmpty()) return false
    val timestamp = resolveComparableTimestampForDuplicate(registro)
    val units = resolveComparableUnitsForDuplicate(registro)
    if (!units.isFinite() || units <= 0f) return false
    return candidates.any { candidate ->
        if (candidate.id == registro.id) return@any false
        val candidateUnits = resolveComparableUnitsForDuplicate(candidate)
        if (!candidateUnits.isFinite() || candidateUnits <= 0f) return@any false
        val candidateTimestamp = resolveComparableTimestampForDuplicate(candidate)
        val withinTimeWindow = abs(candidateTimestamp - timestamp) <= toleranceMillis
        if (!withinTimeWindow) return@any false
        val withinUnitsWindow = abs(candidateUnits - units) <= toleranceUnits
        withinUnitsWindow || isMealAndNfcCanonicalPair(registro, candidate)
    }
}

internal fun isMealAndNfcCanonicalPair(
    first: RegistroComida,
    second: RegistroComida
): Boolean {
    val firstIsNfc = isNovoPenNfcRegistroLocal(first)
    val secondIsNfc = isNovoPenNfcRegistroLocal(second)
    if (firstIsNfc == secondIsNfc) return false

    val nfc = if (firstIsNfc) first else second
    val meal = if (firstIsNfc) second else first
    if (OrigenRegistro.fromValue(nfc.origenRegistro) != OrigenRegistro.LOCAL) return false
    if (OrigenRegistro.fromValue(meal.origenRegistro) != OrigenRegistro.LOCAL) return false
    if (EstadoDosis.fromValue(meal.dosisEstado) == EstadoDosis.OMITIDA) return false

    val hasMealCarbs = meal.hidratosTotales > 0f || meal.racionesCalculadas > 0f
    if (!hasMealCarbs) return false

    val nfcUnits = resolveComparableUnitsForDuplicate(nfc)
    return nfcUnits.isFinite() && nfcUnits > 0f
}

private fun resolveComparableTimestampForDuplicate(registro: RegistroComida): Long {
    return registro.dosisConfirmadaAt ?: registro.fecha
}

private fun resolveComparableUnitsForDuplicate(registro: RegistroComida): Float {
    val remote = registro.unidadesInsulinaRemota
        ?.takeIf { it.isFinite() && it > 0f }
    if (remote != null) return remote
    val local = registro.unidadesInsulina
    return if (local.isFinite() && local > 0f) local else 0f
}

private fun isNovoPenNfcRegistroLocal(registro: RegistroComida): Boolean {
    val dcid = registro.nightscoutSyncDcid?.trim().orEmpty()
    if (dcid.startsWith("nfc-")) return true
    val notes = registro.notas?.trim().orEmpty()
    return notes.contains("[NovoPen NFC]", ignoreCase = true)
}

class NightscoutRegistrosSyncService(
    private val registroRepository: RegistroComidaRepository,
    private val queueRepository: RegistroNightscoutSyncRepository,
    private val tombstoneRepository: NightscoutTreatmentTombstoneRepository,
    private val nightscoutRepository: NightscoutRepository,
    private val libreviewQueueRepository: RegistroLibreviewSyncRepository? = null
) {
    companion object {
        private const val GLUCOSE_TOLERANCE_MINUTES = 15
        private const val TWO_HOURS_MILLIS = 2L * 60L * 60L * 1000L
        private const val REQUIRED_LINK_WINDOW_MINUTES = SyncLinkTolerance.WINDOW_MINUTES
        private const val REQUIRED_LINK_WINDOW_UNITS = SyncLinkTolerance.WINDOW_UNITS
        private const val DCID_LOOKUP_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
    }

    suspend fun sync(
        profile: UsuarioProfile,
        fromMillis: Long,
        toMillis: Long,
        ignoreTombstones: Boolean,
        enqueueAllLocalRecords: Boolean = false,
        enqueueFromMillis: Long? = null,
        fullHistoricalReconcile: Boolean = false
    ): NightscoutSyncRunResult {
        val url = profile.nightscoutUrl?.trim().orEmpty()
        if (url.isBlank()) return NightscoutSyncRunResult()

        val now = System.currentTimeMillis()
        processPendingRemoteDeletes(
            baseUrl = url,
            token = profile.nightscoutToken
        )
        importAndReconcileRemote(
            profile = profile,
            fromMillis = fromMillis,
            toMillis = toMillis,
            ignoreTombstones = ignoreTombstones,
            now = now,
            fullHistoricalReconcile = fullHistoricalReconcile
        )

        if (enqueueAllLocalRecords || enqueueFromMillis != null) {
            enqueueLocalRecordsForUpload(
                now = now,
                toMillis = toMillis,
                enqueueAllLocalRecords = enqueueAllLocalRecords,
                enqueueFromMillis = enqueueFromMillis
            )
        }

        val runResult = uploadPendingLocals(
            profile = profile,
            now = now
        )
        processPendingRemoteDeletes(
            baseUrl = url,
            token = profile.nightscoutToken
        )
        return runResult
    }

    suspend fun reconcileLocalDuplicatesOnly(
        now: Long = System.currentTimeMillis(),
        linkOffsetMinutes: Int = SyncLinkTolerance.WINDOW_MINUTES,
        linkOffsetUnits: Float = SyncLinkTolerance.WINDOW_UNITS
    ) {
        val toleranceMinutes = max(
            linkOffsetMinutes.coerceIn(0, 180),
            REQUIRED_LINK_WINDOW_MINUTES
        )
        val toleranceUnits = max(
            linkOffsetUnits.coerceIn(0f, 5f),
            REQUIRED_LINK_WINDOW_UNITS
        )
        val toleranceMillis = toleranceMinutes * 60_000L

        val initial = registroRepository.getAllRegistrosRaw()
        if (initial.isEmpty()) return

        mergeLocalAppAndNfcDuplicates(
            localRegistros = initial,
            toleranceMillis = toleranceMillis,
            toleranceUnits = toleranceUnits,
            now = now
        )

        cleanupImportedNightscoutAliases(
            localRegistros = registroRepository.getAllRegistrosRaw(),
            toleranceMillis = toleranceMillis,
            toleranceUnits = toleranceUnits,
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
            if (!shouldUploadLocalRegistro(registro)) return@forEach
            if (!registro.nightscoutTreatmentId.isNullOrBlank()) {
                queueRepository.markSyncedNoUpload(registro.id, now)
                return@forEach
            }
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
        now: Long,
        fullHistoricalReconcile: Boolean
    ) {
        val url = profile.nightscoutUrl?.trim().orEmpty()
        val token = profile.nightscoutToken
        if (url.isBlank()) return

        val toleranceMinutes = max(
            profile.nightscoutLinkOffsetMinutes.coerceIn(0, 180),
            REQUIRED_LINK_WINDOW_MINUTES
        )
        val toleranceUnits = max(
            profile.nightscoutLinkOffsetUnits.coerceIn(0f, 5f),
            REQUIRED_LINK_WINDOW_UNITS
        )
        val toleranceMillis = toleranceMinutes * 60_000L
        val aliasMatchMillis = toleranceMillis
        val aliasMatchUnits = toleranceUnits
        val remotes = fetchRemoteCandidates(
            baseUrl = url,
            token = token,
            fromMillis = fromMillis,
            toMillis = toMillis
        )
        val remoteByTreatmentId = remotes.associateBy { it.treatmentId }

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
            val mergeScopeRegistros = if (fullHistoricalReconcile) {
                registroRepository.getAllRegistrosRaw()
            } else {
                localRegistros
            }
            mergeLocalAppAndNfcDuplicates(
                localRegistros = mergeScopeRegistros,
                toleranceMillis = toleranceMillis,
                toleranceUnits = toleranceUnits,
                now = now
            )
            cleanupImportedNightscoutAliases(
                localRegistros = mergeScopeRegistros,
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
                    duplicateCheckMillis = aliasMatchMillis,
                    duplicateCheckUnits = aliasMatchUnits,
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
                    duplicateCheckMillis = aliasMatchMillis,
                    duplicateCheckUnits = aliasMatchUnits,
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
                    duplicateCheckMillis = aliasMatchMillis,
                    duplicateCheckUnits = aliasMatchUnits,
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
                val currentLinkedRemote = currentLinkedTreatmentId?.let { remoteByTreatmentId[it] }
                val candidateDeltaMillis = abs(nearLinkedLocal.timestampMillis - remote.timestampMillis)
                val candidateDeltaUnits = abs(nearLinkedLocal.units - remote.units)
                val currentDeltaMillis = currentLinkedRemote?.let {
                    abs(nearLinkedLocal.timestampMillis - it.timestampMillis)
                }
                val currentDeltaUnits = currentLinkedRemote?.let {
                    abs(nearLinkedLocal.units - it.units)
                }

                val shouldRelink = currentLinkedTreatmentId.isNullOrBlank() ||
                    currentLinkedTreatmentId == remote.treatmentId ||
                    currentLinkedRemote == null ||
                    currentDeltaMillis == null ||
                    candidateDeltaMillis < currentDeltaMillis ||
                    (candidateDeltaMillis == currentDeltaMillis &&
                        (currentDeltaUnits == null || candidateDeltaUnits <= currentDeltaUnits))

                if (shouldRelink) {
                    val linked = linkLocalWithRemote(
                        registroId = nearLinkedLocal.registroId,
                        remote = remote,
                        reconciledAt = now,
                        duplicateCheckMillis = toleranceMillis,
                        duplicateCheckUnits = toleranceUnits,
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
                } else {
                    tombstoneRepository.add(remote.treatmentId, now)
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

        val mergeScopeRegistros = if (fullHistoricalReconcile) {
            registroRepository.getAllRegistrosRaw()
        } else {
            registroRepository.getRegistrosInRangeRaw(
                from = fromMillis - toleranceMillis,
                to = toMillis + toleranceMillis
            )
        }

        mergeLocalAppAndNfcDuplicates(
            localRegistros = mergeScopeRegistros,
            toleranceMillis = toleranceMillis,
            toleranceUnits = toleranceUnits,
            now = now
        )

        cleanupImportedNightscoutAliases(
            localRegistros = mergeScopeRegistros,
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

        if (!shouldUploadLocalRegistro(registro)) {
            queueRepository.markSyncedNoUpload(registro.id, now)
            return
        }

        try {
            val effectiveTime = resolveEffectiveTimestamp(registro)
            val dcid = registro.nightscoutSyncDcid ?: buildDcid(registro.id, effectiveTime)
            if (registro.nightscoutSyncDcid.isNullOrBlank()) {
                registroRepository.updateNightscoutSyncDcid(registro.id, dcid)
            }
            val linkedTreatmentId = registro.nightscoutTreatmentId?.takeIf { it.isNotBlank() }
            if (linkedTreatmentId != null) {
                findRemoteTreatmentIdsByDcid(
                    baseUrl = baseUrl,
                    token = token,
                    aroundMillis = effectiveTime,
                    dcid = dcid
                )
                    .asSequence()
                    .filter { it != linkedTreatmentId }
                    .forEach { duplicateId ->
                        tombstoneRepository.add(duplicateId, now)
                    }
                queueRepository.markSyncedNoUpload(registro.id, now)
                return
            }
            val remoteMatchesByDcid = findRemoteTreatmentIdsByDcid(
                baseUrl = baseUrl,
                token = token,
                aroundMillis = effectiveTime,
                dcid = dcid
            )
            if (remoteMatchesByDcid.isNotEmpty()) {
                val canonicalTreatmentId = remoteMatchesByDcid.first()
                val linked = linkLocalWithRemote(
                    registroId = registro.id,
                    remote = RemoteInjectionCandidate(
                        treatmentId = canonicalTreatmentId,
                        timestampMillis = effectiveTime,
                        units = resolveFinalLocalDoseUnits(registro).coerceAtLeast(0f),
                        dcid = dcid
                    ),
                    reconciledAt = now,
                    baseUrl = baseUrl,
                    token = token,
                    now = now
                )
                if (!linked) {
                    queueRepository.markFailed(
                        registro.id,
                        "Conflicto al enlazar por dcid con Nightscout",
                        now
                    )
                    return
                }
                remoteMatchesByDcid
                    .drop(1)
                    .forEach { duplicateId ->
                        tombstoneRepository.add(duplicateId, now)
                    }
                queueRepository.markSyncedNoUpload(registro.id, now)
                return
            }
            val payload = buildUploadPayload(registro, dcid) ?: run {
                queueRepository.markSyncedNoUpload(registro.id, now)
                return
            }
            val request = NightscoutCreateTreatmentRequest(
                eventType = payload.eventType,
                insulin = payload.insulin,
                carbs = payload.carbs,
                createdAt = Instant.ofEpochMilli(effectiveTime).toString(),
                notes = payload.notes
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

            val linked = registroRepository.updateNightscoutLink(
                registroId = registro.id,
                treatmentId = treatmentId,
                unidadesInsulinaRemota = payload.insulin
                    .takeIf { it.isFinite() && it > 0f },
                reconciliadoAt = now,
                dcid = dcid
            )
            if (linked <= 0) {
                queueRepository.markFailed(registro.id, "No se pudo enlazar tratamiento Nightscout", now)
                return
            }

            findRemoteTreatmentIdsByDcid(
                baseUrl = baseUrl,
                token = token,
                aroundMillis = effectiveTime,
                dcid = dcid
            )
                .asSequence()
                .filter { it != treatmentId }
                .forEach { duplicateId ->
                    tombstoneRepository.add(duplicateId, now)
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
        val localAfterCleanup = registroRepository.getRegistrosInRangeRaw(
            from = localRegistros.minOfOrNull { it.fecha } ?: 0L,
            to = localRegistros.maxOfOrNull { it.fecha } ?: 0L
        )

        val linkedLocals = localAfterCleanup.filter { registro ->
            registro.origenRegistro == OrigenRegistro.LOCAL.value &&
                !registro.nightscoutTreatmentId.isNullOrBlank()
        }

        val importedAliases = localAfterCleanup.filter { registro ->
            registro.origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT.value
        }
        if (linkedLocals.isNotEmpty() && importedAliases.isNotEmpty()) {
            importedAliases.forEach { imported ->
                val duplicateOfLinkedLocal = hasDuplicatePair(
                    registro = imported,
                    candidates = linkedLocals,
                    toleranceMillis = toleranceMillis,
                    toleranceUnits = toleranceUnits
                )
                if (!duplicateOfLinkedLocal) return@forEach

                val hasAnotherImportedPair = hasDuplicatePair(
                    registro = imported,
                    candidates = importedAliases,
                    toleranceMillis = toleranceMillis,
                    toleranceUnits = toleranceUnits
                )
                if (hasAnotherImportedPair || linkedLocals.isNotEmpty()) {
                    registroRepository.deleteById(imported.id)
                    imported.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { treatmentId ->
                        tombstoneRepository.add(treatmentId, now)
                    }
                }
            }
        }

        val importedByTimestamp = localAfterCleanup
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
                    if (
                        !hasDuplicatePair(
                            registro = duplicate,
                            candidates = cluster,
                            toleranceMillis = toleranceMillis,
                            toleranceUnits = toleranceUnits
                        )
                    ) return@forEach
                    registroRepository.deleteById(duplicate.id)
                    alreadyDeleted += duplicate.id
                    duplicate.nightscoutTreatmentId?.takeIf { it.isNotBlank() }?.let { treatmentId ->
                        tombstoneRepository.add(treatmentId, now)
                    }
                }
        }
    }

    private suspend fun mergeLocalAppAndNfcDuplicates(
        localRegistros: List<RegistroComida>,
        toleranceMillis: Long,
        toleranceUnits: Float,
        now: Long
    ) {
        val locals = localRegistros
            .asSequence()
            .filter { it.origenRegistro == OrigenRegistro.LOCAL.value }
            .filter { it.dosisEstado != EstadoDosis.OMITIDA.value }
            .filter { resolveFinalLocalDoseUnits(it) > 0f }
            .sortedBy { resolveEffectiveTimestamp(it) }
            .toList()
        if (locals.size <= 1) return

        val processed = mutableSetOf<Int>()
        locals.forEach { base ->
            if (processed.contains(base.id)) return@forEach
            val baseTimestamp = resolveEffectiveTimestamp(base)
            val baseUnits = resolveFinalLocalDoseUnits(base)
            val cluster = locals.filter { candidate ->
                if (processed.contains(candidate.id)) return@filter false
                val candidateTimestamp = resolveEffectiveTimestamp(candidate)
                if (abs(candidateTimestamp - baseTimestamp) > toleranceMillis) return@filter false
                val unitsMatch = abs(resolveFinalLocalDoseUnits(candidate) - baseUnits) <= toleranceUnits
                unitsMatch || isMealAndNfcCanonicalPair(base, candidate)
            }
            if (cluster.size <= 1) return@forEach

            if (cluster.size <= 1) return@forEach

            val keeper = choosePreferredLocalKeeper(cluster)
            val targetTreatmentId = resolvePreferredTreatmentIdForCluster(
                cluster = cluster,
                keeper = keeper
            )

            var keeperUpdated = keeper
            var canonicalizedByNfc = false
            val nfcSource = cluster
                .asSequence()
                .filter { isNovoPenNfcRegistro(it) }
                .filter { resolveFinalLocalDoseUnits(it) > 0f }
                .sortedWith(
                    compareBy<RegistroComida> {
                        abs(resolveEffectiveTimestamp(it) - resolveEffectiveTimestamp(keeper))
                    }.thenBy { it.id }
                )
                .firstOrNull()

            if (nfcSource != null) {
                val canonicalTimestamp = resolveEffectiveTimestamp(nfcSource)
                val canonicalUnits = resolveFinalLocalDoseUnits(nfcSource)
                val canonicalDcid = nfcSource.nightscoutSyncDcid
                    ?.takeIf { it.isNotBlank() }
                    ?: buildDcid(keeper.id, canonicalTimestamp)
                val canonicalized = registroRepository.canonicalizeLocalRegistroWithNfcDose(
                    registroId = keeper.id,
                    unidades = canonicalUnits,
                    confirmadaAt = canonicalTimestamp,
                    dcid = canonicalDcid,
                    now = now
                )
                if (canonicalized != null) {
                    keeperUpdated = canonicalized.updatedRegistro
                    canonicalizedByNfc = true
                    canonicalized.invalidatedNightscoutTreatmentId
                        ?.takeIf { it.isNotBlank() && it != targetTreatmentId }
                        ?.let { treatmentId ->
                            tombstoneRepository.add(treatmentId, now)
                        }
                    enqueueLibreviewLegacyDeletes(canonicalized.legacyDeletes, now)
                }
            }

            if (
                !canonicalizedByNfc &&
                EstadoDosis.fromValue(keeperUpdated.dosisEstado) != EstadoDosis.APLICADA &&
                cluster.any { isNovoPenNfcRegistro(it) }
            ) {
                val bestConfirmation = cluster
                    .asSequence()
                    .filter { isNovoPenNfcRegistro(it) }
                    .map { it.dosisConfirmadaAt ?: it.fecha }
                    .firstOrNull()
                    ?: (keeperUpdated.dosisConfirmadaAt ?: keeperUpdated.fecha)
                registroRepository.updateDosisEstado(
                    registroId = keeperUpdated.id,
                    estado = EstadoDosis.APLICADA,
                    confirmadaAt = bestConfirmation
                )
                keeperUpdated = registroRepository.getRegistroRawById(keeperUpdated.id) ?: keeperUpdated
            }

            if (!targetTreatmentId.isNullOrBlank()) {
                val owner = cluster.firstOrNull { it.nightscoutTreatmentId == targetTreatmentId }
                if (owner != null && owner.id != keeperUpdated.id) {
                    registroRepository.clearNightscoutLink(owner.id)
                }

                val canonicalDcid = cluster
                    .asSequence()
                    .mapNotNull { it.nightscoutSyncDcid?.takeIf { dcid -> dcid.isNotBlank() } }
                    .firstOrNull()
                val canonicalRemoteUnits = cluster
                    .asSequence()
                    .firstOrNull { it.nightscoutTreatmentId == targetTreatmentId }
                    ?.let { it.unidadesInsulinaRemota ?: it.unidadesInsulina }
                val linked = registroRepository.updateNightscoutLink(
                    registroId = keeperUpdated.id,
                    treatmentId = targetTreatmentId,
                    unidadesInsulinaRemota = canonicalRemoteUnits,
                    reconciliadoAt = now,
                    dcid = canonicalDcid
                )
                if (linked > 0) {
                    queueRepository.markSyncedNoUpload(keeperUpdated.id, now)
                }
            }

            val nfcDcid = cluster
                .asSequence()
                .mapNotNull { it.nightscoutSyncDcid }
                .firstOrNull { it.startsWith("nfc-") }
            if (!nfcDcid.isNullOrBlank() && keeperUpdated.nightscoutSyncDcid != nfcDcid) {
                registroRepository.updateNightscoutSyncDcid(keeperUpdated.id, nfcDcid)
            }

            if (!canonicalizedByNfc && keeperUpdated.libreviewInsulinRecordNumber == null) {
                val insulinDonor = cluster.firstOrNull {
                    it.id != keeperUpdated.id && it.libreviewInsulinRecordNumber != null
                }
                if (insulinDonor?.libreviewInsulinRecordNumber != null) {
                    registroRepository.updateLibreviewInsulinLink(
                        registroId = keeperUpdated.id,
                        recordNumber = insulinDonor.libreviewInsulinRecordNumber,
                        payloadHash = insulinDonor.libreviewInsulinPayloadHash,
                        reconciliadoAt = insulinDonor.libreviewReconciliadoAt ?: now
                    )
                }
            }

            if (!canonicalizedByNfc && keeperUpdated.libreviewCarbsRecordNumber == null) {
                val carbsDonor = cluster.firstOrNull {
                    it.id != keeperUpdated.id && it.libreviewCarbsRecordNumber != null
                }
                if (carbsDonor?.libreviewCarbsRecordNumber != null) {
                    registroRepository.updateLibreviewCarbsLink(
                        registroId = keeperUpdated.id,
                        recordNumber = carbsDonor.libreviewCarbsRecordNumber,
                        payloadHash = carbsDonor.libreviewCarbsPayloadHash,
                        reconciliadoAt = carbsDonor.libreviewReconciliadoAt ?: now
                    )
                }
            }

            cluster
                .filter { it.id != keeperUpdated.id }
                .forEach { duplicate ->
                    if (
                        !hasDuplicatePair(
                            registro = duplicate,
                            candidates = cluster,
                            toleranceMillis = toleranceMillis,
                            toleranceUnits = toleranceUnits
                        )
                    ) return@forEach
                    val duplicateTreatmentId = duplicate.nightscoutTreatmentId?.takeIf { it.isNotBlank() }
                    if (
                        duplicateTreatmentId != null &&
                        duplicateTreatmentId != targetTreatmentId
                    ) {
                        tombstoneRepository.add(duplicateTreatmentId, now)
                    }
                    enqueueLibreviewDeleteForDuplicate(
                        duplicate = duplicate,
                        keeper = keeperUpdated,
                        now = now
                    )
                    queueRepository.deleteByRegistroId(duplicate.id)
                    registroRepository.deleteById(duplicate.id)
                    processed += duplicate.id
                }

            processed += keeperUpdated.id
        }
    }

    private suspend fun enqueueLibreviewDeleteForDuplicate(
        duplicate: RegistroComida,
        keeper: RegistroComida,
        now: Long
    ) {
        val libreviewQueue = libreviewQueueRepository ?: return

        duplicate.libreviewCarbsRecordNumber
            ?.takeIf { it != keeper.libreviewCarbsRecordNumber }
            ?.let { recordNumber ->
                val carbsEventMillis = resolveCarbsTimestampForRecordNumber(
                    registro = duplicate,
                    recordNumber = recordNumber,
                    fallbackTimestamp = duplicate.fecha
                )
                libreviewQueue.upsertPending(
                    registroId = duplicate.id,
                    channel = RegistroLibreviewSyncChannel.CARBS,
                    operation = RegistroLibreviewSyncOperation.DELETE,
                    now = now,
                    recordNumber = recordNumber,
                    eventTimestampMillis = carbsEventMillis,
                    amountValue = 0f,
                    payloadHash = duplicate.libreviewCarbsPayloadHash
                )
            }

        duplicate.libreviewInsulinRecordNumber
            ?.takeIf { it != keeper.libreviewInsulinRecordNumber }
            ?.let { recordNumber ->
                val insulinEventMillis = resolveInsulinTimestampForRecordNumber(
                    registro = duplicate,
                    recordNumber = recordNumber,
                    fallbackTimestamp = resolveEffectiveTimestamp(duplicate)
                )
                libreviewQueue.upsertPending(
                    registroId = duplicate.id,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                    operation = RegistroLibreviewSyncOperation.DELETE,
                    now = now,
                    recordNumber = recordNumber,
                    eventTimestampMillis = insulinEventMillis,
                    amountValue = 0f,
                    payloadHash = duplicate.libreviewInsulinPayloadHash
                )
            }
    }

    private suspend fun enqueueLibreviewLegacyDeletes(
        legacyDeletes: List<LegacyLibreviewDeleteLink>,
        now: Long
    ) {
        val libreviewQueue = libreviewQueueRepository ?: return
        legacyDeletes.forEach { delete ->
            libreviewQueue.upsertPending(
                registroId = syntheticLibreviewDeleteRegistroId(
                    channel = delete.channel,
                    recordNumber = delete.recordNumber
                ),
                channel = delete.channel,
                operation = RegistroLibreviewSyncOperation.DELETE,
                now = now,
                recordNumber = delete.recordNumber,
                eventTimestampMillis = delete.eventTimestampMillis,
                amountValue = 0f,
                payloadHash = delete.payloadHash
            )
        }
    }

    private suspend fun linkLocalWithRemote(
        registroId: Int,
        remote: RemoteInjectionCandidate,
        reconciledAt: Long,
        duplicateCheckMillis: Long = SyncLinkTolerance.WINDOW_MILLIS,
        duplicateCheckUnits: Float = SyncLinkTolerance.WINDOW_UNITS,
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
            val owner = registroRepository.getRegistroRawById(registroId)
            val duplicate = registroRepository.getRegistroRawById(duplicateIdToDelete)
            val canDeleteDuplicate = owner != null &&
                duplicate != null &&
                hasDuplicatePair(
                    registro = duplicate,
                    candidates = listOf(owner),
                    toleranceMillis = duplicateCheckMillis,
                    toleranceUnits = duplicateCheckUnits
                )
            if (canDeleteDuplicate) {
                registroRepository.deleteById(duplicateIdToDelete)
                val duplicateTreatmentId = duplicate?.nightscoutTreatmentId?.takeIf { it.isNotBlank() }
                if (duplicateTreatmentId != null && duplicateTreatmentId != remote.treatmentId) {
                    tombstoneRepository.add(duplicateTreatmentId, now)
                }
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

        return (fromEntries + fromTreatments)
            .distinctBy { it.treatmentId }
            .sortedBy { it.timestampMillis }
    }

    private suspend fun findRemoteTreatmentIdByDcid(
        baseUrl: String,
        token: String?,
        aroundMillis: Long,
        dcid: String
    ): String? {
        return findRemoteTreatmentIdsByDcid(
            baseUrl = baseUrl,
            token = token,
            aroundMillis = aroundMillis,
            dcid = dcid
        ).firstOrNull()
    }

    private suspend fun findRemoteTreatmentIdsByDcid(
        baseUrl: String,
        token: String?,
        aroundMillis: Long,
        dcid: String
    ): List<String> {
        if (dcid.isBlank()) return emptyList()
        val from = aroundMillis - DCID_LOOKUP_WINDOW_MILLIS
        val to = aroundMillis + DCID_LOOKUP_WINDOW_MILLIS
        val remotes = nightscoutRepository.getTreatmentsInRangeAll(
            baseUrl = baseUrl,
            token = token,
            fromMillis = from,
            toMillis = to
        )
        return remotes
            .asSequence()
            .mapNotNull { treatment ->
                val id = treatment.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val notes = treatment.notes.orEmpty()
                if (!notes.contains("dcid=$dcid")) return@mapNotNull null
                val millis = nightscoutRepository.resolveTreatmentMillis(treatment) ?: aroundMillis
                Triple(id, abs(millis - aroundMillis), millis)
            }
            .distinctBy { it.first }
            .sortedWith(
                compareBy<Triple<String, Long, Long>> { it.second }
                    .thenBy { it.third }
                    .thenBy { it.first }
            )
            .map { it.first }
            .toList()
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

    private data class UploadPayload(
        val eventType: String,
        val insulin: Float,
        val carbs: Float?,
        val notes: String
    )

    private fun buildUploadPayload(
        registro: RegistroComida,
        dcid: String
    ): UploadPayload? {
        val carbs = registro.hidratosTotales
            .takeIf { it.isFinite() && it > 0f }
        if (carbs != null) {
            val appliedMealDose = if (EstadoDosis.fromValue(registro.dosisEstado) == EstadoDosis.APLICADA) {
                resolveFinalLocalDoseUnits(registro)
                    .takeIf { it.isFinite() && it > 0f }
            } else {
                null
            }
            return UploadPayload(
                eventType = if (appliedMealDose != null) "Meal Bolus" else "Carb Correction",
                insulin = appliedMealDose ?: 0f,
                carbs = carbs,
                notes = buildMealUploadNotes(registro.notas, dcid)
            )
        }

        val insulinUnits = resolveFinalLocalDoseUnits(registro)
        if (!shouldUploadDoseOnlyLocalToNightscout(registro, insulinUnits)) return null
        return UploadPayload(
            eventType = "Correction Bolus",
            insulin = insulinUnits,
            carbs = null,
            notes = buildDoseUploadNotes(registro.notas, dcid)
        )
    }

    private fun shouldUploadLocalRegistro(registro: RegistroComida): Boolean {
        val hasCarbs = registro.hidratosTotales.isFinite() && registro.hidratosTotales > 0f
        if (hasCarbs) return true
        val effectiveUnits = resolveFinalLocalDoseUnits(registro)
        return shouldUploadDoseOnlyLocalToNightscout(registro, effectiveUnits)
    }

    private fun isNovoPenNfcRegistro(registro: RegistroComida): Boolean {
        return isNovoPenNfcRegistroLocal(registro)
    }

    private suspend fun processPendingRemoteDeletes(
        baseUrl: String,
        token: String?
    ) {
        val tombstones = tombstoneRepository.getAllTreatmentIds()
        if (tombstones.isEmpty()) return
        tombstones.forEach { treatmentId ->
            val deleted = nightscoutRepository.deleteByTreatmentOrEntryId(
                baseUrl = baseUrl,
                token = token,
                treatmentId = treatmentId
            )
            if (deleted) {
                tombstoneRepository.delete(treatmentId)
            }
        }
    }

    private fun buildMealUploadNotes(existing: String?, dcid: String): String {
        val cleanExisting = existing?.trim().orEmpty()
        return if (cleanExisting.isBlank()) {
            "[DiabetesCalculator meal] dcid=$dcid"
        } else {
            "[DiabetesCalculator meal] dcid=$dcid · $cleanExisting"
        }
    }

    private fun buildDoseUploadNotes(existing: String?, dcid: String): String {
        val cleanExisting = existing?.trim().orEmpty()
        return if (cleanExisting.isBlank()) {
            "[DiabetesCalculator dose] dcid=$dcid"
        } else {
            "[DiabetesCalculator dose] dcid=$dcid · $cleanExisting"
        }
    }

    private fun buildDcid(registroId: Int, timestampMillis: Long): String {
        return "reg-$registroId-$timestampMillis"
    }

    private fun syntheticLibreviewDeleteRegistroId(
        channel: RegistroLibreviewSyncChannel,
        recordNumber: Long
    ): Int {
        val mixed = ((recordNumber xor (recordNumber ushr 32)).toInt()) xor channel.value.hashCode()
        val normalized = mixed.absoluteValue.coerceAtLeast(1)
        return -normalized
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
