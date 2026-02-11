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
import com.diabetes.calculator.data.entity.PendingGlucose
import com.diabetes.calculator.data.entity.PendingGlucoseTipo
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.repository.AlimentoRepository
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.PendingGlucoseRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.util.DateUtils
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.work.NightscoutRetryWorker
import com.diabetes.calculator.work.Recordatorio2hWorker
import com.diabetes.calculator.work.Glucosa2hWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Representa un elemento individual dentro de una comida en construcción.
 */
data class ItemComidaTemporal(
    val id: Long = System.nanoTime(),
    val alimento: Alimento? = null,
    val gramosStr: String = "",
    val hidratos: Float = 0f
)

/**
 * Estados posibles de la pantalla de nueva comida.
 */
sealed class NuevaComidaUiState {
    object Loading : NuevaComidaUiState()
    object NoProfile : NuevaComidaUiState()
    data class Ready(
        val alimentos: List<Alimento>, // Estos son los filtrados para el selector
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
    val unidadesCorreccion: Float = 0f,
    val unidadesInsulina: Float = 0f,
    val glucosaUsadaMgdl: Int? = null
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
    private val workManager: WorkManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<NuevaComidaUiState>(NuevaComidaUiState.Loading)
    val uiState: StateFlow<NuevaComidaUiState> = _uiState.asStateFlow()
    
    // Lista de alimentos en la comida actual
    private val _items = MutableStateFlow<List<ItemComidaTemporal>>(listOf(ItemComidaTemporal()))
    val items: StateFlow<List<ItemComidaTemporal>> = _items.asStateFlow()
    
    // Búsqueda de alimentos
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val plantillas: StateFlow<List<com.diabetes.calculator.data.dao.PlantillaConItems>> =
        plantillaRepository.plantillas.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )
    
    // Notas generales del registro
    private val _notas = MutableStateFlow("")
    val notas: StateFlow<String> = _notas.asStateFlow()
    
    // Cálculo total
    private val _calculo = MutableStateFlow(CalculoActual())
    val calculo: StateFlow<CalculoActual> = _calculo.asStateFlow()

    private val _glucosaActualMgdl = MutableStateFlow<Int?>(null)
    
    // Estados de UI auxiliares
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    private var cachedProfile: UsuarioProfile? = null
    private var cachedAlimentos: List<Alimento> = emptyList()
    
