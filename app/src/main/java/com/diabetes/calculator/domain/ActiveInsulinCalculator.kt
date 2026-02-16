package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.RegistroComida

const val ACTIVE_INSULIN_DURATION_MINUTES = 240
private const val ACTIVE_INSULIN_DURATION_MILLIS = ACTIVE_INSULIN_DURATION_MINUTES * 60_000L

data class ActiveInsulinSnapshot(
    val totalUnits: Float = 0f,
    val doseCount: Int = 0,
    val minutesToZero: Int? = null
)

object ActiveInsulinCalculator {

    fun calculate(
        registros: List<RegistroComida>,
        nowMillis: Long
    ): ActiveInsulinSnapshot {
        var total = 0f
        var count = 0
        var maxRemainingMinutes = 0

        registros.forEach { registro ->
            val units = registro.unidadesInsulina
            if (!units.isFinite() || units <= 0f) return@forEach

            val eventMillis = registro.dosisConfirmadaAt ?: registro.fecha
            val elapsed = nowMillis - eventMillis
            if (elapsed <= 0L || elapsed >= ACTIVE_INSULIN_DURATION_MILLIS) return@forEach

            val fraction = (1f - elapsed.toFloat() / ACTIVE_INSULIN_DURATION_MILLIS.toFloat())
                .coerceIn(0f, 1f)
            val active = units * fraction
            if (!active.isFinite() || active <= 0f) return@forEach

            total += active
            count += 1

            val remainingMillis = ACTIVE_INSULIN_DURATION_MILLIS - elapsed
            val remainingMinutes = ((remainingMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            if (remainingMinutes > maxRemainingMinutes) {
                maxRemainingMinutes = remainingMinutes
            }
        }

        return if (count == 0) {
            ActiveInsulinSnapshot()
        } else {
            ActiveInsulinSnapshot(
                totalUnits = total,
                doseCount = count,
                minutesToZero = maxRemainingMinutes
            )
        }
    }
}
