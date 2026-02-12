package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.UsuarioProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoresContextoInsulinaTest {

    private val neutralProfile = UsuarioProfile(
        nombre = "Test",
        gramosPorRacion = 10f,
        ratioInsulina = 1f
    )

    @Test
    fun `resolve mantiene factor neutro con niveles base`() {
        val selection = SeleccionContextoInsulina(
            franjaHoraria = FranjaHoraria.MANANA,
            nivelEstres = NivelEstres.NINGUNO,
            nivelEnfermedad = NivelEnfermedad.NINGUNA,
            faseCiclo = FaseCicloHormonal.NO_APLICAR,
            nivelEjercicio = NivelEjercicio.NINGUNO
        )

        val result = FactoresContextoInsulina.resolve(neutralProfile, selection)
        assertEquals(1f, result.factorTotalRaw, 0.0001f)
        assertEquals(1f, result.factorTotalAplicado, 0.0001f)
        assertFalse(result.factorCapado)
    }

    @Test
    fun `resolve aplica cap superior`() {
        val profile = neutralProfile.copy(
            factorHoraManana = 1.2f,
            factorEstresLeve = 1.2f,
            factorEnfermedadLeve = 1.2f,
            cicloHormonalActivo = true,
            factorCicloOvulacion = 1.2f
        )
        val selection = SeleccionContextoInsulina(
            franjaHoraria = FranjaHoraria.MANANA,
            nivelEstres = NivelEstres.LEVE,
            nivelEnfermedad = NivelEnfermedad.LEVE,
            faseCiclo = FaseCicloHormonal.OVULACION,
            nivelEjercicio = NivelEjercicio.NINGUNO
        )

        val result = FactoresContextoInsulina.resolve(profile, selection)
        assertTrue(result.factorTotalRaw > FactoresContextoInsulina.FACTOR_MAX)
        assertEquals(FactoresContextoInsulina.FACTOR_MAX, result.factorTotalAplicado, 0.0001f)
        assertTrue(result.factorCapado)
    }

    @Test
    fun `resolve aplica cap inferior`() {
        val profile = neutralProfile.copy(
            factorHoraMadrugada = 0.7f,
            cicloHormonalActivo = true,
            factorCicloMenstruacion = 0.8f,
            factorEjercicioIntenso = 0.5f
        )
        val selection = SeleccionContextoInsulina(
            franjaHoraria = FranjaHoraria.MADRUGADA,
            nivelEstres = NivelEstres.NINGUNO,
            nivelEnfermedad = NivelEnfermedad.NINGUNA,
            faseCiclo = FaseCicloHormonal.MENSTRUACION,
            nivelEjercicio = NivelEjercicio.INTENSO
        )

        val result = FactoresContextoInsulina.resolve(profile, selection)
        assertTrue(result.factorTotalRaw < FactoresContextoInsulina.FACTOR_MIN)
        assertEquals(FactoresContextoInsulina.FACTOR_MIN, result.factorTotalAplicado, 0.0001f)
        assertTrue(result.factorCapado)
    }

    @Test
    fun `resolve ignora ciclo cuando esta desactivado en perfil`() {
        val profile = neutralProfile.copy(
            cicloHormonalActivo = false,
            factorCicloLutea = 1.25f
        )
        val selection = SeleccionContextoInsulina(
            franjaHoraria = FranjaHoraria.NOCHE,
            nivelEstres = NivelEstres.NINGUNO,
            nivelEnfermedad = NivelEnfermedad.NINGUNA,
            faseCiclo = FaseCicloHormonal.LUTEA,
            nivelEjercicio = NivelEjercicio.NINGUNO
        )

        val result = FactoresContextoInsulina.resolve(profile, selection)
        assertEquals(1f, result.factorCiclo, 0.0001f)
        assertEquals(1f, result.factorTotalAplicado, 0.0001f)
    }

    @Test
    fun `franja por hora respeta limites`() {
        assertEquals(FranjaHoraria.MADRUGADA, FactoresContextoInsulina.franjaPorHora(5))
        assertEquals(FranjaHoraria.MANANA, FactoresContextoInsulina.franjaPorHora(6))
        assertEquals(FranjaHoraria.MANANA, FactoresContextoInsulina.franjaPorHora(11))
        assertEquals(FranjaHoraria.TARDE, FactoresContextoInsulina.franjaPorHora(12))
        assertEquals(FranjaHoraria.TARDE, FactoresContextoInsulina.franjaPorHora(17))
        assertEquals(FranjaHoraria.NOCHE, FactoresContextoInsulina.franjaPorHora(18))
    }

    @Test
    fun `applyFactorToDoses redondea y no deja negativos`() {
        val result = FactoresContextoInsulina.applyFactorToDoses(
            unidadesComida = 3.1f,
            unidadesCorreccion = -6f,
            factorTotalAplicado = 1.2f
        )
        assertEquals(3.5f, result.totalSinCorreccion, 0.0001f)
        assertEquals(0f, result.totalConCorreccion, 0.0001f)
    }
}
