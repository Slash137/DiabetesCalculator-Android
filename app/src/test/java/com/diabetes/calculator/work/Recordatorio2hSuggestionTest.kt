package com.diabetes.calculator.work

import org.junit.Assert.assertEquals
import org.junit.Test

class Recordatorio2hSuggestionTest {

    @Test
    fun `sugiere correccion cuando glucosa esta alta y activa es parcial`() {
        val result = calculate2hCorrectionSuggestion(
            applyByDefault = true,
            glucosaActualMgdl = 210,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = 50f,
            insulinaActivaUnidades = 0.4f
        )

        assertEquals(CorrectionSuggestionReason.SUGGESTED, result.reason)
        assertEquals(1.5f, result.suggestedUnits, 0.0001f)
    }

    @Test
    fun `activa mayor que correccion bruta deja sugerencia en cero`() {
        val result = calculate2hCorrectionSuggestion(
            applyByDefault = true,
            glucosaActualMgdl = 180,
            objetivoMgdl = 100,
            factorCorreccionMgdlPorU = 40f,
            insulinaActivaUnidades = 2.5f
        )

        assertEquals(CorrectionSuggestionReason.NO_CORRECTION_NEEDED, result.reason)
        assertEquals(0f, result.suggestedUnits, 0.0001f)
    }

    @Test
    fun `glucosa en objetivo no sugiere correccion`() {
        val result = calculate2hCorrectionSuggestion(
            applyByDefault = true,
            glucosaActualMgdl = 100,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = 50f,
            insulinaActivaUnidades = 0.2f
        )

        assertEquals(CorrectionSuggestionReason.NO_CORRECTION_NEEDED, result.reason)
        assertEquals(0f, result.suggestedUnits, 0.0001f)
    }

    @Test
    fun `sin objetivo o factor no sugiere y marca configuracion incompleta`() {
        val noObjetivo = calculate2hCorrectionSuggestion(
            applyByDefault = true,
            glucosaActualMgdl = 220,
            objetivoMgdl = null,
            factorCorreccionMgdlPorU = 45f,
            insulinaActivaUnidades = 0f
        )
        val noFactor = calculate2hCorrectionSuggestion(
            applyByDefault = true,
            glucosaActualMgdl = 220,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = null,
            insulinaActivaUnidades = 0f
        )

        assertEquals(CorrectionSuggestionReason.MISSING_CORRECTION_CONFIG, noObjetivo.reason)
        assertEquals(0f, noObjetivo.suggestedUnits, 0.0001f)
        assertEquals(CorrectionSuggestionReason.MISSING_CORRECTION_CONFIG, noFactor.reason)
        assertEquals(0f, noFactor.suggestedUnits, 0.0001f)
    }

    @Test
    fun `si aplicarCorreccionPorDefecto esta desactivado no sugiere automatica`() {
        val result = calculate2hCorrectionSuggestion(
            applyByDefault = false,
            glucosaActualMgdl = 230,
            objetivoMgdl = 110,
            factorCorreccionMgdlPorU = 50f,
            insulinaActivaUnidades = 0f
        )

        assertEquals(CorrectionSuggestionReason.DISABLED_BY_PROFILE, result.reason)
        assertEquals(0f, result.suggestedUnits, 0.0001f)
    }
}

