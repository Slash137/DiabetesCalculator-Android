package com.diabetes.calculator.domain

import kotlin.math.roundToInt

data class CorrectionWithTrendResult(
    val correccionBaseRaw: Float,
    val ajusteTendenciaRaw: Float,
    val correccionFinalRaw: Float,
    val projectedGlucoseMgdl: Int?
)

object CgmTrendCorrection {
    const val DEFAULT_PROJECTION_MINUTES = 30
    const val DEFAULT_TREND_ADJUSTMENT_CAP_UNITS = 1.0f

    fun calculateCorrectionWithTrend(
        reading: CgmReading?,
        objetivoMgdl: Int?,
        factorCorreccionMgdlPorU: Float?,
        projectionMinutes: Int = DEFAULT_PROJECTION_MINUTES,
        trendAdjustmentCapUnits: Float = DEFAULT_TREND_ADJUSTMENT_CAP_UNITS
    ): CorrectionWithTrendResult {
        if (reading == null) {
            return CorrectionWithTrendResult(
                correccionBaseRaw = 0f,
                ajusteTendenciaRaw = 0f,
                correccionFinalRaw = 0f,
                projectedGlucoseMgdl = null
            )
        }
        val objetivo = objetivoMgdl?.takeIf { it > 0 } ?: return CorrectionWithTrendResult(
            correccionBaseRaw = 0f,
            ajusteTendenciaRaw = 0f,
            correccionFinalRaw = 0f,
            projectedGlucoseMgdl = null
        )
        val factor = factorCorreccionMgdlPorU
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return CorrectionWithTrendResult(
                correccionBaseRaw = 0f,
                ajusteTendenciaRaw = 0f,
                correccionFinalRaw = 0f,
                projectedGlucoseMgdl = null
            )

        val correccionBase = (reading.mgdl - objetivo) / factor
        val adjustmentCap = trendAdjustmentCapUnits.takeIf { it.isFinite() && it > 0f } ?: 0f
        val trendRate = trendRateMgdlPerMin(reading.direction)
        val projected = (reading.mgdl.toFloat() + trendRate * projectionMinutes.coerceAtLeast(0)).roundToInt()
        val ajusteTendencia = if (reading.source == CgmSource.NIGHTSCOUT) {
            ((projected - reading.mgdl) / factor).coerceIn(-adjustmentCap, adjustmentCap)
        } else {
            0f
        }
        return CorrectionWithTrendResult(
            correccionBaseRaw = correccionBase,
            ajusteTendenciaRaw = ajusteTendencia,
            correccionFinalRaw = correccionBase + ajusteTendencia,
            projectedGlucoseMgdl = projected
        )
    }

    fun trendArrow(direction: String?): String {
        return when (direction) {
            "TripleUp" -> "⇈"
            "DoubleUp" -> "↑↑"
            "SingleUp" -> "↑"
            "FortyFiveUp" -> "↗"
            "Flat" -> "→"
            "FortyFiveDown" -> "↘"
            "SingleDown" -> "↓"
            "DoubleDown" -> "↓↓"
            "TripleDown" -> "⇊"
            else -> ""
        }
    }

    private fun trendRateMgdlPerMin(direction: String?): Float {
        return when (direction) {
            "TripleUp" -> 3.0f
            "DoubleUp" -> 2.0f
            "SingleUp" -> 1.5f
            "FortyFiveUp" -> 1.0f
            "Flat" -> 0f
            "FortyFiveDown" -> -1.0f
            "SingleDown" -> -1.5f
            "DoubleDown" -> -2.0f
            "TripleDown" -> -3.0f
            else -> 0f
        }
    }
}
