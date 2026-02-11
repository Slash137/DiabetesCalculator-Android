package com.diabetes.calculator.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculadorInsulinaTest {

    @Test
    fun `calcularHidratos devuelve cero con valores invalidos`() {
        assertEquals(0f, CalculadorInsulina.calcularHidratos(10f, 0f))
        assertEquals(0f, CalculadorInsulina.calcularHidratos(-1f, 100f))
    }

    @Test
    fun `calcularRaciones protege division por cero`() {
        assertEquals(0f, CalculadorInsulina.calcularRaciones(30f, 0f))
    }

    @Test
    fun `calcularInsulina redondea a medios`() {
        val insulina = CalculadorInsulina.calcularInsulina(
            raciones = 2.3f,
            ratioInsulina = 1f
        )
        assertEquals(2.5f, insulina)
    }

    @Test
    fun `calcularTodo combina formulas esperadas`() {
        val (hidratos, raciones, insulina) = CalculadorInsulina.calcularTodo(
            hidratosPor100g = 50f,
            gramosConsumidos = 60f,
            gramosPorRacion = 10f,
            ratioInsulina = 1.2f
        )

        assertEquals(30f, hidratos)
        assertEquals(3f, raciones)
        assertEquals(3.5f, insulina)
    }
}
