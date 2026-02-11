package com.diabetes.calculator.ui.screens.historial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
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
    private val plantillaRepository: PlantillaRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<HistorialUiState>(HistorialUiState.Loading)
    val uiState: StateFlow<HistorialUiState> = _uiState.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dayFilter = MutableStateFlow(DayFilter.ALL)
    val dayFilter: StateFlow<DayFilter> = _dayFilter.asStateFlow()

    private val _doseStatusFilter = MutableStateFlow(DoseStatusFilter.ALL)
    val doseStatusFilter: StateFlow<DoseStatusFilter> = _doseStatusFilter.asStateFlow()
    
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
            repository.deleteById(id)
        }
    }

    fun updateDoseStatus(registroId: Int, status: EstadoDosis) {
        viewModelScope.launch {
            repository.updateDosisEstado(registroId, status)
        }
    }

    fun updateDoseCorrection(registroId: Int, conCorreccion: Boolean?) {
        viewModelScope.launch {
            repository.updateDosisCorreccion(registroId, conCorreccion)
        }
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
                gramos = it.item.gramosConsumidos
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
        private val plantillaRepository: PlantillaRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HistorialViewModel::class.java)) {
                return HistorialViewModel(repository, plantillaRepository) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
