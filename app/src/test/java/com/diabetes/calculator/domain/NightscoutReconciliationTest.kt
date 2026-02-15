package com.diabetes.calculator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutReconciliationTest {
    @Test
    fun `hace match exacto por tiempo y dosis`() {
        val local = LocalInjectionCandidate(
            registroId = 1,
            timestampMillis = 1_000_000L,
            units = 4.0f
        )
        val remote = RemoteInjectionCandidate(
            treatmentId = "r1",
            timestampMillis = 1_000_000L,
            units = 4.0f
        )

        val result = NightscoutReconciliation.reconcile(
            locals = listOf(local),
            remotes = listOf(remote)
        )

        assertEquals(1, result.matches.size)
        assertTrue(result.unmatchedLocals.isEmpty())
        assertTrue(result.unmatchedRemotes.isEmpty())
    }

    @Test
    fun `hace match dentro de tolerancia`() {
        val local = LocalInjectionCandidate(
            registroId = 10,
            timestampMillis = 1_000_000L,
            units = 5.0f
        )
        val remote = RemoteInjectionCandidate(
            treatmentId = "r10",
            timestampMillis = 1_000_000L + (14 * 60_000L),
            units = 5.4f
        )

        val result = NightscoutReconciliation.reconcile(
            locals = listOf(local),
            remotes = listOf(remote)
        )

        assertEquals(1, result.matches.size)
    }

    @Test
    fun `no hace match fuera de tolerancia`() {
        val local = LocalInjectionCandidate(
            registroId = 20,
            timestampMillis = 1_000_000L,
            units = 5.0f
        )
        val remote = RemoteInjectionCandidate(
            treatmentId = "r20",
            timestampMillis = 1_000_000L + (16 * 60_000L),
            units = 5.0f
        )

        val result = NightscoutReconciliation.reconcile(
            locals = listOf(local),
            remotes = listOf(remote)
        )

        assertTrue(result.matches.isEmpty())
        assertEquals(1, result.unmatchedLocals.size)
        assertEquals(1, result.unmatchedRemotes.size)
    }

    @Test
    fun `resuelve de forma determinista con multiples candidatos`() {
        val base = 1_000_000L
        val locals = listOf(
            LocalInjectionCandidate(registroId = 1, timestampMillis = base, units = 4.0f),
            LocalInjectionCandidate(registroId = 2, timestampMillis = base + 60_000L, units = 4.5f)
        )
        val remotes = listOf(
            RemoteInjectionCandidate(treatmentId = "a", timestampMillis = base + 30_000L, units = 4.0f),
            RemoteInjectionCandidate(treatmentId = "b", timestampMillis = base + 60_000L, units = 4.5f)
        )

        val result = NightscoutReconciliation.reconcile(locals = locals, remotes = remotes)

        assertEquals(2, result.matches.size)
        assertEquals(2, result.matches.map { it.local.registroId }.distinct().size)
        assertEquals(2, result.matches.map { it.remote.treatmentId }.distinct().size)
    }
}
