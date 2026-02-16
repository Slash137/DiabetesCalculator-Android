package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.RegistroComida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveInsulinCalculatorTest {

    private val now = 1_000_000_000L

    @Test
    fun `single 4U just applied keeps near 4U active`() {
        val registro = registro(units = 4f, eventMillis = now - 60_000L)

        val snapshot = ActiveInsulinCalculator.calculate(listOf(registro), now)

        assertEquals(4f, snapshot.totalUnits, 0.05f)
        assertEquals(1, snapshot.doseCount)
    }

    @Test
    fun `single 4U after 2h returns 2U active`() {
        val twoHoursMillis = 2 * 60 * 60 * 1000L
        val registro = registro(units = 4f, eventMillis = now - twoHoursMillis)

        val snapshot = ActiveInsulinCalculator.calculate(listOf(registro), now)

        assertEquals(2f, snapshot.totalUnits, 0.01f)
        assertEquals(1, snapshot.doseCount)
    }

    @Test
    fun `single 4U after more than 4h returns zero`() {
        val moreThanFourHoursMillis = (ACTIVE_INSULIN_DURATION_MINUTES + 1) * 60_000L
        val registro = registro(units = 4f, eventMillis = now - moreThanFourHoursMillis)

        val snapshot = ActiveInsulinCalculator.calculate(listOf(registro), now)

        assertEquals(0f, snapshot.totalUnits, 0.0001f)
        assertEquals(0, snapshot.doseCount)
        assertNull(snapshot.minutesToZero)
    }

    @Test
    fun `multiple doses sum active insulin correctly`() {
        val oneHour = 60 * 60 * 1000L
        val thirtyMinutes = 30 * 60 * 1000L
        val a = registro(units = 4f, eventMillis = now - oneHour)      // 3.0U
        val b = registro(units = 2f, eventMillis = now - thirtyMinutes) // 1.75U

        val snapshot = ActiveInsulinCalculator.calculate(listOf(a, b), now)

        assertEquals(4.75f, snapshot.totalUnits, 0.01f)
        assertEquals(2, snapshot.doseCount)
        assertEquals(210, snapshot.minutesToZero)
    }

    @Test
    fun `future and invalid doses are ignored`() {
        val valid = registro(units = 3f, eventMillis = now - 60_000L)
        val future = registro(units = 4f, eventMillis = now + 60_000L)
        val negative = registro(units = -1f, eventMillis = now - 60_000L)
        val nanDose = registro(units = Float.NaN, eventMillis = now - 60_000L)
        val infiniteDose = registro(units = Float.POSITIVE_INFINITY, eventMillis = now - 60_000L)

        val snapshot = ActiveInsulinCalculator.calculate(
            listOf(valid, future, negative, nanDose, infiniteDose),
            now
        )

        assertEquals(1, snapshot.doseCount)
        assertEquals(3f, snapshot.totalUnits, 0.05f)
    }

    @Test
    fun `uses dosisConfirmadaAt over fecha as event time`() {
        val oldFecha = now - (5 * 60 * 60 * 1000L)
        val confirmadaReciente = now - (60 * 60 * 1000L)
        val registro = registro(
            units = 4f,
            eventMillis = oldFecha,
            dosisConfirmadaAt = confirmadaReciente
        )

        val snapshot = ActiveInsulinCalculator.calculate(listOf(registro), now)

        assertEquals(3f, snapshot.totalUnits, 0.01f)
        assertEquals(1, snapshot.doseCount)
    }

    private fun registro(
        units: Float,
        eventMillis: Long,
        dosisConfirmadaAt: Long? = null
    ): RegistroComida {
        return RegistroComida(
            id = 0,
            hidratosTotales = 0f,
            racionesCalculadas = 0f,
            unidadesInsulina = units,
            fecha = eventMillis,
            dosisConfirmadaAt = dosisConfirmadaAt
        )
    }
}