    init {
        loadData()
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
                    _uiState.value = NuevaComidaUiState.NoProfile
                } else {
                    cachedProfile = profile
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
            ItemComidaTemporal(
                alimento = alimento,
                gramosStr = formatGramos(item.item.gramos),
                hidratos = (alimento.hidratosPor100g * item.item.gramos) / 100f
            )
        }
        if (nuevosItems.isNotEmpty()) {
            _items.value = nuevosItems
            recalculate()
        }
    }

    fun savePlantilla(nombre: String) {
        val itemsValidos = _items.value.filter { it.alimento != null && (parseDecimal(it.gramosStr) ?: 0f) > 0f }
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
            val plantillaItems = itemsValidos.map {
                PlantillaItem(
                    plantillaId = 0,
                    alimentoId = it.alimento!!.id,
                    gramos = parseDecimal(it.gramosStr) ?: 0f
                )
            }
            plantillaRepository.insertPlantilla(cleanName, plantillaItems)
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
            val gramos = parseDecimal(current[index].gramosStr) ?: 0f
            val hidratos = (alimento.hidratosPor100g * gramos) / 100f
            current[index] = current[index].copy(alimento = alimento, hidratos = hidratos)
            _items.value = current
            recalculate()
        }
    }
    
    fun updateItemGramos(item: ItemComidaTemporal, gramosStr: String) {
        if (gramosStr.isNotEmpty() && !gramosStr.matches(Regex("^\\d*([\\.,]\\d*)?$"))) return
        
        val current = _items.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index != -1) {
            val alimento = current[index].alimento
            val gramos = parseDecimal(gramosStr) ?: 0f
            val hidratos = if (alimento != null) (alimento.hidratosPor100g * gramos) / 100f else 0f
            current[index] = current[index].copy(gramosStr = gramosStr, hidratos = hidratos)
            _items.value = current
            recalculate()
        }
    }
    
    private fun recalculate() {
        val profile = cachedProfile ?: return
        val totalHidratos = _items.value.sumOf { it.hidratos.toDouble() }.toFloat()
        _calculo.value = buildCalculo(
            profile = profile,
            totalHidratos = totalHidratos,
            glucosaMgdl = _glucosaActualMgdl.value
        )
    }
    
    fun canSave(): Boolean {
        val profile = cachedProfile ?: return false
        if (profile.gramosPorRacion <= 0f || profile.ratioInsulina <= 0f) {
            return false
        }
        return _items.value.any { it.alimento != null && (parseDecimal(it.gramosStr) ?: 0f) > 0f }
    }
    
    fun saveRegistro() {
        val profile = cachedProfile ?: return
        val validItems = _items.value.filter { it.alimento != null && (parseDecimal(it.gramosStr) ?: 0f) > 0f }

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

                val totalHidratos = _items.value.sumOf { it.hidratos.toDouble() }.toFloat()
                val calc = buildCalculo(
                    profile = profile,
                    totalHidratos = totalHidratos,
                    glucosaMgdl = glucosaAntes ?: _glucosaActualMgdl.value
                )
                if (calc.hidratosTotales.isNaN() || calc.hidratosTotales.isInfinite() ||
                    calc.raciones.isNaN() || calc.raciones.isInfinite() ||
                    calc.unidadesInsulina.isNaN() || calc.unidadesInsulina.isInfinite()
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
                    unidadesInsulina = calc.unidadesInsulina,
                    ratioInsulinaHc = ratioInsulinaHc,
                    notas = _notas.value.trim().ifEmpty { null },
                    glucosaAntesMgdl = glucosaAntes
                )
                
                val itemsEntities = validItems.map {
                    val gramos = parseDecimal(it.gramosStr) ?: 0f
                    AlimentoEnRegistro(
                        registroId = 0, // Se asigna en el repositorio/DAO
                        alimentoId = it.alimento!!.id,
                        gramosConsumidos = gramos,
                        hidratosCalculados = it.hidratos
                    )
                }
                
                val registroId = registroRepository.insertRegistroCompleto(registro, itemsEntities)
                if (nightscoutEnabled) {
                    scheduleGlucosa2h(registroId)
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
        val unidadesCorreccion = calculateCorrectionUnits(profile, glucosaMgdl)
        val totalSinRedondear = (unidadesComida + unidadesCorreccion).coerceAtLeast(0f)
        val unidadesInsulina = Math.round(totalSinRedondear * 2f) / 2f

        return CalculoActual(
            hidratosTotales = totalHidratos,
            raciones = raciones,
            unidadesComida = unidadesComida,
            unidadesCorreccion = unidadesCorreccion,
            unidadesInsulina = unidadesInsulina,
            glucosaUsadaMgdl = glucosaMgdl
        )
    }

    private fun calculateCorrectionUnits(profile: UsuarioProfile, glucosaMgdl: Int?): Float {
        if (glucosaMgdl == null) return 0f
        val objetivo = profile.glucosaObjetivoMgdl ?: return 0f
        val factor = profile.factorCorreccionMgdlPorU ?: return 0f
        if (objetivo <= 0 || factor <= 0f) return 0f
        return (glucosaMgdl - objetivo) / factor
    }
    
    private fun resetForm() {
        _items.value = listOf(ItemComidaTemporal())
        _notas.value = ""
        _calculo.value = CalculoActual()
        _searchQuery.value = ""
    }
    
    fun resetSaveSuccess() {
        _saveSuccess.value = false
    }

    private fun parseDecimal(value: String): Float? {
        return value.trim().replace(',', '.').toFloatOrNull()
    }

    private fun formatGramos(value: Float): String {
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
    
    class Factory(
        private val usuarioRepository: UsuarioProfileRepository,
        private val alimentoRepository: AlimentoRepository,
        private val registroRepository: RegistroComidaRepository,
        private val nightscoutRepository: NightscoutRepository,
        private val plantillaRepository: PlantillaRepository,
        private val pendingGlucoseRepository: PendingGlucoseRepository,
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
                    workManager
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
