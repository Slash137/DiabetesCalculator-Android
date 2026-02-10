package com.diabetes.calculator.ui.screens.historial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.dao.RegistroComidaConItems
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

/**
 * ViewModel para la pantalla de historial.
 */
class HistorialViewModel(
    private val repository: RegistroComidaRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<HistorialUiState>(HistorialUiState.Loading)
    val uiState: StateFlow<HistorialUiState> = _uiState.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dayFilter = MutableStateFlow(DayFilter.ALL)
    val dayFilter: StateFlow<DayFilter> = _dayFilter.asStateFlow()
    
    init {
        observeRegistros()
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRegistros() {
        viewModelScope.launch {
            combine(_searchQuery, _dayFilter) { query, filter ->
                query to filter
            }.flatMapLatest { (query, filter) ->
                val flow = if (query.isBlank()) {
                    repository.allRegistros
                } else {
                    repository.searchRegistros(query)
                }
                flow.map { list -> applyDayFilter(list, filter) }
            }.collect { list ->
                val isAllFilter = _dayFilter.value == DayFilter.ALL
                _uiState.value = if (list.isEmpty()) {
                    if (_searchQuery.value.isBlank() && isAllFilter) {
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
    
    fun deleteRegistro(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
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
    
    class Factory(private val repository: RegistroComidaRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HistorialViewModel::class.java)) {
                return HistorialViewModel(repository) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
