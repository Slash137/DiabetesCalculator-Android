package com.diabetes.calculator.ui.screens.estadisticas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.BuildConfig
import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.repository.AlimentoRepository
import com.diabetes.calculator.data.repository.GeminiRepository
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class StatsPeriod(val label: String, val days: Int?) {
    ALL("Todo", null),
    LAST_7("7d", 7),
    LAST_30("30d", 30),
    LAST_90("90d", 90)
}

sealed class EstadisticasUiState {
    object Loading : EstadisticasUiState()
    data class Empty(val period: StatsPeriod) : EstadisticasUiState()
    data class Success(
        val period: StatsPeriod,
        val resumen: EstadisticasResumen
    ) : EstadisticasUiState()
    data class Error(val message: String) : EstadisticasUiState()
}

sealed class InformeIaUiState {
    object Hidden : InformeIaUiState()
    data class Conversation(val chat: InformeIaConversationUi) : InformeIaUiState()
}

enum class InformeIaRole {
    USER,
    ASSISTANT
}

data class InformeIaMessage(
    val role: InformeIaRole,
    val content: String
)

data class InformeIaConversationUi(
    val title: String,
    val messages: List<InformeIaMessage> = emptyList(),
    val draft: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class EstadisticasResumen(
    val totalRegistros: Int,
    val diasConRegistros: Int,
    val totalDiasPeriodo: Int,
    val comidasPorDia: Float,
    val hidratosTotales: Float,
    val racionesTotales: Float,
    val insulinaTotal: Float,
    val insulinaExternaTotal: Float,
    val pinchazosExternos: Int,
    val hidratosPorComida: Float,
    val racionesPorComida: Float,
    val insulinaPorComida: Float,
    val ratioEfectivoURacion: Float?,
    val ratioEfectivoUG: Float?,
    val gramosPorUnidad: Float?,
    val ratioConfiguradoURacion: Float?,
    val desviacionRatioPct: Float?,
    val glucosaAntesMedia: Float?,
    val glucosaDespuesMedia: Float?,
    val delta2hMedia: Float?,
    val delta2hStdDev: Float?,
    val porcentaje2hEnRango: Float?,
    val registrosConGlucosa: Int,
    val registrosConNotas: Int,
    val dosisAplicadas: Int,
    val dosisConCorreccion: Int,
    val dosisSinCorreccion: Int,
    val dosisSinMarcarCorreccion: Int,
    val porcentajeConCorreccion: Float?,
    val insulinaMediaConCorreccion: Float?,
    val insulinaMediaSinCorreccion: Float?,
    val diasSinRegistros: Int,
    val franjaDistribution: List<FranjaStat>,
    val weekdayStats: List<WeekdayStat>,
    val topAlimentos: List<TopAlimentoStat>,
    val alimentosConMayorDelta: List<AlimentoDeltaStat>,
    val similarMeals: List<SimilarMealStat>,
    val tendenciaHidratosPct: Float?,
    val tendenciaInsulinaPct: Float?,
    val tendenciaRatioUGPct: Float?,
    val tendenciaDelta2hPct: Float?,
    val nightscoutConfigured: Boolean,
    val nightscoutLoading: Boolean,
    val nightscoutStats: NightscoutAdvancedStat?,
    val nightscoutError: String?
)

data class FranjaStat(
    val label: String,
    val comidas: Int,
    val porcentaje: Float
)

data class WeekdayStat(
    val label: String,
    val comidas: Int,
    val hidratosMedios: Float,
    val insulinaMedia: Float
)

data class TopAlimentoStat(
    val nombre: String,
    val usos: Int,
    val gramosTotales: Float,
    val hidratosTotales: Float
)

data class AlimentoDeltaStat(
    val nombre: String,
    val deltaMedio: Float,
    val muestras: Int
)

data class SimilarMealStat(
    val titulo: String,
    val muestras: Int,
    val hidratosMedios: Float,
    val insulinaMedia: Float,
    val ratioUG: Float?,
    val delta2hMedio: Float?,
    val delta2hStdDev: Float?
)

data class NightscoutAdvancedStat(
    val muestras: Int,
    val glucosaMedia: Float,
    val tir70_180: Float,
    val tbrBelow70: Float,
    val tarAbove180: Float,
    val tbrBelow54: Float,
    val tarAbove250: Float,
    val cv: Float?,
    val gmi: Float?
)

private data class InformeIaConversationContext(
    val period: StatsPeriod,
    val resumen: EstadisticasResumen,
    val fullAppContext: String,
    val followupAppContext: String
)

private data class IaContextBudget(
    val maxChars: Int,
    val maxRegistros: Int,
    val maxItemsPerRegistro: Int,
    val maxAlimentos: Int
)

class EstadisticasViewModel(
    private val registroRepository: RegistroComidaRepository,
    private val usuarioRepository: UsuarioProfileRepository,
    private val alimentoRepository: AlimentoRepository,
    private val nightscoutRepository: NightscoutRepository,
    private val geminiRepository: GeminiRepository
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.LAST_30)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _uiState = MutableStateFlow<EstadisticasUiState>(EstadisticasUiState.Loading)
    val uiState: StateFlow<EstadisticasUiState> = _uiState.asStateFlow()

    private val _informeIaState = MutableStateFlow<InformeIaUiState>(InformeIaUiState.Hidden)
    val informeIaState: StateFlow<InformeIaUiState> = _informeIaState.asStateFlow()
    private var cachedIaConversation: InformeIaConversationUi? = null
    private var cachedIaContext: InformeIaConversationContext? = null
    private var latestAllRegistrosSnapshot: List<RegistroComidaConItems> = emptyList()
    private var latestProfileSnapshot: UsuarioProfile? = null

    init {
        observeStats()
    }

    fun updatePeriod(period: StatsPeriod) {
        _period.value = period
    }

    fun dismissInformeIa() {
        _informeIaState.value = InformeIaUiState.Hidden
    }

    fun consultarIa() {
        val cached = cachedIaConversation
        if (cached != null) {
            _informeIaState.value = InformeIaUiState.Conversation(cached)
            return
        }
        startNewIaConversation()
    }

    fun refreshInformeIaConversation() {
        cachedIaConversation = null
        cachedIaContext = null
        startNewIaConversation()
    }

    fun updateInformeIaDraft(value: String) {
        val current = cachedIaConversation ?: return
        updateIaConversation(
            current.copy(
                draft = value,
                errorMessage = null
            )
        )
    }

    fun sendInformeIaMessage() {
        val context = cachedIaContext ?: run {
            showIaConversationError("No hay contexto cargado. Pulsa \"Refrescar conversación\".")
            return
        }
        val current = cachedIaConversation ?: return
        val question = current.draft.trim()
        if (question.isBlank() || current.isLoading) return

        viewModelScope.launch {
            val conversationWithUser = current.copy(
                messages = current.messages + InformeIaMessage(
                    role = InformeIaRole.USER,
                    content = question
                ),
                draft = "",
                isLoading = true,
                errorMessage = null
            )
            updateIaConversation(conversationWithUser)

            val model = BuildConfig.GEMINI_MODEL.trim().ifBlank { DEFAULT_GEMINI_MODEL }
            val initialAssistantReport = conversationWithUser.messages
                .firstOrNull { it.role == InformeIaRole.ASSISTANT }
                ?.content
            val prompt = buildGeminiConversationPrompt(
                period = context.period,
                resumen = context.resumen,
                messages = conversationWithUser.messages,
                appContext = context.followupAppContext,
                initialReport = initialAssistantReport
            )
            val body = geminiRepository.generateHealthReport(
                model = model,
                prompt = prompt
            )

            val updated = if (body.isNullOrBlank()) {
                conversationWithUser.copy(
                    isLoading = false,
                    errorMessage = "No se pudo consultar Gemini (${geminiRepository.lastErrorMessage ?: "error desconocido"})."
                )
            } else {
                conversationWithUser.copy(
                    messages = conversationWithUser.messages + InformeIaMessage(
                        role = InformeIaRole.ASSISTANT,
                        content = body
                    ),
                    isLoading = false,
                    errorMessage = null
                )
            }
            updateIaConversation(updated)
        }
    }

    private fun startNewIaConversation() {
        viewModelScope.launch {
            val snapshot = _uiState.value
            when (snapshot) {
                is EstadisticasUiState.Success -> {
                    val title = "Informe Gemini · ${snapshot.period.label}"
                    val fullAppContext = buildAppContext(
                        period = snapshot.period,
                        resumen = snapshot.resumen,
                        budget = INITIAL_CONTEXT_BUDGET
                    )
                    val followupAppContext = buildAppContext(
                        period = snapshot.period,
                        resumen = snapshot.resumen,
                        budget = FOLLOWUP_CONTEXT_BUDGET
                    )
                    cachedIaContext = InformeIaConversationContext(
                        period = snapshot.period,
                        resumen = snapshot.resumen,
                        fullAppContext = fullAppContext,
                        followupAppContext = followupAppContext
                    )
                    val loadingConversation = InformeIaConversationUi(
                        title = title,
                        isLoading = true
                    )
                    updateIaConversation(loadingConversation, forceVisible = true)

                    val model = BuildConfig.GEMINI_MODEL.trim().ifBlank { DEFAULT_GEMINI_MODEL }
                    val prompt = buildGeminiPrompt(
                        period = snapshot.period,
                        resumen = snapshot.resumen,
                        fullAppContext = fullAppContext
                    )
                    val body = geminiRepository.generateHealthReport(
                        model = model,
                        prompt = prompt
                    )

                    val updated = if (body.isNullOrBlank()) {
                        loadingConversation.copy(
                            isLoading = false,
                            errorMessage = "No se pudo consultar Gemini (${geminiRepository.lastErrorMessage ?: "error desconocido"})."
                        )
                    } else {
                        loadingConversation.copy(
                            messages = listOf(
                                InformeIaMessage(
                                    role = InformeIaRole.ASSISTANT,
                                    content = body
                                )
                            ),
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                    updateIaConversation(updated)
                }

                is EstadisticasUiState.Empty -> {
                    showIaConversationError("No hay datos suficientes para generar el informe IA en este periodo.")
                }

                is EstadisticasUiState.Loading -> {
                    showIaConversationError("Todavía se están calculando estadísticas. Intenta de nuevo en unos segundos.")
                }

                is EstadisticasUiState.Error -> {
                    showIaConversationError("No se puede generar el informe IA porque hay un error en estadísticas.")
                }
            }
        }
    }

    private fun showIaConversationError(message: String) {
        val title = cachedIaConversation?.title ?: "Informe Gemini"
        updateIaConversation(
            conversation = InformeIaConversationUi(
                title = title,
                isLoading = false,
                errorMessage = message
            ),
            forceVisible = true
        )
    }

    private fun updateIaConversation(
        conversation: InformeIaConversationUi,
        forceVisible: Boolean = false
    ) {
        cachedIaConversation = conversation
        if (forceVisible || _informeIaState.value is InformeIaUiState.Conversation) {
            _informeIaState.value = InformeIaUiState.Conversation(conversation)
        }
    }

    private fun observeStats() {
        viewModelScope.launch {
            combine(
                _period,
                registroRepository.allRegistros,
                usuarioRepository.profile
            ) { period, registros, profile ->
                Triple(period, registros, profile)
            }.collectLatest { (period, registros, profile) ->
                latestAllRegistrosSnapshot = registros
                latestProfileSnapshot = profile
                runCatching {
                    val filtered = applyPeriod(registros, period)
                    val nightscoutUrl = profile?.nightscoutUrl
                    val nightscoutToken = profile?.nightscoutToken
                    val nightscoutConfigured = !nightscoutUrl.isNullOrBlank()

                    if (filtered.isEmpty() && !nightscoutConfigured) {
                        _uiState.value = EstadisticasUiState.Empty(period)
                        return@runCatching
                    }

                    val ratioConfigurado = profile?.ratioInsulina
                    val localResumen = buildResumen(
                        registros = filtered,
                        trendSourceRegistros = registros,
                        period = period,
                        ratioConfiguradoURacion = ratioConfigurado,
                        nightscoutConfigured = nightscoutConfigured,
                        nightscoutLoading = false,
                        nightscoutStats = null,
                        nightscoutError = null
                    )

                    if (!nightscoutConfigured) {
                        _uiState.value = EstadisticasUiState.Success(period, localResumen)
                        return@runCatching
                    }

                    _uiState.value = EstadisticasUiState.Success(
                        period,
                        localResumen.copy(nightscoutLoading = true)
                    )

                    val (from, to) = resolveNightscoutRange(period, filtered)
                    val nsStats = fetchNightscoutStats(
                        baseUrl = nightscoutUrl.orEmpty(),
                        token = nightscoutToken,
                        from = from,
                        to = to
                    )
                    val nsTrend = fetchNightscoutTrend(
                        baseUrl = nightscoutUrl.orEmpty(),
                        token = nightscoutToken
                    )

                    _uiState.value = EstadisticasUiState.Success(
                        period = period,
                        resumen = localResumen.copy(
                            nightscoutLoading = false,
                            nightscoutStats = nsStats.stat,
                            nightscoutError = nsStats.error,
                            tendenciaHidratosPct = localResumen.tendenciaHidratosPct ?: nsTrend?.hidratosPct,
                            tendenciaInsulinaPct = localResumen.tendenciaInsulinaPct ?: nsTrend?.insulinaPct,
                            tendenciaRatioUGPct = localResumen.tendenciaRatioUGPct ?: nsTrend?.ratioUGPct,
                            tendenciaDelta2hPct = localResumen.tendenciaDelta2hPct ?: nsTrend?.delta2hPct
                        )
                    )
                }.onFailure { error ->
                    _uiState.value = EstadisticasUiState.Error(
                        error.message ?: "Error al calcular estadísticas"
                    )
                }
            }
        }
    }

    private fun applyPeriod(
        registros: List<RegistroComidaConItems>,
        period: StatsPeriod
    ): List<RegistroComidaConItems> {
        val days = period.days ?: return registros
        val dayMs = 24 * 60 * 60 * 1000L
        val startOfToday = DateUtils.getStartOfToday()
        val endOfToday = DateUtils.getEndOfToday()
        val start = startOfToday - ((days - 1) * dayMs)
        return registros.filter { it.registro.fecha in start..endOfToday }
    }

    private fun buildResumen(
        registros: List<RegistroComidaConItems>,
        trendSourceRegistros: List<RegistroComidaConItems>,
        period: StatsPeriod,
        ratioConfiguradoURacion: Float?,
        nightscoutConfigured: Boolean,
        nightscoutLoading: Boolean,
        nightscoutStats: NightscoutAdvancedStat?,
        nightscoutError: String?
    ): EstadisticasResumen {
        val mealRegistros = registros.filter {
            OrigenRegistro.fromValue(it.registro.origenRegistro) != OrigenRegistro.NIGHTSCOUT_IMPORT
        }
        val externalRegistros = registros.filter {
            OrigenRegistro.fromValue(it.registro.origenRegistro) == OrigenRegistro.NIGHTSCOUT_IMPORT
        }

        val totalRegistros = mealRegistros.size
        val dayStarts = registros
            .map { DateUtils.getStartOfDay(it.registro.fecha) }
            .toSet()

        val totalDiasPeriodo = period.days ?: inferRangeDays(dayStarts)
        val diasConRegistros = dayStarts.size
        val diasSinRegistros = (totalDiasPeriodo - diasConRegistros).coerceAtLeast(0)
        val comidasPorDia = safeDiv(totalRegistros.toFloat(), totalDiasPeriodo.toFloat())

        val hidratosTotales = mealRegistros.sumOf { it.registro.hidratosTotales.toDouble() }.toFloat()
        val racionesTotales = mealRegistros.sumOf { it.registro.racionesCalculadas.toDouble() }.toFloat()
        val insulinaTotal = registros.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat()
        val insulinaExternaTotal = externalRegistros.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat()
        val pinchazosExternos = externalRegistros.size

        val hidratosPorComida = safeDiv(hidratosTotales, totalRegistros.toFloat())
        val racionesPorComida = safeDiv(racionesTotales, totalRegistros.toFloat())
        val insulinaComidasTotal = mealRegistros.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat()
        val insulinaPorComida = safeDiv(insulinaComidasTotal, totalRegistros.toFloat())

        val ratioEfectivoURacion = if (racionesTotales > 0f) insulinaComidasTotal / racionesTotales else null
        val ratioEfectivoUGComidas = if (hidratosTotales > 0f) insulinaComidasTotal / hidratosTotales else null
        val gramosPorUnidad = ratioEfectivoUGComidas?.takeIf { it > 0f }?.let { 1f / it }

        val desviacionRatioPct =
            if (ratioConfiguradoURacion != null && ratioConfiguradoURacion > 0f && ratioEfectivoURacion != null) {
                ((ratioEfectivoURacion - ratioConfiguradoURacion) / ratioConfiguradoURacion) * 100f
            } else {
                null
            }

        val glucosaAntesValues = mealRegistros.mapNotNull { it.registro.glucosaAntesMgdl?.toFloat() }
        val glucosaDespuesValues = mealRegistros.mapNotNull { it.registro.glucosaDespues2hMgdl?.toFloat() }
        val deltas2h = mealRegistros.mapNotNull { registro ->
            val antes = registro.registro.glucosaAntesMgdl
            val despues = registro.registro.glucosaDespues2hMgdl
            if (antes != null && despues != null) (despues - antes).toFloat() else null
        }

        val glucosaAntesMedia = average(glucosaAntesValues)
        val glucosaDespuesMedia = average(glucosaDespuesValues)
        val delta2hMedia = average(deltas2h)
        val delta2hStdDev = stdDev(deltas2h)
        val porcentaje2hEnRango = if (glucosaDespuesValues.isNotEmpty()) {
            val inRange = glucosaDespuesValues.count { it in 80f..180f }
            (inRange.toFloat() / glucosaDespuesValues.size.toFloat()) * 100f
        } else {
            null
        }

        val registrosConGlucosa = mealRegistros.count {
            it.registro.glucosaAntesMgdl != null || it.registro.glucosaDespues2hMgdl != null
        }
        val registrosConNotas = mealRegistros.count { !it.registro.notas.isNullOrBlank() }
        val dosisAplicadasRegs = mealRegistros.filter {
            EstadoDosis.fromValue(it.registro.dosisEstado) == EstadoDosis.APLICADA
        }
        val dosisAplicadas = dosisAplicadasRegs.size
        val dosisConCorreccion = dosisAplicadasRegs.count { it.registro.dosisConCorreccion == true }
        val dosisSinCorreccion = dosisAplicadasRegs.count { it.registro.dosisConCorreccion == false }
        val dosisSinMarcarCorreccion = dosisAplicadasRegs.count { it.registro.dosisConCorreccion == null }
        val porcentajeConCorreccion = if (dosisAplicadas > 0) {
            (dosisConCorreccion.toFloat() / dosisAplicadas.toFloat()) * 100f
        } else {
            null
        }
        val insulinaMediaConCorreccion = average(
            dosisAplicadasRegs
                .filter { it.registro.dosisConCorreccion == true }
                .map { it.registro.unidadesInsulina }
        )
        val insulinaMediaSinCorreccion = average(
            dosisAplicadasRegs
                .filter { it.registro.dosisConCorreccion == false }
                .map { it.registro.unidadesInsulina }
        )

        val franjaDistribution = buildFranjaDistribution(mealRegistros)
        val weekdayStats = buildWeekdayStats(mealRegistros)
        val topAlimentos = buildTopAlimentos(mealRegistros)
        val alimentosConMayorDelta = buildAlimentosConMayorDelta(mealRegistros)
        val similarMeals = buildSimilarMeals(mealRegistros)
        val trendSourceMeals = trendSourceRegistros.filter {
            OrigenRegistro.fromValue(it.registro.origenRegistro) != OrigenRegistro.NIGHTSCOUT_IMPORT
        }
        val trend = buildTrends(trendSourceMeals)

        return EstadisticasResumen(
            totalRegistros = totalRegistros,
            diasConRegistros = diasConRegistros,
            totalDiasPeriodo = totalDiasPeriodo,
            comidasPorDia = comidasPorDia,
            hidratosTotales = hidratosTotales,
            racionesTotales = racionesTotales,
            insulinaTotal = insulinaTotal,
            insulinaExternaTotal = insulinaExternaTotal,
            pinchazosExternos = pinchazosExternos,
            hidratosPorComida = hidratosPorComida,
            racionesPorComida = racionesPorComida,
            insulinaPorComida = insulinaPorComida,
            ratioEfectivoURacion = ratioEfectivoURacion,
            ratioEfectivoUG = ratioEfectivoUGComidas,
            gramosPorUnidad = gramosPorUnidad,
            ratioConfiguradoURacion = ratioConfiguradoURacion,
            desviacionRatioPct = desviacionRatioPct,
            glucosaAntesMedia = glucosaAntesMedia,
            glucosaDespuesMedia = glucosaDespuesMedia,
            delta2hMedia = delta2hMedia,
            delta2hStdDev = delta2hStdDev,
            porcentaje2hEnRango = porcentaje2hEnRango,
            registrosConGlucosa = registrosConGlucosa,
            registrosConNotas = registrosConNotas,
            dosisAplicadas = dosisAplicadas,
            dosisConCorreccion = dosisConCorreccion,
            dosisSinCorreccion = dosisSinCorreccion,
            dosisSinMarcarCorreccion = dosisSinMarcarCorreccion,
            porcentajeConCorreccion = porcentajeConCorreccion,
            insulinaMediaConCorreccion = insulinaMediaConCorreccion,
            insulinaMediaSinCorreccion = insulinaMediaSinCorreccion,
            diasSinRegistros = diasSinRegistros,
            franjaDistribution = franjaDistribution,
            weekdayStats = weekdayStats,
            topAlimentos = topAlimentos,
            alimentosConMayorDelta = alimentosConMayorDelta,
            similarMeals = similarMeals,
            tendenciaHidratosPct = trend.hidratosPct,
            tendenciaInsulinaPct = trend.insulinaPct,
            tendenciaRatioUGPct = trend.ratioUGPct,
            tendenciaDelta2hPct = trend.delta2hPct,
            nightscoutConfigured = nightscoutConfigured,
            nightscoutLoading = nightscoutLoading,
            nightscoutStats = nightscoutStats,
            nightscoutError = nightscoutError
        )
    }

    private data class TrendResult(
        val hidratosPct: Float?,
        val insulinaPct: Float?,
        val ratioUGPct: Float?,
        val delta2hPct: Float?
    )

    private fun buildTrends(registros: List<RegistroComidaConItems>): TrendResult {
        if (registros.isEmpty()) return TrendResult(null, null, null, null)

        val end = DateUtils.getEndOfToday()
        val dayMs = 24 * 60 * 60 * 1000L
        val startCurrent = DateUtils.getStartOfToday() - (6 * dayMs)
        val endPrevious = startCurrent - 1
        val startPrevious = startCurrent - (7 * dayMs)

        val current = registros.filter { it.registro.fecha in startCurrent..end }
        val previous = registros.filter { it.registro.fecha in startPrevious..endPrevious }

        if (current.isEmpty() || previous.isEmpty()) {
            return TrendResult(null, null, null, null)
        }

        val currentHidratos = current.sumOf { it.registro.hidratosTotales.toDouble() }.toFloat()
        val previousHidratos = previous.sumOf { it.registro.hidratosTotales.toDouble() }.toFloat()

        val currentInsulina = current.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat()
        val previousInsulina = previous.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat()

        val currentRatioUG = if (currentHidratos > 0f) currentInsulina / currentHidratos else 0f
        val previousRatioUG = if (previousHidratos > 0f) previousInsulina / previousHidratos else 0f

        val currentDelta = average(
            current.mapNotNull {
                val a = it.registro.glucosaAntesMgdl
                val d = it.registro.glucosaDespues2hMgdl
                if (a != null && d != null) (d - a).toFloat() else null
            }
        ) ?: 0f
        val previousDelta = average(
            previous.mapNotNull {
                val a = it.registro.glucosaAntesMgdl
                val d = it.registro.glucosaDespues2hMgdl
                if (a != null && d != null) (d - a).toFloat() else null
            }
        ) ?: 0f

        return TrendResult(
            hidratosPct = percentChange(currentHidratos, previousHidratos),
            insulinaPct = percentChange(currentInsulina, previousInsulina),
            ratioUGPct = percentChange(currentRatioUG, previousRatioUG),
            delta2hPct = percentChange(currentDelta, previousDelta)
        )
    }

    private fun percentChange(current: Float, previous: Float): Float? {
        if (previous == 0f) return null
        return ((current - previous) / previous) * 100f
    }

    private fun buildFranjaDistribution(
        registros: List<RegistroComidaConItems>
    ): List<FranjaStat> {
        val labels = listOf("Madrugada", "Mañana", "Tarde", "Noche")
        val counts = IntArray(labels.size)
        val calendar = Calendar.getInstance()

        registros.forEach { registro ->
            calendar.timeInMillis = registro.registro.fecha
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val index = when (hour) {
                in 0..5 -> 0
                in 6..11 -> 1
                in 12..17 -> 2
                else -> 3
            }
            counts[index]++
        }

        val total = registros.size.toFloat().coerceAtLeast(1f)
        return labels.mapIndexed { index, label ->
            val comidas = counts[index]
            FranjaStat(
                label = label,
                comidas = comidas,
                porcentaje = (comidas.toFloat() / total) * 100f
            )
        }
    }

    private fun buildWeekdayStats(
        registros: List<RegistroComidaConItems>
    ): List<WeekdayStat> {
        val labels = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
        val grouped = registros.groupBy { weekdayIndex(it.registro.fecha) }

        return labels.indices.map { index ->
            val dayItems = grouped[index].orEmpty()
            val comidas = dayItems.size
            val hidratosMedios = if (comidas > 0) {
                dayItems.sumOf { it.registro.hidratosTotales.toDouble() }.toFloat() / comidas
            } else {
                0f
            }
            val insulinaMedia = if (comidas > 0) {
                dayItems.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat() / comidas
            } else {
                0f
            }
            WeekdayStat(
                label = labels[index],
                comidas = comidas,
                hidratosMedios = hidratosMedios,
                insulinaMedia = insulinaMedia
            )
        }
    }

    private fun buildTopAlimentos(
        registros: List<RegistroComidaConItems>
    ): List<TopAlimentoStat> {
        data class Acc(
            var usos: Int = 0,
            var gramos: Float = 0f,
            var hidratos: Float = 0f
        )

        val map = mutableMapOf<String, Acc>()
        registros.forEach { registro ->
            registro.items.forEach { item ->
                val name = item.alimento.nombre
                val acc = map.getOrPut(name) { Acc() }
                acc.usos += 1
                acc.gramos += item.item.gramosConsumidos
                acc.hidratos += item.item.hidratosCalculados
            }
        }

        return map.map { (name, acc) ->
            TopAlimentoStat(
                nombre = name,
                usos = acc.usos,
                gramosTotales = acc.gramos,
                hidratosTotales = acc.hidratos
            )
        }.sortedWith(
            compareByDescending<TopAlimentoStat> { it.usos }
                .thenByDescending { it.hidratosTotales }
        ).take(8)
    }

    private fun buildAlimentosConMayorDelta(
        registros: List<RegistroComidaConItems>
    ): List<AlimentoDeltaStat> {
        val map = mutableMapOf<String, MutableList<Float>>()

        registros.forEach { registro ->
            val antes = registro.registro.glucosaAntesMgdl
            val despues = registro.registro.glucosaDespues2hMgdl
            if (antes == null || despues == null) return@forEach

            val delta = (despues - antes).toFloat()
            registro.items
                .map { it.alimento.nombre }
                .distinct()
                .forEach { alimento ->
                    map.getOrPut(alimento) { mutableListOf() }.add(delta)
                }
        }

        return map.mapNotNull { (alimento, deltas) ->
            if (deltas.size < 2) return@mapNotNull null
            AlimentoDeltaStat(
                nombre = alimento,
                deltaMedio = deltas.average().toFloat(),
                muestras = deltas.size
            )
        }.sortedByDescending { it.deltaMedio }
            .take(5)
    }

    private fun buildSimilarMeals(
        registros: List<RegistroComidaConItems>
    ): List<SimilarMealStat> {
        data class Acc(
            var muestras: Int = 0,
            var hidratosSum: Float = 0f,
            var insulinaSum: Float = 0f,
            val deltas: MutableList<Float> = mutableListOf()
        )

        val groups = mutableMapOf<String, Acc>()
        registros.forEach { registro ->
            val names = registro.items
                .map { it.alimento.nombre.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            if (names.isEmpty()) return@forEach

            val title = compactMealTitle(names)
            val acc = groups.getOrPut(title) { Acc() }
            acc.muestras += 1
            acc.hidratosSum += registro.registro.hidratosTotales
            acc.insulinaSum += registro.registro.unidadesInsulina

            val before = registro.registro.glucosaAntesMgdl
            val after = registro.registro.glucosaDespues2hMgdl
            if (before != null && after != null) {
                acc.deltas += (after - before).toFloat()
            }
        }

        return groups.mapNotNull { (title, acc) ->
            if (acc.muestras < 2) return@mapNotNull null
            val ratioUG = if (acc.hidratosSum > 0f) acc.insulinaSum / acc.hidratosSum else null
            SimilarMealStat(
                titulo = title,
                muestras = acc.muestras,
                hidratosMedios = acc.hidratosSum / acc.muestras,
                insulinaMedia = acc.insulinaSum / acc.muestras,
                ratioUG = ratioUG,
                delta2hMedio = average(acc.deltas),
                delta2hStdDev = stdDev(acc.deltas)
            )
        }.sortedWith(
            compareByDescending<SimilarMealStat> { it.muestras }
                .thenByDescending { it.hidratosMedios }
        ).take(8)
    }

    private fun compactMealTitle(names: List<String>): String {
        return if (names.size <= 3) {
            names.joinToString(" + ")
        } else {
            names.take(3).joinToString(" + ") + " +${names.size - 3}"
        }
    }

    private fun resolveNightscoutRange(
        period: StatsPeriod,
        registros: List<RegistroComidaConItems>
    ): Pair<Long, Long> {
        val end = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        val start = if (period.days != null) {
            DateUtils.getStartOfToday() - ((period.days - 1) * dayMs)
        } else {
            val earliest = registros.minOfOrNull { it.registro.fecha } ?: DateUtils.getStartOfToday()
            DateUtils.getStartOfDay(earliest)
        }
        return start to end
    }

    private suspend fun fetchNightscoutTrend(
        baseUrl: String,
        token: String?
    ): TrendResult? {
        val dayMs = 24 * 60 * 60 * 1000L
        val startCurrent = DateUtils.getStartOfToday() - (6 * dayMs)
        val endCurrent = DateUtils.getEndOfToday()
        val startPrevious = startCurrent - (7 * dayMs)
        val endPrevious = startCurrent - 1

        val treatments = withTimeoutOrNull(15_000L) {
            nightscoutRepository.getTreatmentsInRangeAll(
                baseUrl = baseUrl,
                token = token,
                fromMillis = startPrevious,
                toMillis = endCurrent,
                pageSize = 400,
                maxEntries = 12000
            )
        } ?: return null

        data class Aggregate(
            var count: Int = 0,
            var hidratos: Float = 0f,
            var insulina: Float = 0f
        )

        val current = Aggregate()
        val previous = Aggregate()
        treatments.forEach { treatment ->
            val timestamp = nightscoutRepository.resolveTreatmentMillis(treatment) ?: return@forEach
            val insulin = nightscoutRepository.resolveTreatmentInsulinUnits(treatment)
                ?.takeIf { it.isFinite() && !it.isNaN() && it > 0f }
                ?: 0f
            val carbs = treatment.carbs
                ?.takeIf { it.isFinite() && !it.isNaN() && it >= 0f }
                ?: 0f
            if (insulin <= 0f && carbs <= 0f) return@forEach

            when (timestamp) {
                in startCurrent..endCurrent -> {
                    current.count += 1
                    current.hidratos += carbs
                    current.insulina += insulin
                }

                in startPrevious..endPrevious -> {
                    previous.count += 1
                    previous.hidratos += carbs
                    previous.insulina += insulin
                }
            }
        }

        if (current.count == 0 || previous.count == 0) return null

        val currentRatioUG = if (current.hidratos > 0f) current.insulina / current.hidratos else 0f
        val previousRatioUG = if (previous.hidratos > 0f) previous.insulina / previous.hidratos else 0f

        return TrendResult(
            hidratosPct = percentChange(current.hidratos, previous.hidratos),
            insulinaPct = percentChange(current.insulina, previous.insulina),
            ratioUGPct = percentChange(currentRatioUG, previousRatioUG),
            delta2hPct = null
        )
    }

    private suspend fun fetchNightscoutStats(
        baseUrl: String,
        token: String?,
        from: Long,
        to: Long
    ): NightscoutFetchResult {
        val entries = withTimeoutOrNull(15_000L) {
            nightscoutRepository.getEntriesInRangeAll(
                baseUrl = baseUrl,
                token = token,
                from = from,
                to = to,
                pageSize = 800,
                maxEntries = 25000
            )
        } ?: return NightscoutFetchResult(
            stat = null,
            error = "Nightscout no respondió a tiempo"
        )

        val values = entries
            .map { it.sgv.toFloat() }
            .filter { it > 0f }
        if (values.isEmpty()) {
            return NightscoutFetchResult(
                stat = null,
                error = "Nightscout sin datos en el periodo"
            )
        }

        val mean = values.average().toFloat()
        val total = values.size.toFloat()
        val tir70_180 = values.count { it in 70f..180f } / total * 100f
        val tbrBelow70 = values.count { it < 70f } / total * 100f
        val tarAbove180 = values.count { it > 180f } / total * 100f
        val tbrBelow54 = values.count { it < 54f } / total * 100f
        val tarAbove250 = values.count { it > 250f } / total * 100f
        val std = stdDev(values)
        val cv = if (std != null && mean > 0f) (std / mean) * 100f else null
        val gmi = 3.31f + (0.02392f * mean)

        return NightscoutFetchResult(
            stat = NightscoutAdvancedStat(
                muestras = values.size,
                glucosaMedia = mean,
                tir70_180 = tir70_180,
                tbrBelow70 = tbrBelow70,
                tarAbove180 = tarAbove180,
                tbrBelow54 = tbrBelow54,
                tarAbove250 = tarAbove250,
                cv = cv,
                gmi = gmi
            ),
            error = null
        )
    }

    private data class NightscoutFetchResult(
        val stat: NightscoutAdvancedStat?,
        val error: String?
    )

    private fun weekdayIndex(timestamp: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
    }

    private fun inferRangeDays(dayStarts: Set<Long>): Int {
        if (dayStarts.isEmpty()) return 0
        val min = dayStarts.minOrNull() ?: return 0
        val max = dayStarts.maxOrNull() ?: return 0
        val dayMs = 24 * 60 * 60 * 1000L
        return (((max - min) / dayMs) + 1).toInt()
    }

    private fun average(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        return values.average().toFloat()
    }

    private fun stdDev(values: List<Float>): Float? {
        if (values.size < 2) return null
        val mean = values.average().toFloat()
        val variance = values
            .map { (it - mean) * (it - mean) }
            .average()
            .toFloat()
        return sqrt(variance)
    }

    private fun safeDiv(numerator: Float, denominator: Float): Float {
        if (denominator <= 0f) return 0f
        return (numerator / denominator * 100f).roundToInt() / 100f
    }

    private suspend fun buildAppContext(
        period: StatsPeriod,
        resumen: EstadisticasResumen,
        budget: IaContextBudget
    ): String {
        val profile = latestProfileSnapshot ?: usuarioRepository.getProfileSync()
        if (profile != null) {
            latestProfileSnapshot = profile
        }
        val alimentos = runCatching { alimentoRepository.getAllSync() }
            .getOrDefault(emptyList())
        val registros = latestAllRegistrosSnapshot

        val content = buildString {
            appendLine("PERIODO_ACTIVO=${period.label}")
            appendLine("RESUMEN_COMIDAS_PERIODO=${resumen.totalRegistros}")
            appendLine("TOTAL_REGISTROS_HISTORIAL=${registros.size}")
            appendLine("TOTAL_ALIMENTOS_CATALOGO=${alimentos.size}")
            appendLine()
            appendLine("=== PERFIL_Y_CONFIGURACION ===")
            appendLine(formatProfileForIa(profile))
            appendLine()
            appendLine("=== CATALOGO_ALIMENTOS ===")
            appendLine(
                formatAlimentosForIa(
                    alimentos = alimentos,
                    registros = registros,
                    maxAlimentos = budget.maxAlimentos
                )
            )
            appendLine()
            appendLine("=== HISTORIAL_REGISTROS ===")
            appendLine(
                formatRegistrosForIa(
                    registros = registros,
                    maxRegistros = budget.maxRegistros,
                    maxItemsPerRegistro = budget.maxItemsPerRegistro
                )
            )
        }

        return if (content.length <= budget.maxChars) {
            content
        } else {
            content.take(budget.maxChars) +
                "\n[CONTEXTO_APP_TRUNCADO para respetar límite de tokens]"
        }
    }

    private fun formatProfileForIa(profile: UsuarioProfile?): String {
        if (profile == null) return "Perfil no configurado."
        return buildString {
            appendLine("nombre=${profile.nombre}")
            appendLine("gramosPorRacion=${format2(profile.gramosPorRacion)}")
            appendLine("ratioInsulina=${format2(profile.ratioInsulina)}")
            appendLine("objetivos: HC=${profile.objetivoHidratosDia?.let(::format1) ?: "N/D"}g, Raciones=${profile.objetivoRacionesDia?.let(::format1) ?: "N/D"}, Insulina=${profile.objetivoInsulinaDia?.let(::format1) ?: "N/D"}U")
            appendLine("correccion: objetivoMgdl=${profile.glucosaObjetivoMgdl ?: "N/D"}, factorMgdlPorU=${profile.factorCorreccionMgdlPorU?.let(::format2) ?: "N/D"}, aplicarPorDefecto=${profile.aplicarCorreccionPorDefecto}")
            appendLine("recordatorio2hActivo=${profile.recordatorio2hActivo}")
            appendLine("nightscout: url=${profile.nightscoutUrl ?: "N/D"}, tokenConfigurado=${!profile.nightscoutToken.isNullOrBlank()}, syncRegistrosActivo=${profile.nightscoutSyncRegistrosActivo}")
            appendLine("nightscoutSync: backfillDoneAt=${formatDateTime(profile.nightscoutSyncBackfillDoneAt)}, linkOffsetMin=${profile.nightscoutLinkOffsetMinutes}, linkOffsetUnits=${format2(profile.nightscoutLinkOffsetUnits)}")
            appendLine("factoresHora: madrugada=${format2(profile.factorHoraMadrugada)}, manana=${format2(profile.factorHoraManana)}, tarde=${format2(profile.factorHoraTarde)}, noche=${format2(profile.factorHoraNoche)}")
            appendLine("factoresEstres: leve=${format2(profile.factorEstresLeve)}, moderado=${format2(profile.factorEstresModerado)}, alto=${format2(profile.factorEstresAlto)}")
            appendLine("factoresEnfermedad: leve=${format2(profile.factorEnfermedadLeve)}, moderada=${format2(profile.factorEnfermedadModerada)}, alta=${format2(profile.factorEnfermedadAlta)}")
            appendLine("cicloHormonalActivo=${profile.cicloHormonalActivo}")
            appendLine("factoresCiclo: menstruacion=${format2(profile.factorCicloMenstruacion)}, folicular=${format2(profile.factorCicloFolicular)}, ovulacion=${format2(profile.factorCicloOvulacion)}, lutea=${format2(profile.factorCicloLutea)}")
            appendLine("factoresEjercicio: suave=${format2(profile.factorEjercicioSuave)}, moderado=${format2(profile.factorEjercicioModerado)}, intenso=${format2(profile.factorEjercicioIntenso)}")
            appendLine("fechaCreacion=${formatDateTime(profile.fechaCreacion)}")
        }.trimEnd()
    }

    private fun formatAlimentosForIa(
        alimentos: List<Alimento>,
        registros: List<RegistroComidaConItems>,
        maxAlimentos: Int
    ): String {
        if (alimentos.isEmpty()) return "Sin alimentos en catálogo."

        val usageByFoodId = mutableMapOf<Int, Int>()
        registros.forEach { registro ->
            registro.items.forEach { item ->
                usageByFoodId[item.alimento.id] = (usageByFoodId[item.alimento.id] ?: 0) + 1
            }
        }

        val ordered = alimentos.sortedWith(
            compareByDescending<Alimento> { usageByFoodId[it.id] ?: 0 }
                .thenBy { it.nombre.lowercase(Locale.getDefault()) }
        )
        val included = ordered.take(maxAlimentos)

        return buildString {
            appendLine("incluidos=${included.size}/${alimentos.size}")
            included.forEach { alimento ->
                val usages = usageByFoodId[alimento.id] ?: 0
                appendLine(
                    "- id=${alimento.id} | nombre=${compactText(alimento.nombre)} | fuente=${alimento.fuente} | hc100g=${format1(alimento.hidratosPor100g)} | hc100ml=${alimento.hidratosPor100ml?.let(::format1) ?: "N/D"} | tipo=${alimento.tipoMedicionPrincipal} | estado=${alimento.estadoFisico} | unidad=${alimento.unidadNombre ?: "N/D"} | gUnidad=${alimento.gramosPorUnidad?.let(::format1) ?: "N/D"} | mlUnidad=${alimento.mlPorUnidad?.let(::format1) ?: "N/D"} | foto=${!alimento.fotoUri.isNullOrBlank()} | usosHistorial=$usages | nota=${compactText(alimento.nota ?: "N/D")}"
                )
            }
            if (alimentos.size > included.size) {
                appendLine("[Alimentos adicionales omitidos=${alimentos.size - included.size}]")
            }
        }.trimEnd()
    }

    private fun formatRegistrosForIa(
        registros: List<RegistroComidaConItems>,
        maxRegistros: Int,
        maxItemsPerRegistro: Int
    ): String {
        if (registros.isEmpty()) return "Sin registros."
        val ordered = registros.sortedByDescending { it.registro.fecha }
        val included = ordered.take(maxRegistros)

        return buildString {
            appendLine("incluidos=${included.size}/${registros.size}")
            included.forEach { registroConItems ->
                val r = registroConItems.registro
                appendLine(
                    "- registroId=${r.id} | fecha=${formatDateTime(r.fecha)} | origen=${r.origenRegistro} | hc=${format1(r.hidratosTotales)}g | raciones=${format2(r.racionesCalculadas)} | insulina=${format2(r.unidadesInsulina)}U | ratio=${r.ratioInsulinaHc?.let(::format3) ?: "N/D"} | glucosaAntes=${r.glucosaAntesMgdl ?: "N/D"} | glucosa2h=${r.glucosaDespues2hMgdl ?: "N/D"} | dosisEstado=${r.dosisEstado} | correccion=${r.dosisConCorreccion?.toString() ?: "N/D"} | uCorreccion=${r.unidadesCorreccionSugerida?.let(::format2) ?: "N/D"} | factorCorr=${r.factorCorreccionMgdlPorUUsado?.let(::format2) ?: "N/D"} | franja=${r.franjaHorariaUsada ?: "N/D"} | estres=${r.nivelEstresUsado ?: "N/D"} | enfermedad=${r.nivelEnfermedadUsado ?: "N/D"} | ciclo=${r.faseCicloUsada ?: "N/D"} | ejercicio=${r.nivelEjercicioUsado ?: "N/D"} | fHora=${r.factorHoraUsado?.let(::format2) ?: "N/D"} | fEstres=${r.factorEstresUsado?.let(::format2) ?: "N/D"} | fEnfermedad=${r.factorEnfermedadUsado?.let(::format2) ?: "N/D"} | fCiclo=${r.factorCicloUsado?.let(::format2) ?: "N/D"} | fEjercicio=${r.factorEjercicioUsado?.let(::format2) ?: "N/D"} | fContextoRaw=${r.factorContextoTotalRaw?.let(::format2) ?: "N/D"} | fContextoAplicado=${r.factorContextoTotalAplicado?.let(::format2) ?: "N/D"} | capado=${r.factorContextoCapado} | nota=${compactText(r.notas ?: "N/D")}"
                )

                val itemsIncluded = registroConItems.items.take(maxItemsPerRegistro)
                itemsIncluded.forEach { itemConAlimento ->
                    val item = itemConAlimento.item
                    val alimento = itemConAlimento.alimento
                    appendLine(
                        "  * itemId=${item.id} | alimentoId=${alimento.id} | nombre=${compactText(alimento.nombre)} | cantidad=${format2(item.cantidadConsumida)} ${item.unidadConsumida} | gramos=${format2(item.gramosConsumidos)} | hidratos=${format2(item.hidratosCalculados)}"
                    )
                }
                if (registroConItems.items.size > itemsIncluded.size) {
                    appendLine("  * [items omitidos=${registroConItems.items.size - itemsIncluded.size}]")
                }
            }
            if (registros.size > included.size) {
                appendLine("[Registros adicionales omitidos=${registros.size - included.size}]")
            }
        }.trimEnd()
    }

    private fun compactText(raw: String): String {
        return raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_INLINE_TEXT_CHARS)
    }

    private fun compactForPrompt(raw: String, maxChars: Int): String {
        return raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)
    }

    private fun formatDateTime(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "N/D"
        return runCatching {
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(DATE_TIME_FORMATTER)
        }.getOrElse { timestamp.toString() }
    }

    private fun buildGeminiPrompt(
        period: StatsPeriod,
        resumen: EstadisticasResumen,
        fullAppContext: String
    ): String {
        val statsContext = buildAiReport(period, resumen)
        return """
            Actúa como una endocrina experta en diabetes con enfoque práctico.
            Tu tarea es redactar un informe claro, accionable y técnicamente sólido en español para la persona usuaria.
            
            Reglas:
            - Usa la información de "DATOS_ESTADISTICAS" y "DATOS_APP_COMPLETOS".
            - No inventes métricas ni valores.
            - Si algo falta, dilo explícitamente.
            - Da recomendaciones concretas si los datos lo permiten (ajustes de hábitos, timing, monitorización, hipótesis de patrón).
            - Mantén tono profesional, directo y empático.
            - No repitas advertencias genéricas.
            - Menciona consultar con equipo sanitario solo si detectas riesgo alto, hipoglucemias graves/repetidas o incertidumbre crítica.
            
            Formato de salida:
            1) Resumen ejecutivo (3-5 líneas)
            2) Hallazgos clave (bullets)
            3) Riesgos/puntos de vigilancia (bullets)
            4) Recomendaciones priorizadas para próximos 7 días (máx. 6 bullets)
            5) Qué medir para mejorar el análisis (bullets cortos)
            6) Próximo paso recomendado (1-2 líneas)
            
            DATOS_ESTADISTICAS:
            $statsContext
            
            DATOS_APP_COMPLETOS:
            $fullAppContext
        """.trimIndent()
    }

    private fun buildGeminiConversationPrompt(
        period: StatsPeriod,
        resumen: EstadisticasResumen,
        messages: List<InformeIaMessage>,
        appContext: String,
        initialReport: String?
    ): String {
        val statsContext = buildAiReport(period, resumen)
        val history = formatConversationHistory(messages)
        val seed = initialReport?.let { compactForPrompt(it, MAX_INITIAL_REPORT_SEED_CHARS) }
            ?: "Sin informe previo."
        return """
            Actúa como una endocrina experta en diabetes.
            Sigue la conversación y responde solo al último mensaje del usuario, manteniendo contexto.
            
            Reglas:
            - Usa "DATOS_ESTADISTICAS", "RESUMEN_INFORME_INICIAL" y "DATOS_APP_COMPACTOS" como base factual.
            - No inventes métricas ni valores.
            - Si no hay datos suficientes para responder algo, dilo.
            - Ofrece recomendaciones concretas y accionables cuando te las pidan.
            - Mantén tono profesional, claro y directo.
            - Evita repetir el aviso de consultar al médico.
            - Incluye ese aviso solo ante riesgo alto, hipoglucemias graves/repetidas o incertidumbre clínica relevante.
            - Responde en markdown legible con títulos y bullets cuando ayude.
            
            DATOS_ESTADISTICAS:
            $statsContext
            
            RESUMEN_INFORME_INICIAL:
            $seed
            
            DATOS_APP_COMPACTOS:
            $appContext
            
            HISTORIAL DE CONVERSACIÓN:
            $history
        """.trimIndent()
    }

    private fun formatConversationHistory(messages: List<InformeIaMessage>): String {
        if (messages.isEmpty()) return "Sin mensajes previos."
        return messages
            .takeLast(MAX_HISTORY_MESSAGES_FOR_PROMPT)
            .joinToString(separator = "\n") { message ->
                val roleLabel = if (message.role == InformeIaRole.USER) "Usuario" else "Asistente"
                val compact = message.content
                    .replace('\n', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_HISTORY_CHARS_PER_MESSAGE)
                "$roleLabel: $compact"
            }
    }

    private fun buildAiReport(period: StatsPeriod, resumen: EstadisticasResumen): String {
        val recomendaciones = mutableListOf<String>()

        resumen.delta2hMedia?.let { delta ->
            when {
                delta >= 35f -> recomendaciones += "Hay una subida media 2h relevante (${signed1(delta)} mg/dL). Revisa ratio HC/insulina, composición de comidas y timing del bolo."
                delta <= -25f -> recomendaciones += "Hay una bajada media 2h marcada (${signed1(delta)} mg/dL). Valora reducir insulina en comidas similares y revisar factores de corrección."
            }
        }

        resumen.porcentaje2hEnRango?.let { inRange ->
            if (inRange < 65f) {
                recomendaciones += "El porcentaje 2h en rango (80-180) está bajo (${format1(inRange)}%). Conviene revisar patrones por alimento y por franja horaria."
            }
        }

        resumen.nightscoutStats?.cv?.let { cv ->
            if (cv > 36f) {
                recomendaciones += "La variabilidad glucémica (CV ${format1(cv)}%) está por encima del objetivo habitual (<36%). Prioriza regularidad en dosis y comidas."
            }
        }

        resumen.desviacionRatioPct?.let { dev ->
            if (kotlin.math.abs(dev) >= 20f) {
                recomendaciones += "La desviación frente al ratio configurado es alta (${signed1(dev)}%). Puede ser momento de revisar el ratio base del perfil."
            }
        }

        if (resumen.totalDiasPeriodo > 0) {
            val coverage = (resumen.diasConRegistros.toFloat() / resumen.totalDiasPeriodo.toFloat()) * 100f
            if (coverage < 60f) {
                recomendaciones += "La cobertura de registros es ${format1(coverage)}%. Con más días registrados el análisis será más fiable."
            }
        }

        val alimentoMasImpacto = resumen.alimentosConMayorDelta.firstOrNull()
        if (alimentoMasImpacto != null && alimentoMasImpacto.deltaMedio > 25f) {
            recomendaciones += "El alimento '${alimentoMasImpacto.nombre}' destaca por subida media de ${signed1(alimentoMasImpacto.deltaMedio)} mg/dL (${alimentoMasImpacto.muestras} muestras)."
        }

        if (recomendaciones.isEmpty()) {
            recomendaciones += "No se detectan alertas mayores en este periodo. Mantén el plan actual y sigue monitorizando la tendencia."
        }

        val nightscoutLinea = when {
            !resumen.nightscoutConfigured -> "Nightscout no configurado."
            resumen.nightscoutLoading -> "Nightscout en carga."
            resumen.nightscoutStats != null -> {
                val ns = resumen.nightscoutStats
                "Nightscout: TIR ${format1(ns.tir70_180)}%, TBR ${format1(ns.tbrBelow70)}%, TAR ${format1(ns.tarAbove180)}%, CV ${ns.cv?.let { format1(it) + "%" } ?: "N/D"}, GMI ${ns.gmi?.let { format2(it) + "%" } ?: "N/D"}."
            }

            else -> resumen.nightscoutError ?: "Nightscout sin datos."
        }

        val topAlimentos = if (resumen.topAlimentos.isEmpty()) {
            "Sin datos."
        } else {
            resumen.topAlimentos.take(3).joinToString(" | ") {
                "${it.nombre} (${it.usos} usos, ${format1(it.hidratosTotales)} g HC)"
            }
        }

        val comidasSimilares = if (resumen.similarMeals.isEmpty()) {
            "Sin suficientes repeticiones."
        } else {
            resumen.similarMeals.take(2).joinToString(" | ") {
                val deltaTxt = it.delta2hMedio?.let { d -> "Δ2h ${signed1(d)} mg/dL" } ?: "Δ2h N/D"
                "${it.titulo} (${it.muestras} muestras, $deltaTxt)"
            }
        }

        return buildString {
            appendLine("Periodo analizado: ${period.label}")
            appendLine("Registros analizados: ${resumen.totalRegistros} comidas (${resumen.diasConRegistros}/${resumen.totalDiasPeriodo} días con datos)")
            appendLine()
            appendLine("1) Estado general")
            appendLine("- Hidratos totales: ${format1(resumen.hidratosTotales)} g")
            appendLine("- Insulina total: ${format1(resumen.insulinaTotal)} U")
            appendLine("- Ratio efectivo: ${resumen.ratioEfectivoURacion?.let { format2(it) + " U/ración" } ?: "N/D"}")
            appendLine("- Desviación vs perfil: ${resumen.desviacionRatioPct?.let { signed1(it) + "%" } ?: "N/D"}")
            appendLine()
            appendLine("2) Glucosa (antes vs 2h)")
            appendLine("- Media antes: ${resumen.glucosaAntesMedia?.let { format1(it) + " mg/dL" } ?: "N/D"}")
            appendLine("- Media 2h: ${resumen.glucosaDespuesMedia?.let { format1(it) + " mg/dL" } ?: "N/D"}")
            appendLine("- Delta medio 2h: ${resumen.delta2hMedia?.let { signed1(it) + " mg/dL" } ?: "N/D"}")
            appendLine("- Variabilidad delta: ${resumen.delta2hStdDev?.let { format1(it) + " mg/dL" } ?: "N/D"}")
            appendLine("- En rango 2h (80-180): ${resumen.porcentaje2hEnRango?.let { format1(it) + "%" } ?: "N/D"}")
            appendLine()
            appendLine("3) Uso de corrección")
            appendLine("- Dosis aplicadas: ${resumen.dosisAplicadas}")
            appendLine("- Con corrección: ${resumen.dosisConCorreccion}")
            appendLine("- Sin corrección: ${resumen.dosisSinCorreccion}")
            appendLine("- % con corrección: ${resumen.porcentajeConCorreccion?.let { format1(it) + "%" } ?: "N/D"}")
            appendLine()
            appendLine("4) Tendencias")
            appendLine("- Hidratos: ${resumen.tendenciaHidratosPct?.let { signed1(it) + "%" } ?: "N/D"}")
            appendLine("- Insulina: ${resumen.tendenciaInsulinaPct?.let { signed1(it) + "%" } ?: "N/D"}")
            appendLine("- Ratio U/g: ${resumen.tendenciaRatioUGPct?.let { signed1(it) + "%" } ?: "N/D"}")
            appendLine("- Delta 2h: ${resumen.tendenciaDelta2hPct?.let { signed1(it) + "%" } ?: "N/D"}")
            appendLine()
            appendLine("5) Patrones relevantes")
            appendLine("- Top alimentos: $topAlimentos")
            appendLine("- Comidas similares: $comidasSimilares")
            appendLine("- $nightscoutLinea")
            appendLine()
            appendLine("6) Recomendaciones IA")
            recomendaciones.forEach { appendLine("- $it") }
        }
    }

    private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
    private fun format2(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)
    private fun format3(value: Float): String = String.format(Locale.getDefault(), "%.3f", value)
    private fun signed1(value: Float): String = String.format(Locale.getDefault(), "%+.1f", value)

    class Factory(
        private val registroRepository: RegistroComidaRepository,
        private val usuarioRepository: UsuarioProfileRepository,
        private val alimentoRepository: AlimentoRepository,
        private val nightscoutRepository: NightscoutRepository,
        private val geminiRepository: GeminiRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EstadisticasViewModel::class.java)) {
                return EstadisticasViewModel(
                    registroRepository = registroRepository,
                    usuarioRepository = usuarioRepository,
                    alimentoRepository = alimentoRepository,
                    nightscoutRepository = nightscoutRepository,
                    geminiRepository = geminiRepository
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }

    companion object {
        private const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        private const val MAX_HISTORY_MESSAGES_FOR_PROMPT = 5
        private const val MAX_HISTORY_CHARS_PER_MESSAGE = 340
        private const val MAX_INITIAL_REPORT_SEED_CHARS = 1_200
        private const val MAX_INLINE_TEXT_CHARS = 220
        private val INITIAL_CONTEXT_BUDGET = IaContextBudget(
            maxChars = 12_500,
            maxRegistros = 65,
            maxItemsPerRegistro = 5,
            maxAlimentos = 110
        )
        private val FOLLOWUP_CONTEXT_BUDGET = IaContextBudget(
            maxChars = 4_600,
            maxRegistros = 20,
            maxItemsPerRegistro = 3,
            maxAlimentos = 36
        )
        private val DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
