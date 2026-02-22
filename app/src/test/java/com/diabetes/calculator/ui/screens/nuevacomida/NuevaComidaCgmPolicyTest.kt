package com.diabetes.calculator.ui.screens.nuevacomida

import com.diabetes.calculator.domain.CgmReading
import com.diabetes.calculator.domain.CgmSource
import com.diabetes.calculator.domain.NightscoutAuthorityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuevaComidaCgmPolicyTest {

    @Test
    fun `nightscout fresh overrides manual fallback`() {
        val now = 1_700_000_000_000L
        val nightscout = CgmReading(
            mgdl = 190,
            direction = "SingleUp",
            timestampMillis = now - 3 * 60_000L,
            source = CgmSource.NIGHTSCOUT
        )
        val manual = CgmReading(
            mgdl = 160,
            direction = null,
            timestampMillis = now,
            source = CgmSource.MANUAL_FALLBACK
        )

        val resolved = NightscoutAuthorityPolicy.resolveGlucoseSource(
            nightscoutReading = nightscout,
            manualFallback = manual,
            nowMillis = now,
            freshnessMinutes = 10
        )

        assertTrue(resolved.isNightscoutFresh)
        assertEquals(CgmSource.NIGHTSCOUT, resolved.reading?.source)
        assertEquals(190, resolved.reading?.mgdl)
    }

    @Test
    fun `stale nightscout falls back to manual`() {
        val now = 1_700_000_000_000L
        val nightscout = CgmReading(
            mgdl = 210,
            direction = "DoubleUp",
            timestampMillis = now - 15 * 60_000L,
            source = CgmSource.NIGHTSCOUT
        )
        val manual = CgmReading(
            mgdl = 175,
            direction = null,
            timestampMillis = now,
            source = CgmSource.MANUAL_FALLBACK
        )

        val resolved = NightscoutAuthorityPolicy.resolveGlucoseSource(
            nightscoutReading = nightscout,
            manualFallback = manual,
            nowMillis = now,
            freshnessMinutes = 10
        )

        assertFalse(resolved.isNightscoutFresh)
        assertEquals(CgmSource.MANUAL_FALLBACK, resolved.reading?.source)
        assertEquals(175, resolved.reading?.mgdl)
    }

    @Test
    fun `without any source it returns null reading`() {
        val resolved = NightscoutAuthorityPolicy.resolveGlucoseSource(
            nightscoutReading = null,
            manualFallback = null,
            nowMillis = 1_700_000_000_000L,
            freshnessMinutes = 10
        )

        assertFalse(resolved.isNightscoutFresh)
        assertNotNull(resolved)
        assertEquals(null, resolved.reading)
    }
}

