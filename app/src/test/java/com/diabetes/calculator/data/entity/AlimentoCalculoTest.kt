package com.diabetes.calculator.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlimentoCalculoTest {

    @Test
    fun calcularDesdeCantidad_gramos() {
        val alimento = Alimento(
            nombre = "Pan",
            hidratosPor100g = 50f,
            fuente = "test"
        )

        val resultado = alimento.calcularDesdeCantidad(40f)

        assertNotNull(resultado)
        assertEquals(20f, resultado!!.hidratos, 0.001f)
        assertEquals(40f, resultado.cantidadBase, 0.001f)
        assertEquals(UnidadConsumoAlimento.GRAMOS, resultado.unidadBase)
    }

    @Test
    fun calcularDesdeCantidad_ml() {
        val alimento = Alimento(
            nombre = "Zumo",
            hidratosPor100g = 0f,
            fuente = "test",
            tipoMedicionPrincipal = TipoMedicionAlimento.ML,
            estadoFisico = EstadoFisicoAlimento.LIQUIDO,
            hidratosPor100ml = 12f
        )

        val resultado = alimento.calcularDesdeCantidad(250f)

        assertNotNull(resultado)
        assertEquals(30f, resultado!!.hidratos, 0.001f)
        assertEquals(250f, resultado.cantidadBase, 0.001f)
        assertEquals(UnidadConsumoAlimento.ML, resultado.unidadBase)
    }

    @Test
    fun calcularDesdeCantidad_unidadSolido() {
        val alimento = Alimento(
            nombre = "Galleta",
            hidratosPor100g = 60f,
            fuente = "test",
            tipoMedicionPrincipal = TipoMedicionAlimento.UNIDAD,
            estadoFisico = EstadoFisicoAlimento.SOLIDO,
            unidadNombre = "pieza",
            gramosPorUnidad = 15f
        )

        val resultado = alimento.calcularDesdeCantidad(2f)

        assertNotNull(resultado)
        assertEquals(18f, resultado!!.hidratos, 0.001f)
        assertEquals(30f, resultado.cantidadBase, 0.001f)
        assertEquals(UnidadConsumoAlimento.GRAMOS, resultado.unidadBase)
    }

    @Test
    fun calcularDesdeCantidad_unidadLiquido() {
        val alimento = Alimento(
            nombre = "Bebida",
            hidratosPor100g = 0f,
            fuente = "test",
            tipoMedicionPrincipal = TipoMedicionAlimento.UNIDAD,
            estadoFisico = EstadoFisicoAlimento.LIQUIDO,
            hidratosPor100ml = 8f,
            unidadNombre = "vaso",
            mlPorUnidad = 200f
        )

        val resultado = alimento.calcularDesdeCantidad(1.5f)

        assertNotNull(resultado)
        assertEquals(24f, resultado!!.hidratos, 0.001f)
        assertEquals(300f, resultado.cantidadBase, 0.001f)
        assertEquals(UnidadConsumoAlimento.ML, resultado.unidadBase)
    }

    @Test
    fun calcularDesdeCantidad_unidadSinEquivalencia_retornaNull() {
        val alimento = Alimento(
            nombre = "Unidad incompleta",
            hidratosPor100g = 10f,
            fuente = "test",
            tipoMedicionPrincipal = TipoMedicionAlimento.UNIDAD,
            estadoFisico = EstadoFisicoAlimento.SOLIDO,
            unidadNombre = "pieza"
        )

        assertTrue(alimento.requiereEquivalenciaUnidad())
        assertNull(alimento.calcularDesdeCantidad(1f))
    }
}
