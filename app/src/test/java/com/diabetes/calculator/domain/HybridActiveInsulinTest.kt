package com.diabetes.calculator.domain

import com.diabetes.calculator.data.repository.mergeHybridIobCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridActiveInsulinTest {

    @Test
    fun `remote candidate replaces matching local candidate`() {
        val local = listOf(
            LocalInjectionCandidate(
                registroId = 10,
                timestampMillis = 1_700_000_000_000L,
                units = 3.0f
            )
        )
        val remote = listOf(
            RemoteInjectionCandidate(
                treatmentId = "remote-1",
                timestampMillis = 1_700_000_060_000L,
                units = 3.1f
            )
        )

        val merged = mergeHybridIobCandidates(local, remote)

        assertEquals(1, merged.size)
        assertEquals(3.1f, merged.first().units, 0.0001f)
    }

    @Test
    fun `local applied candidate is kept as provisional when remote is absent`() {
        val local = listOf(
            LocalInjectionCandidate(
                registroId = 11,
                timestampMillis = 1_700_001_000_000L,
                units = 2.4f
            )
        )

        val merged = mergeHybridIobCandidates(local, emptyList())

        assertEquals(1, merged.size)
        assertEquals(2.4f, merged.first().units, 0.0001f)
    }

    @Test
    fun `unmatched local and unmatched remote are both preserved`() {
        val local = listOf(
            LocalInjectionCandidate(
                registroId = 20,
                timestampMillis = 1_700_000_000_000L,
                units = 1.0f
            )
        )
        val remote = listOf(
            RemoteInjectionCandidate(
                treatmentId = "remote-2",
                timestampMillis = 1_700_003_600_000L,
                units = 1.2f
            )
        )

        val merged = mergeHybridIobCandidates(local, remote)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.units == 1.0f })
        assertTrue(merged.any { it.units == 1.2f })
    }
}

