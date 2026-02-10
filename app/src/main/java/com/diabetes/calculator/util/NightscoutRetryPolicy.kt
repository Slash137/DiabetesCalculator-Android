package com.diabetes.calculator.util

import kotlin.math.min

/**
 * Política de reintento para Nightscout con backoff exponencial.
 */
object NightscoutRetryPolicy {
    const val MAX_ATTEMPTS = 6
    const val BASE_DELAY_MINUTES = 10L
    const val MAX_DELAY_MINUTES = 360L

    /**
     * Calcula el siguiente retraso en minutos a partir del número de intentos fallidos.
     */
    fun nextDelayMinutes(attempts: Int): Long {
        val clamped = attempts.coerceAtLeast(0)
        val factor = 1L shl clamped.coerceAtMost(10)
        val delay = BASE_DELAY_MINUTES * factor
        return min(delay, MAX_DELAY_MINUTES)
    }
}
