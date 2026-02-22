package com.diabetes.calculator.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.domain.ACTIVE_INSULIN_DURATION_MINUTES
import com.diabetes.calculator.domain.ActiveInsulinCalculator
import com.diabetes.calculator.domain.FactoresContextoInsulina
import com.diabetes.calculator.util.NightscoutTokenStore
import com.diabetes.calculator.util.DateUtils
import java.util.Locale

/**
 * Recordatorio manual para medir glucosa 2 h después de una comida.
 */
class Recordatorio2hWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val registroId = inputData.getInt(KEY_REGISTRO_ID, -1)
        if (registroId <= 0) return Result.failure()

        val database = AppDatabase.getDatabase(applicationContext)
        val registro = database.registroComidaDao().getById(registroId)?.registro ?: return Result.success()
        val tokenStore = NightscoutTokenStore(applicationContext)
        val usuarioRepository = UsuarioProfileRepository(database.usuarioProfileDao(), tokenStore)
        val profile = usuarioRepository.getProfileSync()
        val nowMillis = System.currentTimeMillis()

        val baseEventMillis = registro.dosisConfirmadaAt ?: registro.fecha
        val controlTimeLabel = DateUtils.formatTime(baseEventMillis + TWO_HOURS_MS)
        val glucosaActual = fetchCurrentGlucose(profile)
        val insulinaActiva = fetchActiveInsulinUnits(database, nowMillis)
        val sugerencia = calculate2hCorrectionSuggestion(
            applyByDefault = profile?.aplicarCorreccionPorDefecto == true,
            glucosaActualMgdl = glucosaActual,
            objetivoMgdl = profile?.glucosaObjetivoMgdl,
            factorCorreccionMgdlPorU = profile?.factorCorreccionMgdlPorU,
            insulinaActivaUnidades = insulinaActiva
        )
        val notificationText = buildNotificationText(
            glucosaActualMgdl = glucosaActual,
            profile = profile,
            insulinaActivaUnidades = insulinaActiva,
            controlTimeLabel = controlTimeLabel,
            suggestion = sugerencia
        )

        Recordatorio2hNotificationHelper.showRealNotification(
            context = applicationContext,
            registroId = registroId,
            title = notificationText.title,
            contentText = notificationText.content,
            bigText = notificationText.details
        )
        return Result.success()
    }

    private suspend fun fetchCurrentGlucose(profile: UsuarioProfile?): Int? {
        val baseUrl = profile?.nightscoutUrl?.trim().orEmpty()
        if (baseUrl.isBlank()) return null
        return NightscoutRepository().getLatestGlucose(baseUrl, profile?.nightscoutToken)?.sgv
    }

    private suspend fun fetchActiveInsulinUnits(
        database: AppDatabase,
        nowMillis: Long
    ): Float {
        val fromMillis = nowMillis - (ACTIVE_INSULIN_DURATION_MINUTES * 60_000L)
        val dosis = database.registroComidaDao().getReliableAppliedDosesInWindow(fromMillis, nowMillis)
        val snapshot = ActiveInsulinCalculator.calculate(dosis, nowMillis)
        return snapshot.totalUnits.takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    private fun buildNotificationText(
        glucosaActualMgdl: Int?,
        profile: UsuarioProfile?,
        insulinaActivaUnidades: Float,
        controlTimeLabel: String,
        suggestion: CorrectionSuggestion
    ): ReminderNotificationText {
        val title = "Control de glucosa a las 2h"
        val content = when {
            suggestion.reason == CorrectionSuggestionReason.SUGGESTED && glucosaActualMgdl != null ->
                "Glucosa $glucosaActualMgdl mg/dL · Corrección sugerida ${formatUnits(suggestion.suggestedUnits)} U"

            glucosaActualMgdl != null ->
                "Glucosa $glucosaActualMgdl mg/dL · Revisión 2h"

            else -> "Mide tu glucosa para valorar corrección"
        }

        val details = buildString {
            appendLine("Hora objetivo: $controlTimeLabel")
            appendLine(
                if (glucosaActualMgdl != null) {
                    "Glucosa actual: $glucosaActualMgdl mg/dL"
                } else {
                    "Glucosa actual: no disponible"
                }
            )
            appendLine(
                profile?.glucosaObjetivoMgdl?.let { "Objetivo: $it mg/dL" }
                    ?: "Objetivo: no configurado"
            )
            appendLine("Insulina activa estimada: ${formatUnits(insulinaActivaUnidades)} U")
            when (suggestion.reason) {
                CorrectionSuggestionReason.SUGGESTED -> {
                    appendLine("Corrección orientativa sugerida: ${formatUnits(suggestion.suggestedUnits)} U")
                }
                CorrectionSuggestionReason.NO_CORRECTION_NEEDED -> {
                    appendLine("No se recomienda corrección en este momento.")
                }
                CorrectionSuggestionReason.DISABLED_BY_PROFILE -> {
                    appendLine("Corrección automática desactivada en Perfil.")
                }
                CorrectionSuggestionReason.MISSING_GLUCOSE -> {
                    appendLine("No se pudo obtener glucosa actual desde Nightscout.")
                }
                CorrectionSuggestionReason.MISSING_CORRECTION_CONFIG -> {
                    appendLine("Configura objetivo y factor de corrección en Perfil.")
                }
            }
            append("Aviso: orientativo; no sustituye criterio médico.")
        }

        return ReminderNotificationText(
            title = title,
            content = content,
            details = details
        )
    }

    private fun formatUnits(value: Float): String {
        return String.format(Locale.getDefault(), "%.1f", value)
    }

    companion object {
        const val KEY_REGISTRO_ID = "registro_id"
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
    }
}

