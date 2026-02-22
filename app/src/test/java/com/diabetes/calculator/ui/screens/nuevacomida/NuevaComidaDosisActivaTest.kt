package com.diabetes.calculator.ui.screens.nuevacomida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuevaComidaDosisActivaTest {

    @Test
    fun `sin insulina activa mantiene dosis contextual redondeada`() {
        val result = calcularDosisFinalConInsulinaActiva(
            unidadesComida = 3.1f,
            unidadesCorreccionBruta = 1f,
            insulinaActiva = 0f,
            factorTotalAplicado = 1.2f
        )

        assertEquals(1f, result.unidadesCorreccion, 0.0001f)
        assertEquals(0f, result.unidadesCorreccionReducidaPorActiva, 0.0001f)
        assertEquals(0f, result.unidadesComidaReducidaPorActiva, 0.0001f)
        assertEquals(3.5f, result.unidadesInsulinaSinCorreccion, 0.0001f)
        assertEquals(5f, result.unidadesInsulinaConCorreccion, 0.0001f)
    }

    @Test
    fun `insulina activa cubre solo correccion positiva`() {
        val result = calcularDosisFinalConInsulinaActiva(
            unidadesComida = 3.1f,
            unidadesCorreccionBruta = 1f,
            insulinaActiva = 0.8f,
            factorTotalAplicado = 1f
        )

        assertEquals(0.2f, result.unidadesCorreccion, 0.0001f)
        assertEquals(0.8f, result.unidadesCorreccionReducidaPorActiva, 0.0001f)
        assertEquals(0f, result.unidadesComidaReducidaPorActiva, 0.0001f)
        assertEquals(3f, result.unidadesInsulinaSinCorreccion, 0.0001f)
        assertEquals(3.5f, result.unidadesInsulinaConCorreccion, 0.0001f)
        assertTrue(
            result.unidadesCorreccionReducidaPorActiva + result.unidadesComidaReducidaPorActiva <= 0.8f + 0.0001f
        )
    }

    @Test
    fun `insulina activa puede cubrir correccion y parte de la dosis base`() {
        val result = calcularDosisFinalConInsulinaActiva(
            unidadesComida = 4.2f,
            unidadesCorreccionBruta = 1.6f,
            insulinaActiva = 2.1f,
            factorTotalAplicado = 1f
        )

        assertEquals(0f, result.unidadesCorreccion, 0.0001f)
        assertEquals(1.6f, result.unidadesCorreccionReducidaPorActiva, 0.0001f)
        assertEquals(0.5f, result.unidadesComidaReducidaPorActiva, 0.0001f)
        assertEquals(3.5f, result.unidadesInsulinaSinCorreccion, 0.0001f)
        assertEquals(3.5f, result.unidadesInsulinaConCorreccion, 0.0001f)
        assertTrue(
            result.unidadesCorreccionReducidaPorActiva + result.unidadesComidaReducidaPorActiva <= 2.1f + 0.0001f
        )
    }

    @Test
    fun `con correccion negativa no duplica descuento y no baja de cero`() {
        val result = calcularDosisFinalConInsulinaActiva(
            unidadesComida = 2.4f,
            unidadesCorreccionBruta = -1f,
            insulinaActiva = 1.5f,
            factorTotalAplicado = 1f
        )

        assertEquals(-1f, result.unidadesCorreccion, 0.0001f)
        assertEquals(0f, result.unidadesCorreccionReducidaPorActiva, 0.0001f)
        assertEquals(1.4f, result.unidadesComidaReducidaPorActiva, 0.0001f)
        assertEquals(1f, result.unidadesInsulinaSinCorreccion, 0.0001f)
        assertEquals(0f, result.unidadesInsulinaConCorreccion, 0.0001f)
        assertTrue(
            result.unidadesCorreccionReducidaPorActiva + result.unidadesComidaReducidaPorActiva <= 1.5f + 0.0001f
        )
        assertTrue(result.unidadesInsulinaSinCorreccion >= 0f)
        assertTrue(result.unidadesInsulinaConCorreccion >= 0f)
    }

    @Test
    fun `redondea solo al final y evita error por redondeo intermedio`() {
        val result = calcularDosisFinalConInsulinaActiva(
            unidadesComida = 3.24f,
            unidadesCorreccionBruta = 0f,
            insulinaActiva = 0.26f,
            factorTotalAplicado = 1f
        )

        assertEquals(0.26f, result.unidadesComidaReducidaPorActiva, 0.0001f)
        assertEquals(3f, result.unidadesInsulinaSinCorreccion, 0.0001f)
        assertEquals(3f, result.unidadesInsulinaConCorreccion, 0.0001f)
    }

    @Test
    fun `salidas finales siempre quedan en pasos de media unidad`() {
        val result = calcularDosisFinalConInsulinaActiva(
            unidadesComida = 5.37f,
            unidadesCorreccionBruta = 0.83f,
            insulinaActiva = 0.21f,
            factorTotalAplicado = 1.11f
        )

        val stepCon = result.unidadesInsulinaConCorreccion * 2f
        val stepSin = result.unidadesInsulinaSinCorreccion * 2f
        assertEquals(stepCon, kotlin.math.round(stepCon), 0.0001f)
        assertEquals(stepSin, kotlin.math.round(stepSin), 0.0001f)
    }
}
