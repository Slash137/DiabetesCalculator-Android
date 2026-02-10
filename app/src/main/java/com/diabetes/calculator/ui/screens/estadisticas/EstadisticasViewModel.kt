package com.diabetes.calculator.ui.screens.estadisticas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.dao.RegistroComidaConItems
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

data class EstadisticasResumen(
    val totalRegistros: Int,
    val diasConRegistros: Int,
    val totalDiasPeriodo: Int,
    val comidasPorDia: Float,
    val hidratosTotales: Float,
    val racionesTotales: Float,
    val insulinaTotal: Float,
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

class EstadisticasViewModel(
    private val registroRepository: RegistroComidaRepository,
    private val usuarioRepository: UsuarioProfileRepository,
    private val nightscoutRepository: NightscoutRepository
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.LAST_30)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _uiState = MutableStateFlow<EstadisticasUiState>(EstadisticasUiState.Loading)
    val uiState: StateFlow<EstadisticasUiState> = _uiState.asStateFlow()

    init {
        observeStats()
    }

    fun updatePeriod(period: StatsPeriod) {
        _period.value = period
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
                runCatching {
                    val filtered = applyPeriod(registros, period)
                    if (filtered.isEmpty()) {
                        _uiState.value = EstadisticasUiState.Empty(period)
                        return@runCatching
                    }

                    val ratioConfigurado = profile?.ratioInsulina
                    val localResumen = buildResumen(
                        registros = filtered,
                        period = period,
                        ratioConfiguradoURacion = ratioConfigurado,
                        nightscoutConfigured = !profile?.nightscoutUrl.isNullOrBlank(),
                        nightscoutLoading = false,
                        nightscoutStats = null,
                        nightscoutError = null
                    )

                    val nightscoutUrl = profile?.nightscoutUrl
                    val nightscoutToken = profile?.nightscoutToken
                    if (nightscoutUrl.isNullOrBlank()) {
                        _uiState.value = EstadisticasUiState.Success(period, localResumen)
                        return@runCatching
                    }

                    _uiState.value = EstadisticasUiState.Success(
                        period,
                        localResumen.copy(nightscoutLoading = true)
                    )

                    val (from, to) = resolveNightscoutRange(period, filtered)
                    val nsStats = fetchNightscoutStats(
                        baseUrl = nightscoutUrl,
                        token = nightscoutToken,
                        from = from,
                        to = to
                    )

                    _uiState.value = EstadisticasUiState.Success(
                        period = period,
                        resumen = localResumen.copy(
                            nightscoutLoading = false,
                            nightscoutStats = nsStats.stat,
                            nightscoutError = nsStats.error
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
        period: StatsPeriod,
        ratioConfiguradoURacion: Float?,
        nightscoutConfigured: Boolean,
        nightscoutLoading: Boolean,
        nightscoutStats: NightscoutAdvancedStat?,
        nightscoutError: String?
    ): EstadisticasResumen {
        val totalRegistros = registros.size
        val dayStarts = registros
            .map { DateUtils.getStartOfDay(it.registro.fecha) }
            .toSet()

        val totalDiasPeriodo = period.days ?: inferRangeDays(dayStarts)
        val diasConRegistros = dayStarts.size
        val diasSinRegistros = (totalDiasPeriodo - diasConRegistros).coerceAtLeast(0)
        val comidasPorDia = safeDiv(totalRegistros.toFloat(), totalDiasPeriodo.toFloat())

        val hidratosTotales = registros.sumOf { it.registro.hidratosTotales.toDouble() }.toFloat()
        val racionesTotales = registros.sumOf { it.registro.racionesCalculadas.toDouble() }.toFloat()
        val insulinaTotal = registros.sumOf { it.registro.unidadesInsulina.toDouble() }.toFloat()

        val hidratosPorComida = safeDiv(hidratosTotales, totalRegistros.toFloat())
        val racionesPorComida = safeDiv(racionesTotales, totalRegistros.toFloat())
        val insulinaPorComida = safeDiv(insulinaTotal, totalRegistros.toFloat())

        val ratioEfectivoURacion = if (racionesTotales > 0f) insulinaTotal / racionesTotales else null
        val ratioEfectivoUG = if (hidratosTotales > 0f) insulinaTotal / hidratosTotales else null
        val gramosPorUnidad = ratioEfectivoUG?.takeIf { it > 0f }?.let { 1f / it }

        val desviacionRatioPct =
            if (ratioConfiguradoURacion != null && ratioConfiguradoURacion > 0f && ratioEfectivoURacion != null) {
                ((ratioEfectivoURacion - ratioConfiguradoURacion) / ratioConfiguradoURacion) * 100f
            } else {
                null
            }

        val glucosaAntesValues = registros.mapNotNull { it.registro.glucosaAntesMgdl?.toFloat() }
        val glucosaDespuesValues = registros.mapNotNull { it.registro.glucosaDespues2hMgdl?.toFloat() }
        val deltas2h = registros.mapNotNull { registro ->
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

        val registrosConGlucosa = registros.count {
            it.registro.glucosaAntesMgdl != null || it.registro.glucosaDespues2hMgdl != null
        }
        val registrosConNotas = registros.count { !it.registro.notas.isNullOrBlank() }

        val franjaDistribution = buildFranjaDistribution(registros)
        val weekdayStats = buildWeekdayStats(registros)
        val topAlimentos = buildTopAlimentos(registros)
        val alimentosConMayorDelta = buildAlimentosConMayorDelta(registros)
        val similarMeals = buildSimilarMeals(registros)
        val trend = buildTrends(registros)

        return EstadisticasResumen(
            totalRegistros = totalRegistros,
            diasConRegistros = diasConRegistros,
            totalDiasPeriodo = totalDiasPeriodo,
            comidasPorDia = comidasPorDia,
            hidratosTotales = hidratosTotales,
            racionesTotales = racionesTotales,
            insulinaTotal = insulinaTotal,
            hidratosPorComida = hidratosPorComida,
            racionesPorComida = racionesPorComida,
            insulinaPorComida = insulinaPorComida,
            ratioEfectivoURacion = ratioEfectivoURacion,
            ratioEfectivoUG = ratioEfectivoUG,
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

    class Factory(
        private val registroRepository: RegistroComidaRepository,
        private val usuarioRepository: UsuarioProfileRepository,
        private val nightscoutRepository: NightscoutRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EstadisticasViewModel::class.java)) {
                return EstadisticasViewModel(
                    registroRepository = registroRepository,
                    usuarioRepository = usuarioRepository,
                    nightscoutRepository = nightscoutRepository
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
