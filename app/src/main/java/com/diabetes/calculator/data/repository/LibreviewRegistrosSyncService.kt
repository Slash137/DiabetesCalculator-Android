package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroLibreviewSync
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncStatus
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.model.LibreviewRemoteEntry
import com.diabetes.calculator.data.model.LibreviewSession
import com.diabetes.calculator.domain.LibreviewPayloadBuilder
import com.diabetes.calculator.domain.LibreviewPayloadOperation
import com.diabetes.calculator.domain.LibreviewRecordNumber
import com.diabetes.calculator.domain.LibreviewUploadPolicy
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.util.NightscoutRetryPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LibreviewSyncRunResult(
    val processedPending: Int = 0,
    val failedPending: Int = 0,
    val maxFailedAttempts: Int = 0,
    val abortedByConsecutiveErrors: Boolean = false
)

data class LibreviewRepairResetPreview(
    val canonicalRecords: Int = 0,
    val knownManagedRemoteDeletes: Int = 0,
    val unknownOverlap: Int = 0,
    val foreignRemote: Int = 0
)

@Serializable
data class LibreviewRepairSnapshot(
    val canonicalRecords: Int = 0,
    val knownRecordNumbers: List<LibreviewKnownRecordNumber> = emptyList(),
    val seriallessKnownRecordNumbers: List<LibreviewKnownRecordNumber> = emptyList(),
    val overlapCandidates: List<LibreviewOverlapCandidate> = emptyList(),
    val upsertOps: List<LibreviewRepairQueueItem> = emptyList(),
    val nightscoutManagedDeleteOps: List<LibreviewRepairQueueItem> = emptyList(),
    val nightscoutImportSkippedUpserts: Int = 0,
    val clearCarbsLinkRegistroIds: List<Int> = emptyList(),
    val clearInsulinLinkRegistroIds: List<Int> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class LibreviewKnownRecordNumber(
    val channel: String,
    val recordNumber: Long,
    val eventTimestampMillis: Long? = null
)

@Serializable
data class LibreviewOverlapCandidate(
    val channel: String,
    val eventTimestampMillis: Long,
    val amountValue: Float
)

@Serializable
data class LibreviewRepairQueueItem(
    val registroId: Int,
    val channel: String,
    val operation: String,
    val recordNumber: Long,
    val eventTimestampMillis: Long,
    val amountValue: Float,
    val payloadHash: String? = null
)

data class LibreviewWipePlan(
    val knownAppManaged: List<LibreviewRemoteEntry> = emptyList(),
    val unknownOverlap: List<LibreviewRemoteEntry> = emptyList(),
    val seriallessOverlap: List<LibreviewRemoteEntry> = emptyList(),
    val foreign: List<LibreviewRemoteEntry> = emptyList()
)

data class LibreviewRepairVerification(
    val missingCanonical: Int = 0,
    val managedDuplicates: Int = 0,
    val unknownOverlap: Int = 0,
    val seriallessOverlap: Int = 0,
    val foreign: Int = 0
)

@Serializable
data class LibreviewPartialUpsertResult(
    val planned: Int = 0,
    val skippedByRemoteMatch: Int = 0,
    val linkedToRemote: Int = 0,
    val failedRead: Boolean = false
)

internal fun resolveLibreviewEventTimestamp(
    registro: RegistroComida,
    channel: RegistroLibreviewSyncChannel
): Long {
    return when (channel) {
        RegistroLibreviewSyncChannel.CARBS -> registro.fecha
        RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.dosisConfirmadaAt ?: registro.fecha
    }
}

internal fun resolveCarbsTimestampForRecordNumber(
    registro: RegistroComida,
    recordNumber: Long,
    fallbackTimestamp: Long
): Long {
    val canonicalTimestamp = registro.fecha
    val legacyTimestamp = registro.dosisConfirmadaAt ?: registro.fecha
    if (legacyTimestamp == canonicalTimestamp) return canonicalTimestamp

    val canonicalRecordNumber = LibreviewRecordNumber.from(
        registroId = registro.id,
        channel = RegistroLibreviewSyncChannel.CARBS,
        effectiveTimestamp = canonicalTimestamp
    )
    if (recordNumber == canonicalRecordNumber) return canonicalTimestamp

    val legacyRecordNumber = LibreviewRecordNumber.from(
        registroId = registro.id,
        channel = RegistroLibreviewSyncChannel.CARBS,
        effectiveTimestamp = legacyTimestamp
    )
    if (recordNumber == legacyRecordNumber) return legacyTimestamp

    return fallbackTimestamp
}

internal fun resolveInsulinTimestampForRecordNumber(
    registro: RegistroComida,
    recordNumber: Long,
    fallbackTimestamp: Long
): Long {
    val canonicalTimestamp = registro.dosisConfirmadaAt ?: registro.fecha
    val legacyTimestamp = registro.fecha
    if (legacyTimestamp == canonicalTimestamp) return canonicalTimestamp

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

    return fallbackTimestamp
}

class LibreviewRegistrosSyncService(
    private val registroRepository: RegistroComidaRepository,
    private val queueRepository: RegistroLibreviewSyncRepository,
    private val libreviewRepository: LibreviewRepository,
    private val linkMatchDeltaMillis: Long = SyncLinkTolerance.WINDOW_MILLIS,
    private val linkMatchInsulinDelta: Float = SyncLinkTolerance.WINDOW_UNITS,
    private val appStableSerial: String? = null,
    private val recordCatalogRepository: LibreviewRecordCatalogRepository? = null,
    private val repairRunRepository: LibreviewRepairRunRepository? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class WipeDeleteSpec(
        val channel: RegistroLibreviewSyncChannel,
        val recordNumber: Long,
        val eventTimestampMillis: Long,
        val repeatCount: Int
    )

    private data class QueueProcessOutcome(
        val attemptedUpload: Boolean,
        val error: String? = null
    )

    private data class PlannedQueueItem(
        val registroId: Int,
        val channel: RegistroLibreviewSyncChannel,
        val operation: RegistroLibreviewSyncOperation,
        val recordNumber: Long,
        val eventTimestampMillis: Long,
        val amountValue: Float,
        val payloadHash: String?
    )

    private data class RepairResetPlan(
        val deleteOps: List<PlannedQueueItem>,
        val upsertOps: List<PlannedQueueItem>,
        val overlapCandidates: List<LibreviewOverlapCandidate>,
        val nightscoutManagedDeleteOps: List<PlannedQueueItem>,
        val nightscoutImportSkippedUpserts: Int,
        val clearCarbsLinkRegistroIds: Set<Int>,
        val clearInsulinLinkRegistroIds: Set<Int>
    ) {
        fun toPreview(): LibreviewRepairResetPreview {
            return LibreviewRepairResetPreview(
                canonicalRecords = upsertOps
                    .asSequence()
                    .map { it.registroId }
                    .distinct()
                    .count(),
                knownManagedRemoteDeletes = deleteOps.size,
                unknownOverlap = 0,
                foreignRemote = 0
            )
        }
    }

    private suspend fun enqueueLegacyDeleteOperation(
        channel: RegistroLibreviewSyncChannel,
        recordNumber: Long,
        eventTimestampMillis: Long,
        now: Long
    ) {
        val deletePayloadHash = LibreviewPayloadBuilder.hashPayload(
            channel = channel.value,
            operation = LibreviewPayloadOperation.DELETE,
            recordNumber = recordNumber,
            eventTimestampMillis = eventTimestampMillis,
            amountValue = 0f
        )
        queueRepository.upsertPending(
            registroId = syntheticDeleteRegistroId(channel, recordNumber),
            channel = channel,
            operation = RegistroLibreviewSyncOperation.DELETE,
            now = now,
            recordNumber = recordNumber,
            eventTimestampMillis = eventTimestampMillis,
            amountValue = 0f,
            payloadHash = deletePayloadHash
        )
    }

    suspend fun enqueueUpsertForRegistro(
        registroId: Int,
        now: Long = System.currentTimeMillis(),
        includeAllOrigins: Boolean = false,
        allowPendingInsulin: Boolean = false
    ) {
        val registro = registroRepository.getRegistroRawById(registroId) ?: return
        enqueueUpsertCarbsForRegistro(
            registro = registro,
            now = now,
            includeAllOrigins = includeAllOrigins
        )
        enqueueUpsertInsulinForRegistro(
            registro = registro,
            now = now,
            includeAllOrigins = includeAllOrigins,
            allowPendingInsulin = allowPendingInsulin
        )
    }

    private suspend fun enqueueUpsertCarbsForRegistro(
        registro: RegistroComida,
        now: Long,
        includeAllOrigins: Boolean
    ) {
        val eligible = if (includeAllOrigins) {
            LibreviewUploadPolicy.shouldRepairUploadCarbs(registro)
        } else {
            LibreviewUploadPolicy.shouldUploadCarbs(registro)
        }
        if (!eligible) return

        val channel = RegistroLibreviewSyncChannel.CARBS
        val currentQueueItem = queueRepository.getByRegistroAndChannel(registro.id, channel)
        val currentStatus = currentQueueItem?.let { RegistroLibreviewSyncStatus.fromValue(it.status) }
        val baseEventMillis = resolveLibreviewEventTimestamp(registro, channel)
        val pendingRecordNumber = currentQueueItem?.recordNumber
        val canonicalRecordNumber = LibreviewRecordNumber.from(registro.id, channel, baseEventMillis)
        val legacyRecordNumbers = linkedSetOf<Long>()
        registro.libreviewCarbsRecordNumber
            ?.takeIf { it != canonicalRecordNumber }
            ?.let { legacyRecordNumbers += it }
        pendingRecordNumber
            ?.takeIf { it != canonicalRecordNumber }
            ?.let { legacyRecordNumbers += it }
        if (includeAllOrigins) {
            legacyRecordNumbers.forEach { legacyRecordNumber ->
                val legacyEventMillis = resolveCarbsTimestampForRecordNumber(
                    registro = registro,
                    recordNumber = legacyRecordNumber,
                    fallbackTimestamp = baseEventMillis
                )
                enqueueLegacyDeleteOperation(
                    channel = channel,
                    recordNumber = legacyRecordNumber,
                    eventTimestampMillis = legacyEventMillis,
                    now = now
                )
            }
        }
        val eventMillis = baseEventMillis
        val payloadHash = LibreviewPayloadBuilder.hashPayload(
            channel = channel.value,
            operation = LibreviewPayloadOperation.UPSERT,
            recordNumber = canonicalRecordNumber,
            eventTimestampMillis = eventMillis,
            amountValue = registro.hidratosTotales.coerceAtLeast(0f)
        )
        if (
            registro.libreviewCarbsRecordNumber == canonicalRecordNumber &&
            registro.libreviewCarbsPayloadHash == payloadHash
        ) {
            if (currentQueueItem?.operation == RegistroLibreviewSyncOperation.UPSERT.value) {
                queueRepository.deleteByRegistroAndChannel(registro.id, channel)
            }
        } else if (
            isSameQueuedUpsert(
                item = currentQueueItem,
                recordNumber = canonicalRecordNumber,
                eventTimestampMillis = eventMillis,
                amountValue = registro.hidratosTotales,
                payloadHash = payloadHash
            )
        ) {
            if (currentStatus == RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD) {
                queueRepository.upsertPending(
                    registroId = registro.id,
                    channel = channel,
                    operation = RegistroLibreviewSyncOperation.UPSERT,
                    now = now,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = eventMillis,
                    amountValue = registro.hidratosTotales,
                    payloadHash = payloadHash
                )
            }
        } else {
            queueRepository.upsertPending(
                registroId = registro.id,
                channel = channel,
                operation = RegistroLibreviewSyncOperation.UPSERT,
                now = now,
                recordNumber = canonicalRecordNumber,
                eventTimestampMillis = eventMillis,
                amountValue = registro.hidratosTotales,
                payloadHash = payloadHash
            )
        }
    }

    private suspend fun enqueueUpsertInsulinForRegistro(
        registro: RegistroComida,
        now: Long,
        includeAllOrigins: Boolean,
        allowPendingInsulin: Boolean = false,
        nfcInsulinOnly: Boolean = false
    ) {
        val eligible = if (allowPendingInsulin) {
            if (nfcInsulinOnly) {
                LibreviewUploadPolicy.shouldUploadNfcInsulin(registro)
            } else {
                LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(registro)
            }
        } else if (includeAllOrigins) {
            LibreviewUploadPolicy.shouldRepairUploadInsulin(registro)
        } else {
            LibreviewUploadPolicy.shouldUploadNfcInsulin(registro)
        }
        if (!eligible) return

        val channel = RegistroLibreviewSyncChannel.NFC_INSULIN
        val currentQueueItem = queueRepository.getByRegistroAndChannel(registro.id, channel)
        val currentStatus = currentQueueItem?.let { RegistroLibreviewSyncStatus.fromValue(it.status) }
        val baseEventMillis = resolveLibreviewEventTimestamp(registro, channel)
        val pendingRecordNumber = currentQueueItem?.recordNumber
        val canonicalRecordNumber = LibreviewRecordNumber.from(registro.id, channel, baseEventMillis)
        val legacyRecordNumbers = linkedSetOf<Long>()
        registro.libreviewInsulinRecordNumber
            ?.takeIf { it != canonicalRecordNumber }
            ?.let { legacyRecordNumbers += it }
        pendingRecordNumber
            ?.takeIf { it != canonicalRecordNumber }
            ?.let { legacyRecordNumbers += it }
        if (includeAllOrigins) {
            legacyRecordNumbers.forEach { legacyRecordNumber ->
                val legacyEventMillis = resolveInsulinTimestampForRecordNumber(
                    registro = registro,
                    recordNumber = legacyRecordNumber,
                    fallbackTimestamp = baseEventMillis
                )
                enqueueLegacyDeleteOperation(
                    channel = channel,
                    recordNumber = legacyRecordNumber,
                    eventTimestampMillis = legacyEventMillis,
                    now = now
                )
            }
        }
        val eventMillis = baseEventMillis
        val payloadHash = LibreviewPayloadBuilder.hashPayload(
            channel = channel.value,
            operation = LibreviewPayloadOperation.UPSERT,
            recordNumber = canonicalRecordNumber,
            eventTimestampMillis = eventMillis,
            amountValue = registro.unidadesInsulina.coerceAtLeast(0f)
        )
        if (
            registro.libreviewInsulinRecordNumber == canonicalRecordNumber &&
            registro.libreviewInsulinPayloadHash == payloadHash
        ) {
            if (currentQueueItem?.operation == RegistroLibreviewSyncOperation.UPSERT.value) {
                queueRepository.deleteByRegistroAndChannel(registro.id, channel)
            }
        } else if (
            isSameQueuedUpsert(
                item = currentQueueItem,
                recordNumber = canonicalRecordNumber,
                eventTimestampMillis = eventMillis,
                amountValue = registro.unidadesInsulina,
                payloadHash = payloadHash
            )
        ) {
            if (currentStatus == RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD) {
                queueRepository.upsertPending(
                    registroId = registro.id,
                    channel = channel,
                    operation = RegistroLibreviewSyncOperation.UPSERT,
                    now = now,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = eventMillis,
                    amountValue = registro.unidadesInsulina,
                    payloadHash = payloadHash
                )
            }
        } else {
            queueRepository.upsertPending(
                registroId = registro.id,
                channel = channel,
                operation = RegistroLibreviewSyncOperation.UPSERT,
                now = now,
                recordNumber = canonicalRecordNumber,
                eventTimestampMillis = eventMillis,
                amountValue = registro.unidadesInsulina,
                payloadHash = payloadHash
            )
        }
    }

    suspend fun enqueueMissingCanonicalForManualSync(
        linkOffsetMinutes: Int? = null,
        linkOffsetUnits: Float? = null,
        nfcInsulinOnly: Boolean = false,
        minCarbsEventTimestampMillis: Long? = null,
        minInsulinEventTimestampMillis: Long? = null,
        forceReuploadCarbs: Boolean = false,
        forceReuploadInsulin: Boolean = false,
        now: Long = System.currentTimeMillis()
    ) {
        val registros = registroRepository
            .getAllRegistrosRaw()
            .sortedBy { it.id }
        if (registros.isEmpty()) return

        val toleranceMillis = resolveRepairLinkDeltaMillis(linkOffsetMinutes)
        val toleranceUnits = resolveRepairLinkAmountDelta(linkOffsetUnits)
        val carbsKeepers = selectRepairKeeperIds(
            registros = registros,
            channel = RegistroLibreviewSyncChannel.CARBS,
            timestampToleranceMillis = toleranceMillis,
            amountTolerance = toleranceUnits,
            isEligible = { registro, selectedChannel ->
                when (selectedChannel) {
                    RegistroLibreviewSyncChannel.CARBS -> {
                        val inScope = minCarbsEventTimestampMillis == null ||
                            resolveLibreviewEventTimestamp(registro, selectedChannel) >= minCarbsEventTimestampMillis
                        inScope && LibreviewUploadPolicy.shouldUploadCarbs(registro)
                    }
                    RegistroLibreviewSyncChannel.NFC_INSULIN ->
                        if (nfcInsulinOnly) {
                            val inScope = minInsulinEventTimestampMillis == null ||
                                resolveLibreviewEventTimestamp(registro, selectedChannel) >= minInsulinEventTimestampMillis
                            inScope && LibreviewUploadPolicy.shouldUploadNfcInsulin(registro)
                        } else {
                            val inScope = minInsulinEventTimestampMillis == null ||
                                resolveLibreviewEventTimestamp(registro, selectedChannel) >= minInsulinEventTimestampMillis
                            inScope && LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(registro)
                        }
                }
            }
        )
        val insulinKeepers = selectRepairKeeperIds(
            registros = registros,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            timestampToleranceMillis = toleranceMillis,
            amountTolerance = toleranceUnits,
            isEligible = { registro, selectedChannel ->
                when (selectedChannel) {
                    RegistroLibreviewSyncChannel.CARBS -> {
                        val inScope = minCarbsEventTimestampMillis == null ||
                            resolveLibreviewEventTimestamp(registro, selectedChannel) >= minCarbsEventTimestampMillis
                        inScope && LibreviewUploadPolicy.shouldRepairUploadCarbs(registro)
                    }
                    RegistroLibreviewSyncChannel.NFC_INSULIN ->
                        if (nfcInsulinOnly) {
                            val inScope = minInsulinEventTimestampMillis == null ||
                                resolveLibreviewEventTimestamp(registro, selectedChannel) >= minInsulinEventTimestampMillis
                            inScope && LibreviewUploadPolicy.shouldUploadNfcInsulin(registro)
                        } else {
                            val inScope = minInsulinEventTimestampMillis == null ||
                                resolveLibreviewEventTimestamp(registro, selectedChannel) >= minInsulinEventTimestampMillis
                            inScope && LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(registro)
                        }
                }
            }
        )

        registros.forEach { registro ->
            val wantCarbs = carbsKeepers.contains(registro.id)
            val wantInsulin = insulinKeepers.contains(registro.id)
            if (forceReuploadCarbs && wantCarbs) {
                registroRepository.clearLibreviewCarbsLink(
                    registroId = registro.id,
                    reconciliadoAt = now
                )
            }
            if (forceReuploadInsulin && wantInsulin) {
                registroRepository.clearLibreviewInsulinLink(
                    registroId = registro.id,
                    reconciliadoAt = now
                )
            }
            val effectiveRegistro = if (forceReuploadCarbs || forceReuploadInsulin) {
                registroRepository.getRegistroRawById(registro.id) ?: registro
            } else {
                registro
            }
            if (wantCarbs) {
                enqueueUpsertCarbsForRegistro(
                    registro = effectiveRegistro,
                    now = now,
                    includeAllOrigins = false
                )
            }
            if (wantInsulin) {
                enqueueUpsertInsulinForRegistro(
                    registro = effectiveRegistro,
                    now = now,
                    includeAllOrigins = false,
                    allowPendingInsulin = true,
                    nfcInsulinOnly = nfcInsulinOnly
                )
            }
        }
    }

    suspend fun buildRepairResetPreview(
        repairLinkOffsetMinutes: Int? = null,
        repairLinkOffsetUnits: Float? = null,
        minEventTimestampMillis: Long? = null
    ): LibreviewRepairResetPreview {
        val plan = buildRepairResetPlan(
            repairLinkDeltaMillis = resolveRepairLinkDeltaMillis(repairLinkOffsetMinutes),
            repairLinkAmountDelta = resolveRepairLinkAmountDelta(repairLinkOffsetUnits),
            minEventTimestampMillis = minEventTimestampMillis
        )
        return plan.toPreview()
    }

    suspend fun buildLocalRepairSnapshot(
        repairLinkOffsetMinutes: Int? = null,
        repairLinkOffsetUnits: Float? = null,
        minEventTimestampMillis: Long? = null,
        now: Long = System.currentTimeMillis()
    ): LibreviewRepairSnapshot {
        val plan = buildRepairResetPlan(
            repairLinkDeltaMillis = resolveRepairLinkDeltaMillis(repairLinkOffsetMinutes),
            repairLinkAmountDelta = resolveRepairLinkAmountDelta(repairLinkOffsetUnits),
            minEventTimestampMillis = minEventTimestampMillis
        )
        val knownRecordNumbers = linkedMapOf<Pair<String, Long>, LinkedHashSet<Long?>>()
        fun addKnownRecord(channel: String, recordNumber: Long, eventTimestampMillis: Long?) {
            val key = channel to recordNumber
            val timestamps = knownRecordNumbers.getOrPut(key) { linkedSetOf() }
            if (eventTimestampMillis != null) {
                timestamps.remove(null)
                timestamps += eventTimestampMillis
            } else if (timestamps.none { it != null }) {
                timestamps += null
            }
        }
        plan.deleteOps.forEach { op ->
            addKnownRecord(
                channel = op.channel.value,
                recordNumber = op.recordNumber,
                eventTimestampMillis = op.eventTimestampMillis
            )
        }
        plan.upsertOps.forEach { op ->
            addKnownRecord(
                channel = op.channel.value,
                recordNumber = op.recordNumber,
                eventTimestampMillis = op.eventTimestampMillis
            )
        }
        val catalogKeys = when {
            recordCatalogRepository == null -> emptySet()
            minEventTimestampMillis == null -> recordCatalogRepository.getKnownKeys()
            else -> recordCatalogRepository.getKnownKeysUpdatedSince(minEventTimestampMillis)
        }
        catalogKeys.forEach { key ->
            addKnownRecord(
                channel = key.channel.value,
                recordNumber = key.recordNumber,
                eventTimestampMillis = null
            )
        }

        return LibreviewRepairSnapshot(
            canonicalRecords = plan.toPreview().canonicalRecords,
            knownRecordNumbers = knownRecordNumbers.entries.flatMap { (key, timestamps) ->
                timestamps.map { eventTimestampMillis ->
                    LibreviewKnownRecordNumber(
                        channel = key.first,
                        recordNumber = key.second,
                        eventTimestampMillis = eventTimestampMillis
                    )
                }
            },
            seriallessKnownRecordNumbers = emptyList(),
            overlapCandidates = plan.overlapCandidates,
            upsertOps = plan.upsertOps.map { op ->
                LibreviewRepairQueueItem(
                    registroId = op.registroId,
                    channel = op.channel.value,
                    operation = op.operation.value,
                    recordNumber = op.recordNumber,
                    eventTimestampMillis = op.eventTimestampMillis,
                    amountValue = op.amountValue,
                    payloadHash = op.payloadHash
                )
            },
            nightscoutManagedDeleteOps = plan.nightscoutManagedDeleteOps.map { op ->
                LibreviewRepairQueueItem(
                    registroId = op.registroId,
                    channel = op.channel.value,
                    operation = op.operation.value,
                    recordNumber = op.recordNumber,
                    eventTimestampMillis = op.eventTimestampMillis,
                    amountValue = op.amountValue,
                    payloadHash = op.payloadHash
                )
            },
            nightscoutImportSkippedUpserts = plan.nightscoutImportSkippedUpserts,
            clearCarbsLinkRegistroIds = plan.clearCarbsLinkRegistroIds.toList(),
            clearInsulinLinkRegistroIds = plan.clearInsulinLinkRegistroIds.toList(),
            generatedAt = now
        )
    }

    suspend fun buildRemoteWipePlan(
        snapshot: LibreviewRepairSnapshot,
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long
    ): LibreviewWipePlan {
        val remoteEntries = libreviewRepository.fetchMeasurements(
            session = session,
            fromMillis = fromMillis,
            toMillis = toMillis
        )
        return classifyRemoteEntries(
            remoteEntries = remoteEntries,
            snapshot = snapshot
        )
    }

    suspend fun buildRemoteAggressiveWipePlan(
        snapshot: LibreviewRepairSnapshot,
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long
    ): LibreviewWipePlan {
        val basePlan = buildRemoteWipePlan(
            snapshot = snapshot,
            session = session,
            fromMillis = fromMillis,
            toMillis = toMillis
        )
        return buildRemoteAggressiveWipePlan(basePlan)
    }

    fun buildRemoteAggressiveWipePlan(
        basePlan: LibreviewWipePlan
    ): LibreviewWipePlan {
        if (basePlan.unknownOverlap.isEmpty() && basePlan.seriallessOverlap.isEmpty()) {
            return basePlan
        }
        val deleteCandidates = basePlan.knownAppManaged +
            basePlan.unknownOverlap +
            basePlan.seriallessOverlap
        return LibreviewWipePlan(
            knownAppManaged = deleteCandidates,
            unknownOverlap = basePlan.unknownOverlap,
            seriallessOverlap = basePlan.seriallessOverlap,
            foreign = basePlan.foreign
        )
    }

    fun buildBlindWipePlan(
        snapshot: LibreviewRepairSnapshot
    ): LibreviewWipePlan {
        val canonicalByKey = snapshot.upsertOps.associateBy { it.channel to it.recordNumber }
        val anchorTimestampsByChannel = snapshot.overlapCandidates
            .groupBy { it.channel }
            .mapValues { (_, candidates) ->
                candidates
                    .map { it.eventTimestampMillis }
                    .distinct()
                    .sorted()
            }
        val knownManaged = snapshot.knownRecordNumbers
            .distinctBy { Triple(it.channel, it.recordNumber, it.eventTimestampMillis) }
            .flatMap { known ->
                val key = known.channel to known.recordNumber
                val canonical = canonicalByKey[key]
                val fallbackTimestamp = canonical?.eventTimestampMillis ?: snapshot.generatedAt
                val timestamps = known.eventTimestampMillis?.let { listOf(it) }
                    ?: canonical?.eventTimestampMillis?.let { listOf(it) }
                    ?: selectBlindWipeTimestamps(
                        candidates = anchorTimestampsByChannel[known.channel],
                        fallbackTimestamp = fallbackTimestamp,
                        maxVariants = BLIND_WIPE_MAX_TIMESTAMP_VARIANTS
                    )
                timestamps.map { eventTimestampMillis ->
                    LibreviewRemoteEntry(
                        channel = known.channel,
                        recordNumber = known.recordNumber,
                        eventTimestampMillis = eventTimestampMillis,
                        amountValue = canonical?.amountValue ?: 0f,
                        timestampRaw = null
                    )
                }
            }
        return LibreviewWipePlan(
            knownAppManaged = knownManaged,
            unknownOverlap = emptyList(),
            seriallessOverlap = emptyList(),
            foreign = emptyList()
        )
    }

    private fun selectBlindWipeTimestamps(
        candidates: List<Long>?,
        fallbackTimestamp: Long,
        maxVariants: Int
    ): List<Long> {
        val unique = candidates
            ?.distinct()
            ?.sorted()
            ?.filter { it > 0L }
            .orEmpty()
        if (unique.isEmpty()) return listOf(fallbackTimestamp)
        if (unique.size <= maxVariants) return unique
        val step = (unique.size - 1).toDouble() / (maxVariants - 1).toDouble()
        return (0 until maxVariants)
            .map { idx ->
                val sourceIndex = (idx * step).roundToInt().coerceIn(0, unique.lastIndex)
                unique[sourceIndex]
            }
            .distinct()
    }

    fun encodeRepairSnapshot(snapshot: LibreviewRepairSnapshot): String {
        return json.encodeToString(snapshot)
    }

    fun decodeRepairSnapshot(raw: String?): LibreviewRepairSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(LibreviewRepairSnapshot.serializer(), raw)
        }.getOrNull()
    }

    suspend fun enqueueRepairWipeOnly(
        wipePlan: LibreviewWipePlan,
        minRepeatsPerKey: Int = DEFAULT_WIPE_MIN_REPEATS,
        maxRepeatsPerKey: Int = DEFAULT_WIPE_MAX_REPEATS,
        now: Long = System.currentTimeMillis()
    ) {
        queueRepository.deleteAll()
        val deleteSpecs = computeWipeDeleteSpecs(
            wipePlan = wipePlan,
            minRepeatsPerKey = minRepeatsPerKey,
            maxRepeatsPerKey = maxRepeatsPerKey,
            now = now
        )
        val usedSyntheticIds = mutableSetOf<Int>()
        deleteSpecs.forEach { spec ->
            repeat(spec.repeatCount) { index ->
                val sequence = index + 1
                var syntheticRegistroId = syntheticDeleteRegistroId(
                    channel = spec.channel,
                    recordNumber = spec.recordNumber,
                    sequence = sequence
                )
                while (!usedSyntheticIds.add(syntheticRegistroId)) {
                    syntheticRegistroId = if (syntheticRegistroId <= Int.MIN_VALUE + 1) {
                        -1
                    } else {
                        syntheticRegistroId - 1
                    }
                }
                queueRepository.upsertPending(
                    registroId = syntheticRegistroId,
                    channel = spec.channel,
                    operation = RegistroLibreviewSyncOperation.DELETE,
                    now = now,
                    recordNumber = spec.recordNumber,
                    eventTimestampMillis = spec.eventTimestampMillis,
                    amountValue = 0f,
                    payloadHash = LibreviewPayloadBuilder.hashPayload(
                        channel = spec.channel.value,
                        operation = LibreviewPayloadOperation.DELETE,
                        recordNumber = spec.recordNumber,
                        eventTimestampMillis = spec.eventTimestampMillis,
                        amountValue = 0f
                    )
                )
            }
        }
    }

    fun countWipeDeleteOps(
        wipePlan: LibreviewWipePlan,
        minRepeatsPerKey: Int = DEFAULT_WIPE_MIN_REPEATS,
        maxRepeatsPerKey: Int = DEFAULT_WIPE_MAX_REPEATS
    ): Int {
        return computeWipeDeleteSpecs(
            wipePlan = wipePlan,
            minRepeatsPerKey = minRepeatsPerKey,
            maxRepeatsPerKey = maxRepeatsPerKey,
            now = System.currentTimeMillis()
        ).sumOf { it.repeatCount }
    }

    fun countWipeDeleteOpsForKnownKeys(
        wipePlan: LibreviewWipePlan,
        keys: Set<Pair<String, Long>>,
        minRepeatsPerKey: Int = DEFAULT_WIPE_MIN_REPEATS,
        maxRepeatsPerKey: Int = DEFAULT_WIPE_MAX_REPEATS
    ): Int {
        if (keys.isEmpty()) return 0
        return computeWipeDeleteSpecs(
            wipePlan = wipePlan,
            minRepeatsPerKey = minRepeatsPerKey,
            maxRepeatsPerKey = maxRepeatsPerKey,
            now = System.currentTimeMillis()
        ).sumOf { spec ->
            if (keys.contains(spec.channel.value to spec.recordNumber)) spec.repeatCount else 0
        }
    }

    private fun computeWipeDeleteSpecs(
        wipePlan: LibreviewWipePlan,
        minRepeatsPerKey: Int,
        maxRepeatsPerKey: Int,
        now: Long
    ): List<WipeDeleteSpec> {
        if (wipePlan.knownAppManaged.isEmpty()) return emptyList()
        val minRepeats = minRepeatsPerKey.coerceAtLeast(1)
        val maxRepeats = max(maxRepeatsPerKey, minRepeats)
        return wipePlan.knownAppManaged
            .groupBy { it.channel to it.recordNumber }
            .flatMap { (key, entries) ->
                val channel = RegistroLibreviewSyncChannel.fromValue(key.first)
                    ?: return@flatMap emptyList()
                val recordNumber = key.second
                val observedCount = entries.size.coerceAtLeast(1)
                val targetOps = max(minRepeats, observedCount).coerceAtMost(maxRepeats)
                val timestampVariants = entries
                    .map { it.eventTimestampMillis ?: now }
                    .distinct()
                    .sorted()
                    .ifEmpty { listOf(now) }
                val chosenTimeline = if (timestampVariants.size >= targetOps) {
                    timestampVariants.take(targetOps)
                } else {
                    buildList {
                        addAll(timestampVariants)
                        repeat(targetOps - timestampVariants.size) {
                            add(timestampVariants.first())
                        }
                    }
                }
                val perTimestampRepeats = chosenTimeline.groupingBy { it }.eachCount()
                perTimestampRepeats.entries.map { (eventTimestampMillis, repeatCount) ->
                    WipeDeleteSpec(
                        channel = channel,
                        recordNumber = recordNumber,
                        eventTimestampMillis = eventTimestampMillis,
                        repeatCount = repeatCount
                    )
                }
            }
            .sortedWith(compareBy<WipeDeleteSpec> { it.channel.value }.thenBy { it.recordNumber })
    }

    suspend fun enqueueRepairUpsertOnly(
        snapshot: LibreviewRepairSnapshot,
        now: Long = System.currentTimeMillis()
    ) {
        queueRepository.deleteAll()
        snapshot.clearCarbsLinkRegistroIds.forEach { registroId ->
            registroRepository.clearLibreviewCarbsLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }
        snapshot.clearInsulinLinkRegistroIds.forEach { registroId ->
            registroRepository.clearLibreviewInsulinLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }
        snapshot.upsertOps.forEach { item ->
            val channel = RegistroLibreviewSyncChannel.fromValue(item.channel) ?: return@forEach
            val operation = RegistroLibreviewSyncOperation.fromValue(item.operation)
            queueRepository.upsertPending(
                registroId = item.registroId,
                channel = channel,
                operation = operation,
                now = now,
                recordNumber = item.recordNumber,
                eventTimestampMillis = item.eventTimestampMillis,
                amountValue = item.amountValue,
                payloadHash = item.payloadHash
            )
        }
    }

    suspend fun enqueueRepairUpsertPartialManual(
        snapshot: LibreviewRepairSnapshot,
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long,
        now: Long = System.currentTimeMillis()
    ): LibreviewPartialUpsertResult {
        val probe = libreviewRepository.probeMeasurementsReadEndpoint(
            session = session,
            fromMillis = fromMillis,
            toMillis = toMillis
        )
        if (!probe.success) {
            return LibreviewPartialUpsertResult(failedRead = true)
        }

        queueRepository.deleteAll()
        snapshot.clearCarbsLinkRegistroIds.forEach { registroId ->
            registroRepository.clearLibreviewCarbsLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }
        snapshot.clearInsulinLinkRegistroIds.forEach { registroId ->
            registroRepository.clearLibreviewInsulinLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }

        val remoteEntries = libreviewRepository.fetchMeasurements(
            session = session,
            fromMillis = fromMillis,
            toMillis = toMillis
        )
        if (remoteEntries.isEmpty() && !libreviewRepository.lastErrorMessage.isNullOrBlank()) {
            return LibreviewPartialUpsertResult(failedRead = true)
        }
        var planned = 0
        var skippedByRemote = 0
        var linkedToRemote = 0

        snapshot.upsertOps.forEach { item ->
            val registro = registroRepository.getRegistroRawById(item.registroId) ?: return@forEach
            if (!LibreviewUploadPolicy.isLocalRegistroEligible(registro)) {
                return@forEach
            }
            val channel = RegistroLibreviewSyncChannel.fromValue(item.channel) ?: return@forEach
            val operation = RegistroLibreviewSyncOperation.fromValue(item.operation)
            if (operation != RegistroLibreviewSyncOperation.UPSERT) return@forEach
            val amountTolerance = remoteMatchAmountTolerance(channel)
            val remoteMatch = libreviewRepository.findEquivalentRemoteEntry(
                remoteEntries = remoteEntries,
                channel = channel.value,
                recordNumber = item.recordNumber,
                eventTimestampMillis = item.eventTimestampMillis,
                amountValue = item.amountValue,
                timestampToleranceMillis = linkMatchDeltaMillis,
                amountTolerance = amountTolerance
            )
            if (remoteMatch != null) {
                skippedByRemote += 1
                when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> registroRepository.updateLibreviewCarbsLink(
                        registroId = item.registroId,
                        recordNumber = remoteMatch.recordNumber,
                        payloadHash = null,
                        reconciliadoAt = now
                    )
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> registroRepository.updateLibreviewInsulinLink(
                        registroId = item.registroId,
                        recordNumber = remoteMatch.recordNumber,
                        payloadHash = null,
                        reconciliadoAt = now
                    )
                }
                recordCatalogRepository?.upsert(
                    channel = channel,
                    recordNumber = remoteMatch.recordNumber,
                    sourceRegistroId = item.registroId,
                    now = now,
                    operation = RegistroLibreviewSyncOperation.UPSERT,
                    payloadHash = null
                )
                linkedToRemote += 1
            } else {
                queueRepository.upsertPending(
                    registroId = item.registroId,
                    channel = channel,
                    operation = operation,
                    now = now,
                    recordNumber = item.recordNumber,
                    eventTimestampMillis = item.eventTimestampMillis,
                    amountValue = item.amountValue,
                    payloadHash = item.payloadHash
                )
                planned += 1
            }
        }

        return LibreviewPartialUpsertResult(
            planned = planned,
            skippedByRemoteMatch = skippedByRemote,
            linkedToRemote = linkedToRemote,
            failedRead = false
        )
    }

    suspend fun verifyRepairOutcome(
        snapshot: LibreviewRepairSnapshot,
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long
    ): LibreviewRepairVerification {
        val remoteEntries = libreviewRepository.fetchMeasurements(
            session = session,
            fromMillis = fromMillis,
            toMillis = toMillis
        )
        val plan = classifyRemoteEntries(remoteEntries = remoteEntries, snapshot = snapshot)
        val canonicalKeys = snapshot.upsertOps
            .map { it.channel to it.recordNumber }
            .toSet()
        val remoteByKey = remoteEntries.groupBy { it.channel to it.recordNumber }
        val missingCanonical = canonicalKeys.count { key ->
            remoteByKey[key].isNullOrEmpty()
        }
        val managedDuplicates = plan.knownAppManaged
            .groupBy { it.channel to it.recordNumber }
            .count { it.value.size > 1 }
        return LibreviewRepairVerification(
            missingCanonical = missingCanonical,
            managedDuplicates = managedDuplicates,
            unknownOverlap = plan.unknownOverlap.size,
            seriallessOverlap = plan.seriallessOverlap.size,
            foreign = plan.foreign.size
        )
    }

    suspend fun enqueueRepairResetPlan(
        repairLinkOffsetMinutes: Int? = null,
        repairLinkOffsetUnits: Float? = null,
        minEventTimestampMillis: Long? = null,
        now: Long = System.currentTimeMillis()
    ): LibreviewRepairResetPreview {
        val plan = buildRepairResetPlan(
            repairLinkDeltaMillis = resolveRepairLinkDeltaMillis(repairLinkOffsetMinutes),
            repairLinkAmountDelta = resolveRepairLinkAmountDelta(repairLinkOffsetUnits),
            minEventTimestampMillis = minEventTimestampMillis
        )
        queueRepository.deleteAll()

        plan.clearCarbsLinkRegistroIds.forEach { registroId ->
            registroRepository.clearLibreviewCarbsLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }
        plan.clearInsulinLinkRegistroIds.forEach { registroId ->
            registroRepository.clearLibreviewInsulinLink(
                registroId = registroId,
                reconciliadoAt = now
            )
        }

        (plan.deleteOps + plan.upsertOps).forEach { item ->
            queueRepository.upsertPending(
                registroId = item.registroId,
                channel = item.channel,
                operation = item.operation,
                now = now,
                recordNumber = item.recordNumber,
                eventTimestampMillis = item.eventTimestampMillis,
                amountValue = item.amountValue,
                payloadHash = item.payloadHash
            )
        }

        return plan.toPreview()
    }

    private suspend fun buildRepairResetPlan(
        repairLinkDeltaMillis: Long,
        repairLinkAmountDelta: Float,
        minEventTimestampMillis: Long?
    ): RepairResetPlan {
        val allRegistros = registroRepository
            .getAllRegistrosRaw()
            .sortedBy { it.id }
        val registros = if (minEventTimestampMillis != null) {
            allRegistros.filter { registro ->
                val carbsTimestamp = resolveLibreviewEventTimestamp(
                    registro = registro,
                    channel = RegistroLibreviewSyncChannel.CARBS
                )
                val insulinTimestamp = resolveLibreviewEventTimestamp(
                    registro = registro,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN
                )
                carbsTimestamp >= minEventTimestampMillis || insulinTimestamp >= minEventTimestampMillis
            }
        } else {
            allRegistros
        }

        val deleteOps = linkedMapOf<Pair<RegistroLibreviewSyncChannel, Long>, PlannedQueueItem>()
        val nightscoutManagedDeleteOps = linkedMapOf<Pair<RegistroLibreviewSyncChannel, Long>, PlannedQueueItem>()
        val upsertOps = linkedMapOf<Pair<Int, RegistroLibreviewSyncChannel>, PlannedQueueItem>()
        val overlapCandidates = linkedMapOf<Triple<String, Long, Int>, LibreviewOverlapCandidate>()
        val clearCarbsLinks = mutableSetOf<Int>()
        val clearInsulinLinks = mutableSetOf<Int>()
        var nightscoutImportSkippedUpserts = 0
        var nextSyntheticDeleteId = -1
        val carbsKeepers = selectRepairKeeperIds(
            registros = registros,
            channel = RegistroLibreviewSyncChannel.CARBS,
            timestampToleranceMillis = repairLinkDeltaMillis,
            amountTolerance = repairLinkAmountDelta
        )
        val insulinKeepers = selectRepairKeeperIds(
            registros = registros,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            timestampToleranceMillis = repairLinkDeltaMillis,
            amountTolerance = repairLinkAmountDelta
        )

        registros.forEach { registro ->
            val origenRegistro = OrigenRegistro.fromValue(registro.origenRegistro)
            val isNightscoutImport = origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT
            val carbsAmount = registro.hidratosTotales
                .takeIf { it.isFinite() && it > 0f }
                ?: 0f
            val insulinAmount = registro.unidadesInsulina
                .takeIf { it.isFinite() && it > 0f }
                ?: 0f
            val carbsEligible = LibreviewUploadPolicy.shouldRepairUploadCarbs(registro) &&
                carbsAmount > 0f
            val insulinEligible = LibreviewUploadPolicy.shouldRepairUploadInsulin(registro) &&
                insulinAmount > 0f

            val channels = listOf(
                RegistroLibreviewSyncChannel.CARBS,
                RegistroLibreviewSyncChannel.NFC_INSULIN
            )
            channels.forEach { channel ->
                val linkedRecordNumber = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> registro.libreviewCarbsRecordNumber
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.libreviewInsulinRecordNumber
                }
                if (channel == RegistroLibreviewSyncChannel.CARBS && linkedRecordNumber != null) {
                    clearCarbsLinks += registro.id
                }
                if (channel == RegistroLibreviewSyncChannel.NFC_INSULIN && linkedRecordNumber != null) {
                    clearInsulinLinks += registro.id
                }

                val amountValue = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> carbsAmount
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> insulinAmount
                }
                val hasPositiveAmount = amountValue > 0f
                val eligibleByChannel = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> carbsEligible
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> insulinEligible
                }
                val isKeeperForChannel = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> carbsKeepers.contains(registro.id)
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> insulinKeepers.contains(registro.id)
                }
                val eligible = eligibleByChannel && isKeeperForChannel
                val wouldBeEligibleIgnoringOrigin = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> carbsAmount > 0f
                    RegistroLibreviewSyncChannel.NFC_INSULIN ->
                        insulinAmount > 0f &&
                            EstadoDosis.fromValue(registro.dosisEstado) == EstadoDosis.APLICADA
                }
                if (isNightscoutImport && wouldBeEligibleIgnoringOrigin && !eligibleByChannel) {
                    nightscoutImportSkippedUpserts += 1
                }
                if (linkedRecordNumber == null && !hasPositiveAmount) return@forEach

                val canonicalTimestamp = resolveLibreviewEventTimestamp(registro, channel)
                if (wouldBeEligibleIgnoringOrigin && hasPositiveAmount) {
                    val amountMilli = (amountValue * 1000f).roundToInt()
                    val overlapKey = Triple(channel.value, canonicalTimestamp, amountMilli)
                    overlapCandidates.putIfAbsent(
                        overlapKey,
                        LibreviewOverlapCandidate(
                            channel = channel.value,
                            eventTimestampMillis = canonicalTimestamp,
                            amountValue = amountValue
                        )
                    )
                }
                val canonicalRecordNumber = LibreviewRecordNumber.from(
                    registroId = registro.id,
                    channel = channel,
                    effectiveTimestamp = canonicalTimestamp
                )
                val legacyTimestamp = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> registro.dosisConfirmadaAt ?: registro.fecha
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.fecha
                }
                val deleteCandidates = linkedSetOf<Long>()
                if (linkedRecordNumber != null) {
                    deleteCandidates += linkedRecordNumber
                }
                deleteCandidates += canonicalRecordNumber
                deleteCandidates += legacyRecordNumberAliases(
                    channel = channel,
                    effectiveTimestamp = canonicalTimestamp
                )
                if (legacyTimestamp != canonicalTimestamp) {
                    deleteCandidates += LibreviewRecordNumber.from(
                        registroId = registro.id,
                        channel = channel,
                        effectiveTimestamp = legacyTimestamp
                    )
                    deleteCandidates += legacyRecordNumberAliases(
                        channel = channel,
                        effectiveTimestamp = legacyTimestamp
                    )
                }

                deleteCandidates.forEach { recordNumber ->
                    val deleteKey = channel to recordNumber
                    if (deleteOps.containsKey(deleteKey)) return@forEach
                    val eventMillis = if (channel == RegistroLibreviewSyncChannel.CARBS) {
                        resolveCarbsTimestampForRecordNumber(
                            registro = registro,
                            recordNumber = recordNumber,
                            fallbackTimestamp = canonicalTimestamp
                        )
                    } else {
                        resolveInsulinTimestampForRecordNumber(
                            registro = registro,
                            recordNumber = recordNumber,
                            fallbackTimestamp = canonicalTimestamp
                        )
                    }
                    val syntheticDeleteId = nextSyntheticDeleteId
                    if (nextSyntheticDeleteId > Int.MIN_VALUE) {
                        nextSyntheticDeleteId -= 1
                    }
                    deleteOps[deleteKey] = PlannedQueueItem(
                        registroId = syntheticDeleteId,
                        channel = channel,
                        operation = RegistroLibreviewSyncOperation.DELETE,
                        recordNumber = recordNumber,
                        eventTimestampMillis = eventMillis,
                        amountValue = 0f,
                        payloadHash = LibreviewPayloadBuilder.hashPayload(
                            channel = channel.value,
                            operation = LibreviewPayloadOperation.DELETE,
                            recordNumber = recordNumber,
                            eventTimestampMillis = eventMillis,
                            amountValue = 0f
                        )
                    )
                    if (isNightscoutImport) {
                        nightscoutManagedDeleteOps[deleteKey] = deleteOps.getValue(deleteKey)
                    }
                }

                if (!eligible) return@forEach

                upsertOps[registro.id to channel] = PlannedQueueItem(
                    registroId = registro.id,
                    channel = channel,
                    operation = RegistroLibreviewSyncOperation.UPSERT,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = canonicalTimestamp,
                    amountValue = amountValue,
                    payloadHash = LibreviewPayloadBuilder.hashPayload(
                        channel = channel.value,
                        operation = LibreviewPayloadOperation.UPSERT,
                        recordNumber = canonicalRecordNumber,
                        eventTimestampMillis = canonicalTimestamp,
                        amountValue = amountValue
                    )
                )
            }
        }

        return RepairResetPlan(
            deleteOps = deleteOps.values.toList(),
            upsertOps = upsertOps.values.toList(),
            overlapCandidates = overlapCandidates.values.toList(),
            nightscoutManagedDeleteOps = nightscoutManagedDeleteOps.values.toList(),
            nightscoutImportSkippedUpserts = nightscoutImportSkippedUpserts,
            clearCarbsLinkRegistroIds = clearCarbsLinks,
            clearInsulinLinkRegistroIds = clearInsulinLinks
        )
    }

    private fun classifyRemoteEntries(
        remoteEntries: List<LibreviewRemoteEntry>,
        snapshot: LibreviewRepairSnapshot
    ): LibreviewWipePlan {
        if (remoteEntries.isEmpty()) return LibreviewWipePlan()
        val knownKeys = snapshot.knownRecordNumbers
            .map { it.channel to it.recordNumber }
            .toSet()

        val knownManaged = mutableListOf<LibreviewRemoteEntry>()
        val unknownOverlap = mutableListOf<LibreviewRemoteEntry>()
        val seriallessOverlap = mutableListOf<LibreviewRemoteEntry>()
        val foreign = mutableListOf<LibreviewRemoteEntry>()

        remoteEntries.forEach { remote ->
            val key = remote.channel to remote.recordNumber
            when {
                isProtectedRemoteEntry(remote) -> foreign += remote
                knownKeys.contains(key) -> knownManaged += remote
                isSeriallessRemoteEntry(remote) -> seriallessOverlap += remote
                overlapsWithLocalCanonical(remote = remote, snapshot = snapshot) -> unknownOverlap += remote
                else -> foreign += remote
            }
        }

        return LibreviewWipePlan(
            knownAppManaged = knownManaged,
            unknownOverlap = unknownOverlap,
            seriallessOverlap = seriallessOverlap,
            foreign = foreign
        )
    }

    private fun isSeriallessRemoteEntry(remote: LibreviewRemoteEntry): Boolean {
        val serial = remote.deviceSerial?.trim()?.lowercase().orEmpty()
        if (serial.isBlank()) return true
        return serial == "unknown" ||
            serial == "n/a" ||
            serial == "na" ||
            serial == "none" ||
            serial == "null" ||
            serial == "-" ||
            serial == "noserial" ||
            serial == "no_serial"
    }

    private fun isProtectedRemoteEntry(remote: LibreviewRemoteEntry): Boolean {
        val protectedValues = PROTECTED_REMOTE_DEVICE_IDENTIFIERS
        if (protectedValues.isEmpty()) return false
        val candidates = listOfNotNull(
            remote.deviceSerial?.trim()?.lowercase(),
            remote.deviceId?.trim()?.lowercase(),
            remote.sourceTag?.trim()?.lowercase()
        ).filter { it.isNotBlank() }
        return candidates.any { candidate ->
            protectedValues.any { protected ->
                candidate == protected || candidate.contains(protected)
            }
        }
    }

    private fun legacyRecordNumberAliases(
        channel: RegistroLibreviewSyncChannel,
        effectiveTimestamp: Long
    ): Set<Long> {
        val seconds = effectiveTimestamp / 1_000L
        val channelValue = channel.value
        return linkedSetOf(
            LibreviewRecordNumber.hash64("$channelValue:$effectiveTimestamp"),
            LibreviewRecordNumber.hash64("$effectiveTimestamp:$channelValue"),
            LibreviewRecordNumber.hash64("$channelValue|$effectiveTimestamp"),
            LibreviewRecordNumber.hash64("$effectiveTimestamp|$channelValue"),
            LibreviewRecordNumber.hash64("$channelValue:$seconds"),
            LibreviewRecordNumber.hash64("$seconds:$channelValue")
        )
    }

    private fun overlapsWithLocalCanonical(
        remote: LibreviewRemoteEntry,
        snapshot: LibreviewRepairSnapshot
    ): Boolean {
        val remoteTimestamp = remote.eventTimestampMillis ?: return false
        val remoteAmount = remote.amountValue
        if (!remoteAmount.isFinite() || remoteAmount <= 0f) return false
        val amountTolerance = when (remote.channel) {
            RegistroLibreviewSyncChannel.CARBS.value -> remoteMatchAmountTolerance(RegistroLibreviewSyncChannel.CARBS)
            RegistroLibreviewSyncChannel.NFC_INSULIN.value -> remoteMatchAmountTolerance(RegistroLibreviewSyncChannel.NFC_INSULIN)
            else -> return false
        }
        val overlapCandidates = if (snapshot.overlapCandidates.isNotEmpty()) {
            snapshot.overlapCandidates.asSequence()
        } else {
            snapshot.upsertOps.asSequence().map { local ->
                LibreviewOverlapCandidate(
                    channel = local.channel,
                    eventTimestampMillis = local.eventTimestampMillis,
                    amountValue = local.amountValue
                )
            }
        }
        return overlapCandidates.any { local ->
            if (local.channel != remote.channel) return@any false
            abs(local.eventTimestampMillis - remoteTimestamp) <= linkMatchDeltaMillis &&
                abs(local.amountValue - remoteAmount) <= amountTolerance
        }
    }

    private data class RepairCandidate(
        val registro: RegistroComida,
        val timestamp: Long,
        val amount: Float
    )

    private fun selectRepairKeeperIds(
        registros: List<RegistroComida>,
        channel: RegistroLibreviewSyncChannel,
        timestampToleranceMillis: Long,
        amountTolerance: Float,
        isEligible: (RegistroComida, RegistroLibreviewSyncChannel) -> Boolean = { registro, selectedChannel ->
            when (selectedChannel) {
                RegistroLibreviewSyncChannel.CARBS -> LibreviewUploadPolicy.shouldRepairUploadCarbs(registro)
                RegistroLibreviewSyncChannel.NFC_INSULIN -> LibreviewUploadPolicy.shouldRepairUploadInsulin(registro)
            }
        }
    ): Set<Int> {
        val candidates = registros
            .asSequence()
            .mapNotNull { registro ->
                val amount = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> registro.hidratosTotales
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.unidadesInsulina
                }
                val eligible = isEligible(registro, channel)
                if (!eligible || !amount.isFinite() || amount <= 0f) return@mapNotNull null
                RepairCandidate(
                    registro = registro,
                    timestamp = resolveLibreviewEventTimestamp(registro, channel),
                    amount = amount
                )
            }
            .sortedBy { it.timestamp }
            .toList()
        if (candidates.isEmpty()) return emptySet()

        val processed = mutableSetOf<Int>()
        val keepers = mutableSetOf<Int>()

        candidates.forEach { base ->
            if (processed.contains(base.registro.id)) return@forEach
            val cluster = candidates.filter { candidate ->
                !processed.contains(candidate.registro.id) &&
                    abs(candidate.timestamp - base.timestamp) <= timestampToleranceMillis &&
                    abs(candidate.amount - base.amount) <= amountTolerance
            }
            if (cluster.isEmpty()) return@forEach

            val keeper = cluster
                .map { it.registro }
                .sortedWith(
                    compareByDescending<RegistroComida> { scoreRepairKeeper(it, channel) }
                        .thenBy { it.id }
                )
                .first()
            keepers += keeper.id
            cluster.forEach { candidate -> processed += candidate.registro.id }
        }

        return keepers
    }

    private fun resolveRepairLinkDeltaMillis(repairLinkOffsetMinutes: Int?): Long {
        val configuredMinutes = repairLinkOffsetMinutes
            ?.coerceIn(0, 180)
            ?: (linkMatchDeltaMillis / 60_000L).toInt().coerceIn(0, 180)
        return configuredMinutes * 60_000L
    }

    private fun resolveRepairLinkAmountDelta(repairLinkOffsetUnits: Float?): Float {
        val configuredUnits = repairLinkOffsetUnits
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 5f)
            ?: linkMatchInsulinDelta.takeIf { it.isFinite() }?.coerceIn(0f, 5f)
            ?: 0f
        return configuredUnits
    }

    private fun remoteMatchAmountTolerance(channel: RegistroLibreviewSyncChannel): Float {
        return when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> LINK_MATCH_CARBS_DELTA
            RegistroLibreviewSyncChannel.NFC_INSULIN -> max(linkMatchInsulinDelta, 0.3f)
        }
    }

    private fun scoreRepairKeeper(
        registro: RegistroComida,
        channel: RegistroLibreviewSyncChannel
    ): Int {
        var score = 0
        if (registro.hidratosTotales > 0f || registro.racionesCalculadas > 0f) score += 1_000
        if (OrigenRegistro.fromValue(registro.origenRegistro) == OrigenRegistro.LOCAL) score += 600
        if (!LibreviewUploadPolicy.isNovoPenNfcRegistro(registro)) score += 200
        val hasChannelLink = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> registro.libreviewCarbsRecordNumber != null
            RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.libreviewInsulinRecordNumber != null
        }
        if (hasChannelLink) score += 100
        return score
    }

    private fun isEligibleForChannelInCurrentMode(
        registro: RegistroComida,
        channel: RegistroLibreviewSyncChannel,
        repairMode: Boolean,
        allowPendingInsulin: Boolean
    ): Boolean {
        return when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> if (repairMode) {
                LibreviewUploadPolicy.shouldRepairUploadCarbs(registro)
            } else {
                LibreviewUploadPolicy.shouldUploadCarbs(registro)
            }
            RegistroLibreviewSyncChannel.NFC_INSULIN -> if (allowPendingInsulin) {
                LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(registro)
            } else if (repairMode) {
                LibreviewUploadPolicy.shouldRepairUploadInsulin(registro)
            } else {
                LibreviewUploadPolicy.shouldUploadNfcInsulin(registro)
            }
        }
    }

    private suspend fun shouldSkipImportedInFavorOfLocal(
        registro: RegistroComida,
        channel: RegistroLibreviewSyncChannel,
        amountValue: Float,
        eventTimestampMillis: Long,
        repairMode: Boolean,
        allowPendingInsulin: Boolean
    ): Boolean {
        if (OrigenRegistro.fromValue(registro.origenRegistro) != OrigenRegistro.NIGHTSCOUT_IMPORT) {
            return false
        }
        val from = eventTimestampMillis - linkMatchDeltaMillis
        val to = eventTimestampMillis + linkMatchDeltaMillis
        val toleranceAmount = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> LINK_MATCH_CARBS_DELTA
            RegistroLibreviewSyncChannel.NFC_INSULIN -> linkMatchInsulinDelta
        }
        val locals = registroRepository.getRegistrosInRangeRaw(from, to)
        return locals.any { candidate ->
            if (candidate.id == registro.id) return@any false
            if (OrigenRegistro.fromValue(candidate.origenRegistro) != OrigenRegistro.LOCAL) return@any false
            if (!isEligibleForChannelInCurrentMode(candidate, channel, repairMode, allowPendingInsulin)) {
                return@any false
            }
            val candidateAmount = when (channel) {
                RegistroLibreviewSyncChannel.CARBS -> candidate.hidratosTotales
                RegistroLibreviewSyncChannel.NFC_INSULIN -> candidate.unidadesInsulina
            }
            if (!candidateAmount.isFinite() || candidateAmount <= 0f) return@any false
            val candidateTimestamp = resolveLibreviewEventTimestamp(candidate, channel)
            abs(candidateTimestamp - eventTimestampMillis) <= linkMatchDeltaMillis &&
                abs(candidateAmount - amountValue) <= toleranceAmount
        }
    }

    suspend fun enqueueDeleteForRegistro(
        registro: RegistroComida,
        now: Long = System.currentTimeMillis()
    ) {
        registro.libreviewCarbsRecordNumber?.let { recordNumber ->
            val baseEventMillis = resolveLibreviewEventTimestamp(
                registro,
                RegistroLibreviewSyncChannel.CARBS
            )
            val eventMillis = resolveCarbsTimestampForRecordNumber(
                registro = registro,
                recordNumber = recordNumber,
                fallbackTimestamp = baseEventMillis
            )
            queueRepository.upsertPending(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.CARBS,
                operation = RegistroLibreviewSyncOperation.DELETE,
                now = now,
                recordNumber = recordNumber,
                eventTimestampMillis = eventMillis,
                amountValue = 0f,
                payloadHash = registro.libreviewCarbsPayloadHash
            )
        }
        registro.libreviewInsulinRecordNumber?.let { recordNumber ->
            val baseEventMillis = resolveLibreviewEventTimestamp(
                registro,
                RegistroLibreviewSyncChannel.NFC_INSULIN
            )
            val eventMillis = resolveInsulinTimestampForRecordNumber(
                registro = registro,
                recordNumber = recordNumber,
                fallbackTimestamp = baseEventMillis
            )
            queueRepository.upsertPending(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                operation = RegistroLibreviewSyncOperation.DELETE,
                now = now,
                recordNumber = recordNumber,
                eventTimestampMillis = eventMillis,
                amountValue = 0f,
                payloadHash = registro.libreviewInsulinPayloadHash
            )
        }
    }

    suspend fun enqueueBackfill(
        fromMillis: Long,
        toMillis: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val registros = registroRepository.getRegistrosInRangeRaw(fromMillis, toMillis)
        val sorted = registros.sortedBy { it.id }
        val carbsKeepers = selectRepairKeeperIds(
            registros = sorted,
            channel = RegistroLibreviewSyncChannel.CARBS,
            timestampToleranceMillis = linkMatchDeltaMillis,
            amountTolerance = LINK_MATCH_CARBS_DELTA,
            isEligible = { r, ch ->
                when (ch) {
                    RegistroLibreviewSyncChannel.CARBS ->
                        LibreviewUploadPolicy.shouldUploadCarbs(r)
                    RegistroLibreviewSyncChannel.NFC_INSULIN ->
                        LibreviewUploadPolicy.shouldUploadNfcInsulin(r)
                }
            }
        )
        val insulinKeepers = selectRepairKeeperIds(
            registros = sorted,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            timestampToleranceMillis = linkMatchDeltaMillis,
            amountTolerance = linkMatchInsulinDelta,
            isEligible = { r, ch ->
                when (ch) {
                    RegistroLibreviewSyncChannel.CARBS ->
                        LibreviewUploadPolicy.shouldUploadCarbs(r)
                    RegistroLibreviewSyncChannel.NFC_INSULIN ->
                        LibreviewUploadPolicy.shouldUploadNfcInsulin(r)
                }
            }
        )
        sorted.forEach { registro ->
            val wantCarbs = carbsKeepers.contains(registro.id)
            val wantInsulin = insulinKeepers.contains(registro.id)
            if (wantCarbs) {
                enqueueUpsertCarbsForRegistro(registro, now, includeAllOrigins = false)
            }
            if (wantInsulin) {
                enqueueUpsertInsulinForRegistro(registro, now, includeAllOrigins = false)
            }
        }
    }

    suspend fun sync(
        profile: UsuarioProfile,
        session: LibreviewSession,
        bypassFailureBackoff: Boolean = false,
        prioritizeDeleteOperations: Boolean = false,
        repairMode: Boolean = false,
        allowPendingInsulin: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): LibreviewSyncRunResult {
        if (!profile.libreviewSyncActivo) return LibreviewSyncRunResult()
        val orderedPending = if (prioritizeDeleteOperations) {
            queueRepository.getPendingOrFailedPrioritizingDeletes()
        } else {
            queueRepository.getPendingOrFailed()
        }
        if (orderedPending.isEmpty()) return LibreviewSyncRunResult()
        if (prioritizeDeleteOperations && repairMode) {
            val deleteOnlyPending = orderedPending.filter {
                it.operation == RegistroLibreviewSyncOperation.DELETE.value
            }
            if (deleteOnlyPending.isEmpty()) return LibreviewSyncRunResult()
            return processPendingQueueItems(
                pending = deleteOnlyPending,
                session = session,
                bypassFailureBackoff = bypassFailureBackoff,
                repairMode = repairMode,
                allowPendingInsulin = allowPendingInsulin,
                prioritizeDeleteOperations = prioritizeDeleteOperations,
                now = now
            )
        }
        val pendingDeleteOps = orderedPending.filter {
            it.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                RegistroLibreviewSyncStatus.fromValue(it.status) == RegistroLibreviewSyncStatus.PENDING
        }
        val deletePhaseActive = prioritizeDeleteOperations && pendingDeleteOps.isNotEmpty()
        val pending = if (deletePhaseActive) {
            pendingDeleteOps
        } else if (prioritizeDeleteOperations) {
            val (upserts, failedDeletes) = orderedPending.partition {
                it.operation != RegistroLibreviewSyncOperation.DELETE.value
            }
            upserts + failedDeletes
        } else {
            orderedPending
        }
        if (pending.isEmpty()) return LibreviewSyncRunResult()
        return processPendingQueueItems(
            pending = pending,
            session = session,
            bypassFailureBackoff = bypassFailureBackoff,
            repairMode = repairMode,
            allowPendingInsulin = allowPendingInsulin,
            prioritizeDeleteOperations = prioritizeDeleteOperations,
            now = now
        )
    }

    private suspend fun processPendingQueueItems(
        pending: List<RegistroLibreviewSync>,
        session: LibreviewSession,
        bypassFailureBackoff: Boolean,
        repairMode: Boolean,
        allowPendingInsulin: Boolean,
        prioritizeDeleteOperations: Boolean,
        now: Long
    ): LibreviewSyncRunResult {
        var processedCount = 0
        var consecutiveFailureCount = 0
        var abortedByConsecutiveErrors = false

        for (item in pending) {
            val outcome = processQueueItem(
                item = item,
                session = session,
                bypassFailureBackoff = bypassFailureBackoff,
                repairMode = repairMode,
                allowPendingInsulin = allowPendingInsulin,
                now = now
            )
            processedCount += 1
            if (!outcome.attemptedUpload) continue

            if (outcome.error == null) {
                consecutiveFailureCount = 0
            } else {
                consecutiveFailureCount += 1
            }

            if (consecutiveFailureCount >= MAX_CONSECUTIVE_FAILED_UPLOADS) {
                abortedByConsecutiveErrors = true
                break
            }
        }

        val stillPendingOrFailed = if (prioritizeDeleteOperations) {
            queueRepository.getPendingOrFailedPrioritizingDeletes()
        } else {
            queueRepository.getPendingOrFailed()
        }
        val failed = stillPendingOrFailed.filter {
            RegistroLibreviewSyncStatus.fromValue(it.status) == RegistroLibreviewSyncStatus.FAILED
        }
        return LibreviewSyncRunResult(
            processedPending = processedCount,
            failedPending = failed.size,
            maxFailedAttempts = failed.maxOfOrNull { it.attempts } ?: 0,
            abortedByConsecutiveErrors = abortedByConsecutiveErrors
        )
    }

    private suspend fun processQueueItem(
        item: RegistroLibreviewSync,
        session: LibreviewSession,
        bypassFailureBackoff: Boolean,
        repairMode: Boolean,
        allowPendingInsulin: Boolean,
        now: Long
    ): QueueProcessOutcome {
        val channel = RegistroLibreviewSyncChannel.fromValue(item.channel) ?: run {
            // Cleanup legacy/unknown queue channel values that can keep stale FAILED errors forever.
            queueRepository.deleteByRegistroAndChannelValue(item.registroId, item.channel)
            return QueueProcessOutcome(attemptedUpload = false)
        }
        val currentItem = queueRepository.getByRegistroAndChannel(item.registroId, channel)
            ?: return QueueProcessOutcome(attemptedUpload = false)
        val currentStatus = RegistroLibreviewSyncStatus.fromValue(currentItem.status)
        if (currentStatus != RegistroLibreviewSyncStatus.PENDING &&
            currentStatus != RegistroLibreviewSyncStatus.FAILED
        ) {
            return QueueProcessOutcome(attemptedUpload = false)
        }

        if (!bypassFailureBackoff &&
            currentStatus == RegistroLibreviewSyncStatus.FAILED
        ) {
            val delayMillis = NightscoutRetryPolicy.nextDelayMinutes(currentItem.attempts) * 60_000L
            if (now - currentItem.updatedAt < delayMillis) {
                return QueueProcessOutcome(attemptedUpload = false)
            }
        }

        val operation = RegistroLibreviewSyncOperation.fromValue(currentItem.operation)
        return when (operation) {
            RegistroLibreviewSyncOperation.UPSERT -> processUpsert(
                item = currentItem,
                channel = channel,
                session = session,
                repairMode = repairMode,
                allowPendingInsulin = allowPendingInsulin,
                now = now
            )
            RegistroLibreviewSyncOperation.DELETE -> processDelete(
                item = currentItem,
                channel = channel,
                session = session,
                repairMode = repairMode,
                now = now
            )
        }
    }

    private suspend fun processUpsert(
        item: RegistroLibreviewSync,
        channel: RegistroLibreviewSyncChannel,
        session: LibreviewSession,
        repairMode: Boolean,
        allowPendingInsulin: Boolean,
        now: Long
    ): QueueProcessOutcome {
        val registro = registroRepository.getRegistroRawById(item.registroId)
        if (registro == null) {
            queueRepository.deleteByRegistroAndChannel(item.registroId, channel)
            return QueueProcessOutcome(attemptedUpload = false)
        }
        if (!LibreviewUploadPolicy.isLocalRegistroEligible(registro)) {
            queueRepository.markSyncedNoUpload(registro.id, channel, now)
            return QueueProcessOutcome(attemptedUpload = false)
        }

        val baseEventMillis = resolveLibreviewEventTimestamp(registro, channel)
        val amountValue = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> registro.hidratosTotales
            RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.unidadesInsulina
        }
        val eligible = isEligibleForChannelInCurrentMode(
            registro = registro,
            channel = channel,
            repairMode = repairMode,
            allowPendingInsulin = allowPendingInsulin
        )

        if (!eligible || !amountValue.isFinite() || amountValue <= 0f) {
            queueRepository.markSyncedNoUpload(registro.id, channel, now)
            return QueueProcessOutcome(attemptedUpload = false)
        }

        if (
            shouldSkipImportedInFavorOfLocal(
                registro = registro,
                channel = channel,
                amountValue = amountValue,
                eventTimestampMillis = baseEventMillis,
                repairMode = repairMode,
                allowPendingInsulin = allowPendingInsulin
            )
        ) {
            queueRepository.markSyncedNoUpload(registro.id, channel, now)
            return QueueProcessOutcome(attemptedUpload = false)
        }

        val linkedRecordNumber = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> registro.libreviewCarbsRecordNumber
            RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.libreviewInsulinRecordNumber
        }
        val canonicalRecordNumber = LibreviewRecordNumber.from(registro.id, channel, baseEventMillis)
        val existingLocalRecordNumber = findExistingLocalRecordNumber(
            sourceRegistro = registro,
            channel = channel,
            amountValue = amountValue,
            eventTimestampMillis = baseEventMillis
        )
        if (existingLocalRecordNumber != null && existingLocalRecordNumber != canonicalRecordNumber) {
            when (channel) {
                RegistroLibreviewSyncChannel.CARBS -> {
                    registroRepository.updateLibreviewCarbsLink(
                        registroId = registro.id,
                        recordNumber = existingLocalRecordNumber,
                        payloadHash = null,
                        reconciliadoAt = now
                    )
                }
                RegistroLibreviewSyncChannel.NFC_INSULIN -> {
                    registroRepository.updateLibreviewInsulinLink(
                        registroId = registro.id,
                        recordNumber = existingLocalRecordNumber,
                        payloadHash = null,
                        reconciliadoAt = now
                    )
                }
            }
            recordCatalogRepository?.upsert(
                channel = channel,
                recordNumber = existingLocalRecordNumber,
                sourceRegistroId = registro.id,
                now = now,
                operation = RegistroLibreviewSyncOperation.UPSERT,
                payloadHash = null
            )
            queueRepository.markSyncedNoUpload(registro.id, channel, now)
            return QueueProcessOutcome(attemptedUpload = false)
        }
        val legacyRecordNumbers = linkedSetOf<Long>()
        linkedRecordNumber
            ?.takeIf { it != canonicalRecordNumber }
            ?.let { legacyRecordNumbers += it }
        item.recordNumber
            ?.takeIf { it != canonicalRecordNumber }
            ?.let { legacyRecordNumbers += it }
        if (repairMode) {
            legacyRecordNumbers.forEach { legacyRecordNumber ->
                val legacyEventMillis = if (channel == RegistroLibreviewSyncChannel.CARBS) {
                    resolveCarbsTimestampForRecordNumber(
                        registro = registro,
                        recordNumber = legacyRecordNumber,
                        fallbackTimestamp = baseEventMillis
                    )
                } else {
                    resolveInsulinTimestampForRecordNumber(
                        registro = registro,
                        recordNumber = legacyRecordNumber,
                        fallbackTimestamp = baseEventMillis
                    )
                }
                enqueueLegacyDeleteOperation(
                    channel = channel,
                    recordNumber = legacyRecordNumber,
                    eventTimestampMillis = legacyEventMillis,
                    now = now
                )
            }
        }
        val recordNumber = canonicalRecordNumber
        val eventMillis = baseEventMillis

        val payloadResult = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> LibreviewPayloadBuilder.buildCarbsPayload(
                userToken = session.userToken,
                recordNumber = recordNumber,
                eventTimestampMillis = eventMillis,
                carbsGrams = amountValue,
                operation = LibreviewPayloadOperation.UPSERT,
                connectedInsulinDevices = connectedInsulinDevicesForPayload(channel)
            )
            RegistroLibreviewSyncChannel.NFC_INSULIN -> LibreviewPayloadBuilder.buildInsulinPayload(
                userToken = session.userToken,
                recordNumber = recordNumber,
                eventTimestampMillis = eventMillis,
                units = amountValue,
                operation = LibreviewPayloadOperation.UPSERT,
                connectedInsulinDevices = connectedInsulinDevicesForPayload(channel)
            )
        }

        val existingHash = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> registro.libreviewCarbsPayloadHash
            RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.libreviewInsulinPayloadHash
        }
        val existingLinkedRecord = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> registro.libreviewCarbsRecordNumber
            RegistroLibreviewSyncChannel.NFC_INSULIN -> registro.libreviewInsulinRecordNumber
        }
        if (existingLinkedRecord == recordNumber && existingHash == payloadResult.payloadHash) {
            queueRepository.markSyncedNoUpload(registro.id, channel, now)
            if (repairMode) {
                queueRepository.deleteDeleteOperationsByRecordNumber(channel, recordNumber)
            }
            return QueueProcessOutcome(attemptedUpload = false)
        }

        val post = libreviewRepository.postMeasurements(
            baseUrl = session.baseUrl,
            apiKey = session.apiKey,
            userToken = session.userToken,
            requestPayload = payloadResult.request,
            domain = session.domain,
            gatewayType = session.gatewayType
        )
        if (!post.success) {
            val error = libreviewRepository.lastErrorMessage ?: post.reason ?: "Error LibreView"
            queueRepository.markFailed(registro.id, channel, error, now)
            return QueueProcessOutcome(
                attemptedUpload = true,
                error = error
            )
        }

        when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> {
                registroRepository.updateLibreviewCarbsLink(
                    registroId = registro.id,
                    recordNumber = recordNumber,
                    payloadHash = payloadResult.payloadHash,
                    reconciliadoAt = now
                )
            }
            RegistroLibreviewSyncChannel.NFC_INSULIN -> {
                registroRepository.updateLibreviewInsulinLink(
                    registroId = registro.id,
                    recordNumber = recordNumber,
                    payloadHash = payloadResult.payloadHash,
                    reconciliadoAt = now
                )
            }
        }
        recordCatalogRepository?.upsert(
            channel = channel,
            recordNumber = recordNumber,
            sourceRegistroId = registro.id,
            now = now,
            operation = RegistroLibreviewSyncOperation.UPSERT,
            payloadHash = payloadResult.payloadHash
        )
        if (repairMode) {
            queueRepository.deleteDeleteOperationsByRecordNumber(channel, recordNumber)
        }
        queueRepository.markSyncedUpload(registro.id, channel, now)
        return QueueProcessOutcome(attemptedUpload = true)
    }

    private suspend fun processDelete(
        item: RegistroLibreviewSync,
        channel: RegistroLibreviewSyncChannel,
        session: LibreviewSession,
        repairMode: Boolean,
        now: Long
    ): QueueProcessOutcome {
        val registro = registroRepository.getRegistroRawById(item.registroId)
        val baseEventMillis = item.eventTimestampMillis
            ?: registro?.let { resolveLibreviewEventTimestamp(it, channel) }
            ?: now
        val amountValue = 0f
        val recordNumber = item.recordNumber
            ?: when (channel) {
                RegistroLibreviewSyncChannel.CARBS -> registro?.libreviewCarbsRecordNumber
                RegistroLibreviewSyncChannel.NFC_INSULIN -> registro?.libreviewInsulinRecordNumber
            }
            ?: run {
                queueRepository.markSyncedNoUpload(item.registroId, channel, now)
                return QueueProcessOutcome(attemptedUpload = false)
            }
        val eventMillis = if (registro != null) {
            if (channel == RegistroLibreviewSyncChannel.CARBS) {
                resolveCarbsTimestampForRecordNumber(
                    registro = registro,
                    recordNumber = recordNumber,
                    fallbackTimestamp = baseEventMillis
                )
            } else {
                resolveInsulinTimestampForRecordNumber(
                    registro = registro,
                    recordNumber = recordNumber,
                    fallbackTimestamp = baseEventMillis
                )
            }
        } else {
            baseEventMillis
        }

        val payloadResult = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> LibreviewPayloadBuilder.buildCarbsPayload(
                userToken = session.userToken,
                recordNumber = recordNumber,
                eventTimestampMillis = eventMillis,
                carbsGrams = amountValue,
                operation = LibreviewPayloadOperation.DELETE,
                connectedInsulinDevices = connectedInsulinDevicesForPayload(channel)
            )
            RegistroLibreviewSyncChannel.NFC_INSULIN -> LibreviewPayloadBuilder.buildInsulinPayload(
                userToken = session.userToken,
                recordNumber = recordNumber,
                eventTimestampMillis = eventMillis,
                units = amountValue,
                operation = LibreviewPayloadOperation.DELETE,
                connectedInsulinDevices = connectedInsulinDevicesForPayload(channel)
            )
        }

        val post = libreviewRepository.postMeasurements(
            baseUrl = session.baseUrl,
            apiKey = session.apiKey,
            userToken = session.userToken,
            requestPayload = payloadResult.request,
            domain = session.domain,
            gatewayType = session.gatewayType
        )
        if (!post.success) {
            val error = libreviewRepository.lastErrorMessage ?: post.reason ?: "Error LibreView"
            queueRepository.markFailed(item.registroId, channel, error, now)
            return QueueProcessOutcome(
                attemptedUpload = true,
                error = error
            )
        }

        if (registro != null) {
            when (channel) {
                RegistroLibreviewSyncChannel.CARBS -> {
                    if (registro.libreviewCarbsRecordNumber == recordNumber) {
                        registroRepository.clearLibreviewCarbsLink(registro.id)
                    }
                }
                RegistroLibreviewSyncChannel.NFC_INSULIN -> {
                    if (registro.libreviewInsulinRecordNumber == recordNumber) {
                        registroRepository.clearLibreviewInsulinLink(registro.id)
                    }
                }
            }
        }
        recordCatalogRepository?.upsert(
            channel = channel,
            recordNumber = recordNumber,
            sourceRegistroId = if (item.registroId > 0) item.registroId else null,
            now = now,
            operation = RegistroLibreviewSyncOperation.DELETE,
            payloadHash = item.payloadHash
        )
        queueRepository.markSyncedUpload(item.registroId, channel, now)
        return QueueProcessOutcome(attemptedUpload = true)
    }

    private fun isSameQueuedUpsert(
        item: RegistroLibreviewSync?,
        recordNumber: Long,
        eventTimestampMillis: Long,
        amountValue: Float,
        payloadHash: String
    ): Boolean {
        if (item == null) return false
        if (item.operation != RegistroLibreviewSyncOperation.UPSERT.value) return false
        if (item.recordNumber != recordNumber) return false
        if (item.eventTimestampMillis != eventTimestampMillis) return false
        if (item.payloadHash != payloadHash) return false
        val queuedAmount = item.amountValue ?: return false
        return abs(queuedAmount - amountValue) < 0.0001f
    }

    private fun connectedInsulinDevicesForPayload(
        channel: RegistroLibreviewSyncChannel
    ): List<String> {
        val baseSerial = appStableSerial?.trim().orEmpty()
        if (baseSerial.isBlank()) return emptyList()
        val channelSerial = when (channel) {
            RegistroLibreviewSyncChannel.CARBS -> "${baseSerial}-CARBS"
            RegistroLibreviewSyncChannel.NFC_INSULIN -> "${baseSerial}-INSULIN"
        }
        return listOf(channelSerial)
    }

    private suspend fun findExistingLocalRecordNumber(
        sourceRegistro: RegistroComida,
        channel: RegistroLibreviewSyncChannel,
        amountValue: Float,
        eventTimestampMillis: Long
    ): Long? {
        val from = eventTimestampMillis - linkMatchDeltaMillis
        val to = eventTimestampMillis + linkMatchDeltaMillis
        val registros = registroRepository.getRegistrosInRangeRaw(from, to)
        val best = registros
            .asSequence()
            .filter { it.id != sourceRegistro.id }
            .mapNotNull { candidate ->
                val candidateRecordNumber = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> candidate.libreviewCarbsRecordNumber
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> candidate.libreviewInsulinRecordNumber
                } ?: return@mapNotNull null
                val candidateAmount = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> candidate.hidratosTotales
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> candidate.unidadesInsulina
                }
                val deltaMillis = abs(
                    resolveLibreviewEventTimestamp(candidate, channel) - eventTimestampMillis
                )
                val deltaAmount = abs(candidateAmount - amountValue)
                val toleranceAmount = when (channel) {
                    RegistroLibreviewSyncChannel.CARBS -> LINK_MATCH_CARBS_DELTA
                    RegistroLibreviewSyncChannel.NFC_INSULIN -> linkMatchInsulinDelta
                }
                if (deltaMillis <= linkMatchDeltaMillis && deltaAmount <= toleranceAmount) {
                    Triple(candidateRecordNumber, deltaMillis, deltaAmount)
                } else {
                    null
                }
            }
            .minWithOrNull(compareBy<Triple<Long, Long, Float>> { it.second }.thenBy { it.third })
        return best?.first
    }

    companion object {
        private const val LINK_MATCH_CARBS_DELTA = 1f
        private const val MAX_CONSECUTIVE_FAILED_UPLOADS = 6
        private const val DEFAULT_WIPE_MIN_REPEATS = 6
        private const val DEFAULT_WIPE_MAX_REPEATS = 24
        private const val BLIND_WIPE_MAX_TIMESTAMP_VARIANTS = 6
        private val PROTECTED_REMOTE_DEVICE_IDENTIFIERS = setOf(
            "9276713d-5a73-402c-93cf-4e374cdc7d7a"
        )

        private fun syntheticDeleteRegistroId(
            channel: RegistroLibreviewSyncChannel,
            recordNumber: Long,
            sequence: Int = 0
        ): Int {
            val sequenceSalt = sequence.coerceAtLeast(0) * 0x9E37
            val mixed = ((recordNumber xor (recordNumber ushr 32)).toInt()) xor channel.value.hashCode() xor sequenceSalt
            val normalized = kotlin.math.abs(mixed.toLong())
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
            return -normalized
        }
    }
}
