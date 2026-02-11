package com.diabetes.calculator.ui.screens.alimentos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.repository.AlimentoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Estados posibles de la pantalla de alimentos.
 */
sealed class AlimentosUiState {
    object Loading : AlimentosUiState()
    object Empty : AlimentosUiState()
    data class Success(val alimentos: List<Alimento>) : AlimentosUiState()
    data class Error(val message: String) : AlimentosUiState()
}

/**
 * ViewModel para la pantalla de listado de alimentos.
 */
class AlimentosViewModel(
    private val repository: AlimentoRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<AlimentosUiState>(AlimentosUiState.Loading)
    val uiState: StateFlow<AlimentosUiState> = _uiState.asStateFlow()
    
    // Campos del diálogo de añadir/editar
    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()
    
    private val _editingAlimento = MutableStateFlow<Alimento?>(null)
    val editingAlimento: StateFlow<Alimento?> = _editingAlimento.asStateFlow()
    
    private val _dialogNombre = MutableStateFlow("")
    val dialogNombre: StateFlow<String> = _dialogNombre.asStateFlow()
    
    private val _dialogHidratos = MutableStateFlow("")
    val dialogHidratos: StateFlow<String> = _dialogHidratos.asStateFlow()
    
    private val _dialogFuente = MutableStateFlow("personal")
    val dialogFuente: StateFlow<String> = _dialogFuente.asStateFlow()

    private val _dialogNota = MutableStateFlow("")
    val dialogNota: StateFlow<String> = _dialogNota.asStateFlow()
    
    // Estado de búsqueda
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()
    
    init {
        observeAlimentos()
    }
    
    /**
     * Observa alimentos y filtros de búsqueda con un único collector.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAlimentos() {
        viewModelScope.launch {
            _searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        repository.alimentos
                    } else {
                        repository.searchByName(query)
                    }
                }
                .collect { list ->
                    _uiState.value = if (list.isEmpty()) {
                        AlimentosUiState.Empty
                    } else {
                        AlimentosUiState.Success(list)
                    }
                }
        }
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    /**
     * Abre el diálogo para añadir nuevo alimento.
     */
    fun openAddDialog() {
        _editingAlimento.value = null
        _dialogNombre.value = ""
        _dialogHidratos.value = ""
        _dialogFuente.value = "personal"
        _dialogNota.value = ""
        _showDialog.value = true
    }
    
    /**
     * Abre el diálogo para editar alimento existente.
     */
    fun openEditDialog(alimento: Alimento) {
        _editingAlimento.value = alimento
        _dialogNombre.value = alimento.nombre
        _dialogHidratos.value = alimento.hidratosPor100g.toString()
        _dialogFuente.value = alimento.fuente
        _dialogNota.value = alimento.nota ?: ""
        _showDialog.value = true
    }
    
    fun closeDialog() {
        _showDialog.value = false
        _editingAlimento.value = null
    }
    
    fun updateDialogNombre(value: String) {
        _dialogNombre.value = value
    }
    
    fun updateDialogHidratos(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _dialogHidratos.value = value
        }
    }
    
    fun updateDialogFuente(value: String) {
        _dialogFuente.value = value
    }

    fun updateDialogNota(value: String) {
        _dialogNota.value = value
    }
    
    /**
     * Guarda el alimento (nuevo o editado).
     */
    fun saveAlimento() {
        val nombre = _dialogNombre.value.trim()
        val hidratos = parseDecimal(_dialogHidratos.value)
        val fuente = _dialogFuente.value.trim()
        val nota = _dialogNota.value.trim().ifEmpty { null }
        
        if (nombre.isEmpty() || hidratos == null || hidratos < 0) {
            _uiEvents.tryEmit("Completa los campos del alimento correctamente")
            return
        }
        
        viewModelScope.launch {
            val existing = _editingAlimento.value
            if (existing != null) {
                // Actualizar existente
                repository.update(existing.copy(
                    nombre = nombre,
                    hidratosPor100g = hidratos,
                    fuente = fuente,
                    nota = nota
                ))
            } else {
                // Crear nuevo
                repository.insert(Alimento(
                    nombre = nombre,
                    hidratosPor100g = hidratos,
                    fuente = fuente,
                    nota = nota
                ))
            }
            closeDialog()
        }
    }

    private fun parseDecimal(value: String): Float? {
        return value.trim().replace(',', '.').toFloatOrNull()
    }
    
    /**
     * Elimina un alimento.
     */
    fun deleteAlimento(alimento: Alimento) {
        viewModelScope.launch {
            try {
                repository.delete(alimento)
            } catch (e: Exception) {
                val isForeignKeyError = e.message?.contains("FOREIGN KEY", ignoreCase = true) == true
                val message = if (isForeignKeyError) {
                    "No se puede eliminar: el alimento está en registros guardados"
                } else {
                    "No se pudo eliminar el alimento"
                }
                _uiEvents.tryEmit(message)
            }
        }
    }
    
    /**
     * Factory para crear el ViewModel con dependencias.
     */
    class Factory(private val repository: AlimentoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AlimentosViewModel::class.java)) {
                return AlimentosViewModel(repository) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
