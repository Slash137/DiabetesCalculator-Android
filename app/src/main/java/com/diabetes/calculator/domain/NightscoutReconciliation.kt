package com.diabetes.calculator.domain

import kotlin.math.abs

data class LocalInjectionCandidate(
    val registroId: Int,
    val timestampMillis: Long,
    val units: Float,
    val dcid: String? = null
)

data class RemoteInjectionCandidate(
    val treatmentId: String,
    val timestampMillis: Long,
    val units: Float,
    val dcid: String? = null
)

data class InjectionMatch(
    val local: LocalInjectionCandidate,
    val remote: RemoteInjectionCandidate,
    val deltaMillis: Long,
    val deltaUnits: Float
)

data class ReconciliationResult(
    val matches: List<InjectionMatch>,
    val unmatchedLocals: List<LocalInjectionCandidate>,
    val unmatchedRemotes: List<RemoteInjectionCandidate>
)

object NightscoutReconciliation {
    const val MAX_DELTA_MINUTES = 15
    const val MAX_DELTA_UNITS = 0.5f

    fun reconcile(
        locals: List<LocalInjectionCandidate>,
        remotes: List<RemoteInjectionCandidate>,
        maxDeltaMinutes: Int = MAX_DELTA_MINUTES,
        maxDeltaUnits: Float = MAX_DELTA_UNITS
    ): ReconciliationResult {
        if (locals.isEmpty() || remotes.isEmpty()) {
            return ReconciliationResult(
                matches = emptyList(),
                unmatchedLocals = locals,
                unmatchedRemotes = remotes
            )
        }

        val maxDeltaMillis = maxDeltaMinutes * 60_000L
        val candidatePairs = mutableListOf<InjectionMatch>()

        remotes.forEach { remote ->
            locals.forEach { local ->
                if (!withinTolerance(local, remote, maxDeltaMillis, maxDeltaUnits)) return@forEach
                candidatePairs += InjectionMatch(
                    local = local,
                    remote = remote,
                    deltaMillis = abs(local.timestampMillis - remote.timestampMillis),
                    deltaUnits = abs(local.units - remote.units)
                )
            }
        }

        val sortedPairs = candidatePairs.sortedWith(
            compareBy<InjectionMatch> { it.deltaMillis }
                .thenBy { it.deltaUnits }
                .thenBy { it.remote.timestampMillis }
                .thenBy { it.local.timestampMillis }
                .thenBy { it.remote.treatmentId }
                .thenBy { it.local.registroId }
        )

        val matchedLocalIds = mutableSetOf<Int>()
        val matchedRemoteIds = mutableSetOf<String>()
        val matches = mutableListOf<InjectionMatch>()

        sortedPairs.forEach { pair ->
            if (matchedLocalIds.contains(pair.local.registroId)) return@forEach
            if (matchedRemoteIds.contains(pair.remote.treatmentId)) return@forEach
            matchedLocalIds += pair.local.registroId
            matchedRemoteIds += pair.remote.treatmentId
            matches += pair
        }

        val unmatchedLocals = locals.filterNot { matchedLocalIds.contains(it.registroId) }
        val unmatchedRemotes = remotes.filterNot { matchedRemoteIds.contains(it.treatmentId) }

        return ReconciliationResult(
            matches = matches,
            unmatchedLocals = unmatchedLocals,
            unmatchedRemotes = unmatchedRemotes
        )
    }

    private fun withinTolerance(
        local: LocalInjectionCandidate,
        remote: RemoteInjectionCandidate,
        maxDeltaMillis: Long,
        maxDeltaUnits: Float
    ): Boolean {
        val deltaMillis = abs(local.timestampMillis - remote.timestampMillis)
        if (deltaMillis > maxDeltaMillis) return false
        val deltaUnits = abs(local.units - remote.units)
        return deltaUnits <= maxDeltaUnits
    }
}
