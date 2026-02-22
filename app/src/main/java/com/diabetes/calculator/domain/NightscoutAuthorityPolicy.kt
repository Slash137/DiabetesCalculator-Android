package com.diabetes.calculator.domain

data class CgmReading(
    val mgdl: Int,
    val direction: String?,
    val timestampMillis: Long,
    val source: CgmSource
)

enum class CgmSource {
    NIGHTSCOUT,
    MANUAL_FALLBACK
}

data class ResolvedCgmReading(
    val reading: CgmReading?,
    val isNightscoutFresh: Boolean
)

object NightscoutAuthorityPolicy {
    const val DEFAULT_FRESHNESS_MINUTES = 10

    fun isFreshReading(
        readingTimestampMillis: Long,
        nowMillis: Long,
        freshnessMinutes: Int = DEFAULT_FRESHNESS_MINUTES
    ): Boolean {
        if (readingTimestampMillis <= 0L) return false
        val maxAgeMillis = freshnessMinutes.coerceAtLeast(1) * 60_000L
        val ageMillis = nowMillis - readingTimestampMillis
        return ageMillis in 0L..maxAgeMillis
    }

    fun resolveGlucoseSource(
        nightscoutReading: CgmReading?,
        manualFallback: CgmReading?,
        nowMillis: Long,
        freshnessMinutes: Int = DEFAULT_FRESHNESS_MINUTES
    ): ResolvedCgmReading {
        if (nightscoutReading != null &&
            isFreshReading(
                readingTimestampMillis = nightscoutReading.timestampMillis,
                nowMillis = nowMillis,
                freshnessMinutes = freshnessMinutes
            )
        ) {
            return ResolvedCgmReading(
                reading = nightscoutReading,
                isNightscoutFresh = true
            )
        }
        return ResolvedCgmReading(
            reading = manualFallback,
            isNightscoutFresh = false
        )
    }
}

