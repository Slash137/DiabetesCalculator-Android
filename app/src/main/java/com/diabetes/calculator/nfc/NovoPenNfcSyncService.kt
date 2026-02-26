package com.diabetes.calculator.nfc

import androidx.work.WorkManager
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation
import com.diabetes.calculator.data.repository.LibreviewRegistrosSyncService
import com.diabetes.calculator.data.repository.LibreviewRepository
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncService
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.RegistroLibreviewSyncRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.work.LibreviewSyncWorker
import com.diabetes.calculator.work.NightscoutSyncWorker
import net.cacheux.nvplib.data.PenResultData
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max

data class NovoPenImportResult(
    val insertedCount: Int,
    val skippedCount: Int,
    val queuedNightscoutSyncCount: Int,
    val queuedLibreviewSyncCount: Int,
    val nightscoutSyncTriggered: Boolean,
    val libreviewSyncTriggered: Boolean
)

internal data class NovoPenDoseCandidate(
    val timestampMillis: Long,
    val unitsRaw: Int,
    val units: Float
)

class NovoPenNfcSyncService(
    private val usuarioRepository: UsuarioProfileRepository,
    private val registroRepository: RegistroComidaRepository,
    private val queueRepository: RegistroNightscoutSyncRepository,
    private val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository,
    private val libreviewQueueRepository: RegistroLibreviewSyncRepository,
    private val workManager: WorkManager
) {
    private val localMergeService: NightscoutRegistrosSyncService by lazy {
        NightscoutRegistrosSyncService(
            registroRepository = registroRepository,
            queueRepository = queueRepository,
            tombstoneRepository = nightscoutTreatmentTombstoneRepository,
            nightscoutRepository = NightscoutRepository(),
            libreviewQueueRepository = libreviewQueueRepository
        )
    }

    suspend fun importPenData(
        data: PenResultData,
        nowMillis: Long = System.currentTimeMillis()
    ): NovoPenImportResult {
        val serial = data.serial.trim().ifBlank { UNKNOWN_SERIAL }
        val candidates = normalizeCandidates(
            data = data,
            nowMillis = nowMillis,
            serial = serial
        )
        if (candidates.isEmpty()) {
            return NovoPenImportResult(
                insertedCount = 0,
                skippedCount = 0,
                queuedNightscoutSyncCount = 0,
                queuedLibreviewSyncCount = 0,
                nightscoutSyncTriggered = false,
                libreviewSyncTriggered = false
            )
        }

        val profile = usuarioRepository.getProfileSync()
        val dedupeDeltaMinutes = max(
            profile?.nightscoutLinkOffsetMinutes?.coerceIn(0, 180) ?: SyncLinkTolerance.WINDOW_MINUTES,
            SyncLinkTolerance.WINDOW_MINUTES
        )
        val dedupeDeltaUnits = max(
            profile?.nightscoutLinkOffsetUnits?.coerceIn(0f, 5f) ?: SyncLinkTolerance.WINDOW_UNITS,
            SyncLinkTolerance.WINDOW_UNITS
        )
        val dedupeDeltaMillis = dedupeDeltaMinutes * 60_000L
        val fromMillis = candidates.minOf { it.timestampMillis } - dedupeDeltaMillis
        val toMillis = candidates.maxOf { it.timestampMillis } + dedupeDeltaMillis
        val existingInRange = registroRepository.getRegistrosInRangeRaw(fromMillis, toMillis).toMutableList()
        val hasNightscoutConfig = !profile?.nightscoutUrl?.trim().isNullOrBlank()
        val libreviewEnabled = profile?.libreviewSyncActivo == true
        val libreviewSyncService = LibreviewRegistrosSyncService(
            registroRepository = registroRepository,
            queueRepository = libreviewQueueRepository,
            libreviewRepository = LibreviewRepository(),
            linkMatchDeltaMillis = dedupeDeltaMillis,
            linkMatchInsulinDelta = dedupeDeltaUnits
        )

        var insertedCount = 0
        var skippedCount = 0
        var queuedNightscoutSyncCount = 0
        var queuedLibreviewSyncCount = 0
        val touchedDcid = linkedSetOf<String>()
        val pendingLibreviewLegacyDeletes = linkedMapOf<Pair<RegistroLibreviewSyncChannel, Long>, Triple<Long, Long, String?>>()

        candidates.forEach { candidate ->
            val dcid = buildNovoPenDcid(
                serial = serial,
                timestampMillis = candidate.timestampMillis,
                unitsRaw = candidate.unitsRaw
            )
            if (registroRepository.getByNightscoutSyncDcid(dcid) != null) {
                skippedCount += 1
                return@forEach
            }
            val existingMatch = existingInRange.firstOrNull {
                matchesCandidate(
                    registro = it,
                    candidate = candidate,
                    maxDeltaMillis = dedupeDeltaMillis,
                    maxDeltaUnits = dedupeDeltaUnits
                )
            }
            if (existingMatch != null) {
                skippedCount += 1
                val isLocalExisting = existingMatch.origenRegistro == OrigenRegistro.LOCAL.value
                if (isLocalExisting) {
                    val canonicalized = registroRepository.canonicalizeLocalRegistroWithNfcDose(
                        registroId = existingMatch.id,
                        unidades = candidate.units,
                        confirmadaAt = candidate.timestampMillis,
                        dcid = dcid,
                        now = nowMillis
                    )
                    if (canonicalized != null) {
                        canonicalized.invalidatedNightscoutTreatmentId
                            ?.takeIf { hasNightscoutConfig }
                            ?.let { treatmentId ->
                                nightscoutTreatmentTombstoneRepository.add(treatmentId, nowMillis)
                            }
                        canonicalized.legacyDeletes.forEach { delete ->
                            pendingLibreviewLegacyDeletes[delete.channel to delete.recordNumber] =
                                Triple(
                                    delete.recordNumber,
                                    delete.eventTimestampMillis,
                                    delete.payloadHash
                                )
                        }
                        existingInRange.removeAll { it.id == canonicalized.updatedRegistro.id }
                        existingInRange += canonicalized.updatedRegistro
                    }
                }
                touchedDcid += dcid
                return@forEach
            }

            val registro = RegistroComida(
                hidratosTotales = 0f,
                racionesCalculadas = 0f,
                unidadesInsulina = candidate.units,
                fecha = candidate.timestampMillis,
                notas = "[NovoPen NFC] serial=$serial dcid=$dcid",
                dosisEstado = EstadoDosis.APLICADA.value,
                origenRegistro = OrigenRegistro.LOCAL.value,
                nightscoutSyncDcid = dcid,
                dosisConfirmadaAt = candidate.timestampMillis
            )
            val insertedId = registroRepository.insertRegistro(registro)
            existingInRange += registro.copy(id = insertedId)
            insertedCount += 1
            touchedDcid += dcid
        }

        localMergeService.reconcileLocalDuplicatesOnly(
            now = nowMillis,
            linkOffsetMinutes = dedupeDeltaMinutes,
            linkOffsetUnits = dedupeDeltaUnits
        )

        val queuedNightscoutRegistroIds = mutableSetOf<Int>()
        val queuedLibreviewRegistroIds = mutableSetOf<Int>()

        touchedDcid.forEach { dcid ->
            val keeper = registroRepository.getByNightscoutSyncDcid(dcid) ?: return@forEach
            if (keeper.origenRegistro != OrigenRegistro.LOCAL.value) return@forEach

            if (hasNightscoutConfig && queuedNightscoutRegistroIds.add(keeper.id)) {
                queueRepository.upsertPending(keeper.id, nowMillis)
                queuedNightscoutSyncCount += 1
            }
            if (libreviewEnabled && queuedLibreviewRegistroIds.add(keeper.id)) {
                libreviewSyncService.enqueueUpsertForRegistro(keeper.id, nowMillis)
                queuedLibreviewSyncCount += 1
            }
        }

        if (libreviewEnabled) {
            pendingLibreviewLegacyDeletes.forEach { (key, value) ->
                val (channel, _) = key
                val (recordNumber, eventTimestampMillis, payloadHash) = value
                libreviewQueueRepository.upsertPending(
                    registroId = syntheticDeleteRegistroId(channel, recordNumber),
                    channel = channel,
                    operation = RegistroLibreviewSyncOperation.DELETE,
                    now = nowMillis,
                    recordNumber = recordNumber,
                    eventTimestampMillis = eventTimestampMillis,
                    amountValue = 0f,
                    payloadHash = payloadHash
                )
                queuedLibreviewSyncCount += 1
            }
        }

        val shouldTriggerNightscoutSync = hasNightscoutConfig && queuedNightscoutSyncCount > 0
        if (shouldTriggerNightscoutSync) {
            NightscoutSyncWorker.enqueueNow(workManager, forceManual = true)
        }
        val shouldTriggerLibreviewSync = libreviewEnabled && queuedLibreviewSyncCount > 0
        if (shouldTriggerLibreviewSync) {
            LibreviewSyncWorker.enqueueNow(workManager, forceManual = true)
        }

        return NovoPenImportResult(
            insertedCount = insertedCount,
            skippedCount = skippedCount,
            queuedNightscoutSyncCount = queuedNightscoutSyncCount,
            queuedLibreviewSyncCount = queuedLibreviewSyncCount,
            nightscoutSyncTriggered = shouldTriggerNightscoutSync,
            libreviewSyncTriggered = shouldTriggerLibreviewSync
        )
    }

    private fun normalizeCandidates(
        data: PenResultData,
        nowMillis: Long,
        serial: String
    ): List<NovoPenDoseCandidate> {
        val oldestAllowed = nowMillis - MAX_IMPORT_AGE_DAYS * DAY_MILLIS
        val latestAllowed = nowMillis + MAX_FUTURE_DRIFT_MILLIS
        return data.doseList
            .asSequence()
            .mapNotNull { dose ->
                val unitsRaw = dose.units
                if (unitsRaw <= 0) return@mapNotNull null
                val timestamp = dose.time
                if (timestamp !in oldestAllowed..latestAllowed) return@mapNotNull null
                val units = unitsRaw / 10f
                if (!units.isFinite() || units <= 0f) return@mapNotNull null
                NovoPenDoseCandidate(
                    timestampMillis = timestamp,
                    unitsRaw = unitsRaw,
                    units = units
                )
            }
            .distinctBy { candidate ->
                buildNovoPenDcid(
                    serial = serial,
                    timestampMillis = candidate.timestampMillis,
                    unitsRaw = candidate.unitsRaw
                )
            }
            .sortedBy { it.timestampMillis }
            .toList()
    }

    private fun matchesCandidate(
        registro: RegistroComida,
        candidate: NovoPenDoseCandidate,
        maxDeltaMillis: Long,
        maxDeltaUnits: Float
    ): Boolean {
        val existingUnits = (registro.unidadesInsulinaRemota ?: registro.unidadesInsulina)
            .takeIf { it.isFinite() && it > 0f }
            ?: return false
        val existingTimestamp = registro.dosisConfirmadaAt ?: registro.fecha
        val withinTimeWindow = abs(existingTimestamp - candidate.timestampMillis) <= maxDeltaMillis
        if (!withinTimeWindow) return false

        val withinUnitsWindow = abs(existingUnits - candidate.units) <= maxDeltaUnits
        if (withinUnitsWindow) return true

        return isRelaxedMealNfcMatchCandidate(registro)
    }

    private fun isRelaxedMealNfcMatchCandidate(registro: RegistroComida): Boolean {
        if (registro.origenRegistro != OrigenRegistro.LOCAL.value) return false
        if (EstadoDosis.fromValue(registro.dosisEstado) == EstadoDosis.OMITIDA) return false
        if (isNovoPenNfcRegistro(registro)) return false
        val hasCarbs = registro.hidratosTotales > 0f || registro.racionesCalculadas > 0f
        if (!hasCarbs) return false
        val units = registro.unidadesInsulina
        return units.isFinite() && units > 0f
    }

    private fun isNovoPenNfcRegistro(registro: RegistroComida): Boolean {
        val dcid = registro.nightscoutSyncDcid?.trim().orEmpty()
        if (dcid.startsWith("nfc-")) return true
        val notes = registro.notas?.trim().orEmpty()
        return notes.contains("[NovoPen NFC]", ignoreCase = true)
    }

    companion object {
        private const val UNKNOWN_SERIAL = "unknown"
        private const val MAX_IMPORT_AGE_DAYS = 180L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val MAX_FUTURE_DRIFT_MILLIS = 5L * 60L * 1000L

        private fun syntheticDeleteRegistroId(
            channel: RegistroLibreviewSyncChannel,
            recordNumber: Long
        ): Int {
            val mixed = ((recordNumber xor (recordNumber ushr 32)).toInt()) xor channel.value.hashCode()
            val normalized = mixed.absoluteValue.coerceAtLeast(1)
            return -normalized
        }
    }
}

internal fun buildNovoPenDcid(
    serial: String,
    timestampMillis: Long,
    unitsRaw: Int
): String {
    val cleanSerial = serial
        .trim()
        .ifBlank { "unknown" }
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "nfc-$cleanSerial-$timestampMillis-$unitsRaw"
}
