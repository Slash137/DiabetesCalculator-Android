package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.RegistroComida

const val ACTIVE_INSULIN_DURATION_MINUTES = 240
private const val ACTIVE_INSULIN_DURATION_MILLIS = ACTIVE_INSULIN_DURATION_MINUTES * 60_000L

data class ActiveInsulinSnapshot(
    val totalUnits: Float = 0f,
    val doseCount: Int = 0,
    val minutesToZero: Int? = null,
    val contributions: List<ActiveInsulinDoseContribution> = emptyList()
)

data class ActiveInsulinDoseEvent(
    val units: Float,
    val eventMillis: Long
)

data class ActiveInsulinDoseContribution(
    val originalUnits: Float,
    val activeUnits: Float,
    val eventMillis: Long,
    val minutesRemaining: Int
)

object ActiveInsulinCalculator {

    fun calculate(
        registros: List<RegistroComida>,
        nowMillis: Long
    ): ActiveInsulinSnapshot {
        val events = registros.map {
            ActiveInsulinDoseEvent(
                units = it.unidadesInsulina,
                eventMillis = it.dosisConfirmadaAt ?: it.fecha
            )
        }
        return calculateFromEvents(events, nowMillis)
    }

    fun calculateFromEvents(
        events: List<ActiveInsulinDoseEvent>,
        nowMillis: Long
    ): ActiveInsulinSnapshot {
        var total = 0f
        var maxRemainingMinutes = 0
        val contributions = mutableListOf<ActiveInsulinDoseContribution>()

        events.forEach { event ->
            val units = event.units
            if (!units.isFinite() || units <= 0f) return@forEach

            val eventMillis = event.eventMillis
            val elapsed = nowMillis - eventMillis
            if (elapsed <= 0L || elapsed >= ACTIVE_INSULIN_DURATION_MILLIS) return@forEach

            val fraction = (1f - elapsed.toFloat() / ACTIVE_INSULIN_DURATION_MILLIS.toFloat())
                .coerceIn(0f, 1f)
            val active = units * fraction
            if (!active.isFinite() || active <= 0f) return@forEach

            total += active

            val remainingMillis = ACTIVE_INSULIN_DURATION_MILLIS - elapsed
            val remainingMinutes = ((remainingMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            if (remainingMinutes > maxRemainingMinutes) {
                maxRemainingMinutes = remainingMinutes
            }
            contributions += ActiveInsulinDoseContribution(
                originalUnits = units,
                activeUnits = active,
                eventMillis = eventMillis,
                minutesRemaining = remainingMinutes
            )
        }

        if (contributions.isEmpty()) {
            return ActiveInsulinSnapshot()
        }
        val sortedContributions = contributions.sortedByDescending { it.eventMillis }
        return ActiveInsulinSnapshot(
            totalUnits = total,
            doseCount = sortedContributions.size,
            minutesToZero = maxRemainingMinutes,
            contributions = sortedContributions
        )
    }
}
