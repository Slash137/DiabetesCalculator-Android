package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.RegistroComidaDao
import com.diabetes.calculator.data.dao.NfcCanonicalizationResult
import com.diabetes.calculator.data.dao.LibreviewSyncOperationKeyCount
import com.diabetes.calculator.data.dao.RegistroLibreviewSyncDao
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroLibreviewSync
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncOperation
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncStatus
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.model.LibreviewRemoteEntry
import com.diabetes.calculator.data.model.LibreviewSession
import com.diabetes.calculator.domain.LibreviewRecordNumber
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreviewRegistrosSyncServiceTest {

    @Test
    fun `enqueueUpsertForRegistro enqueues both carbs and insulin channels`() = runBlocking {
        val registro = RegistroComida(
            id = 1,
            hidratosTotales = 13f,
            racionesCalculadas = 1.3f,
            unidadesInsulina = 4f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_180_000L,
            dosisEstado = EstadoDosis.APLICADA.value,
            nightscoutSyncDcid = "nfc-pen-1"
        )
        val registroStore = InMemoryRegistroStore(listOf(registro))
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(registroStore.dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueUpsertForRegistro(registroId = registro.id, now = 1_700_000_500_000L)

        val queued = queueDao.snapshot()
        assertEquals(2, queued.size)
        assertTrue(
            queued.any {
                it.registroId == registro.id &&
                    it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                    it.operation == RegistroLibreviewSyncOperation.UPSERT.value &&
                    it.recordNumber != null
            }
        )
        assertTrue(
            queued.any {
                it.registroId == registro.id &&
                    it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                    it.operation == RegistroLibreviewSyncOperation.UPSERT.value &&
                    it.recordNumber != null
            }
        )
    }

    @Test
    fun `enqueueUpsertForRegistro rekeys legacy insulin record number to canonical`() = runBlocking {
        val mealTimestamp = 1_700_070_000_000L
        val confirmedTimestamp = mealTimestamp + 5 * 60_000L
        val legacyInsulinRecord = LibreviewRecordNumber.from(
            registroId = 70,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = mealTimestamp
        )
        val canonicalInsulinRecord = LibreviewRecordNumber.from(
            registroId = 70,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = confirmedTimestamp
        )
        val legacyHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
            operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
            recordNumber = legacyInsulinRecord,
            eventTimestampMillis = mealTimestamp,
            amountValue = 4f
        )
        val registro = RegistroComida(
            id = 70,
            hidratosTotales = 20f,
            racionesCalculadas = 2f,
            unidadesInsulina = 4f,
            fecha = mealTimestamp,
            dosisConfirmadaAt = confirmedTimestamp,
            dosisEstado = EstadoDosis.APLICADA.value,
            nightscoutSyncDcid = "nfc-pen-70",
            libreviewInsulinRecordNumber = legacyInsulinRecord,
            libreviewInsulinPayloadHash = legacyHash
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(registro)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueUpsertForRegistro(registro.id, now = 1_700_070_300_000L)

        val snapshot = queueDao.snapshot()
        val insulinUpsert = snapshot.firstOrNull {
            it.registroId == registro.id &&
                it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        assertEquals(canonicalInsulinRecord, insulinUpsert?.recordNumber)
        assertFalse(
            snapshot.any {
                it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                    it.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                    it.recordNumber == legacyInsulinRecord
            }
        )
    }

    @Test
    fun `sync upsert canonicalizes insulin record number even when queue carries legacy record`() = runBlocking {
        val now = 1_700_071_000_000L
        val mealTimestamp = now - 60_000L
        val confirmedTimestamp = mealTimestamp + 6 * 60_000L
        val legacyInsulinRecord = LibreviewRecordNumber.from(
            registroId = 71,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = mealTimestamp
        )
        val canonicalInsulinRecord = LibreviewRecordNumber.from(
            registroId = 71,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = confirmedTimestamp
        )
        val registro = RegistroComida(
            id = 71,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 3.5f,
            fecha = mealTimestamp,
            dosisConfirmadaAt = confirmedTimestamp,
            dosisEstado = EstadoDosis.APLICADA.value,
            nightscoutSyncDcid = "nfc-pen-71"
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = registro.id,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now,
                    recordNumber = legacyInsulinRecord,
                    eventTimestampMillis = mealTimestamp,
                    amountValue = registro.unidadesInsulina,
                    payloadHash = "legacy-upsert"
                )
            )
        )
        val registroStore = InMemoryRegistroStore(listOf(registro))
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(registroStore.dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.start()
        try {
            service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                prioritizeDeleteOperations = false,
                now = now
            )

            val updated = registroStore.items.getValue(registro.id)
            assertEquals(canonicalInsulinRecord, updated.libreviewInsulinRecordNumber)
            assertFalse(
                queueDao.snapshot().any {
                    it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                        it.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                        it.recordNumber == legacyInsulinRecord
                }
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `canonicalizeLocalRegistroWithNfcDose applies real nfc dose and emits legacy delete metadata`() = runBlocking {
        val mealTimestamp = 1_700_072_000_000L
        val confirmedTimestamp = mealTimestamp + 7 * 60_000L
        val legacyInsulinRecord = LibreviewRecordNumber.from(
            registroId = 72,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = mealTimestamp
        )
        val legacyHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
            operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
            recordNumber = legacyInsulinRecord,
            eventTimestampMillis = mealTimestamp,
            amountValue = 2f
        )
        val original = RegistroComida(
            id = 72,
            hidratosTotales = 32f,
            racionesCalculadas = 3.2f,
            unidadesInsulina = 2f,
            fecha = mealTimestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            nightscoutTreatmentId = "treat-legacy-72",
            libreviewInsulinRecordNumber = legacyInsulinRecord,
            libreviewInsulinPayloadHash = legacyHash
        )
        val store = InMemoryRegistroStore(listOf(original))
        val repository = RegistroComidaRepository(store.dao)

        val result = repository.canonicalizeLocalRegistroWithNfcDose(
            registroId = original.id,
            unidades = 4.5f,
            confirmadaAt = confirmedTimestamp,
            dcid = "nfc-pen-72-$confirmedTimestamp-45",
            now = 1_700_072_500_000L
        )

        assertTrue(result != null)
        assertEquals("treat-legacy-72", result?.invalidatedNightscoutTreatmentId)
        assertEquals(1, result?.legacyDeletes?.size)
        assertEquals(legacyInsulinRecord, result?.legacyDeletes?.firstOrNull()?.recordNumber)
        val updated = store.items.getValue(original.id)
        assertEquals(4.5f, updated.unidadesInsulina, 0.0001f)
        assertEquals(EstadoDosis.APLICADA.value, updated.dosisEstado)
        assertEquals(confirmedTimestamp, updated.dosisConfirmadaAt)
        assertEquals("nfc-pen-72-$confirmedTimestamp-45", updated.nightscoutSyncDcid)
        assertNull(updated.nightscoutTreatmentId)
        assertNull(updated.libreviewInsulinRecordNumber)
    }

    @Test
    fun `repair reset rebuilds queue with deletes before upserts and clears links`() = runBlocking {
        val mealTimestamp = 1_700_010_000_000L
        val doseTimestamp = 1_700_010_180_000L
        val registro = RegistroComida(
            id = 7,
            hidratosTotales = 13f,
            racionesCalculadas = 1.3f,
            unidadesInsulina = 4f,
            fecha = mealTimestamp,
            dosisConfirmadaAt = doseTimestamp,
            dosisEstado = EstadoDosis.APLICADA.value,
            libreviewCarbsRecordNumber = 9_001L,
            libreviewInsulinRecordNumber = 9_002L,
            libreviewCarbsPayloadHash = "old-carbs-hash",
            libreviewInsulinPayloadHash = "old-insulin-hash"
        )
        val registroStore = InMemoryRegistroStore(listOf(registro))
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = registro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.FAILED.value,
                    attempts = 2,
                    updatedAt = 10L
                )
            )
        )
        val queueRepository = RegistroLibreviewSyncRepository(queueDao)
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(registroStore.dao),
            queueRepository = queueRepository,
            libreviewRepository = LibreviewRepository()
        )

        val expectedCarbsDeletes = linkedSetOf(
            registro.libreviewCarbsRecordNumber,
            LibreviewRecordNumber.from(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.CARBS,
                effectiveTimestamp = mealTimestamp
            ),
            LibreviewRecordNumber.from(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.CARBS,
                effectiveTimestamp = doseTimestamp
            )
        ).filterNotNull().size
        val expectedInsulinDeletes = linkedSetOf(
            registro.libreviewInsulinRecordNumber,
            LibreviewRecordNumber.from(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                effectiveTimestamp = doseTimestamp
            ),
            LibreviewRecordNumber.from(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                effectiveTimestamp = mealTimestamp
            )
        ).filterNotNull().size

        val preview = service.buildRepairResetPreview()
        assertEquals(1, preview.canonicalRecords)

        service.enqueueRepairResetPlan(now = 1_700_020_000_000L)

        val queueAfterReset = queueRepository.getPendingOrFailedPrioritizingDeletes()
        assertTrue(queueAfterReset.size >= expectedCarbsDeletes + expectedInsulinDeletes + 2)
        assertEquals(
            2,
            queueAfterReset.count { it.operation == RegistroLibreviewSyncOperation.UPSERT.value }
        )
        val firstUpsertIndex = queueAfterReset.indexOfFirst {
            it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        assertNotEquals(-1, firstUpsertIndex)
        assertTrue(
            queueAfterReset
                .take(firstUpsertIndex)
                .all { it.operation == RegistroLibreviewSyncOperation.DELETE.value }
        )
        assertTrue(
            queueAfterReset
                .drop(firstUpsertIndex)
                .all { it.operation == RegistroLibreviewSyncOperation.UPSERT.value }
        )

        val linkedAfterReset = registroStore.items.getValue(registro.id)
        assertNull(linkedAfterReset.libreviewCarbsRecordNumber)
        assertNull(linkedAfterReset.libreviewInsulinRecordNumber)
    }

    @Test
    fun `repair reset validates duplicates by tolerance before enqueueing upserts`() = runBlocking {
        val baseTs = 1_700_025_000_000L
        val appWithMeal = RegistroComida(
            id = 25,
            hidratosTotales = 13f,
            racionesCalculadas = 1.3f,
            unidadesInsulina = 4f,
            fecha = baseTs,
            dosisConfirmadaAt = baseTs + 60_000L,
            dosisEstado = EstadoDosis.APLICADA.value
        )
        val duplicateNfc = RegistroComida(
            id = 26,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = baseTs + 45_000L,
            dosisConfirmadaAt = baseTs + 45_000L,
            dosisEstado = EstadoDosis.APLICADA.value,
            notas = "[NovoPen NFC] dose"
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(
                InMemoryRegistroStore(listOf(appWithMeal, duplicateNfc)).dao
            ),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository(),
            linkMatchDeltaMillis = 15 * 60_000L,
            linkMatchInsulinDelta = 1f
        )

        service.enqueueRepairResetPlan(now = 1_700_026_000_000L)
        val queue = queueDao.snapshot()
        val deleteOps = queue.filter { it.operation == RegistroLibreviewSyncOperation.DELETE.value }
        val upserts = queue.filter { it.operation == RegistroLibreviewSyncOperation.UPSERT.value }

        assertEquals(2, upserts.size)
        assertTrue(
            upserts.any {
                it.registroId == appWithMeal.id && it.channel == RegistroLibreviewSyncChannel.CARBS.value
            }
        )
        assertTrue(
            upserts.any {
                it.registroId == appWithMeal.id &&
                    it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value
            }
        )
        val duplicateInsulinRecord = LibreviewRecordNumber.from(
            registroId = duplicateNfc.id,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = duplicateNfc.dosisConfirmadaAt ?: duplicateNfc.fecha
        )
        assertTrue(
            deleteOps.any {
                it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                    it.recordNumber == duplicateInsulinRecord
            }
        )
    }

    @Test
    fun `repair preview uses exact tolerance parameters without minimum floor`() = runBlocking {
        val baseTs = 1_700_027_000_000L
        val registroA = RegistroComida(
            id = 27,
            hidratosTotales = 20f,
            racionesCalculadas = 2f,
            unidadesInsulina = 0f,
            fecha = baseTs,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )
        val registroB = RegistroComida(
            id = 28,
            hidratosTotales = 20f,
            racionesCalculadas = 2f,
            unidadesInsulina = 0f,
            fecha = baseTs + 30_000L,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(
                InMemoryRegistroStore(listOf(registroA, registroB)).dao
            ),
            queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
            libreviewRepository = LibreviewRepository()
        )

        val strictPreview = service.buildRepairResetPreview(
            repairLinkOffsetMinutes = 0,
            repairLinkOffsetUnits = 0f
        )
        val widePreview = service.buildRepairResetPreview(
            repairLinkOffsetMinutes = 1,
            repairLinkOffsetUnits = 0f
        )

        assertEquals(2, strictPreview.canonicalRecords)
        assertEquals(1, widePreview.canonicalRecords)
    }

    @Test
    fun `enqueueRepairWipeOnly enqueues minimum repeated deletes per key in blind wipe`() = runBlocking {
        val now = 1_700_027_500_000L
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )
        val wipePlan = LibreviewWipePlan(
            knownAppManaged = listOf(
                LibreviewRemoteEntry(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = 8_001L,
                    eventTimestampMillis = now - 60_000L,
                    amountValue = 10f
                )
            )
        )

        service.enqueueRepairWipeOnly(
            wipePlan = wipePlan,
            minRepeatsPerKey = 6,
            maxRepeatsPerKey = 24,
            now = now
        )

        val queuedDeletes = queueDao.snapshot().filter {
            it.operation == RegistroLibreviewSyncOperation.DELETE.value
        }
        assertEquals(6, queuedDeletes.size)
        assertTrue(
            queuedDeletes.all {
                it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                    it.recordNumber == 8_001L &&
                    it.registroId < 0
            }
        )
        assertEquals(6, queuedDeletes.map { it.registroId }.distinct().size)
    }

    @Test
    fun `countWipeDeleteOps grows with observed duplicates and applies cap`() = runBlocking {
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
            libreviewRepository = LibreviewRepository()
        )
        val baseTs = 1_700_027_700_000L
        val tenCopies = List(10) { index ->
            LibreviewRemoteEntry(
                channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                recordNumber = 9_101L,
                eventTimestampMillis = baseTs + index,
                amountValue = 4f
            )
        }
        val singleCopy = listOf(
            LibreviewRemoteEntry(
                channel = RegistroLibreviewSyncChannel.CARBS.value,
                recordNumber = 9_102L,
                eventTimestampMillis = baseTs,
                amountValue = 20f
            )
        )
        val wipePlan = LibreviewWipePlan(knownAppManaged = tenCopies + singleCopy)

        val opCount = service.countWipeDeleteOps(
            wipePlan = wipePlan,
            minRepeatsPerKey = 6,
            maxRepeatsPerKey = 24
        )

        assertEquals(16, opCount)

        val thirtyCopiesPlan = LibreviewWipePlan(
            knownAppManaged = List(30) { index ->
                LibreviewRemoteEntry(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = 9_103L,
                    eventTimestampMillis = baseTs + index,
                    amountValue = 18f
                )
            }
        )
        val capped = service.countWipeDeleteOps(
            wipePlan = thirtyCopiesPlan,
            minRepeatsPerKey = 6,
            maxRepeatsPerKey = 24
        )
        assertEquals(24, capped)
    }

    @Test
    fun `enqueueRepairWipeOnly caps repeated deletes and keeps ids unique`() = runBlocking {
        val now = 1_700_027_900_000L
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )
        val wipePlan = LibreviewWipePlan(
            knownAppManaged = List(30) { index ->
                LibreviewRemoteEntry(
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    recordNumber = 9_201L,
                    eventTimestampMillis = now + index,
                    amountValue = 2f
                )
            }
        )

        service.enqueueRepairWipeOnly(
            wipePlan = wipePlan,
            minRepeatsPerKey = 6,
            maxRepeatsPerKey = 24,
            now = now
        )

        val queuedDeletes = queueDao.snapshot().filter {
            it.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.recordNumber == 9_201L
        }
        assertEquals(24, queuedDeletes.size)
        assertEquals(24, queuedDeletes.map { it.registroId }.distinct().size)
    }

    @Test
    fun `enqueueRepairWipeOnly keeps timestamp variants for same key`() = runBlocking {
        val now = 1_700_028_000_000L
        val tsA = now - 30_000L
        val tsB = now + 30_000L
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )
        val wipePlan = LibreviewWipePlan(
            knownAppManaged = listOf(
                LibreviewRemoteEntry(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = 9_250L,
                    eventTimestampMillis = tsA,
                    amountValue = 10f
                ),
                LibreviewRemoteEntry(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = 9_250L,
                    eventTimestampMillis = tsB,
                    amountValue = 10f
                )
            )
        )

        service.enqueueRepairWipeOnly(
            wipePlan = wipePlan,
            minRepeatsPerKey = 6,
            maxRepeatsPerKey = 24,
            now = now
        )

        val queuedDeletes = queueDao.snapshot().filter {
            it.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                it.recordNumber == 9_250L
        }
        assertEquals(6, queuedDeletes.size)
        val timestamps = queuedDeletes.mapNotNull { it.eventTimestampMillis }.toSet()
        assertTrue(timestamps.contains(tsA))
        assertTrue(timestamps.contains(tsB))
    }

    @Test
    fun `buildBlindWipePlan preserves known timestamp for non canonical keys`() = runBlocking {
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
            libreviewRepository = LibreviewRepository()
        )
        val knownTimestamp = 1_700_028_050_000L
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 0,
            knownRecordNumbers = listOf(
                LibreviewKnownRecordNumber(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = 9_301L,
                    eventTimestampMillis = knownTimestamp
                )
            ),
            upsertOps = emptyList(),
            generatedAt = 1_700_999_000_000L
        )

        val wipePlan = service.buildBlindWipePlan(snapshot)

        assertEquals(1, wipePlan.knownAppManaged.size)
        assertEquals(knownTimestamp, wipePlan.knownAppManaged.first().eventTimestampMillis)
    }

    @Test
    fun `buildBlindWipePlan expands unknown timestamp keys using local overlap anchors`() = runBlocking {
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
            libreviewRepository = LibreviewRepository()
        )
        val tsA = 1_700_031_000_000L
        val tsB = 1_700_031_600_000L
        val tsC = 1_700_032_200_000L
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 0,
            knownRecordNumbers = listOf(
                LibreviewKnownRecordNumber(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = 9_333L,
                    eventTimestampMillis = null
                )
            ),
            overlapCandidates = listOf(
                LibreviewOverlapCandidate(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    eventTimestampMillis = tsA,
                    amountValue = 18f
                ),
                LibreviewOverlapCandidate(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    eventTimestampMillis = tsB,
                    amountValue = 22f
                ),
                LibreviewOverlapCandidate(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    eventTimestampMillis = tsC,
                    amountValue = 12f
                )
            ),
            upsertOps = emptyList(),
            generatedAt = 1_700_999_000_000L
        )

        val wipePlan = service.buildBlindWipePlan(snapshot)

        assertEquals(3, wipePlan.knownAppManaged.size)
        val timestamps = wipePlan.knownAppManaged.mapNotNull { it.eventTimestampMillis }.toSet()
        assertTrue(timestamps.contains(tsA))
        assertTrue(timestamps.contains(tsB))
        assertTrue(timestamps.contains(tsC))
    }

    @Test
    fun `buildRemoteAggressiveWipePlan includes unknown and serialless overlaps in delete candidates`() = runBlocking {
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
            queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
            libreviewRepository = LibreviewRepository()
        )
        val known = LibreviewRemoteEntry(
            channel = RegistroLibreviewSyncChannel.CARBS.value,
            recordNumber = 10_101L,
            eventTimestampMillis = 1_700_030_000_000L,
            amountValue = 20f
        )
        val overlap = LibreviewRemoteEntry(
            channel = RegistroLibreviewSyncChannel.CARBS.value,
            recordNumber = 10_202L,
            eventTimestampMillis = 1_700_030_010_000L,
            amountValue = 20f
        )
        val serialless = LibreviewRemoteEntry(
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
            recordNumber = 10_303L,
            eventTimestampMillis = 1_700_030_020_000L,
            amountValue = 3f,
            deviceSerial = null
        )
        val basePlan = LibreviewWipePlan(
            knownAppManaged = listOf(known),
            unknownOverlap = listOf(overlap),
            seriallessOverlap = listOf(serialless),
            foreign = emptyList()
        )

        val aggressive = service.buildRemoteAggressiveWipePlan(basePlan)

        assertEquals(3, aggressive.knownAppManaged.size)
        assertTrue(aggressive.knownAppManaged.contains(known))
        assertTrue(aggressive.knownAppManaged.contains(overlap))
        assertTrue(aggressive.knownAppManaged.contains(serialless))
        assertEquals(1, aggressive.unknownOverlap.size)
        assertEquals(1, aggressive.seriallessOverlap.size)
    }

    @Test
    fun `buildRemoteWipePlan separa serialless overlap de unknown overlap y foreign`() = runBlocking {
        val eventMillis = 1_700_030_500_000L
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 1,
            knownRecordNumbers = emptyList(),
            upsertOps = listOf(
                LibreviewRepairQueueItem(
                    registroId = 900,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    recordNumber = 10_900L,
                    eventTimestampMillis = eventMillis,
                    amountValue = 20f,
                    payloadHash = "canonical"
                )
            ),
            generatedAt = eventMillis
        )
        val responseBody = """
            {
              "measurements": [
                {
                  "recordNumber": 20001,
                  "gramsCarbs": 20.0,
                  "timestamp": "${eventMillis + 30_000L}",
                  "extendedProperties": {
                    "deviceSerial": ""
                  }
                },
                {
                  "recordNumber": 20002,
                  "gramsCarbs": 20.0,
                  "timestamp": "${eventMillis + 40_000L}",
                  "extendedProperties": {
                    "deviceSerial": "PEN-123"
                  }
                },
                {
                  "recordNumber": 20003,
                  "gramsCarbs": 7.0,
                  "timestamp": "${eventMillis + 8 * 60 * 60 * 1000L}"
                }
              ]
            }
        """.trimIndent()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
                queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
                libreviewRepository = LibreviewRepository()
            )
            val wipePlan = service.buildRemoteWipePlan(
                snapshot = snapshot,
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = eventMillis
                ),
                fromMillis = eventMillis - 60_000L,
                toMillis = eventMillis + 9 * 60 * 60 * 1000L
            )

            assertEquals(0, wipePlan.knownAppManaged.size)
            assertEquals(2, wipePlan.seriallessOverlap.size)
            assertEquals(20001L, wipePlan.seriallessOverlap.first().recordNumber)
            assertEquals(1, wipePlan.unknownOverlap.size)
            assertEquals(20002L, wipePlan.unknownOverlap.first().recordNumber)
            assertEquals(0, wipePlan.foreign.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `buildRemoteWipePlan detecta overlap con candidatos nightscout delete-only aunque no haya upserts`() = runBlocking {
        val eventMillis = 1_700_030_700_000L
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 0,
            knownRecordNumbers = emptyList(),
            overlapCandidates = listOf(
                LibreviewOverlapCandidate(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    eventTimestampMillis = eventMillis,
                    amountValue = 18f
                )
            ),
            upsertOps = emptyList(),
            generatedAt = eventMillis
        )
        val responseBody = """
            {
              "measurements": [
                {
                  "recordNumber": 21001,
                  "gramsCarbs": 18.0,
                  "timestamp": "${eventMillis + 20_000L}",
                  "extendedProperties": {
                    "deviceSerial": ""
                  }
                }
              ]
            }
        """.trimIndent()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
                queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
                libreviewRepository = LibreviewRepository()
            )
            val wipePlan = service.buildRemoteWipePlan(
                snapshot = snapshot,
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = eventMillis
                ),
                fromMillis = eventMillis - 60_000L,
                toMillis = eventMillis + 60_000L
            )

            assertEquals(1, wipePlan.seriallessOverlap.size)
            assertEquals(21001L, wipePlan.seriallessOverlap.first().recordNumber)
            assertEquals(0, wipePlan.foreign.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `buildRemoteWipePlan omite entradas de dispositivo protegido aunque coincidan con known keys`() = runBlocking {
        val eventMillis = 1_700_030_800_000L
        val protectedRecordNumber = 33_001L
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 1,
            knownRecordNumbers = listOf(
                LibreviewKnownRecordNumber(
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    recordNumber = protectedRecordNumber,
                    eventTimestampMillis = eventMillis
                )
            ),
            upsertOps = listOf(
                LibreviewRepairQueueItem(
                    registroId = 930,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    recordNumber = protectedRecordNumber,
                    eventTimestampMillis = eventMillis,
                    amountValue = 25f,
                    payloadHash = "canonical-protected"
                )
            ),
            generatedAt = eventMillis
        )
        val responseBody = """
            {
              "measurements": [
                {
                  "recordNumber": $protectedRecordNumber,
                  "gramsCarbs": 25.0,
                  "timestamp": "$eventMillis",
                  "extendedProperties": {
                    "deviceId": "9276713d-5a73-402c-93cf-4e374cdc7d7a"
                  }
                }
              ]
            }
        """.trimIndent()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
                queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
                libreviewRepository = LibreviewRepository()
            )
            val wipePlan = service.buildRemoteWipePlan(
                snapshot = snapshot,
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = eventMillis
                ),
                fromMillis = eventMillis - 60_000L,
                toMillis = eventMillis + 60_000L
            )

            assertEquals(0, wipePlan.knownAppManaged.size)
            assertEquals(0, wipePlan.unknownOverlap.size)
            assertEquals(0, wipePlan.seriallessOverlap.size)
            assertEquals(1, wipePlan.foreign.size)
            assertEquals(protectedRecordNumber, wipePlan.foreign.first().recordNumber)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `enqueueRepairUpsertPartialManual skips upload and links local when remote equivalent exists`() = runBlocking {
        val now = 1_700_030_100_000L
        val eventMillis = 1_700_030_060_000L
        val registro = RegistroComida(
            id = 303,
            hidratosTotales = 24f,
            racionesCalculadas = 2.4f,
            unidadesInsulina = 0f,
            fecha = eventMillis,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 1,
            knownRecordNumbers = emptyList(),
            upsertOps = listOf(
                LibreviewRepairQueueItem(
                    registroId = registro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    recordNumber = LibreviewRecordNumber.from(
                        registroId = registro.id,
                        channel = RegistroLibreviewSyncChannel.CARBS,
                        effectiveTimestamp = eventMillis
                    ),
                    eventTimestampMillis = eventMillis,
                    amountValue = 24f,
                    payloadHash = "canonical-hash"
                )
            ),
            clearCarbsLinkRegistroIds = listOf(registro.id),
            generatedAt = now
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val store = InMemoryRegistroStore(listOf(registro))
        val server = MockWebServer()
        val remoteRecordNumber = 99_999L
        val responseBody = """
            {
              "measurements": [
                {
                  "recordNumber": $remoteRecordNumber,
                  "gramsCarbs": 24.0,
                  "timestamp": "$eventMillis"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(store.dao),
                queueRepository = RegistroLibreviewSyncRepository(queueDao),
                libreviewRepository = LibreviewRepository()
            )
            val result = service.enqueueRepairUpsertPartialManual(
                snapshot = snapshot,
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                fromMillis = eventMillis - 60_000L,
                toMillis = eventMillis + 60_000L,
                now = now
            )

            assertFalse(result.failedRead)
            assertEquals(0, result.planned)
            assertEquals(1, result.skippedByRemoteMatch)
            assertEquals(1, result.linkedToRemote)
            assertTrue(queueDao.snapshot().isEmpty())
            val updated = store.items.getValue(registro.id)
            assertEquals(remoteRecordNumber, updated.libreviewCarbsRecordNumber)
            assertNull(updated.libreviewCarbsPayloadHash)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `buildLocalRepairSnapshot excludes nightscout imports from upserts and tracks delete-only ops`() = runBlocking {
        val now = 1_700_031_000_000L
        val imported = RegistroComida(
            id = 304,
            hidratosTotales = 16f,
            racionesCalculadas = 1.6f,
            unidadesInsulina = 0f,
            fecha = now - 60_000L,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao),
            queueRepository = RegistroLibreviewSyncRepository(InMemoryRegistroLibreviewSyncDao()),
            libreviewRepository = LibreviewRepository()
        )

        val snapshot = service.buildLocalRepairSnapshot(now = now)

        assertTrue(snapshot.upsertOps.none { it.registroId == imported.id })
        assertTrue(snapshot.nightscoutManagedDeleteOps.any { it.registroId < 0 })
        assertTrue(
            snapshot.overlapCandidates.any {
                it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                    it.amountValue == imported.hidratosTotales &&
                    it.eventTimestampMillis == imported.fecha
            }
        )
        assertEquals(1, snapshot.nightscoutImportSkippedUpserts)
    }

    @Test
    fun `enqueueRepairUpsertPartialManual never enqueues upsert for nightscout import`() = runBlocking {
        val now = 1_700_031_100_000L
        val imported = RegistroComida(
            id = 305,
            hidratosTotales = 18f,
            racionesCalculadas = 1.8f,
            unidadesInsulina = 0f,
            fecha = now - 30_000L,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val recordNumber = LibreviewRecordNumber.from(
            registroId = imported.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = imported.fecha
        )
        val snapshot = LibreviewRepairSnapshot(
            canonicalRecords = 0,
            upsertOps = listOf(
                LibreviewRepairQueueItem(
                    registroId = imported.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    recordNumber = recordNumber,
                    eventTimestampMillis = imported.fecha,
                    amountValue = imported.hidratosTotales,
                    payloadHash = "imported-should-skip"
                )
            ),
            generatedAt = now
        )
        val server = MockWebServer()
        val emptyBody = """{"measurements":[]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyBody))
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao),
                queueRepository = RegistroLibreviewSyncRepository(queueDao),
                libreviewRepository = LibreviewRepository()
            )
            val result = service.enqueueRepairUpsertPartialManual(
                snapshot = snapshot,
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                fromMillis = imported.fecha - 60_000L,
                toMillis = imported.fecha + 60_000L,
                now = now
            )

            assertFalse(result.failedRead)
            assertEquals(0, result.planned)
            assertTrue(queueDao.snapshot().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair reset keeps delete-only for imported recent records`() = runBlocking {
        val timestamp = 1_700_028_000_000L
        val imported = RegistroComida(
            id = 50,
            hidratosTotales = 12f,
            racionesCalculadas = 1.2f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueRepairResetPlan(now = 1_700_028_500_000L)
        val queue = queueDao.snapshot()
        val canonicalRecord = LibreviewRecordNumber.from(
            registroId = imported.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = timestamp
        )

        assertTrue(
            queue.any {
                it.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                    it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                    it.recordNumber == canonicalRecord
            }
        )
        assertFalse(
            queue.any {
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value &&
                    it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                    it.registroId == imported.id
            }
        )
    }

    @Test
    fun `repair reset dedupes across origins and keeps a single canonical upsert`() = runBlocking {
        val timestamp = 1_700_028_100_000L
        val local = RegistroComida(
            id = 51,
            hidratosTotales = 18f,
            racionesCalculadas = 1.8f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val imported = RegistroComida(
            id = 52,
            hidratosTotales = 18f,
            racionesCalculadas = 1.8f,
            unidadesInsulina = 0f,
            fecha = timestamp + 30_000L,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(local, imported)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository(),
            linkMatchDeltaMillis = 2 * 60_000L,
            linkMatchInsulinDelta = 0.5f
        )

        service.enqueueRepairResetPlan(now = 1_700_028_500_000L)
        val queue = queueDao.snapshot()
        val carbsUpserts = queue.filter {
            it.operation == RegistroLibreviewSyncOperation.UPSERT.value &&
                it.channel == RegistroLibreviewSyncChannel.CARBS.value
        }

        assertEquals(1, carbsUpserts.size)
        assertEquals(local.id, carbsUpserts.first().registroId)
    }

    @Test
    fun `manual catch-up skips imported carbs`() = runBlocking {
        val timestamp = 1_700_028_900_000L
        val imported = RegistroComida(
            id = 53,
            hidratosTotales = 14f,
            racionesCalculadas = 1.4f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            now = 1_700_029_000_000L
        )
        val queue = queueDao.snapshot()
        val carbsUpserts = queue.filter {
            it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        val deleteOps = queue.filter { it.operation == RegistroLibreviewSyncOperation.DELETE.value }

        assertTrue(carbsUpserts.isEmpty())
        assertTrue(deleteOps.isEmpty())
    }

    @Test
    fun `manual catch-up dedupes cross-origin and enqueues only canonical upsert`() = runBlocking {
        val timestamp = 1_700_029_100_000L
        val local = RegistroComida(
            id = 54,
            hidratosTotales = 18f,
            racionesCalculadas = 1.8f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val imported = RegistroComida(
            id = 55,
            hidratosTotales = 18f,
            racionesCalculadas = 1.8f,
            unidadesInsulina = 0f,
            fecha = timestamp + 45_000L,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(local, imported)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            now = 1_700_029_500_000L
        )
        val queue = queueDao.snapshot()
        val carbsUpserts = queue.filter {
            it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }

        assertEquals(1, carbsUpserts.size)
        assertEquals(local.id, carbsUpserts.first().registroId)
    }

    @Test
    fun `manual catch-up skips imported insulin`() = runBlocking {
        val timestamp = 1_700_029_700_000L
        val imported = RegistroComida(
            id = 56,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = timestamp,
            dosisConfirmadaAt = timestamp,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            now = 1_700_029_800_000L
        )
        val queue = queueDao.snapshot()
        val insulinUpserts = queue.filter {
            it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        val deleteOps = queue.filter { it.operation == RegistroLibreviewSyncOperation.DELETE.value }

        assertTrue(insulinUpserts.isEmpty())
        assertTrue(deleteOps.isEmpty())
    }

    @Test
    fun `manual catch-up enqueues pending local insulin`() = runBlocking {
        val timestamp = 1_700_029_850_000L
        val pendingLocal = RegistroComida(
            id = 57,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 3f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(pendingLocal)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            now = 1_700_029_900_000L
        )
        val insulinUpserts = queueDao.snapshot().filter {
            it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }

        assertEquals(1, insulinUpserts.size)
        assertEquals(pendingLocal.id, insulinUpserts.first().registroId)
    }

    @Test
    fun `manual catch-up with nfc insulin only skips non-nfc insulin`() = runBlocking {
        val timestamp = 1_700_029_855_000L
        val pendingLocal = RegistroComida(
            id = 571,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 3f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(pendingLocal)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            nfcInsulinOnly = true,
            now = 1_700_029_900_000L
        )
        val insulinUpserts = queueDao.snapshot().filter {
            it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        assertTrue(insulinUpserts.isEmpty())
    }

    @Test
    fun `manual catch-up with nfc insulin only enqueues nfc insulin`() = runBlocking {
        val timestamp = 1_700_029_860_000L
        val nfcLocal = RegistroComida(
            id = 572,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 3f,
            fecha = timestamp,
            dosisConfirmadaAt = timestamp,
            dosisEstado = EstadoDosis.APLICADA.value,
            nightscoutSyncDcid = "nfc-pen-572",
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(nfcLocal)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            nfcInsulinOnly = true,
            now = 1_700_029_900_000L
        )
        val insulinUpserts = queueDao.snapshot().filter {
            it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        assertEquals(1, insulinUpserts.size)
        assertEquals(nfcLocal.id, insulinUpserts.first().registroId)
    }

    @Test
    fun `manual catch-up force reupload requeues carbs even if link and hash already exist`() = runBlocking {
        val timestamp = 1_700_029_865_000L
        val canonicalRecordNumber = LibreviewRecordNumber.from(
            registroId = 573,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = timestamp
        )
        val canonicalHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.CARBS.value,
            operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
            recordNumber = canonicalRecordNumber,
            eventTimestampMillis = timestamp,
            amountValue = 22f
        )
        val local = RegistroComida(
            id = 573,
            hidratosTotales = 22f,
            racionesCalculadas = 2.2f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            libreviewCarbsRecordNumber = canonicalRecordNumber,
            libreviewCarbsPayloadHash = canonicalHash,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val store = InMemoryRegistroStore(listOf(local))
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(store.dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            forceReuploadCarbs = true,
            now = 1_700_029_900_000L
        )

        val carbsUpserts = queueDao.snapshot().filter {
            it.channel == RegistroLibreviewSyncChannel.CARBS.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        assertEquals(1, carbsUpserts.size)
        assertEquals(local.id, carbsUpserts.first().registroId)
        val updated = store.items.getValue(local.id)
        assertNull(updated.libreviewCarbsRecordNumber)
        assertNull(updated.libreviewCarbsPayloadHash)
    }

    @Test
    fun `manual catch-up insulin scope uploads only doses from cutoff`() = runBlocking {
        val cutoff = 1_700_029_900_000L
        val before = RegistroComida(
            id = 574,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = cutoff - 60_000L,
            dosisConfirmadaAt = cutoff - 60_000L,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val after = RegistroComida(
            id = 575,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 5f,
            fecha = cutoff + 60_000L,
            dosisConfirmadaAt = cutoff + 60_000L,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao()
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(before, after)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            minInsulinEventTimestampMillis = cutoff,
            forceReuploadInsulin = true,
            now = cutoff + 120_000L
        )

        val insulinUpserts = queueDao.snapshot().filter {
            it.channel == RegistroLibreviewSyncChannel.NFC_INSULIN.value &&
                it.operation == RegistroLibreviewSyncOperation.UPSERT.value
        }
        assertEquals(1, insulinUpserts.size)
        assertEquals(after.id, insulinUpserts.first().registroId)
    }

    @Test
    fun `manual catch-up requeues insulin previously synced-no-upload`() = runBlocking {
        val timestamp = 1_700_029_880_000L
        val pendingLocal = RegistroComida(
            id = 58,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 3f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val recordNumber = LibreviewRecordNumber.from(
            registroId = pendingLocal.id,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = pendingLocal.fecha
        )
        val payloadHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
            operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
            recordNumber = recordNumber,
            eventTimestampMillis = pendingLocal.fecha,
            amountValue = pendingLocal.unidadesInsulina
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = pendingLocal.id,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD.value,
                    attempts = 0,
                    updatedAt = 100L,
                    recordNumber = recordNumber,
                    eventTimestampMillis = pendingLocal.fecha,
                    amountValue = pendingLocal.unidadesInsulina,
                    payloadHash = payloadHash
                )
            )
        )
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(pendingLocal)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueMissingCanonicalForManualSync(
            linkOffsetMinutes = 1,
            linkOffsetUnits = 0f,
            now = 200L
        )
        val queueItem = queueDao.getByRegistroAndChannel(
            pendingLocal.id,
            RegistroLibreviewSyncChannel.NFC_INSULIN.value
        )

        assertEquals(RegistroLibreviewSyncStatus.PENDING.value, queueItem?.status)
    }

    @Test
    fun `enqueueUpsertForRegistro does not requeue already synced payload`() = runBlocking {
        val timestamp = 1_700_030_000_000L
        val canonicalRecordNumber = LibreviewRecordNumber.from(
            registroId = 30,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = timestamp
        )
        val payloadHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.CARBS.value,
            operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
            recordNumber = canonicalRecordNumber,
            eventTimestampMillis = timestamp,
            amountValue = 30f
        )
        val registro = RegistroComida(
            id = 30,
            hidratosTotales = 30f,
            racionesCalculadas = 3f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            libreviewCarbsRecordNumber = canonicalRecordNumber,
            libreviewCarbsPayloadHash = payloadHash
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = registro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    attempts = 0,
                    updatedAt = 100L,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = timestamp,
                    amountValue = 30f,
                    payloadHash = payloadHash
                )
            )
        )
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(registro)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueUpsertForRegistro(registro.id, now = 200L)

        val queueAfter = queueDao.snapshot()
        assertTrue(queueAfter.isEmpty())
    }

    @Test
    fun `enqueueUpsertForRegistro keeps failed attempts when payload is unchanged`() = runBlocking {
        val timestamp = 1_700_040_000_000L
        val recordNumber = LibreviewRecordNumber.from(
            registroId = 40,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = timestamp
        )
        val payloadHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
            channel = RegistroLibreviewSyncChannel.CARBS.value,
            operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
            recordNumber = recordNumber,
            eventTimestampMillis = timestamp,
            amountValue = 20f
        )
        val registro = RegistroComida(
            id = 40,
            hidratosTotales = 20f,
            racionesCalculadas = 2f,
            unidadesInsulina = 0f,
            fecha = timestamp,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )
        val failed = RegistroLibreviewSync(
            registroId = registro.id,
            channel = RegistroLibreviewSyncChannel.CARBS.value,
            operation = RegistroLibreviewSyncOperation.UPSERT.value,
            status = RegistroLibreviewSyncStatus.FAILED.value,
            attempts = 4,
            lastError = "HTTP 500",
            updatedAt = 500L,
            recordNumber = recordNumber,
            eventTimestampMillis = timestamp,
            amountValue = 20f,
            payloadHash = payloadHash
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(initial = listOf(failed))
        val service = LibreviewRegistrosSyncService(
            registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(registro)).dao),
            queueRepository = RegistroLibreviewSyncRepository(queueDao),
            libreviewRepository = LibreviewRepository()
        )

        service.enqueueUpsertForRegistro(registro.id, now = 900L)

        val current = queueDao.getByRegistroAndChannel(registro.id, RegistroLibreviewSyncChannel.CARBS.value)
        assertEquals(RegistroLibreviewSyncStatus.FAILED.value, current?.status)
        assertEquals(4, current?.attempts)
        assertEquals(500L, current?.updatedAt)
    }

    @Test
    fun `sync aborts after six consecutive failed uploads`() = runBlocking {
        val now = 1_700_050_000_000L
        val registros = (1..10).map { index ->
            RegistroComida(
                id = index,
                hidratosTotales = 10f + index,
                racionesCalculadas = 1f,
                unidadesInsulina = 0f,
                fecha = now + index * 1_000L,
                dosisEstado = EstadoDosis.PENDIENTE.value
            )
        }
        val queueInitial = registros.map { registro ->
            val recordNumber = LibreviewRecordNumber.from(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.CARBS,
                effectiveTimestamp = registro.fecha
            )
            RegistroLibreviewSync(
                registroId = registro.id,
                channel = RegistroLibreviewSyncChannel.CARBS.value,
                operation = RegistroLibreviewSyncOperation.UPSERT.value,
                status = RegistroLibreviewSyncStatus.PENDING.value,
                updatedAt = now,
                recordNumber = recordNumber,
                eventTimestampMillis = registro.fecha,
                amountValue = registro.hidratosTotales,
                payloadHash = "hash-${registro.id}"
            )
        }
        val queueDao = InMemoryRegistroLibreviewSyncDao(initial = queueInitial)
        val queueRepository = RegistroLibreviewSyncRepository(queueDao)
        val registroRepository = RegistroComidaRepository(InMemoryRegistroStore(registros).dao)

        val server = MockWebServer()
        server.enqueueRepeatedHttp500Responses(times = 20)
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = registroRepository,
                queueRepository = queueRepository,
                libreviewRepository = LibreviewRepository()
            )
            val result = service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                now = now
            )

            assertEquals(6, result.processedPending)
            assertTrue(result.abortedByConsecutiveErrors)
            val finalQueue = queueRepository.getPendingOrFailed()
            val failedCount = finalQueue.count { it.status == RegistroLibreviewSyncStatus.FAILED.value }
            val pendingCount = finalQueue.count { it.status == RegistroLibreviewSyncStatus.PENDING.value }
            assertEquals(6, failedCount)
            assertEquals(4, pendingCount)
            assertFalse(finalQueue.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair-prioritized sync processes delete phase before upserts`() = runBlocking {
        val now = 1_700_060_000_000L
        val deleteRegistro = RegistroComida(
            id = 61,
            hidratosTotales = 20f,
            racionesCalculadas = 2f,
            unidadesInsulina = 0f,
            fecha = now,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )
        val upsertRegistro = RegistroComida(
            id = 62,
            hidratosTotales = 25f,
            racionesCalculadas = 2.5f,
            unidadesInsulina = 0f,
            fecha = now + 1_000L,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )
        val deleteRecordNumber = LibreviewRecordNumber.from(
            registroId = deleteRegistro.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = deleteRegistro.fecha
        )
        val upsertRecordNumber = LibreviewRecordNumber.from(
            registroId = upsertRegistro.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = upsertRegistro.fecha
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = deleteRegistro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.DELETE.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now,
                    recordNumber = deleteRecordNumber,
                    eventTimestampMillis = deleteRegistro.fecha,
                    amountValue = 0f
                ),
                RegistroLibreviewSync(
                    registroId = upsertRegistro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now + 1L,
                    recordNumber = upsertRecordNumber,
                    eventTimestampMillis = upsertRegistro.fecha,
                    amountValue = upsertRegistro.hidratosTotales
                )
            )
        )
        val queueRepository = RegistroLibreviewSyncRepository(queueDao)
        val registroRepository = RegistroComidaRepository(
            InMemoryRegistroStore(listOf(deleteRegistro, upsertRegistro)).dao
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":500,"reason":"error"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = registroRepository,
                queueRepository = queueRepository,
                libreviewRepository = LibreviewRepository()
            )
            val result = service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                prioritizeDeleteOperations = true,
                now = now
            )

            assertEquals(1, result.processedPending)
            val deleteItem = queueDao.getByRegistroAndChannel(
                deleteRegistro.id,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.FAILED.value, deleteItem?.status)
            val upsertItem = queueDao.getByRegistroAndChannel(
                upsertRegistro.id,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.PENDING.value, upsertItem?.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair wipe processes deletes and defers upserts`() = runBlocking {
        val now = 1_700_060_500_000L
        val deleteRecordNumber = LibreviewRecordNumber.from(
            registroId = 91,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = now - 2_000L
        )
        val upsertRegistro = RegistroComida(
            id = 92,
            hidratosTotales = 19f,
            racionesCalculadas = 1.9f,
            unidadesInsulina = 0f,
            fecha = now,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )
        val upsertRecordNumber = LibreviewRecordNumber.from(
            registroId = upsertRegistro.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = upsertRegistro.fecha
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = 91,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.DELETE.value,
                    status = RegistroLibreviewSyncStatus.FAILED.value,
                    attempts = 1,
                    updatedAt = now,
                    recordNumber = deleteRecordNumber,
                    eventTimestampMillis = now - 2_000L,
                    amountValue = 0f
                ),
                RegistroLibreviewSync(
                    registroId = upsertRegistro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now + 1L,
                    recordNumber = upsertRecordNumber,
                    eventTimestampMillis = upsertRegistro.fecha,
                    amountValue = upsertRegistro.hidratosTotales
                )
            )
        )
        val queueRepository = RegistroLibreviewSyncRepository(queueDao)
        val registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(upsertRegistro)).dao)
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":500,"reason":"error"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = registroRepository,
                queueRepository = queueRepository,
                libreviewRepository = LibreviewRepository()
            )
            service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                prioritizeDeleteOperations = true,
                repairMode = true,
                now = now
            )

            val upsertItem = queueDao.getByRegistroAndChannel(
                upsertRegistro.id,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.PENDING.value, upsertItem?.status)
            val failedDelete = queueDao.getByRegistroAndChannel(
                91,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.SYNCED_UPLOAD.value, failedDelete?.status)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair mode keeps failed delete for retries after consecutive failures`() = runBlocking {
        val now = 1_700_060_900_000L
        val deleteRecordNumber = LibreviewRecordNumber.from(
            registroId = 93,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = now
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = 93,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.DELETE.value,
                    status = RegistroLibreviewSyncStatus.FAILED.value,
                    attempts = 5,
                    updatedAt = now,
                    recordNumber = deleteRecordNumber,
                    eventTimestampMillis = now,
                    amountValue = 0f
                )
            )
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":500,"reason":"error"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(emptyList()).dao),
                queueRepository = RegistroLibreviewSyncRepository(queueDao),
                libreviewRepository = LibreviewRepository()
            )
            service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                prioritizeDeleteOperations = true,
                repairMode = true,
                now = now
            )

            val item = queueDao.getByRegistroAndChannel(
                93,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.FAILED.value, item?.status)
            assertEquals(6, item?.attempts)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair mode sync skips imported carbs upserts`() = runBlocking {
        val now = 1_700_061_000_000L
        val imported = RegistroComida(
            id = 63,
            hidratosTotales = 13f,
            racionesCalculadas = 1.3f,
            unidadesInsulina = 0f,
            fecha = now,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val recordNumber = LibreviewRecordNumber.from(
            registroId = imported.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = imported.fecha
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = imported.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now,
                    recordNumber = recordNumber,
                    eventTimestampMillis = imported.fecha,
                    amountValue = imported.hidratosTotales
                )
            )
        )
        val queueRepository = RegistroLibreviewSyncRepository(queueDao)
        val registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao)
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = registroRepository,
                queueRepository = queueRepository,
                libreviewRepository = LibreviewRepository()
            )
            val result = service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                repairMode = true,
                now = now
            )

            assertEquals(1, result.processedPending)
            assertEquals(0, server.requestCount)
            val synced = queueDao.getByRegistroAndChannel(
                imported.id,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD.value, synced?.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair mode sync skips imported insulin upserts`() = runBlocking {
        val now = 1_700_061_200_000L
        val imported = RegistroComida(
            id = 64,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 5f,
            fecha = now,
            dosisConfirmadaAt = now,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val recordNumber = LibreviewRecordNumber.from(
            registroId = imported.id,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = imported.dosisConfirmadaAt ?: imported.fecha
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = imported.id,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now,
                    recordNumber = recordNumber,
                    eventTimestampMillis = imported.dosisConfirmadaAt,
                    amountValue = imported.unidadesInsulina
                )
            )
        )
        val queueRepository = RegistroLibreviewSyncRepository(queueDao)
        val registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(imported)).dao)
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = registroRepository,
                queueRepository = queueRepository,
                libreviewRepository = LibreviewRepository()
            )
            val result = service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                repairMode = true,
                now = now
            )

            assertEquals(1, result.processedPending)
            assertEquals(0, server.requestCount)
            val synced = queueDao.getByRegistroAndChannel(
                imported.id,
                RegistroLibreviewSyncChannel.NFC_INSULIN.value
            )
            assertEquals(RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD.value, synced?.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `manual-mode sync skips imported insulin when equivalent local exists`() = runBlocking {
        val now = 1_700_061_250_000L
        val local = RegistroComida(
            id = 66,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 5f,
            fecha = now,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val imported = RegistroComida(
            id = 67,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 5f,
            fecha = now + 30_000L,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )
        val importedRecordNumber = LibreviewRecordNumber.from(
            registroId = imported.id,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = imported.fecha
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = imported.id,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now,
                    recordNumber = importedRecordNumber,
                    eventTimestampMillis = imported.fecha,
                    amountValue = imported.unidadesInsulina
                )
            )
        )
        val server = MockWebServer()
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(
                    InMemoryRegistroStore(listOf(local, imported)).dao
                ),
                queueRepository = RegistroLibreviewSyncRepository(queueDao),
                libreviewRepository = LibreviewRepository(),
                linkMatchDeltaMillis = 60_000L,
                linkMatchInsulinDelta = 0.5f
            )
            service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                repairMode = true,
                allowPendingInsulin = true,
                now = now
            )

            assertEquals(0, server.requestCount)
            val synced = queueDao.getByRegistroAndChannel(
                imported.id,
                RegistroLibreviewSyncChannel.NFC_INSULIN.value
            )
            assertEquals(RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD.value, synced?.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `manual-mode sync uploads pending local insulin upserts`() = runBlocking {
        val now = 1_700_061_300_000L
        val pendingLocal = RegistroComida(
            id = 65,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = now,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val recordNumber = LibreviewRecordNumber.from(
            registroId = pendingLocal.id,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = pendingLocal.fecha
        )
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = pendingLocal.id,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now,
                    recordNumber = recordNumber,
                    eventTimestampMillis = pendingLocal.fecha,
                    amountValue = pendingLocal.unidadesInsulina
                )
            )
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(pendingLocal)).dao),
                queueRepository = RegistroLibreviewSyncRepository(queueDao),
                libreviewRepository = LibreviewRepository()
            )
            service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                repairMode = true,
                allowPendingInsulin = true,
                now = now
            )

            assertEquals(1, server.requestCount)
            val synced = queueDao.getByRegistroAndChannel(
                pendingLocal.id,
                RegistroLibreviewSyncChannel.NFC_INSULIN.value
            )
            assertEquals(RegistroLibreviewSyncStatus.SYNCED_UPLOAD.value, synced?.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repair mode removes stale deletes for canonical record after upsert`() = runBlocking {
        val now = 1_700_061_500_000L
        val registro = RegistroComida(
            id = 94,
            hidratosTotales = 21f,
            racionesCalculadas = 2.1f,
            unidadesInsulina = 0f,
            fecha = now,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )
        val canonicalRecordNumber = LibreviewRecordNumber.from(
            registroId = registro.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = registro.fecha
        )
        val staleDeleteRegistroId = -1001
        val queueDao = InMemoryRegistroLibreviewSyncDao(
            initial = listOf(
                RegistroLibreviewSync(
                    registroId = staleDeleteRegistroId,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.DELETE.value,
                    status = RegistroLibreviewSyncStatus.FAILED.value,
                    attempts = 2,
                    updatedAt = now + 2L,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = now,
                    amountValue = 0f
                ),
                RegistroLibreviewSync(
                    registroId = registro.id,
                    channel = RegistroLibreviewSyncChannel.CARBS.value,
                    operation = RegistroLibreviewSyncOperation.UPSERT.value,
                    status = RegistroLibreviewSyncStatus.PENDING.value,
                    updatedAt = now + 1L,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = registro.fecha,
                    amountValue = registro.hidratosTotales
                )
            )
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":0,"reason":"ok"}""")
        )
        server.start()
        try {
            val service = LibreviewRegistrosSyncService(
                registroRepository = RegistroComidaRepository(InMemoryRegistroStore(listOf(registro)).dao),
                queueRepository = RegistroLibreviewSyncRepository(queueDao),
                libreviewRepository = LibreviewRepository()
            )
            service.sync(
                profile = testProfile(),
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "acc",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = now
                ),
                bypassFailureBackoff = true,
                prioritizeDeleteOperations = false,
                repairMode = true,
                now = now
            )

            assertEquals(1, server.requestCount)
            val staleDelete = queueDao.getByRegistroAndChannel(
                staleDeleteRegistroId,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertNull(staleDelete)
            val upsert = queueDao.getByRegistroAndChannel(
                registro.id,
                RegistroLibreviewSyncChannel.CARBS.value
            )
            assertEquals(RegistroLibreviewSyncStatus.SYNCED_UPLOAD.value, upsert?.status)
        } finally {
            server.shutdown()
        }
    }

    private fun testProfile(): UsuarioProfile {
        return UsuarioProfile(
            nombre = "Test",
            gramosPorRacion = 10f,
            ratioInsulina = 1f,
            libreviewSyncActivo = true
        )
    }
}

private fun MockWebServer.enqueueRepeatedHttp500Responses(times: Int) {
    repeat(times) {
        enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":500,"reason":"error"}""")
        )
    }
}

private class InMemoryRegistroStore(initial: List<RegistroComida>) {
    val items: MutableMap<Int, RegistroComida> = initial.associateBy { it.id }.toMutableMap()

    val dao: RegistroComidaDao = Proxy.newProxyInstance(
        RegistroComidaDao::class.java.classLoader,
        arrayOf(RegistroComidaDao::class.java)
    ) { proxy, method, args ->
        if (method.declaringClass == Any::class.java) {
            return@newProxyInstance when (method.name) {
                "toString" -> "InMemoryRegistroComidaDao"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }

        when (method.name) {
            "getRegistroRawById" -> {
                val registroId = args?.getOrNull(0) as Int
                items[registroId]
            }
            "getAllRegistrosRaw" -> items.values.sortedBy { it.id }
            "getRegistrosInRangeRaw" -> {
                val from = args?.getOrNull(0) as Long
                val to = args.getOrNull(1) as Long
                items.values
                    .filter { it.fecha in from..to }
                    .sortedBy { it.fecha }
            }
            "clearLibreviewCarbsLink" -> {
                val registroId = args?.getOrNull(0) as Int
                val reconciliadoAt = args.getOrNull(1) as Long?
                items[registroId]?.let { registro ->
                    items[registroId] = registro.copy(
                        libreviewCarbsRecordNumber = null,
                        libreviewCarbsPayloadHash = null,
                        libreviewReconciliadoAt = reconciliadoAt
                    )
                }
                Unit
            }
            "clearLibreviewInsulinLink" -> {
                val registroId = args?.getOrNull(0) as Int
                val reconciliadoAt = args.getOrNull(1) as Long?
                items[registroId]?.let { registro ->
                    items[registroId] = registro.copy(
                        libreviewInsulinRecordNumber = null,
                        libreviewInsulinPayloadHash = null,
                        libreviewReconciliadoAt = reconciliadoAt
                    )
                }
                Unit
            }
            "updateLibreviewCarbsLink" -> {
                val registroId = args?.getOrNull(0) as Int
                val recordNumber = args.getOrNull(1) as Long
                val payloadHash = args.getOrNull(2) as String?
                val reconciliadoAt = args.getOrNull(3) as Long?
                items[registroId]?.let { registro ->
                    items[registroId] = registro.copy(
                        libreviewCarbsRecordNumber = recordNumber,
                        libreviewCarbsPayloadHash = payloadHash,
                        libreviewReconciliadoAt = reconciliadoAt
                    )
                }
                Unit
            }
            "updateLibreviewInsulinLink" -> {
                val registroId = args?.getOrNull(0) as Int
                val recordNumber = args.getOrNull(1) as Long
                val payloadHash = args.getOrNull(2) as String?
                val reconciliadoAt = args.getOrNull(3) as Long?
                items[registroId]?.let { registro ->
                    items[registroId] = registro.copy(
                        libreviewInsulinRecordNumber = recordNumber,
                        libreviewInsulinPayloadHash = payloadHash,
                        libreviewReconciliadoAt = reconciliadoAt
                    )
                }
                Unit
            }
            "canonicalizeLocalRegistroWithNfcDose" -> {
                val registroId = args?.getOrNull(0) as Int
                val unidades = args.getOrNull(1) as Float
                val confirmadaAt = args.getOrNull(2) as Long
                val dcid = args.getOrNull(3) as String
                val now = args.getOrNull(4) as Long
                val before = items[registroId] ?: return@newProxyInstance null
                val invalidatedTreatmentId = before.nightscoutTreatmentId?.takeIf { it.isNotBlank() }
                val canonicalRecordNumber = LibreviewRecordNumber.from(
                    registroId = registroId,
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                    effectiveTimestamp = confirmadaAt
                )
                val canonicalPayloadHash = com.diabetes.calculator.domain.LibreviewPayloadBuilder.hashPayload(
                    channel = RegistroLibreviewSyncChannel.NFC_INSULIN.value,
                    operation = com.diabetes.calculator.domain.LibreviewPayloadOperation.UPSERT,
                    recordNumber = canonicalRecordNumber,
                    eventTimestampMillis = confirmadaAt,
                    amountValue = unidades.coerceAtLeast(0f)
                )
                val legacyDeletes = mutableListOf<com.diabetes.calculator.data.dao.LegacyLibreviewDeleteLink>()
                before.libreviewInsulinRecordNumber?.let { linkedRecordNumber ->
                    val linkedHash = before.libreviewInsulinPayloadHash
                    val obsolete = linkedRecordNumber != canonicalRecordNumber ||
                        linkedHash != canonicalPayloadHash
                    if (obsolete) {
                        legacyDeletes += com.diabetes.calculator.data.dao.LegacyLibreviewDeleteLink(
                            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
                            recordNumber = linkedRecordNumber,
                            eventTimestampMillis = resolveInsulinTimestampForRecordNumber(
                                registro = before,
                                recordNumber = linkedRecordNumber,
                                fallbackTimestamp = before.dosisConfirmadaAt ?: before.fecha
                            ),
                            payloadHash = linkedHash
                        )
                    }
                }
                val updated = before.copy(
                    unidadesInsulina = unidades,
                    dosisEstado = EstadoDosis.APLICADA.value,
                    dosisConfirmadaAt = confirmadaAt,
                    nightscoutTreatmentId = null,
                    unidadesInsulinaRemota = null,
                    nightscoutReconciliadoAt = null,
                    nightscoutSyncDcid = dcid,
                    libreviewInsulinRecordNumber = if (legacyDeletes.isNotEmpty()) null else before.libreviewInsulinRecordNumber,
                    libreviewInsulinPayloadHash = if (legacyDeletes.isNotEmpty()) null else before.libreviewInsulinPayloadHash,
                    libreviewReconciliadoAt = if (legacyDeletes.isNotEmpty()) now else before.libreviewReconciliadoAt
                )
                items[registroId] = updated
                NfcCanonicalizationResult(
                    updatedRegistro = updated,
                    invalidatedNightscoutTreatmentId = invalidatedTreatmentId,
                    legacyDeletes = legacyDeletes
                )
            }
            else -> defaultValueFor(method.returnType)
        }
    } as RegistroComidaDao

    private fun defaultValueFor(returnType: Class<*>): Any? {
        return when (returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Void.TYPE -> Unit
            else -> null
        }
    }
}

private class InMemoryRegistroLibreviewSyncDao(
    initial: List<RegistroLibreviewSync> = emptyList()
) : RegistroLibreviewSyncDao {
    private val items: LinkedHashMap<Pair<Int, String>, RegistroLibreviewSync> = linkedMapOf()

    init {
        initial.forEach { item -> items[key(item.registroId, item.channel)] = item }
    }

    override suspend fun upsert(item: RegistroLibreviewSync) {
        items[key(item.registroId, item.channel)] = item
    }

    override suspend fun getByRegistroAndChannel(
        registroId: Int,
        channel: String
    ): RegistroLibreviewSync? = items[key(registroId, channel)]

    override suspend fun getPendingOrFailed(): List<RegistroLibreviewSync> {
        return items.values
            .filter { isPendingOrFailed(it) }
            .sortedBy { it.updatedAt }
    }

    override suspend fun getPendingOrFailedPrioritizingDeletes(): List<RegistroLibreviewSync> {
        return items.values
            .filter { isPendingOrFailed(it) }
            .sortedWith(
                compareBy<RegistroLibreviewSync> {
                    if (it.operation == RegistroLibreviewSyncOperation.DELETE.value) 0 else 1
                }.thenBy { it.updatedAt }
            )
    }

    override suspend fun deleteByRegistroId(registroId: Int) {
        items.keys
            .filter { it.first == registroId }
            .forEach { key -> items.remove(key) }
    }

    override suspend fun deleteAll() {
        items.clear()
    }

    override suspend fun deleteByRegistroAndChannel(registroId: Int, channel: String) {
        items.remove(key(registroId, channel))
    }

    override suspend fun deleteDeleteOperationsByChannelAndRecordNumber(
        channel: String,
        recordNumber: Long
    ) {
        items.entries.removeIf { (_, value) ->
            value.channel == channel &&
                value.operation == RegistroLibreviewSyncOperation.DELETE.value &&
                value.recordNumber == recordNumber
        }
    }

    override suspend fun countByOperationAndStatusSince(
        operation: String,
        status: String,
        sinceMillis: Long
    ): Int {
        return items.values.count { item ->
            item.operation == operation &&
                item.status == status &&
                item.updatedAt >= sinceMillis
        }
    }

    override suspend fun countByOperationAndStatusSinceGroupedByKey(
        operation: String,
        status: String,
        sinceMillis: Long
    ): List<LibreviewSyncOperationKeyCount> {
        return items.values
            .asSequence()
            .filter { item ->
                item.operation == operation &&
                    item.status == status &&
                    item.updatedAt >= sinceMillis
            }
            .groupBy { item -> item.channel to item.recordNumber }
            .map { (key, grouped) ->
                LibreviewSyncOperationKeyCount(
                    channel = key.first,
                    recordNumber = key.second,
                    total = grouped.size
                )
            }
    }

    override fun observePendingCount(): Flow<Int> {
        val count = items.values.count { it.status == RegistroLibreviewSyncStatus.PENDING.value }
        return flowOf(count)
    }

    override fun observeFailedCount(): Flow<Int> {
        val count = items.values.count { it.status == RegistroLibreviewSyncStatus.FAILED.value }
        return flowOf(count)
    }

    override fun observePendingCountByOperation(operation: String): Flow<Int> {
        val count = items.values.count {
            it.status == RegistroLibreviewSyncStatus.PENDING.value &&
                it.operation == operation
        }
        return flowOf(count)
    }

    override fun observeFailedCountByOperation(operation: String): Flow<Int> {
        val count = items.values.count {
            it.status == RegistroLibreviewSyncStatus.FAILED.value &&
                it.operation == operation
        }
        return flowOf(count)
    }

    override fun observeLastSuccessAt(): Flow<Long?> {
        val value = items.values
            .filter {
                it.status == RegistroLibreviewSyncStatus.SYNCED_UPLOAD.value ||
                    it.status == RegistroLibreviewSyncStatus.SYNCED_NO_UPLOAD.value
            }
            .maxOfOrNull { it.updatedAt }
        return flowOf(value)
    }

    override fun observeLastErrorAt(): Flow<Long?> {
        val value = items.values
            .filter { it.status == RegistroLibreviewSyncStatus.FAILED.value }
            .maxOfOrNull { it.updatedAt }
        return flowOf(value)
    }

    override fun observeLastErrorMessage(): Flow<String?> {
        val message = items.values
            .filter { it.status == RegistroLibreviewSyncStatus.FAILED.value }
            .maxByOrNull { it.updatedAt }
            ?.lastError
        return flowOf(message)
    }

    override fun observeFailedRegistroIds(): Flow<List<Int>> {
        val failedIds = items.values
            .filter { it.status == RegistroLibreviewSyncStatus.FAILED.value }
            .map { it.registroId }
            .distinct()
        return flowOf(failedIds)
    }

    override suspend fun deleteByOperation(operation: String) {
        items.entries.removeIf { (_, value) ->
            value.operation == operation
        }
    }

    fun snapshot(): List<RegistroLibreviewSync> = items.values.toList()

    private fun key(registroId: Int, channel: String): Pair<Int, String> = registroId to channel

    private fun isPendingOrFailed(item: RegistroLibreviewSync): Boolean {
        return item.status == RegistroLibreviewSyncStatus.PENDING.value ||
            item.status == RegistroLibreviewSyncStatus.FAILED.value
    }
}
