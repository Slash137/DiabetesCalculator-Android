package com.diabetes.calculator.ui.screens.nuevacomida

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.EstadoFisicoAlimento
import com.diabetes.calculator.data.entity.PendingGlucose
import com.diabetes.calculator.data.entity.PendingGlucoseTipo
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.TipoMedicionAlimento
import com.diabetes.calculator.data.entity.UnidadConsumoAlimento
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.entity.calcularDesdeCantidad
import com.diabetes.calculator.data.entity.estadoFisicoNormalizado
import com.diabetes.calculator.data.entity.requiereEquivalenciaUnidad
import com.diabetes.calculator.data.entity.tipoMedicionNormalizado
import com.diabetes.calculator.data.model.NightscoutEntry
import com.diabetes.calculator.data.repository.AlimentoRepository
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncService
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.PendingGlucoseRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.RegistroLibreviewSyncRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.data.repository.LibreviewRegistrosSyncService
import com.diabetes.calculator.data.repository.LibreviewRepository
import com.diabetes.calculator.domain.CgmReading
import com.diabetes.calculator.domain.CgmSource
import com.diabetes.calculator.domain.CgmTrendCorrection
import com.diabetes.calculator.domain.FactoresContextoInsulina
import com.diabetes.calculator.domain.FaseCicloHormonal
import com.diabetes.calculator.domain.FranjaHoraria
import com.diabetes.calculator.domain.NivelEjercicio
import com.diabetes.calculator.domain.NivelEnfermedad
import com.diabetes.calculator.domain.NivelEstres
import com.diabetes.calculator.domain.NightscoutAuthorityPolicy
import com.diabetes.calculator.domain.ResolvedCgmReading
import com.diabetes.calculator.domain.SeleccionContextoInsulina
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.domain.ActiveInsulinSnapshot
import com.diabetes.calculator.util.DateUtils
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.work.Glucosa2hWorker
import com.diabetes.calculator.work.LibreviewSyncWorker
import com.diabetes.calculator.work.NightscoutRetryWorker
import com.diabetes.calculator.work.NightscoutSyncWorker
import com.diabetes.calculator.work.Recordatorio2hScheduler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val NUEVA_COMIDA_SEARCH_DEBOUNCE_MS = 180L
private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
private const val NIGHTSCOUT_FRESHNESS_MINUTES = 10
private const val CGM_PROJECTION_MINUTES = 30
private const val TREND_ADJUSTMENT_CAP_UNITS = 1.0f

internal data class ResultadoDosisActiva(
    val unidadesCorreccion: Float,
    val unidadesCorreccionReducidaPorActiva: Float,
    val unidadesComidaReducidaPorActiva: Float,
    val unidadesInsulinaConCorreccion: Float,
    val unidadesInsulinaSinCorreccion: Float
)

internal fun calcularDosisFinalConInsulinaActiva(
    unidadesComida: Float,
    unidadesCorreccionBruta: Float,
    insulinaActiva: Float,
    factorTotalAplicado: Float
): ResultadoDosisActiva {
    val insulinaActivaSegura = insulinaActiva
        .takeIf { it.isFinite() && it > 0f }
        ?: 0f
    val reduccionCorreccionPorActiva = if (unidadesCorreccionBruta > 0f) {
        kotlin.math.min(unidadesCorreccionBruta, insulinaActivaSegura)
    } else {
        0f
    }
    val unidadesCorreccion = unidadesCorreccionBruta - reduccionCorreccionPorActiva
    val insulinaActivaResidual = (insulinaActivaSegura - reduccionCorreccionPorActiva).coerceAtLeast(0f)
    val dosisContextualRaw = FactoresContextoInsulina.applyFactorToDosesRaw(
        unidadesComida = unidadesComida,
        unidadesCorreccion = unidadesCorreccion,
        factorTotalAplicado = factorTotalAplicado
    )
    val reduccionComidaPorActiva = kotlin.math.min(
        kotlin.math.min(dosisContextualRaw.totalSinCorreccion, dosisContextualRaw.totalConCorreccion),
        insulinaActivaResidual
    )
    val unidadesSinCorreccion = FactoresContextoInsulina.roundToHalf(
        (dosisContextualRaw.totalSinCorreccion - reduccionComidaPorActiva).coerceAtLeast(0f)
    )
    val unidadesConCorreccion = FactoresContextoInsulina.roundToHalf(
        (dosisContextualRaw.totalConCorreccion - reduccionComidaPorActiva).coerceAtLeast(0f)
    )

    return ResultadoDosisActiva(
        unidadesCorreccion = unidadesCorreccion,
        unidadesCorreccionReducidaPorActiva = reduccionCorreccionPorActiva,
        unidadesComidaReducidaPorActiva = reduccionComidaPorActiva,
        unidadesInsulinaConCorreccion = unidadesConCorreccion,
        unidadesInsulinaSinCorreccion = unidadesSinCorreccion
    )
}

/**
 * Representa un elemento individual dentro de una comida en construcción.
 */
data class ItemComidaTemporal(
    val id: Long = System.nanoTime(),
    val alimento: Alimento? = null,
    val cantidadStr: String = "",
    val hidratos: Float = 0f,
    val cantidadBase: Float = 0f,
    val unidadBase: String = UnidadConsumoAlimento.GRAMOS,
    val configuracionIncompleta: Boolean = false
)

