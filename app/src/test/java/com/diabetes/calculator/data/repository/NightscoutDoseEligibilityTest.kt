package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.RegistroComida
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutDoseEligibilityTest {

    @Test
    fun `dosis local sin hidratos aplicada es elegible para nightscout`() {
        val registro = RegistroComida(
            id = 1,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.APLICADA.value
        )

        assertTrue(
            shouldUploadDoseOnlyLocalToNightscout(
                registro = registro,
                effectiveUnits = 2f
            )
        )
    }

    @Test
    fun `dosis pendiente no es elegible para nightscout`() {
        val registro = RegistroComida(
            id = 2,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.PENDIENTE.value
        )

        assertFalse(
            shouldUploadDoseOnlyLocalToNightscout(
                registro = registro,
                effectiveUnits = 2f
            )
        )
    }

    @Test
    fun `registro con hidratos no entra en regla de dosis sin comida`() {
        val registro = RegistroComida(
            id = 3,
            hidratosTotales = 25f,
            racionesCalculadas = 2.5f,
            unidadesInsulina = 2f,
            dosisEstado = EstadoDosis.APLICADA.value
        )

        assertFalse(
            shouldUploadDoseOnlyLocalToNightscout(
                registro = registro,
                effectiveUnits = 2f
            )
        )
    }
}