internal data class ReminderNotificationText(
    val title: String,
    val content: String,
    val details: String
)

internal enum class CorrectionSuggestionReason {
    DISABLED_BY_PROFILE,
    MISSING_GLUCOSE,
    MISSING_CORRECTION_CONFIG,
    NO_CORRECTION_NEEDED,
    SUGGESTED
}

internal data class CorrectionSuggestion(
    val suggestedUnits: Float,
    val reason: CorrectionSuggestionReason
)

internal fun calculate2hCorrectionSuggestion(
    applyByDefault: Boolean,
    glucosaActualMgdl: Int?,
    objetivoMgdl: Int?,
    factorCorreccionMgdlPorU: Float?,
    insulinaActivaUnidades: Float
): CorrectionSuggestion {
    if (!applyByDefault) {
        return CorrectionSuggestion(
            suggestedUnits = 0f,
            reason = CorrectionSuggestionReason.DISABLED_BY_PROFILE
        )
    }
    if (glucosaActualMgdl == null) {
        return CorrectionSuggestion(
            suggestedUnits = 0f,
            reason = CorrectionSuggestionReason.MISSING_GLUCOSE
        )
    }

    val objetivo = objetivoMgdl?.takeIf { it > 0 } ?: return CorrectionSuggestion(
        suggestedUnits = 0f,
        reason = CorrectionSuggestionReason.MISSING_CORRECTION_CONFIG
    )
    val factor = factorCorreccionMgdlPorU?.takeIf { it.isFinite() && it > 0f } ?: return CorrectionSuggestion(
        suggestedUnits = 0f,
        reason = CorrectionSuggestionReason.MISSING_CORRECTION_CONFIG
    )

    val correccionBruta = (glucosaActualMgdl - objetivo) / factor
    if (correccionBruta <= 0f) {
        return CorrectionSuggestion(
            suggestedUnits = 0f,
            reason = CorrectionSuggestionReason.NO_CORRECTION_NEEDED
        )
    }

    val insulinaActiva = insulinaActivaUnidades.takeIf { it.isFinite() && it > 0f } ?: 0f
    val sugerida = FactoresContextoInsulina.roundToHalf((correccionBruta - insulinaActiva).coerceAtLeast(0f))
    val reason = if (sugerida > 0f) {
        CorrectionSuggestionReason.SUGGESTED
    } else {
        CorrectionSuggestionReason.NO_CORRECTION_NEEDED
    }
    return CorrectionSuggestion(
        suggestedUnits = sugerida,
        reason = reason
    )
}
