package com.diabetes.calculator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmTrendCorrectionTest {

    @Test
    fun `flat trend keeps adjustment close to zero`() {
        val reading = CgmReading(
            mgdl = 180,
            direction = "Flat",
            timestampMillis = 1_700_000_000_000L,
            source = CgmSource.NIGHTSCOUT
        )

        val result = CgmTrendCorrection.calculateCorrectionWithTrend(
            reading = reading,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = 50f
        )

        assertEquals(1.4f, result.correccionBaseRaw, 0.0001f)
        assertEquals(0f, result.ajusteTendenciaRaw, 0.0001f)
        assertEquals(1.4f, result.correccionFinalRaw, 0.0001f)
    }

    @Test
    fun `upward trend adds positive adjustment with cap`() {
        val reading = CgmReading(
            mgdl = 220,
            direction = "TripleUp",
            timestampMillis = 1_700_000_000_000L,
            source = CgmSource.NIGHTSCOUT
        )

        val result = CgmTrendCorrection.calculateCorrectionWithTrend(
            reading = reading,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = 50f,
            projectionMinutes = 30,
            trendAdjustmentCapUnits = 1.0f
        )

        assertEquals(2.2f, result.correccionBaseRaw, 0.0001f)
        assertEquals(1.0f, result.ajusteTendenciaRaw, 0.0001f)
        assertEquals(3.2f, result.correccionFinalRaw, 0.0001f)
    }

    @Test
    fun `downward trend subtracts correction`() {
        val reading = CgmReading(
            mgdl = 170,
            direction = "SingleDown",
            timestampMillis = 1_700_000_000_000L,
            source = CgmSource.NIGHTSCOUT
        )

        val result = CgmTrendCorrection.calculateCorrectionWithTrend(
            reading = reading,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = 50f
        )

        assertEquals(1.2f, result.correccionBaseRaw, 0.0001f)
        assertTrue(result.ajusteTendenciaRaw < 0f)
        assertTrue(result.correccionFinalRaw < result.correccionBaseRaw)
    }

    @Test
    fun `manual fallback does not apply trend adjustment`() {
        val reading = CgmReading(
            mgdl = 200,
            direction = "DoubleUp",
            timestampMillis = 1_700_000_000_000L,
            source = CgmSource.MANUAL_FALLBACK
        )

        val result = CgmTrendCorrection.calculateCorrectionWithTrend(
            reading = reading,
            objetivoMgdl = 100,
            factorCorreccionMgdlPorU = 50f
        )

        assertEquals(2.0f, result.correccionBaseRaw, 0.0001f)
        assertEquals(0f, result.ajusteTendenciaRaw, 0.0001f)
        assertEquals(2.0f, result.correccionFinalRaw, 0.0001f)
    }
}

