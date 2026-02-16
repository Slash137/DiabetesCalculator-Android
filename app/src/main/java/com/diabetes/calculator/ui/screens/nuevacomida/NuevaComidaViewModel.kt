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
import com.diabetes.calculator.data.repository.AlimentoRepository
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.PendingGlucoseRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.domain.FactoresContextoInsulina
import com.diabetes.calculator.domain.FaseCicloHormonal
import com.diabetes.calculator.domain.FranjaHoraria
import com.diabetes.calculator.domain.NivelEjercicio
import com.diabetes.calculator.domain.NivelEnfermedad
import com.diabetes.calculator.domain.NivelEstres
import com.diabetes.calculator.domain.SeleccionContextoInsulina
import com.diabetes.calculator.domain.ActiveInsulinSnapshot
import com.diabetes.calculator.util.DateUtils
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.work.Glucosa2hWorker
import com.diabetes.calculator.work.NightscoutRetryWorker
import com.diabetes.calculator.work.NightscoutSyncWorker
import com.diabetes.calculator.work.Recordatorio2hWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
    val insulinaActivaActual: Float = 0f,
    val unidadesInsulina: Float = 0f,
    val unidadesInsulinaSinCorreccion: Float = 0f,
    val glucosaUsadaMgdl: Int? = null,
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
    private val plantillaRepository: PlantillaRepository,
    private val pendingGlucoseRepository: PendingGlucoseRepository,
    private val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository,
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

    private val _glucosaActualMgdl = MutableStateFlow<Int?>(null)
    private val _dosisConCorreccion = MutableStateFlow(false)
    val dosisConCorreccion: StateFlow<Boolean> = _dosisConCorreccion.asStateFlow()
    private var correctionSelectionEdited = false

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    private val _activeInsulinSnapshot = MutableStateFlow(ActiveInsulinSnapshot())
    val activeInsulinSnapshot: StateFlow<ActiveInsulinSnapshot> = _activeInsulinSnapshot.asStateFlow()

    private val _activeInsulinLoading = MutableStateFlow(true)
    val activeInsulinLoading: StateFlow<Boolean> = _activeInsulinLoading.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiEvents = _uiEvents.asSharedFlow()

    private var cachedProfile: UsuarioProfile? = null
    private var cachedAlimentos: List<Alimento> = emptyList()
    private var contextInitialized = false
    private var activeInsulinTickerJob: Job? = null

    init {
        loadData()
        startActiveInsulinTicker()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                usuarioRepository.profile,
                alimentoRepository.alimentos,
                _searchQuery
            ) { profile, allAlimentos, query ->
                Triple(profile, allAlimentos, query)
            }.collect { (profile, allAlimentos, query) ->
                if (profile == null) {
                    cachedProfile = null
                    contextInitialized = false
                    _uiState.value = NuevaComidaUiState.NoProfile
                } else {
                    val wasNull = cachedProfile == null
                    cachedProfile = profile
                    if (wasNull || !contextInitialized) {
                        resetContextSelections()
                        contextInitialized = true
                    }
                    if (!profile.cicloHormonalActivo && _faseCiclo.value != FaseCicloHormonal.NO_APLICAR) {
                        _faseCiclo.value = FaseCicloHormonal.NO_APLICAR
                    }

                    recalculate()
                    cachedAlimentos = allAlimentos
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
        runCatching {
            registroRepository.getActiveInsulinSnapshot(System.currentTimeMillis())
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

    fun updateGlucosaActual(glucosaMgdl: Int?) {
        if (glucosaMgdl == null) {
            val nightscoutConfigured = !cachedProfile?.nightscoutUrl.isNullOrBlank()
            if (nightscoutConfigured) return
        }
        if (_glucosaActualMgdl.value == glucosaMgdl) return
        _glucosaActualMgdl.value = glucosaMgdl
        recalculate()
    }

    fun updateNotas(notas: String) {
        _notas.value = notas
    }

    fun updateDosisConCorreccion(conCorreccion: Boolean) {
        correctionSelectionEdited = true
        _dosisConCorreccion.value = conCorreccion
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
        val totalHidratos = _items.value.sumOf { it.hidratos.toDouble() }.toFloat()
        val nuevoCalculo = buildCalculo(
            profile = profile,
            totalHidratos = totalHidratos,
            glucosaMgdl = _glucosaActualMgdl.value
        )
        _calculo.value = nuevoCalculo
        if (!correctionSelectionEdited) {
            _dosisConCorreccion.value = profile.aplicarCorreccionPorDefecto && hasRealtimeCorrection(nuevoCalculo)
        }
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
                        ?.sgv
                    glucosaAntes = fetched ?: _glucosaActualMgdl.value
                    if (glucosaAntes == null) {
                        pendingAntes = true
                    }
                }

                val totalHidratos = validItems.sumOf { it.hidratos.toDouble() }.toFloat()
                val calc = buildCalculo(
                    profile = profile,
                    totalHidratos = totalHidratos,
                    glucosaMgdl = glucosaAntes ?: _glucosaActualMgdl.value
                )
                val unidadesSeleccionadas = selectedInsulinUnits(calc, _dosisConCorreccion.value)
                if (calc.hidratosTotales.isNaN() || calc.hidratosTotales.isInfinite() ||
                    calc.raciones.isNaN() || calc.raciones.isInfinite() ||
                    calc.unidadesInsulina.isNaN() || calc.unidadesInsulina.isInfinite() ||
                    calc.unidadesInsulinaSinCorreccion.isNaN() || calc.unidadesInsulinaSinCorreccion.isInfinite() ||
                    unidadesSeleccionadas.isNaN() || unidadesSeleccionadas.isInfinite()
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
                    unidadesInsulina = unidadesSeleccionadas,
                    ratioInsulinaHc = ratioInsulinaHc,
                    notas = _notas.value.trim().ifEmpty { null },
                    glucosaAntesMgdl = glucosaAntes,
                    dosisConCorreccion = _dosisConCorreccion.value,
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
                if (nightscoutEnabled) {
                    scheduleGlucosa2h(registroId)
                }
                if (profile.nightscoutSyncRegistrosActivo && nightscoutEnabled) {
                    registroNightscoutSyncRepository.upsertPending(registroId)
                    NightscoutSyncWorker.enqueueNow(workManager)
                }
                if (profile.recordatorio2hActivo) {
                    scheduleRecordatorio2h(registroId)
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
        glucosaMgdl: Int?
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
        val unidadesCorreccionBruta = calculateCorrectionUnits(profile, glucosaMgdl)
        val insulinaActiva = _activeInsulinSnapshot.value.totalUnits
            .takeIf { it.isFinite() && it > 0f }
            ?: 0f
        val reduccionPorActiva = if (unidadesCorreccionBruta > 0f) {
            kotlin.math.min(unidadesCorreccionBruta, insulinaActiva)
        } else {
            0f
        }
        val unidadesCorreccion = unidadesCorreccionBruta - reduccionPorActiva
        val contexto = FactoresContextoInsulina.resolve(profile, currentSelection(profile))
        val dosisContextual = FactoresContextoInsulina.applyFactorToDoses(
            unidadesComida = unidadesComida,
            unidadesCorreccion = unidadesCorreccion,
            factorTotalAplicado = contexto.factorTotalAplicado
        )

        return CalculoActual(
            hidratosTotales = totalHidratos,
            raciones = raciones,
            unidadesComida = unidadesComida,
            unidadesCorreccionBruta = unidadesCorreccionBruta,
            unidadesCorreccion = unidadesCorreccion,
            unidadesCorreccionReducidaPorActiva = reduccionPorActiva,
            insulinaActivaActual = insulinaActiva,
            unidadesInsulina = dosisContextual.totalConCorreccion,
            unidadesInsulinaSinCorreccion = dosisContextual.totalSinCorreccion,
            glucosaUsadaMgdl = glucosaMgdl,
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

    private fun calculateCorrectionUnits(profile: UsuarioProfile, glucosaMgdl: Int?): Float {
        if (glucosaMgdl == null) return 0f
        val objetivo = profile.glucosaObjetivoMgdl ?: return 0f
        val factor = profile.factorCorreccionMgdlPorU ?: return 0f
        if (objetivo <= 0 || factor <= 0f) return 0f
        return (glucosaMgdl - objetivo) / factor
    }

    private fun hasRealtimeCorrection(calculo: CalculoActual): Boolean {
        return kotlin.math.abs(calculo.unidadesCorreccion) >= 0.05f
    }

    private fun selectedInsulinUnits(calculo: CalculoActual, conCorreccion: Boolean): Float {
        return if (conCorreccion) {
            calculo.unidadesInsulina
        } else {
            calculo.unidadesInsulinaSinCorreccion
        }
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
        _calculo.value = CalculoActual()
        _dosisConCorreccion.value = false
        correctionSelectionEdited = false
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
            String.format("%.0f", value)
        } else {
            String.format("%.1f", value)
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

    private fun format1(value: Float): String = String.format("%.1f", value)

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

    private fun scheduleRecordatorio2h(registroId: Int) {
        val request = OneTimeWorkRequestBuilder<Recordatorio2hWorker>()
            .setInitialDelay(2, TimeUnit.HOURS)
            .setInputData(workDataOf(Recordatorio2hWorker.KEY_REGISTRO_ID to registroId))
            .build()
        workManager.enqueueUniqueWork(
            "recordatorio_2h_$registroId",
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
        private val plantillaRepository: PlantillaRepository,
        private val pendingGlucoseRepository: PendingGlucoseRepository,
        private val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository,
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
                    plantillaRepository,
                    pendingGlucoseRepository,
                    registroNightscoutSyncRepository,
                    workManager
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
