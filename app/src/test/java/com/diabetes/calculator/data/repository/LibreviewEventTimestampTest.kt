package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import com.diabetes.calculator.domain.LibreviewRecordNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class LibreviewEventTimestampTest {

    @Test
    fun `carbs channel always uses meal timestamp`() {
        val registro = RegistroComida(
            id = 1,
            hidratosTotales = 32f,
            racionesCalculadas = 3.2f,
            unidadesInsulina = 4f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_120_000L
        )

        val eventMillis = resolveLibreviewEventTimestamp(
            registro = registro,
            channel = RegistroLibreviewSyncChannel.CARBS
        )

        assertEquals(registro.fecha, eventMillis)
    }

    @Test
    fun `insulin channel uses confirmation timestamp when present`() {
        val registro = RegistroComida(
            id = 2,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_300_000L
        )

        val eventMillis = resolveLibreviewEventTimestamp(
            registro = registro,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN
        )

        assertEquals(registro.dosisConfirmadaAt, eventMillis)
    }

    @Test
    fun `insulin channel falls back to meal timestamp`() {
        val registro = RegistroComida(
            id = 3,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = null
        )

        val eventMillis = resolveLibreviewEventTimestamp(
            registro = registro,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN
        )

        assertEquals(registro.fecha, eventMillis)
    }

    @Test
    fun `carbs delete resolves canonical timestamp for canonical record number`() {
        val registro = RegistroComida(
            id = 4,
            hidratosTotales = 25f,
            racionesCalculadas = 2.5f,
            unidadesInsulina = 3f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_180_000L
        )
        val canonicalRecordNumber = LibreviewRecordNumber.from(
            registroId = registro.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = registro.fecha
        )

        val resolved = resolveCarbsTimestampForRecordNumber(
            registro = registro,
            recordNumber = canonicalRecordNumber,
            fallbackTimestamp = 9L
        )

        assertEquals(registro.fecha, resolved)
    }

    @Test
    fun `carbs delete resolves legacy timestamp for legacy carbs record number`() {
        val registro = RegistroComida(
            id = 5,
            hidratosTotales = 25f,
            racionesCalculadas = 2.5f,
            unidadesInsulina = 3f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_180_000L
        )
        val legacyRecordNumber = LibreviewRecordNumber.from(
            registroId = registro.id,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = registro.dosisConfirmadaAt ?: registro.fecha
        )

        val resolved = resolveCarbsTimestampForRecordNumber(
            registro = registro,
            recordNumber = legacyRecordNumber,
            fallbackTimestamp = 9L
        )

        assertEquals(registro.dosisConfirmadaAt, resolved)
    }

    @Test
    fun `insulin delete resolves legacy timestamp for legacy insulin record number`() {
        val registro = RegistroComida(
            id = 6,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 4f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_180_000L
        )
        val legacyRecordNumber = LibreviewRecordNumber.from(
            registroId = registro.id,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = registro.fecha
        )

        val resolved = resolveInsulinTimestampForRecordNumber(
            registro = registro,
            recordNumber = legacyRecordNumber,
            fallbackTimestamp = 11L
        )

        assertEquals(registro.fecha, resolved)
    }

    @Test
    fun `record number without known mapping falls back to provided timestamp`() {
        val registro = RegistroComida(
            id = 7,
            hidratosTotales = 15f,
            racionesCalculadas = 1.5f,
            unidadesInsulina = 2f,
            fecha = 1_700_000_000_000L,
            dosisConfirmadaAt = 1_700_000_120_000L
        )

        val carbsResolved = resolveCarbsTimestampForRecordNumber(
            registro = registro,
            recordNumber = 999_999_999L,
            fallbackTimestamp = 123L
        )
        val insulinResolved = resolveInsulinTimestampForRecordNumber(
            registro = registro,
            recordNumber = 999_999_999L,
            fallbackTimestamp = 456L
        )

        assertEquals(123L, carbsResolved)
        assertEquals(456L, insulinResolved)
    }
}