/**
 * Estados posibles de la pantalla de nueva comida.
 */
sealed class NuevaComidaUiState {
    object Loading : NuevaComidaUiState()
    object NoProfile : NuevaComidaUiState()
    data class Ready(
        val alimentos: List<Alimento>,
        val profile: UsuarioProfile
    ) : NuevaComidaUiState()
    data class Error(val message: String) : NuevaComidaUiState()
}

/**
 * Representa el cálculo total de la comida actual.
 */
data class CalculoActual(
    val hidratosTotales: Float = 0f,
    val raciones: Float = 0f,
    val unidadesComida: Float = 0f,
    val unidadesCorreccionBruta: Float = 0f,
    val unidadesCorreccion: Float = 0f,
    val unidadesCorreccionReducidaPorActiva: Float = 0f,
    val unidadesComidaReducidaPorActiva: Float = 0f,
    val insulinaActivaActual: Float = 0f,
    val unidadesInsulina: Float = 0f,
    val unidadesInsulinaSinCorreccion: Float = 0f,
    val glucosaUsadaMgdl: Int? = null,
    val glucosaFuente: CgmSource? = null,
    val glucosaEdadMinutos: Int? = null,
    val tendenciaDireccion: String? = null,
    val glucosaProyectadaMgdl: Int? = null,
    val correccionBaseRaw: Float = 0f,
    val ajusteTendenciaRaw: Float = 0f,
    val franjaHoraria: FranjaHoraria = FactoresContextoInsulina.defaultSelection().franjaHoraria,
    val nivelEstres: NivelEstres = NivelEstres.NINGUNO,
    val nivelEnfermedad: NivelEnfermedad = NivelEnfermedad.NINGUNA,
    val faseCiclo: FaseCicloHormonal = FaseCicloHormonal.NO_APLICAR,
    val nivelEjercicio: NivelEjercicio = NivelEjercicio.NINGUNO,
    val factorHora: Float = 1f,
    val factorEstres: Float = 1f,
    val factorEnfermedad: Float = 1f,
    val factorCiclo: Float = 1f,
    val factorEjercicio: Float = 1f,
    val factorContextoTotalRaw: Float = 1f,
    val factorContextoTotalAplicado: Float = 1f,
    val factorContextoCapado: Boolean = false
)

/**
 * ViewModel para la pantalla de nueva comida.
 * Soporta múltiples alimentos por comida.
 */
