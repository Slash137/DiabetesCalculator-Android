package com.diabetes.calculator.ui.screens.historial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.entity.UnidadConsumoAlimento
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Estados posibles de la pantalla de historial.
 */
sealed class HistorialUiState {
    object Loading : HistorialUiState()
    object Empty : HistorialUiState()
    data class Success(val registros: List<RegistroComidaConItems>) : HistorialUiState()
    data class Error(val message: String) : HistorialUiState()
}

enum class DayFilter(val label: String) {
    ALL("Todos"),
    TODAY("Hoy"),
    YESTERDAY("Ayer"),
    LAST_7_DAYS("7 días"),
    LAST_30_DAYS("30 días")
}

enum class DoseStatusFilter(
    val label: String,
    val value: EstadoDosis?
) {
    ALL("Todas", null),
    PENDING("Pendiente", EstadoDosis.PENDIENTE),
    APPLIED("Aplicada", EstadoDosis.APLICADA),
    SKIPPED("No aplicada", EstadoDosis.OMITIDA)
}

/**
 * ViewModel para la pantalla de historial.
 */
class HistorialViewModel(
    private val repository: RegistroComidaRepository,
    private val plantillaRepository: PlantillaRepository,
    private val usuarioRepository: UsuarioProfileRepository,
    private val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository,
    private val nightscoutRepository: NightscoutRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<HistorialUiState>(HistorialUiState.Loading)
    val uiState: StateFlow<HistorialUiState> = _uiState.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dayFilter = MutableStateFlow(DayFilter.ALL)
    val dayFilter: StateFlow<DayFilter> = _dayFilter.asStateFlow()

    private val _doseStatusFilter = MutableStateFlow(DoseStatusFilter.ALL)
    val doseStatusFilter: StateFlow<DoseStatusFilter> = _doseStatusFilter.asStateFlow()

    val factorCorreccionFallback: StateFlow<Float?> = usuarioRepository.profile
        .map { profile ->
            profile?.factorCorreccionMgdlPorU?.takeIf { it > 0f && !it.isNaN() }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null
        )
    
    init {
        observeRegistros()
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRegistros() {
        viewModelScope.launch {
            combine(_searchQuery, _dayFilter, _doseStatusFilter) { query, dayFilter, doseFilter ->
                Triple(query, dayFilter, doseFilter)
            }.flatMapLatest { (query, dayFilter, doseFilter) ->
                val flow = if (query.isBlank()) {
                    repository.allRegistros
                } else {
                    repository.searchRegistros(query)
                }
                flow.map { list ->
                    applyDoseStatusFilter(
                        applyDayFilter(list, dayFilter),
                        doseFilter
                    )
                }
            }.collect { list ->
                val isAllFilter = _dayFilter.value == DayFilter.ALL
                val isAllDoseFilter = _doseStatusFilter.value == DoseStatusFilter.ALL
                _uiState.value = if (list.isEmpty()) {
                    if (_searchQuery.value.isBlank() && isAllFilter && isAllDoseFilter) {
                        HistorialUiState.Empty
                    } else {
                        HistorialUiState.Success(emptyList())
                    }
                } else {
                    HistorialUiState.Success(list)
                }
            }
        }
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateDayFilter(filter: DayFilter) {
        _dayFilter.value = filter
    }

    fun updateDoseStatusFilter(filter: DoseStatusFilter) {
        _doseStatusFilter.value = filter
    }
    
    fun deleteRegistro(id: Int) {
        viewModelScope.launch {
            val registro = repository.getRegistroRawById(id)
            val treatmentId = registro?.nightscoutTreatmentId
            if (!treatmentId.isNullOrBlank()) {
                nightscoutTreatmentTombstoneRepository.add(treatmentId)
            }
            repository.deleteById(id)
        }
    }

    fun updateDoseStatus(registroId: Int, status: EstadoDosis) {
        viewModelScope.launch {
            if (status == EstadoDosis.OMITIDA) {
                val registro = repository.getRegistroRawById(registroId)
                val treatmentId = registro?.nightscoutTreatmentId
                if (!treatmentId.isNullOrBlank()) {
                    nightscoutTreatmentTombstoneRepository.add(treatmentId)
                }
            }
            repository.updateDosisEstado(registroId, status)
            if (status == EstadoDosis.OMITIDA) {
                repository.clearNightscoutLink(registroId)
            }
        }
    }

    fun updateDoseCorrection(registroId: Int, conCorreccion: Boolean?) {
        viewModelScope.launch {
            repository.updateDosisCorreccion(registroId, conCorreccion)
        }
    }

    fun updateDoseForLink(registroId: Int, unidades: Float, confirmadaAt: Long?) {
        viewModelScope.launch {
            repository.updateDoseForLink(registroId, unidades, confirmadaAt)
        }
    }

    suspend fun getAdjustedGlucoseForTimes(confirmadaAt: Long): Pair<Int?, Int?> {
        val profile = usuarioRepository.getProfileSync() ?: return null to null
        val url = profile.nightscoutUrl?.trim().orEmpty()
        if (url.isBlank()) return null to null

        val token = profile.nightscoutToken
        val antes = nightscoutRepository.getGlucoseClosestTo(
            baseUrl = url,
            token = token,
            targetMillis = confirmadaAt,
            toleranceMinutes = 20
        )?.sgv
        val despues = nightscoutRepository.getGlucoseClosestTo(
            baseUrl = url,
            token = token,
            targetMillis = confirmadaAt + (2 * 60 * 60 * 1000L),
            toleranceMinutes = 30
        )?.sgv
        return antes to despues
    }

    suspend fun hydrateNightscoutImportGlucose(
        registro: RegistroComidaConItems
    ): Pair<Int?, Int?> {
        if (OrigenRegistro.fromValue(registro.registro.origenRegistro) != OrigenRegistro.NIGHTSCOUT_IMPORT) {
            return registro.registro.glucosaAntesMgdl to registro.registro.glucosaDespues2hMgdl
        }
        if (registro.registro.glucosaAntesMgdl != null && registro.registro.glucosaDespues2hMgdl != null) {
            return registro.registro.glucosaAntesMgdl to registro.registro.glucosaDespues2hMgdl
        }

        val profile = usuarioRepository.getProfileSync()
            ?: return registro.registro.glucosaAntesMgdl to registro.registro.glucosaDespues2hMgdl
        val url = profile.nightscoutUrl?.trim().orEmpty()
        if (url.isBlank()) {
            return registro.registro.glucosaAntesMgdl to registro.registro.glucosaDespues2hMgdl
        }

        val token = profile.nightscoutToken
        val doseMillis = registro.registro.fecha
        var before = registro.registro.glucosaAntesMgdl
        var after = registro.registro.glucosaDespues2hMgdl

        if (before == null) {
            before = nightscoutRepository.getGlucoseClosestTo(
                baseUrl = url,
                token = token,
                targetMillis = doseMillis,
                toleranceMinutes = 20
            )?.sgv
            if (before != null) {
                repository.updateGlucosaAntes(registro.registro.id, before)
            }
        }

        if (after == null) {
            after = nightscoutRepository.getGlucoseClosestTo(
                baseUrl = url,
                token = token,
                targetMillis = doseMillis + (2 * 60 * 60 * 1000L),
                toleranceMinutes = 30
            )?.sgv
            if (after != null) {
                repository.updateGlucosaDespues2h(registro.registro.id, after)
            }
        }

        return before to after
    }

    fun createPlantillaFromRegistro(
        registro: RegistroComidaConItems,
        nombre: String
    ) {
        val cleanName = nombre.trim()
        if (cleanName.isBlank()) return

        val items = registro.items.map {
            PlantillaItem(
                plantillaId = 0,
                alimentoId = it.item.alimentoId,
                gramos = it.item.gramosConsumidos,
                cantidad = if (it.item.cantidadConsumida > 0f) it.item.cantidadConsumida else it.item.gramosConsumidos,
                unidad = it.item.unidadConsumida.ifBlank { UnidadConsumoAlimento.GRAMOS }
            )
        }
        if (items.isEmpty()) return

        viewModelScope.launch {
            plantillaRepository.insertPlantilla(cleanName, items)
        }
    }

    private fun applyDayFilter(
        list: List<RegistroComidaConItems>,
        filter: DayFilter
    ): List<RegistroComidaConItems> {
        if (filter == DayFilter.ALL) return list

        val dayMs = 24 * 60 * 60 * 1000L
        val startOfToday = com.diabetes.calculator.util.DateUtils.getStartOfToday()
        val endOfToday = com.diabetes.calculator.util.DateUtils.getEndOfToday()

        val (start, end) = when (filter) {
            DayFilter.TODAY -> startOfToday to endOfToday
            DayFilter.YESTERDAY -> (startOfToday - dayMs) to (startOfToday - 1)
            DayFilter.LAST_7_DAYS -> (startOfToday - (6 * dayMs)) to endOfToday
            DayFilter.LAST_30_DAYS -> (startOfToday - (29 * dayMs)) to endOfToday
            DayFilter.ALL -> Long.MIN_VALUE to Long.MAX_VALUE
        }

        return list.filter { it.registro.fecha in start..end }
    }

    private fun applyDoseStatusFilter(
        list: List<RegistroComidaConItems>,
        filter: DoseStatusFilter
    ): List<RegistroComidaConItems> {
        val target = filter.value ?: return list
        return list.filter { EstadoDosis.fromValue(it.registro.dosisEstado) == target }
    }
    
    class Factory(
        private val repository: RegistroComidaRepository,
        private val plantillaRepository: PlantillaRepository,
        private val usuarioRepository: UsuarioProfileRepository,
        private val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository,
        private val nightscoutRepository: NightscoutRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HistorialViewModel::class.java)) {
                return HistorialViewModel(
                    repository,
                    plantillaRepository,
                    usuarioRepository,
                    nightscoutTreatmentTombstoneRepository,
                    nightscoutRepository
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
