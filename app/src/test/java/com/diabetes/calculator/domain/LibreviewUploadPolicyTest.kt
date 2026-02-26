package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreviewUploadPolicyTest {

    @Test
    fun `comida con hidratos sube solo canal carbs`() {
        val meal = RegistroComida(
            id = 1,
            hidratosTotales = 35f,
            racionesCalculadas = 3.5f,
            unidadesInsulina = 4f
        )

        assertTrue(LibreviewUploadPolicy.shouldUploadCarbs(meal))
        assertFalse(LibreviewUploadPolicy.shouldUploadAppliedInsulin(meal))
    }

    @Test
    fun `dosis nfc aplicada sin hidratos sube canal insulin`() {
        val nfcDose = RegistroComida(
            id = 2,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2.5f,
            dosisEstado = EstadoDosis.APLICADA.value,
            nightscoutSyncDcid = "nfc-pen-1700-25"
        )

        assertFalse(LibreviewUploadPolicy.shouldUploadCarbs(nfcDose))
        assertTrue(LibreviewUploadPolicy.shouldUploadAppliedInsulin(nfcDose))
        assertTrue(LibreviewUploadPolicy.shouldUploadNfcInsulin(nfcDose))
    }

    @Test
    fun `dosis local aplicada no nfc tambien se sube a libreview`() {
        val nonNfcDose = RegistroComida(
            id = 3,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 3f,
            dosisEstado = EstadoDosis.APLICADA.value,
            notas = "Bolo manual"
        )

        assertFalse(LibreviewUploadPolicy.shouldUploadCarbs(nonNfcDose))
        assertTrue(LibreviewUploadPolicy.shouldUploadAppliedInsulin(nonNfcDose))
        assertFalse(LibreviewUploadPolicy.shouldUploadNfcInsulin(nonNfcDose))
    }

    @Test
    fun `dosis pendiente no se sube a libreview`() {
        val pendingDose = RegistroComida(
            id = 4,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )

        assertFalse(LibreviewUploadPolicy.shouldUploadAppliedInsulin(pendingDose))
    }

    @Test
    fun `registros importados de nightscout se excluyen de libreview`() {
        val imported = RegistroComida(
            id = 5,
            hidratosTotales = 22f,
            racionesCalculadas = 2.2f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )

        assertFalse(LibreviewUploadPolicy.shouldUploadCarbs(imported))
        assertFalse(LibreviewUploadPolicy.shouldUploadAppliedInsulin(imported))
    }

    @Test
    fun `repair excluye registros importados de nightscout`() {
        val imported = RegistroComida(
            id = 6,
            hidratosTotales = 24f,
            racionesCalculadas = 2.4f,
            unidadesInsulina = 3f,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )

        assertFalse(LibreviewUploadPolicy.shouldRepairUploadCarbs(imported))
        assertFalse(LibreviewUploadPolicy.shouldRepairUploadInsulin(imported))
    }

    @Test
    fun `manual catch-up incluye dosis pendiente con unidades`() {
        val pending = RegistroComida(
            id = 7,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.PENDIENTE.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )

        assertTrue(LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(pending))
    }

    @Test
    fun `manual catch-up excluye dosis omitida`() {
        val skipped = RegistroComida(
            id = 8,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.OMITIDA.value,
            origenRegistro = OrigenRegistro.LOCAL.value
        )

        assertFalse(LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(skipped))
    }

    @Test
    fun `manual catch-up excluye importados de nightscout`() {
        val imported = RegistroComida(
            id = 9,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.APLICADA.value,
            origenRegistro = OrigenRegistro.NIGHTSCOUT_IMPORT.value
        )

        assertFalse(LibreviewUploadPolicy.shouldManualCatchupUploadInsulin(imported))
    }
}