class NuevaComidaViewModel(
    private val usuarioRepository: UsuarioProfileRepository,
    private val alimentoRepository: AlimentoRepository,
    private val registroRepository: RegistroComidaRepository,
    private val nightscoutRepository: NightscoutRepository,
    private val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository,
    private val plantillaRepository: PlantillaRepository,
    private val pendingGlucoseRepository: PendingGlucoseRepository,
    private val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository,
    private val registroLibreviewSyncRepository: RegistroLibreviewSyncRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<NuevaComidaUiState>(NuevaComidaUiState.Loading)
    val uiState: StateFlow<NuevaComidaUiState> = _uiState.asStateFlow()

    private val _items = MutableStateFlow<List<ItemComidaTemporal>>(listOf(ItemComidaTemporal()))
    val items: StateFlow<List<ItemComidaTemporal>> = _items.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val plantillas: StateFlow<List<com.diabetes.calculator.data.dao.PlantillaConItems>> =
        plantillaRepository.plantillas.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    private val _notas = MutableStateFlow("")
    val notas: StateFlow<String> = _notas.asStateFlow()

    private val _calculo = MutableStateFlow(CalculoActual())
    val calculo: StateFlow<CalculoActual> = _calculo.asStateFlow()

    private val initialContext = FactoresContextoInsulina.defaultSelection()
    private val _franjaHoraria = MutableStateFlow(initialContext.franjaHoraria)
    val franjaHoraria: StateFlow<FranjaHoraria> = _franjaHoraria.asStateFlow()

    private val _nivelEstres = MutableStateFlow(initialContext.nivelEstres)
    val nivelEstres: StateFlow<NivelEstres> = _nivelEstres.asStateFlow()

    private val _nivelEnfermedad = MutableStateFlow(initialContext.nivelEnfermedad)
    val nivelEnfermedad: StateFlow<NivelEnfermedad> = _nivelEnfermedad.asStateFlow()

    private val _faseCiclo = MutableStateFlow(initialContext.faseCiclo)
    val faseCiclo: StateFlow<FaseCicloHormonal> = _faseCiclo.asStateFlow()

    private val _nivelEjercicio = MutableStateFlow(initialContext.nivelEjercicio)
    val nivelEjercicio: StateFlow<NivelEjercicio> = _nivelEjercicio.asStateFlow()

    private val _nightscoutEntry = MutableStateFlow<NightscoutEntry?>(null)
    private val _manualGlucosaFallbackInput = MutableStateFlow("")
    val manualGlucosaFallbackInput: StateFlow<String> = _manualGlucosaFallbackInput.asStateFlow()
    private val _allowManualGlucosaFallback = MutableStateFlow(true)
    val allowManualGlucosaFallback: StateFlow<Boolean> = _allowManualGlucosaFallback.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    private val _activeInsulinSnapshot = MutableStateFlow(ActiveInsulinSnapshot())
    val activeInsulinSnapshot: StateFlow<ActiveInsulinSnapshot> = _activeInsulinSnapshot.asStateFlow()

    private val _activeInsulinLoading = MutableStateFlow(true)
    val activeInsulinLoading: StateFlow<Boolean> = _activeInsulinLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiEvents = _uiEvents.asSharedFlow()

    private var cachedProfile: UsuarioProfile? = null
    private var cachedAlimentos: List<Alimento> = emptyList()
    private var contextInitialized = false
    private var activeInsulinTickerJob: Job? = null
    private fun buildLibreviewSyncService(profile: UsuarioProfile): LibreviewRegistrosSyncService {
        val linkMinutes = max(
            profile.nightscoutLinkOffsetMinutes.coerceIn(0, 180),
            SyncLinkTolerance.WINDOW_MINUTES
        )
        val linkUnits = max(
            profile.nightscoutLinkOffsetUnits.coerceIn(0f, 5f),
            SyncLinkTolerance.WINDOW_UNITS
        )
        return LibreviewRegistrosSyncService(
            registroRepository = registroRepository,
            queueRepository = registroLibreviewSyncRepository,
            libreviewRepository = LibreviewRepository(),
            linkMatchDeltaMillis = linkMinutes * 60_000L,
            linkMatchInsulinDelta = linkUnits
        )
    }

    private suspend fun reconcileLocalDuplicates(profile: UsuarioProfile) {
        val linkMinutes = max(
            profile.nightscoutLinkOffsetMinutes.coerceIn(0, 180),
            SyncLinkTolerance.WINDOW_MINUTES
        )
        val linkUnits = max(
            profile.nightscoutLinkOffsetUnits.coerceIn(0f, 5f),
            SyncLinkTolerance.WINDOW_UNITS
        )
        NightscoutRegistrosSyncService(
            registroRepository = registroRepository,
            queueRepository = registroNightscoutSyncRepository,
            tombstoneRepository = nightscoutTreatmentTombstoneRepository,
            nightscoutRepository = nightscoutRepository,
            libreviewQueueRepository = registroLibreviewSyncRepository
        ).reconcileLocalDuplicatesOnly(
            linkOffsetMinutes = linkMinutes,
            linkOffsetUnits = linkUnits
        )
    }

    init {
        loadData()
        startActiveInsulinTicker()
    }

    @OptIn(FlowPreview::class)
    private fun loadData() {
        viewModelScope.launch {
            val debouncedSearchQuery = _searchQuery
                .map { it.trim() }
                .debounce(NUEVA_COMIDA_SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()

            combine(
                usuarioRepository.profile,
                alimentoRepository.alimentos,
                debouncedSearchQuery
            ) { profile, allAlimentos, query ->
                Triple(profile, allAlimentos, query)
            }.collect { (profile, allAlimentos, query) ->
                if (profile == null) {
                    cachedProfile = null
                    cachedAlimentos = emptyList()
                    contextInitialized = false
                    _uiState.value = NuevaComidaUiState.NoProfile
                } else {
                    val profileChanged = cachedProfile != profile
                    val alimentosChanged = cachedAlimentos !== allAlimentos
                    val wasNull = cachedProfile == null
                    cachedProfile = profile
                    cachedAlimentos = allAlimentos
                    if (wasNull || !contextInitialized) {
                        resetContextSelections()
                        contextInitialized = true
                    }
                    if (!profile.cicloHormonalActivo && _faseCiclo.value != FaseCicloHormonal.NO_APLICAR) {
                        _faseCiclo.value = FaseCicloHormonal.NO_APLICAR
                    }

                    if (profileChanged || alimentosChanged) {
                        recalculate()
                    }
                    val filtered = if (query.isBlank()) {
                        allAlimentos
                    } else {
                        allAlimentos.filter { it.nombre.contains(query, ignoreCase = true) }
                    }
                    _uiState.value = NuevaComidaUiState.Ready(filtered, profile)
                }
            }
        }
    }

    private fun startActiveInsulinTicker() {
        activeInsulinTickerJob?.cancel()
        activeInsulinTickerJob = viewModelScope.launch {
            refreshActiveInsulin()
            while (isActive) {
                delay(60_000L)
                refreshActiveInsulin()
            }
        }
    }

    private suspend fun refreshActiveInsulin() {
        val profile = cachedProfile
        val nightscoutUrl = profile?.nightscoutUrl?.trim()
        val nightscoutToken = profile?.nightscoutToken
        val ignoredTreatmentIds = runCatching {
            nightscoutTreatmentTombstoneRepository.getAllTreatmentIds()
        }.getOrElse { emptySet() }
        runCatching {
            registroRepository.getActiveInsulinSnapshot(
                nowMillis = System.currentTimeMillis(),
                nightscoutRepository = nightscoutRepository,
                nightscoutUrl = nightscoutUrl,
                nightscoutToken = nightscoutToken,
                ignoredRemoteTreatmentIds = ignoredTreatmentIds
            )
        }.onSuccess { snapshot ->
            _activeInsulinSnapshot.value = snapshot
            _activeInsulinLoading.value = false
            recalculate()
        }.onFailure {
            _activeInsulinLoading.value = false
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refreshData() {
        viewModelScope.launch {
            if (_isRefreshing.value) return@launch
            _isRefreshing.value = true
            try {
                val profile = cachedProfile
                val nightscoutUrl = profile?.nightscoutUrl?.trim().orEmpty()
                if (nightscoutUrl.isNotBlank()) {
                    runCatching {
                        nightscoutRepository.getLatestGlucose(
                            baseUrl = nightscoutUrl,
                            token = profile?.nightscoutToken
                        )
                    }.getOrNull()?.let { latest ->
                        _nightscoutEntry.value = latest
                    }
                }
                refreshActiveInsulin()
                recalculate()
                delay(350L)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun updateNightscoutEntry(entry: NightscoutEntry?) {
        if (_nightscoutEntry.value == entry) return
        _nightscoutEntry.value = entry
        recalculate()
    }

    fun updateManualGlucosaFallback(value: String) {
        if (value.isNotEmpty() && !value.matches(Regex("^\\d*$"))) return
        _manualGlucosaFallbackInput.value = value
        recalculate()
    }

    fun updateNotas(notas: String) {
        _notas.value = notas
    }

    fun updateFranjaHoraria(value: FranjaHoraria) {
        if (_franjaHoraria.value == value) return
        _franjaHoraria.value = value
        recalculate()
    }

    fun updateNivelEstres(value: NivelEstres) {
        if (_nivelEstres.value == value) return
        _nivelEstres.value = value
        recalculate()
    }

    fun updateNivelEnfermedad(value: NivelEnfermedad) {
        if (_nivelEnfermedad.value == value) return
        _nivelEnfermedad.value = value
        recalculate()
    }

    fun updateFaseCiclo(value: FaseCicloHormonal) {
        val profile = cachedProfile ?: return
        if (!profile.cicloHormonalActivo) {
            _faseCiclo.value = FaseCicloHormonal.NO_APLICAR
            return
        }
        if (_faseCiclo.value == value) return
        _faseCiclo.value = value
        recalculate()
    }

    fun updateNivelEjercicio(value: NivelEjercicio) {
        if (_nivelEjercicio.value == value) return
        _nivelEjercicio.value = value
        recalculate()
    }

    fun addItem() {
        val current = _items.value.toMutableList()
        current.add(ItemComidaTemporal())
        _items.value = current
    }

    fun removeItem(item: ItemComidaTemporal) {
        val current = _items.value.toMutableList()
        if (current.size > 1) {
            current.remove(item)
            _items.value = current
            recalculate()
        }
    }

    fun applyPlantilla(plantilla: com.diabetes.calculator.data.dao.PlantillaConItems) {
        if (cachedAlimentos.isEmpty()) return
        val alimentosById = cachedAlimentos.associateBy { it.id }
        val nuevosItems = plantilla.items.mapNotNull { item ->
            val alimento = alimentosById[item.item.alimentoId] ?: return@mapNotNull null
            val cantidad = cantidadPlantillaConFallback(item.item)
            buildItemState(
                base = ItemComidaTemporal(),
                alimento = alimento,
                cantidadStr = formatCantidad(cantidad)
            )
        }
        if (nuevosItems.isNotEmpty()) {
            _items.value = nuevosItems
            recalculate()
        }
    }

    fun savePlantilla(nombre: String) {
        if (hasBlockingUnitConfigurationIssue()) {
            _uiEvents.tryEmit("Configura equivalencia en Alimentos")
            return
        }
        val itemsValidos = _items.value.mapNotNull { toPlantillaItemOrNull(it) }
        if (itemsValidos.isEmpty()) {
            _uiEvents.tryEmit("No hay alimentos válidos para guardar la plantilla")
            return
        }
        val cleanName = nombre.trim()
        if (cleanName.isBlank()) {
            _uiEvents.tryEmit("Introduce un nombre para la plantilla")
            return
        }
        viewModelScope.launch {
            plantillaRepository.insertPlantilla(cleanName, itemsValidos)
            _uiEvents.tryEmit("Plantilla guardada")
        }
    }

    fun deletePlantilla(id: Int) {
        viewModelScope.launch {
            plantillaRepository.deletePlantilla(id)
            _uiEvents.tryEmit("Plantilla eliminada")
        }
    }

    fun updateItemAlimento(item: ItemComidaTemporal, alimento: Alimento) {
        val current = _items.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index != -1) {
            current[index] = buildItemState(
                base = current[index],
                alimento = alimento,
                cantidadStr = current[index].cantidadStr
            )
            _items.value = current
            recalculate()
        }
    }

    fun updateItemCantidad(item: ItemComidaTemporal, cantidadStr: String) {
        if (cantidadStr.isNotEmpty() && !cantidadStr.matches(Regex("^\\d*([\\.,]\\d*)?$"))) return

        val current = _items.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index != -1) {
            current[index] = buildItemState(
                base = current[index],
                alimento = current[index].alimento,
                cantidadStr = cantidadStr
            )
            _items.value = current
            recalculate()
        }
    }

    private fun recalculate() {
        val profile = cachedProfile ?: return
        val nowMillis = System.currentTimeMillis()
        val totalHidratos = _items.value.sumOf { it.hidratos.toDouble() }.toFloat()
        val nuevoCalculo = buildCalculo(
            profile = profile,
            totalHidratos = totalHidratos,
            nowMillis = nowMillis
        )
        _calculo.value = nuevoCalculo
        _allowManualGlucosaFallback.value = nuevoCalculo.glucosaFuente != CgmSource.NIGHTSCOUT
    }

    fun canSave(): Boolean {
        val profile = cachedProfile ?: return false
        if (profile.gramosPorRacion <= 0f || profile.ratioInsulina <= 0f) {
            return false
        }
        if (hasBlockingUnitConfigurationIssue()) {
            return false
        }
        return _items.value.any { isItemValidForSave(it) }
    }

    fun saveRegistro() {
        val profile = cachedProfile ?: return
        if (hasBlockingUnitConfigurationIssue()) {
            _uiEvents.tryEmit("Configura equivalencia en Alimentos")
            return
        }
        val validItems = _items.value.filter { isItemValidForSave(it) }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (profile.gramosPorRacion <= 0f || profile.ratioInsulina <= 0f) {
                    _uiEvents.tryEmit("Configura un perfil válido antes de guardar")
                    return@launch
                }
                if (validItems.isEmpty()) {
                    _uiEvents.tryEmit("Añade al menos un alimento válido")
                    return@launch
                }

                val nightscoutUrl = profile.nightscoutUrl
                val nightscoutEnabled = !nightscoutUrl.isNullOrBlank()
                var glucosaAntes: Int? = null
                var pendingAntes = false
                if (nightscoutEnabled) {
                    val fetched = nightscoutRepository
                        .getLatestGlucose(nightscoutUrl!!, profile.nightscoutToken)
                    if (fetched != null) {
                        _nightscoutEntry.value = fetched
                    }
                }
                val resolvedGlucose = resolveGlucoseInput(System.currentTimeMillis())
                glucosaAntes = resolvedGlucose.reading?.mgdl
                pendingAntes = nightscoutEnabled && resolvedGlucose.reading?.source != CgmSource.NIGHTSCOUT

                val totalHidratos = validItems.sumOf { it.hidratos.toDouble() }.toFloat()
                val calc = buildCalculo(
                    profile = profile,
                    totalHidratos = totalHidratos,
                    nowMillis = System.currentTimeMillis()
                )
                val unidadesFinales = calc.unidadesInsulina
                if (calc.hidratosTotales.isNaN() || calc.hidratosTotales.isInfinite() ||
                    calc.raciones.isNaN() || calc.raciones.isInfinite() ||
                    calc.unidadesInsulina.isNaN() || calc.unidadesInsulina.isInfinite() ||
                    calc.unidadesInsulinaSinCorreccion.isNaN() || calc.unidadesInsulinaSinCorreccion.isInfinite() ||
                    unidadesFinales.isNaN() || unidadesFinales.isInfinite()
                ) {
                    _uiEvents.tryEmit("Cálculo inválido. Revisa los datos introducidos")
                    return@launch
                }
                _calculo.value = calc

                val ratioInsulinaHc = if (profile.gramosPorRacion > 0f) {
                    profile.ratioInsulina / profile.gramosPorRacion
                } else {
                    null
                }

                val registro = RegistroComida(
                    hidratosTotales = calc.hidratosTotales,
                    racionesCalculadas = calc.raciones,
                    unidadesInsulina = unidadesFinales,
                    ratioInsulinaHc = ratioInsulinaHc,
                    notas = _notas.value.trim().ifEmpty { null },
                    glucosaAntesMgdl = glucosaAntes,
                    dosisEstado = EstadoDosis.PENDIENTE.value,
                    dosisConCorreccion = hasRealtimeCorrection(calc),
                    unidadesCorreccionSugerida = calc.unidadesCorreccion,
                    factorCorreccionMgdlPorUUsado = profile.factorCorreccionMgdlPorU,
                    franjaHorariaUsada = calc.franjaHoraria.key,
                    nivelEstresUsado = calc.nivelEstres.key,
                    nivelEnfermedadUsado = calc.nivelEnfermedad.key,
                    faseCicloUsada = calc.faseCiclo.key,
                    nivelEjercicioUsado = calc.nivelEjercicio.key,
                    factorHoraUsado = calc.factorHora,
                    factorEstresUsado = calc.factorEstres,
                    factorEnfermedadUsado = calc.factorEnfermedad,
                    factorCicloUsado = calc.factorCiclo,
                    factorEjercicioUsado = calc.factorEjercicio,
                    factorContextoTotalRaw = calc.factorContextoTotalRaw,
                    factorContextoTotalAplicado = calc.factorContextoTotalAplicado,
                    factorContextoCapado = calc.factorContextoCapado
                )

                val itemsEntities = validItems.mapNotNull { item ->
                    val alimento = item.alimento ?: return@mapNotNull null
                    val cantidad = parseDecimal(item.cantidadStr) ?: return@mapNotNull null
                    val resultado = alimento.calcularDesdeCantidad(cantidad) ?: return@mapNotNull null
                    AlimentoEnRegistro(
                        registroId = 0,
                        alimentoId = alimento.id,
                        gramosConsumidos = resultado.cantidadBase,
                        hidratosCalculados = resultado.hidratos,
                        cantidadConsumida = cantidad,
                        unidadConsumida = unidadConsumidaPara(alimento)
                    )
                }
                if (itemsEntities.isEmpty()) {
                    _uiEvents.tryEmit("No se pudo calcular la comida con los datos actuales")
                    return@launch
                }
                val registroId = registroRepository.insertRegistroCompleto(registro, itemsEntities)
                reconcileLocalDuplicates(profile)
                if (nightscoutEnabled) {
                    scheduleGlucosa2h(registroId)
                }
                if (profile.nightscoutSyncRegistrosActivo && nightscoutEnabled) {
                    registroNightscoutSyncRepository.upsertPending(registroId)
                    NightscoutSyncWorker.enqueueNow(workManager)
                }
                if (profile.libreviewSyncActivo) {
                    val libreviewSyncService = buildLibreviewSyncService(profile)
                    libreviewSyncService.enqueueUpsertForRegistro(registroId)
                    LibreviewSyncWorker.enqueueNow(workManager)
                }
                if (profile.recordatorio2hActivo) {
                    Recordatorio2hScheduler.schedule(
                        workManager = workManager,
                        registroId = registroId,
                        triggerAtMillis = registro.fecha + TWO_HOURS_MS
                    )
                }
                if (pendingAntes) {
                    pendingGlucoseRepository.insert(
                        PendingGlucose(
                            registroId = registroId,
                            tipo = PendingGlucoseTipo.ANTES,
                            targetMillis = registro.fecha
                        )
                    )
                    scheduleNightscoutRetry()
                }

                val alertMsg = buildObjetivoAlert()
                if (alertMsg != null) {
                    _uiEvents.tryEmit(alertMsg)
                }
                refreshActiveInsulin()
                _saveSuccess.value = true
                resetForm()
            } catch (e: Exception) {
                _uiEvents.tryEmit("Error al guardar: ${e.message ?: "desconocido"}")
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun buildCalculo(
        profile: UsuarioProfile,
        totalHidratos: Float,
        nowMillis: Long
    ): CalculoActual {
        val raciones = if (profile.gramosPorRacion > 0f) {
            totalHidratos / profile.gramosPorRacion
        } else {
            0f
        }
        val unidadesComida = if (profile.ratioInsulina > 0f) {
            raciones * profile.ratioInsulina
        } else {
            0f
        }
        val resolvedGlucose = resolveGlucoseInput(nowMillis)
        val glucoseReading = resolvedGlucose.reading
        val correctionWithTrend = CgmTrendCorrection.calculateCorrectionWithTrend(
            reading = glucoseReading,
            objetivoMgdl = profile.glucosaObjetivoMgdl,
            factorCorreccionMgdlPorU = profile.factorCorreccionMgdlPorU,
            projectionMinutes = CGM_PROJECTION_MINUTES,
            trendAdjustmentCapUnits = TREND_ADJUSTMENT_CAP_UNITS
        )
        val unidadesCorreccionBruta = correctionWithTrend.correccionFinalRaw
        val insulinaActiva = _activeInsulinSnapshot.value.totalUnits
            .takeIf { it.isFinite() && it > 0f }
            ?: 0f
        val contexto = FactoresContextoInsulina.resolve(profile, currentSelection(profile))
        val dosisFinal = calcularDosisFinalConInsulinaActiva(
            unidadesComida = unidadesComida,
            unidadesCorreccionBruta = unidadesCorreccionBruta,
            insulinaActiva = insulinaActiva,
            factorTotalAplicado = contexto.factorTotalAplicado
        )

        return CalculoActual(
            hidratosTotales = totalHidratos,
            raciones = raciones,
            unidadesComida = unidadesComida,
            unidadesCorreccionBruta = unidadesCorreccionBruta,
            unidadesCorreccion = dosisFinal.unidadesCorreccion,
            unidadesCorreccionReducidaPorActiva = dosisFinal.unidadesCorreccionReducidaPorActiva,
            unidadesComidaReducidaPorActiva = dosisFinal.unidadesComidaReducidaPorActiva,
            insulinaActivaActual = insulinaActiva,
            unidadesInsulina = dosisFinal.unidadesInsulinaConCorreccion,
            unidadesInsulinaSinCorreccion = dosisFinal.unidadesInsulinaSinCorreccion,
            glucosaUsadaMgdl = glucoseReading?.mgdl,
            glucosaFuente = glucoseReading?.source,
            glucosaEdadMinutos = if (glucoseReading?.source == CgmSource.NIGHTSCOUT) {
                ((nowMillis - glucoseReading.timestampMillis).coerceAtLeast(0L) / 60_000L).toInt()
            } else {
                null
            },
            tendenciaDireccion = glucoseReading?.direction,
            glucosaProyectadaMgdl = correctionWithTrend.projectedGlucoseMgdl,
            correccionBaseRaw = correctionWithTrend.correccionBaseRaw,
            ajusteTendenciaRaw = correctionWithTrend.ajusteTendenciaRaw,
            franjaHoraria = _franjaHoraria.value,
            nivelEstres = _nivelEstres.value,
            nivelEnfermedad = _nivelEnfermedad.value,
            faseCiclo = if (profile.cicloHormonalActivo) _faseCiclo.value else FaseCicloHormonal.NO_APLICAR,
            nivelEjercicio = _nivelEjercicio.value,
            factorHora = contexto.factorHora,
            factorEstres = contexto.factorEstres,
            factorEnfermedad = contexto.factorEnfermedad,
            factorCiclo = contexto.factorCiclo,
            factorEjercicio = contexto.factorEjercicio,
            factorContextoTotalRaw = contexto.factorTotalRaw,
            factorContextoTotalAplicado = contexto.factorTotalAplicado,
            factorContextoCapado = contexto.factorCapado
        )
    }

    private fun currentSelection(profile: UsuarioProfile): SeleccionContextoInsulina {
        val fase = if (profile.cicloHormonalActivo) {
            _faseCiclo.value
        } else {
            FaseCicloHormonal.NO_APLICAR
        }
        return SeleccionContextoInsulina(
            franjaHoraria = _franjaHoraria.value,
            nivelEstres = _nivelEstres.value,
            nivelEnfermedad = _nivelEnfermedad.value,
            faseCiclo = fase,
            nivelEjercicio = _nivelEjercicio.value
        )
    }

    private fun resolveGlucoseInput(nowMillis: Long): ResolvedCgmReading {
        val nightscoutReading = _nightscoutEntry.value
            ?.let { entry ->
                CgmReading(
                    mgdl = entry.sgv,
                    direction = entry.direction,
                    timestampMillis = entry.date,
                    source = CgmSource.NIGHTSCOUT
                )
            }
        val manualFallback = parseManualGlucoseFallback()?.let { mgdl ->
            CgmReading(
                mgdl = mgdl,
                direction = null,
                timestampMillis = nowMillis,
                source = CgmSource.MANUAL_FALLBACK
            )
        }
        return NightscoutAuthorityPolicy.resolveGlucoseSource(
            nightscoutReading = nightscoutReading,
            manualFallback = manualFallback,
            nowMillis = nowMillis,
            freshnessMinutes = NIGHTSCOUT_FRESHNESS_MINUTES
        )
    }

    private fun parseManualGlucoseFallback(): Int? {
        val value = _manualGlucosaFallbackInput.value.trim()
        val mgdl = value.toIntOrNull() ?: return null
        return mgdl.takeIf { it in 20..600 }
    }

    private fun hasRealtimeCorrection(calculo: CalculoActual): Boolean {
        return kotlin.math.abs(calculo.unidadesCorreccion) >= 0.05f
    }

    private fun resetContextSelections() {
        val defaults = FactoresContextoInsulina.defaultSelection()
        _franjaHoraria.value = defaults.franjaHoraria
        _nivelEstres.value = defaults.nivelEstres
        _nivelEnfermedad.value = defaults.nivelEnfermedad
        _faseCiclo.value = defaults.faseCiclo
        _nivelEjercicio.value = defaults.nivelEjercicio
    }

    private fun resetForm() {
        _items.value = listOf(ItemComidaTemporal())
        _notas.value = ""
        _manualGlucosaFallbackInput.value = ""
        _calculo.value = CalculoActual()
        _searchQuery.value = ""
        resetContextSelections()
        recalculate()
    }

    fun resetSaveSuccess() {
        _saveSuccess.value = false
    }

    private fun buildItemState(
        base: ItemComidaTemporal,
        alimento: Alimento?,
        cantidadStr: String
    ): ItemComidaTemporal {
        if (alimento == null) {
            return base.copy(
                alimento = null,
                cantidadStr = cantidadStr,
                hidratos = 0f,
                cantidadBase = 0f,
                unidadBase = UnidadConsumoAlimento.GRAMOS,
                configuracionIncompleta = false
            )
        }

        val cantidad = parseDecimal(cantidadStr) ?: 0f
        val resultado = alimento.calcularDesdeCantidad(cantidad)
        val unidadBase = resultado?.unidadBase ?: defaultUnidadBase(alimento)

        return base.copy(
            alimento = alimento,
            cantidadStr = cantidadStr,
            hidratos = resultado?.hidratos ?: 0f,
            cantidadBase = resultado?.cantidadBase ?: 0f,
            unidadBase = unidadBase,
            configuracionIncompleta = alimento.tipoMedicionNormalizado() == TipoMedicionAlimento.UNIDAD &&
                alimento.requiereEquivalenciaUnidad()
        )
    }

    private fun hasBlockingUnitConfigurationIssue(): Boolean {
        return _items.value.any { item ->
            val alimento = item.alimento ?: return@any false
            alimento.tipoMedicionNormalizado() == TipoMedicionAlimento.UNIDAD &&
                alimento.requiereEquivalenciaUnidad()
        }
    }

    private fun isItemValidForSave(item: ItemComidaTemporal): Boolean {
        val alimento = item.alimento ?: return false
        val cantidad = parseDecimal(item.cantidadStr) ?: return false
        if (cantidad <= 0f) return false
        if (alimento.tipoMedicionNormalizado() == TipoMedicionAlimento.UNIDAD &&
            alimento.requiereEquivalenciaUnidad()
        ) {
            return false
        }
        return alimento.calcularDesdeCantidad(cantidad) != null
    }

    private fun toPlantillaItemOrNull(item: ItemComidaTemporal): PlantillaItem? {
        val alimento = item.alimento ?: return null
        val cantidad = parseDecimal(item.cantidadStr) ?: return null
        if (cantidad <= 0f) return null
        val resultado = alimento.calcularDesdeCantidad(cantidad) ?: return null
        return PlantillaItem(
            plantillaId = 0,
            alimentoId = alimento.id,
            gramos = resultado.cantidadBase,
            cantidad = cantidad,
            unidad = unidadConsumidaPara(alimento)
        )
    }

    private fun cantidadPlantillaConFallback(item: PlantillaItem): Float {
        return if (item.cantidad > 0f) item.cantidad else item.gramos
    }

    private fun unidadConsumidaPara(alimento: Alimento): String {
        return when (alimento.tipoMedicionNormalizado()) {
            TipoMedicionAlimento.ML -> UnidadConsumoAlimento.ML
            TipoMedicionAlimento.UNIDAD -> UnidadConsumoAlimento.UNIDAD
            else -> UnidadConsumoAlimento.GRAMOS
        }
    }

    private fun defaultUnidadBase(alimento: Alimento): String {
        return if (alimento.tipoMedicionNormalizado() == TipoMedicionAlimento.ML ||
            (alimento.tipoMedicionNormalizado() == TipoMedicionAlimento.UNIDAD &&
                alimento.estadoFisicoNormalizado() == EstadoFisicoAlimento.LIQUIDO)
        ) {
            UnidadConsumoAlimento.ML
        } else {
            UnidadConsumoAlimento.GRAMOS
        }
    }

    private fun parseDecimal(value: String): Float? {
        return value.trim().replace(',', '.').toFloatOrNull()
    }

    private fun formatCantidad(value: Float): String {
        return if (value % 1f == 0f) {
            String.format(Locale.getDefault(), "%.0f", value)
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    }

    private suspend fun buildObjetivoAlert(): String? {
        val profile = cachedProfile ?: return null
        val start = DateUtils.getStartOfToday()
        val end = DateUtils.getEndOfToday()

        val hidratosTotales = registroRepository.sumHidratosInRange(start, end)
        val racionesTotales = registroRepository.sumRacionesInRange(start, end)
        val insulinaTotal = registroRepository.sumInsulinaInRange(start, end)

        val mensajes = mutableListOf<String>()
        profile.objetivoHidratosDia?.takeIf { it > 0f }?.let { objetivo ->
            if (hidratosTotales > objetivo) {
                mensajes.add("Hidratos diarios superados (${format1(hidratosTotales)} g / ${format1(objetivo)} g)")
            }
        }
        profile.objetivoRacionesDia?.takeIf { it > 0f }?.let { objetivo ->
            if (racionesTotales > objetivo) {
                mensajes.add("Raciones diarias superadas (${format1(racionesTotales)} / ${format1(objetivo)})")
            }
        }
        profile.objetivoInsulinaDia?.takeIf { it > 0f }?.let { objetivo ->
            if (insulinaTotal > objetivo) {
                mensajes.add("Insulina diaria superada (${format1(insulinaTotal)} U / ${format1(objetivo)} U)")
            }
        }

        return if (mensajes.isEmpty()) null else mensajes.joinToString(" · ")
    }

    private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

    private fun scheduleGlucosa2h(registroId: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<Glucosa2hWorker>()
            .setInitialDelay(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(Glucosa2hWorker.KEY_REGISTRO_ID to registroId))
            .build()

        workManager.enqueueUniqueWork(
            "glucosa_2h_$registroId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private suspend fun scheduleNightscoutRetry() {
        val pending = pendingGlucoseRepository.getAll()
        if (pending.isEmpty()) return
        val maxAttempts = pending.maxOfOrNull { it.attempts } ?: 0
        val delayMinutes = NightscoutRetryPolicy.nextDelayMinutes(maxAttempts)
        NightscoutRetryWorker.enqueue(workManager, delayMinutes)
    }

    override fun onCleared() {
        activeInsulinTickerJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val usuarioRepository: UsuarioProfileRepository,
        private val alimentoRepository: AlimentoRepository,
        private val registroRepository: RegistroComidaRepository,
        private val nightscoutRepository: NightscoutRepository,
        private val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository,
        private val plantillaRepository: PlantillaRepository,
        private val pendingGlucoseRepository: PendingGlucoseRepository,
        private val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository,
        private val registroLibreviewSyncRepository: RegistroLibreviewSyncRepository,
        private val workManager: WorkManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NuevaComidaViewModel::class.java)) {
                return NuevaComidaViewModel(
                    usuarioRepository,
                    alimentoRepository,
                    registroRepository,
                    nightscoutRepository,
                    nightscoutTreatmentTombstoneRepository,
                    plantillaRepository,
                    pendingGlucoseRepository,
                    registroNightscoutSyncRepository,
                    registroLibreviewSyncRepository,
                    workManager
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
