package com.diabetes.calculator.ui.screens.alimentos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.EstadoFisicoAlimento
import com.diabetes.calculator.data.entity.TipoMedicionAlimento
import com.diabetes.calculator.data.entity.estadoFisicoNormalizado
import com.diabetes.calculator.data.entity.tipoMedicionNormalizado
import com.diabetes.calculator.data.repository.AlimentoRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

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

    companion object {
        const val UNIDAD_PERSONALIZADA = "__PERSONALIZADO__"
        private const val SEARCH_DEBOUNCE_MS = 180L
        val UNIDADES_RAPIDAS = listOf(
            "pieza",
            "rebanada",
            "vaso",
            "taza",
            "lata",
            "cucharada",
            "cucharadita",
            "porción"
        )
    }

    private val _uiState = MutableStateFlow<AlimentosUiState>(AlimentosUiState.Loading)
    val uiState: StateFlow<AlimentosUiState> = _uiState.asStateFlow()

    private val _selectedAlimentoId = MutableStateFlow<Int?>(null)
    val selectedAlimentoId: StateFlow<Int?> = _selectedAlimentoId.asStateFlow()

    // Campos del diálogo de añadir/editar
    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    private val _editingAlimento = MutableStateFlow<Alimento?>(null)
    val editingAlimento: StateFlow<Alimento?> = _editingAlimento.asStateFlow()

    private val _dialogNombre = MutableStateFlow("")
    val dialogNombre: StateFlow<String> = _dialogNombre.asStateFlow()

    private val _dialogHidratos100g = MutableStateFlow("")
    val dialogHidratos100g: StateFlow<String> = _dialogHidratos100g.asStateFlow()

    private val _dialogHidratos100ml = MutableStateFlow("")
    val dialogHidratos100ml: StateFlow<String> = _dialogHidratos100ml.asStateFlow()

    private val _dialogTipoMedicion = MutableStateFlow(TipoMedicionAlimento.GRAMOS)
    val dialogTipoMedicion: StateFlow<String> = _dialogTipoMedicion.asStateFlow()

    private val _dialogEstadoFisico = MutableStateFlow(EstadoFisicoAlimento.SOLIDO)
    val dialogEstadoFisico: StateFlow<String> = _dialogEstadoFisico.asStateFlow()

    private val _dialogUnidadPreset = MutableStateFlow(UNIDADES_RAPIDAS.first())
    val dialogUnidadPreset: StateFlow<String> = _dialogUnidadPreset.asStateFlow()

    private val _dialogUnidadCustom = MutableStateFlow("")
    val dialogUnidadCustom: StateFlow<String> = _dialogUnidadCustom.asStateFlow()

    private val _dialogGramosPorUnidad = MutableStateFlow("")
    val dialogGramosPorUnidad: StateFlow<String> = _dialogGramosPorUnidad.asStateFlow()

    private val _dialogMlPorUnidad = MutableStateFlow("")
    val dialogMlPorUnidad: StateFlow<String> = _dialogMlPorUnidad.asStateFlow()

    private val _dialogFuente = MutableStateFlow("personal")
    val dialogFuente: StateFlow<String> = _dialogFuente.asStateFlow()

    private val _dialogNota = MutableStateFlow("")
    val dialogNota: StateFlow<String> = _dialogNota.asStateFlow()

    private val _dialogFotoUri = MutableStateFlow("")
    val dialogFotoUri: StateFlow<String> = _dialogFotoUri.asStateFlow()

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
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeAlimentos() {
        viewModelScope.launch {
            _searchQuery
                .map { it.trim() }
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
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

    fun openDetail(alimento: Alimento) {
        _selectedAlimentoId.value = alimento.id
    }

    fun closeDetail() {
        _selectedAlimentoId.value = null
    }

    /**
     * Abre el diálogo para añadir nuevo alimento.
     */
    fun openAddDialog() {
        _editingAlimento.value = null
        _dialogNombre.value = ""
        _dialogHidratos100g.value = ""
        _dialogHidratos100ml.value = ""
        _dialogTipoMedicion.value = TipoMedicionAlimento.GRAMOS
        _dialogEstadoFisico.value = EstadoFisicoAlimento.SOLIDO
        _dialogUnidadPreset.value = UNIDADES_RAPIDAS.first()
        _dialogUnidadCustom.value = ""
        _dialogGramosPorUnidad.value = ""
        _dialogMlPorUnidad.value = ""
        _dialogFuente.value = "personal"
        _dialogNota.value = ""
        _dialogFotoUri.value = ""
        _showDialog.value = true
    }

    /**
     * Abre el diálogo para editar alimento existente.
     */
    fun openEditDialog(alimento: Alimento) {
        _editingAlimento.value = alimento
        _dialogNombre.value = alimento.nombre
        _dialogHidratos100g.value = if (alimento.hidratosPor100g % 1f == 0f) {
            String.format(Locale.getDefault(), "%.0f", alimento.hidratosPor100g)
        } else {
            String.format(Locale.getDefault(), "%.1f", alimento.hidratosPor100g)
        }
        _dialogHidratos100ml.value = alimento.hidratosPor100ml?.let {
            if (it % 1f == 0f) String.format(Locale.getDefault(), "%.0f", it) else String.format(Locale.getDefault(), "%.1f", it)
        } ?: ""
        _dialogTipoMedicion.value = alimento.tipoMedicionNormalizado()
        _dialogEstadoFisico.value = alimento.estadoFisicoNormalizado()
        val unidad = alimento.unidadNombre?.trim().orEmpty()
        if (unidad.isNotBlank() && UNIDADES_RAPIDAS.contains(unidad)) {
            _dialogUnidadPreset.value = unidad
            _dialogUnidadCustom.value = ""
        } else if (unidad.isNotBlank()) {
            _dialogUnidadPreset.value = UNIDAD_PERSONALIZADA
            _dialogUnidadCustom.value = unidad
        } else {
            _dialogUnidadPreset.value = UNIDADES_RAPIDAS.first()
            _dialogUnidadCustom.value = ""
        }
        _dialogGramosPorUnidad.value = alimento.gramosPorUnidad?.let {
            if (it % 1f == 0f) String.format(Locale.getDefault(), "%.0f", it) else String.format(Locale.getDefault(), "%.1f", it)
        } ?: ""
        _dialogMlPorUnidad.value = alimento.mlPorUnidad?.let {
            if (it % 1f == 0f) String.format(Locale.getDefault(), "%.0f", it) else String.format(Locale.getDefault(), "%.1f", it)
        } ?: ""
        _dialogFuente.value = alimento.fuente
        _dialogNota.value = alimento.nota ?: ""
        _dialogFotoUri.value = alimento.fotoUri ?: ""
        _showDialog.value = true
    }

    fun closeDialog() {
        _showDialog.value = false
        _editingAlimento.value = null
    }

    fun updateDialogNombre(value: String) {
        _dialogNombre.value = value
    }

    fun updateDialogHidratos100g(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _dialogHidratos100g.value = value
        }
    }

    fun updateDialogHidratos100ml(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _dialogHidratos100ml.value = value
        }
    }

    fun updateDialogTipoMedicion(value: String) {
        _dialogTipoMedicion.value = if (TipoMedicionAlimento.all.contains(value)) {
            value
        } else {
            TipoMedicionAlimento.GRAMOS
        }
    }

    fun updateDialogEstadoFisico(value: String) {
        _dialogEstadoFisico.value = if (EstadoFisicoAlimento.all.contains(value)) {
            value
        } else {
            EstadoFisicoAlimento.SOLIDO
        }
    }

    fun updateDialogUnidadPreset(value: String) {
        _dialogUnidadPreset.value = value
    }

    fun updateDialogUnidadCustom(value: String) {
        _dialogUnidadCustom.value = value
    }

    fun updateDialogGramosPorUnidad(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _dialogGramosPorUnidad.value = value
        }
    }

    fun updateDialogMlPorUnidad(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _dialogMlPorUnidad.value = value
        }
    }

    fun updateDialogFuente(value: String) {
        _dialogFuente.value = value
    }

    fun updateDialogNota(value: String) {
        _dialogNota.value = value
    }

    fun updateDialogFotoUri(value: String?) {
        _dialogFotoUri.value = value?.trim().orEmpty()
    }

    fun emitMessage(message: String) {
        _uiEvents.tryEmit(message)
    }

    fun canSaveDialog(): Boolean {
        val nombre = _dialogNombre.value.trim()
        if (nombre.isBlank()) return false

        val tipo = _dialogTipoMedicion.value
        val estado = _dialogEstadoFisico.value
        val hc100g = parseDecimal(_dialogHidratos100g.value)
        val hc100ml = parseDecimal(_dialogHidratos100ml.value)
        val unidad = resolvedDialogUnidadNombre()
        val gramosPorUnidad = parseDecimal(_dialogGramosPorUnidad.value)
        val mlPorUnidad = parseDecimal(_dialogMlPorUnidad.value)

        return when (tipo) {
            TipoMedicionAlimento.GRAMOS -> (hc100g ?: -1f) >= 0f
            TipoMedicionAlimento.ML -> (hc100ml ?: -1f) >= 0f
            TipoMedicionAlimento.UNIDAD -> {
                if (estado == EstadoFisicoAlimento.LIQUIDO) {
                    unidad.isNotBlank() && (mlPorUnidad ?: 0f) > 0f && (hc100ml ?: -1f) >= 0f
                } else {
                    unidad.isNotBlank() && (gramosPorUnidad ?: 0f) > 0f && (hc100g ?: -1f) >= 0f
                }
            }

            else -> false
        }
    }

    /**
     * Guarda el alimento (nuevo o editado).
     */
    fun saveAlimento() {
        if (!canSaveDialog()) {
            _uiEvents.tryEmit("Completa los campos del alimento correctamente")
            return
        }

        val nombre = _dialogNombre.value.trim()
        val fuente = _dialogFuente.value.trim()
        val nota = _dialogNota.value.trim().ifEmpty { null }
        val fotoUri = _dialogFotoUri.value.trim().ifEmpty { null }
        val tipo = _dialogTipoMedicion.value
        val estado = _dialogEstadoFisico.value
        val hc100g = parseDecimal(_dialogHidratos100g.value)
        val hc100ml = parseDecimal(_dialogHidratos100ml.value)
        val unidadNombre = if (tipo == TipoMedicionAlimento.UNIDAD) {
            resolvedDialogUnidadNombre().ifBlank { null }
        } else {
            null
        }
        val gramosPorUnidad = if (tipo == TipoMedicionAlimento.UNIDAD && estado != EstadoFisicoAlimento.LIQUIDO) {
            parseDecimal(_dialogGramosPorUnidad.value)
        } else {
            null
        }
        val mlPorUnidad = if (tipo == TipoMedicionAlimento.UNIDAD && estado == EstadoFisicoAlimento.LIQUIDO) {
            parseDecimal(_dialogMlPorUnidad.value)
        } else {
            null
        }

        viewModelScope.launch {
            val existing = _editingAlimento.value
            val alimento = Alimento(
                id = existing?.id ?: 0,
                nombre = nombre,
                hidratosPor100g = (hc100g ?: existing?.hidratosPor100g ?: 0f),
                fuente = fuente,
                nota = nota,
                tipoMedicionPrincipal = tipo,
                estadoFisico = estado,
                hidratosPor100ml = hc100ml ?: existing?.hidratosPor100ml,
                unidadNombre = unidadNombre,
                gramosPorUnidad = gramosPorUnidad,
                mlPorUnidad = mlPorUnidad,
                fotoUri = fotoUri
            )
            if (existing != null) {
                repository.update(alimento)
            } else {
                repository.insert(alimento)
            }
            closeDialog()
        }
    }

    private fun resolvedDialogUnidadNombre(): String {
        val preset = _dialogUnidadPreset.value
        return if (preset == UNIDAD_PERSONALIZADA) {
            _dialogUnidadCustom.value.trim()
        } else {
            preset.trim()
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
                if (_selectedAlimentoId.value == alimento.id) {
                    _selectedAlimentoId.value = null
                }
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
