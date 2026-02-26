package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutDuplicateSafetyTest {

    @Test
    fun `singleton local nfc without pair is never considered duplicate`() {
        val nfc = registro(
            id = 1,
            carbs = 0f,
            rations = 0f,
            units = 4f,
            timestamp = 1_700_000_000_000L,
            dcid = "nfc-pen-1-1700000000000-40",
            notes = "[NovoPen NFC] dose",
            origen = OrigenRegistro.LOCAL
        )

        val hasPair = hasDuplicatePair(
            registro = nfc,
            candidates = listOf(nfc),
            toleranceMillis = 15 * 60_000L,
            toleranceUnits = 1f
        )

        assertFalse(hasPair)
    }

    @Test
    fun `singleton imported with dcid without pair is never considered duplicate`() {
        val imported = registro(
            id = 2,
            carbs = 0f,
            rations = 0f,
            units = 3.5f,
            timestamp = 1_700_000_060_000L,
            dcid = "reg-22-1700000060000",
            notes = "[Nightscout/Novopen] import",
            origen = OrigenRegistro.NIGHTSCOUT_IMPORT,
            treatmentId = "treat-22",
            remoteUnits = 3.5f
        )

        val hasPair = hasDuplicatePair(
            registro = imported,
            candidates = listOf(imported),
            toleranceMillis = 15 * 60_000L,
            toleranceUnits = 1f
        )

        assertFalse(hasPair)
    }

    @Test
    fun `app registro with carbs wins over nfc dose only duplicate`() {
        val appMeal = registro(
            id = 10,
            carbs = 42f,
            rations = 4.2f,
            units = 4f,
            timestamp = 1_700_000_120_000L,
            dcid = "reg-10-1700000120000",
            notes = "Comida app",
            origen = OrigenRegistro.LOCAL
        )
        val nfcDoseOnly = registro(
            id = 11,
            carbs = 0f,
            rations = 0f,
            units = 4f,
            timestamp = 1_700_000_121_000L,
            dcid = "nfc-pen-1-1700000121000-40",
            notes = "[NovoPen NFC] dose",
            origen = OrigenRegistro.LOCAL
        )

        assertTrue(
            hasDuplicatePair(
                registro = nfcDoseOnly,
                candidates = listOf(appMeal),
                toleranceMillis = 15 * 60_000L,
                toleranceUnits = 1f
            )
        )

        val keeper = choosePreferredLocalKeeper(listOf(appMeal, nfcDoseOnly))
        assertEquals(appMeal.id, keeper.id)
    }

    @Test
    fun `meal plus nfc pair is treated as duplicate even when dose delta exceeds tolerance`() {
        val appMeal = registro(
            id = 15,
            carbs = 48f,
            rations = 4.8f,
            units = 6f,
            timestamp = 1_700_000_150_000L,
            dcid = "reg-15-1700000150000",
            notes = "Comida app",
            origen = OrigenRegistro.LOCAL
        )
        val nfcDoseOnly = registro(
            id = 16,
            carbs = 0f,
            rations = 0f,
            units = 9.5f,
            timestamp = 1_700_000_151_000L,
            dcid = "nfc-pen-1-1700000151000-95",
            notes = "[NovoPen NFC] dose",
            origen = OrigenRegistro.LOCAL
        )

        assertTrue(isMealAndNfcCanonicalPair(appMeal, nfcDoseOnly))
        assertTrue(
            hasDuplicatePair(
                registro = appMeal,
                candidates = listOf(nfcDoseOnly),
                toleranceMillis = 15 * 60_000L,
                toleranceUnits = 1f
            )
        )
        assertTrue(
            hasDuplicatePair(
                registro = nfcDoseOnly,
                candidates = listOf(appMeal),
                toleranceMillis = 15 * 60_000L,
                toleranceUnits = 1f
            )
        )
    }

    @Test
    fun `dose-only nfc pair without carbs still requires units tolerance`() {
        val localDoseOnly = registro(
            id = 17,
            carbs = 0f,
            rations = 0f,
            units = 4f,
            timestamp = 1_700_000_152_000L,
            dcid = "reg-17-1700000152000",
            notes = "Corrección manual",
            origen = OrigenRegistro.LOCAL
        )
        val nfcDoseOnly = registro(
            id = 18,
            carbs = 0f,
            rations = 0f,
            units = 7f,
            timestamp = 1_700_000_152_500L,
            dcid = "nfc-pen-1-1700000152500-70",
            notes = "[NovoPen NFC] dose",
            origen = OrigenRegistro.LOCAL
        )

        assertFalse(isMealAndNfcCanonicalPair(localDoseOnly, nfcDoseOnly))
        assertFalse(
            hasDuplicatePair(
                registro = localDoseOnly,
                candidates = listOf(nfcDoseOnly),
                toleranceMillis = 15 * 60_000L,
                toleranceUnits = 1f
            )
        )
    }

    @Test
    fun `keeper keeps canonical treatment id from duplicate pair`() {
        val appMeal = registro(
            id = 20,
            carbs = 36f,
            rations = 3.6f,
            units = 4f,
            timestamp = 1_700_000_180_000L,
            dcid = "reg-20-1700000180000",
            notes = "Meal",
            origen = OrigenRegistro.LOCAL
        )
        val duplicateWithLink = registro(
            id = 21,
            carbs = 0f,
            rations = 0f,
            units = 4f,
            timestamp = 1_700_000_181_000L,
            dcid = "nfc-pen-1-1700000181000-40",
            notes = "[NovoPen NFC] dose",
            origen = OrigenRegistro.LOCAL,
            treatmentId = "treat-keep-me"
        )

        val cluster = listOf(appMeal, duplicateWithLink)
        val keeper = choosePreferredLocalKeeper(cluster)
        val treatmentId = resolvePreferredTreatmentIdForCluster(cluster, keeper)

        assertEquals(appMeal.id, keeper.id)
        assertEquals("treat-keep-me", treatmentId)
    }

    @Test
    fun `historical cleanup safety only allows delete when cluster has pair`() {
        val importedA = registro(
            id = 30,
            carbs = 0f,
            rations = 0f,
            units = 5f,
            timestamp = 1_700_000_240_000L,
            dcid = "legacy-a",
            notes = "import A",
            origen = OrigenRegistro.NIGHTSCOUT_IMPORT,
            treatmentId = "treat-a",
            remoteUnits = 5f
        )
        val importedB = importedA.copy(
            id = 31,
            fecha = 1_700_000_241_000L,
            nightscoutTreatmentId = "treat-b",
            nightscoutSyncDcid = "legacy-b"
        )

        assertFalse(
            hasDuplicatePair(
                registro = importedA,
                candidates = listOf(importedA),
                toleranceMillis = 15 * 60_000L,
                toleranceUnits = 1f
            )
        )
        assertTrue(
            hasDuplicatePair(
                registro = importedA,
                candidates = listOf(importedA, importedB),
                toleranceMillis = 15 * 60_000L,
                toleranceUnits = 1f
            )
        )
    }

    private fun registro(
        id: Int,
        carbs: Float,
        rations: Float,
        units: Float,
        timestamp: Long,
        dcid: String?,
        notes: String,
        origen: OrigenRegistro,
        treatmentId: String? = null,
        remoteUnits: Float? = null
    ): RegistroComida {
        return RegistroComida(
            id = id,
            hidratosTotales = carbs,
            racionesCalculadas = rations,
            unidadesInsulina = units,
            fecha = timestamp,
            notas = notes,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = origen.value,
            nightscoutSyncDcid = dcid,
            nightscoutTreatmentId = treatmentId,
            unidadesInsulinaRemota = remoteUnits
        )
    }
}
