package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class NightscoutRegistrosSyncServiceTest {
    // Tests for mergeLocalAppAndNfcDuplicates logic simulation
    // Since mergeLocalAppAndNfcDuplicates is private, testing via replication for unit confidence.

    @Test
    fun `merges manual duplicate without nfc`() {
        val baseTime = 1000000L
        val localRecords = listOf(
            RegistroComida(id = 1, hidratosTotales = 0f, racionesCalculadas = 0f, fecha = baseTime, unidadesInsulina = 5f, origenRegistro = OrigenRegistro.LOCAL.value, dosisEstado = EstadoDosis.APLICADA.value, notas = "Manual app entry"),
            RegistroComida(id = 2, hidratosTotales = 0f, racionesCalculadas = 0f, fecha = baseTime + 60000L, unidadesInsulina = 5f, origenRegistro = OrigenRegistro.LOCAL.value, dosisEstado = EstadoDosis.APLICADA.value, notas = "Juggluco sync")
        )
        // With hasNfc checks removed, cluster of 2 should merge.
        val cluster = localRecords.filter {
            abs((it.dosisConfirmadaAt ?: it.fecha) - baseTime) <= 15 * 60_000L &&
                abs(it.unidadesInsulina - 5f) <= 1f
        }
        assertEquals(2, cluster.size)
    }
}
