package com.diabetes.calculator.ui.screens.perfil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.util.BackupManager
import com.diabetes.calculator.util.BackupPasswordStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context

/**
 * Estados posibles de la pantalla de perfil.
 */
sealed class PerfilUiState {
    object Loading : PerfilUiState()
    object Empty : PerfilUiState()
    data class Success(val profile: UsuarioProfile) : PerfilUiState()
    data class Error(val message: String) : PerfilUiState()
}

/**
 * ViewModel para la pantalla de configuración de perfil.
 */
class PerfilViewModel(
    private val repository: UsuarioProfileRepository,
    private val backupManager: BackupManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<PerfilUiState>(PerfilUiState.Loading)
    val uiState: StateFlow<PerfilUiState> = _uiState.asStateFlow()
    
    // Campos del formulario
    private val _nombre = MutableStateFlow("")
    val nombre: StateFlow<String> = _nombre.asStateFlow()
    
    private val _gramosPorRacion = MutableStateFlow("")
    val gramosPorRacion: StateFlow<String> = _gramosPorRacion.asStateFlow()
    
    private val _ratioInsulina = MutableStateFlow("")
    val ratioInsulina: StateFlow<String> = _ratioInsulina.asStateFlow()

    private val _objetivoHidratosDia = MutableStateFlow("")
    val objetivoHidratosDia: StateFlow<String> = _objetivoHidratosDia.asStateFlow()

    private val _objetivoRacionesDia = MutableStateFlow("")
    val objetivoRacionesDia: StateFlow<String> = _objetivoRacionesDia.asStateFlow()

    private val _objetivoInsulinaDia = MutableStateFlow("")
    val objetivoInsulinaDia: StateFlow<String> = _objetivoInsulinaDia.asStateFlow()

    private val _glucosaObjetivoMgdl = MutableStateFlow("")
    val glucosaObjetivoMgdl: StateFlow<String> = _glucosaObjetivoMgdl.asStateFlow()

    private val _factorCorreccionMgdlPorU = MutableStateFlow("")
    val factorCorreccionMgdlPorU: StateFlow<String> = _factorCorreccionMgdlPorU.asStateFlow()

    private val _aplicarCorreccionPorDefecto = MutableStateFlow(true)
    val aplicarCorreccionPorDefecto: StateFlow<Boolean> = _aplicarCorreccionPorDefecto.asStateFlow()

    private val _recordatorio2hActivo = MutableStateFlow(false)
    val recordatorio2hActivo: StateFlow<Boolean> = _recordatorio2hActivo.asStateFlow()
    
    private val _nightscoutUrl = MutableStateFlow("")
    val nightscoutUrl: StateFlow<String> = _nightscoutUrl.asStateFlow()
    
    private val _nightscoutToken = MutableStateFlow("")
    val nightscoutToken: StateFlow<String> = _nightscoutToken.asStateFlow()
    
    // Estado de guardado
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()
    
    // Estado de export/import
    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()
    
    private val _isBackupLoading = MutableStateFlow(false)
    val isBackupLoading: StateFlow<Boolean> = _isBackupLoading.asStateFlow()

    private var profileJob: Job? = null
    
    init {
        loadProfile()
    }
    
    /**
     * Carga el perfil existente, si lo hay.
     */
    private fun loadProfile() {
        if (profileJob?.isActive == true) return
        profileJob = viewModelScope.launch {
            repository.profile.collect { profile ->
                if (profile != null) {
                    _nombre.value = profile.nombre
                    _gramosPorRacion.value = profile.gramosPorRacion.toString()
                    _ratioInsulina.value = profile.ratioInsulina.toString()
                    _objetivoHidratosDia.value = profile.objetivoHidratosDia?.toString().orEmpty()
                    _objetivoRacionesDia.value = profile.objetivoRacionesDia?.toString().orEmpty()
                    _objetivoInsulinaDia.value = profile.objetivoInsulinaDia?.toString().orEmpty()
                    _glucosaObjetivoMgdl.value = profile.glucosaObjetivoMgdl?.toString().orEmpty()
                    _factorCorreccionMgdlPorU.value = profile.factorCorreccionMgdlPorU?.toString().orEmpty()
                    _aplicarCorreccionPorDefecto.value = profile.aplicarCorreccionPorDefecto
                    _recordatorio2hActivo.value = profile.recordatorio2hActivo
                    _nightscoutUrl.value = profile.nightscoutUrl ?: ""
                    _nightscoutToken.value = profile.nightscoutToken ?: ""
                    _uiState.value = PerfilUiState.Success(profile)
                } else {
                    // Valores por defecto sugeridos
                    _gramosPorRacion.value = "10"
                    _ratioInsulina.value = "1.0"
                    _objetivoHidratosDia.value = ""
                    _objetivoRacionesDia.value = ""
                    _objetivoInsulinaDia.value = ""
                    _glucosaObjetivoMgdl.value = ""
                    _factorCorreccionMgdlPorU.value = ""
                    _aplicarCorreccionPorDefecto.value = true
                    _recordatorio2hActivo.value = false
                    _uiState.value = PerfilUiState.Empty
                }
            }
        }
    }
    
    fun updateNombre(value: String) {
        _nombre.value = value
    }
    
    fun updateGramosPorRacion(value: String) {
        // Solo permite números y un separador decimal (coma o punto)
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _gramosPorRacion.value = value
        }
    }
    
    fun updateRatioInsulina(value: String) {
        // Solo permite números y un separador decimal (coma o punto)
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _ratioInsulina.value = value
        }
    }

    fun updateObjetivoHidratosDia(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _objetivoHidratosDia.value = value
        }
    }

    fun updateObjetivoRacionesDia(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _objetivoRacionesDia.value = value
        }
    }

    fun updateObjetivoInsulinaDia(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _objetivoInsulinaDia.value = value
        }
    }

    fun updateGlucosaObjetivoMgdl(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
            _glucosaObjetivoMgdl.value = value
        }
    }

    fun updateFactorCorreccionMgdlPorU(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))) {
            _factorCorreccionMgdlPorU.value = value
        }
    }

    fun updateAplicarCorreccionPorDefecto(value: Boolean) {
        _aplicarCorreccionPorDefecto.value = value
    }

    fun updateRecordatorio2hActivo(value: Boolean) {
        _recordatorio2hActivo.value = value
    }
    
    fun updateNightscoutUrl(value: String) {
        _nightscoutUrl.value = value
    }
    
    fun updateNightscoutToken(value: String) {
        _nightscoutToken.value = value
    }
    
    /**
     * Valida los campos del formulario.
     */
    fun validateFields(): Boolean {
        val nombreVal = _nombre.value.trim()
        val gramosVal = parseDecimal(_gramosPorRacion.value)
        val ratioVal = parseDecimal(_ratioInsulina.value)
        
        return nombreVal.isNotEmpty() && 
               gramosVal != null && gramosVal > 0 &&
               ratioVal != null && ratioVal > 0
    }
    
    /**
     * Guarda el perfil en la base de datos.
     */
    fun saveProfile() {
        if (!validateFields()) {
            _uiState.value = PerfilUiState.Error("Por favor, completa todos los campos correctamente")
            return
        }
        
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val currentProfile = repository.getProfileSync()
                val gramos = parseDecimal(_gramosPorRacion.value)
                val ratio = parseDecimal(_ratioInsulina.value)
                val objetivoHidratos = parseDecimal(_objetivoHidratosDia.value)
                val objetivoRaciones = parseDecimal(_objetivoRacionesDia.value)
                val objetivoInsulina = parseDecimal(_objetivoInsulinaDia.value)
                val glucosaObjetivo = _glucosaObjetivoMgdl.value.trim().toIntOrNull()
                val factorCorreccion = parseDecimal(_factorCorreccionMgdlPorU.value)
                if (gramos == null || ratio == null) {
                    _uiState.value = PerfilUiState.Error("Formato numérico no válido")
                    return@launch
                }
                if ((objetivoHidratos != null && objetivoHidratos < 0f) ||
                    (objetivoRaciones != null && objetivoRaciones < 0f) ||
                    (objetivoInsulina != null && objetivoInsulina < 0f)
                ) {
                    _uiState.value = PerfilUiState.Error("Los objetivos no pueden ser negativos")
                    return@launch
                }
                val objetivoText = _glucosaObjetivoMgdl.value.trim()
                val factorText = _factorCorreccionMgdlPorU.value.trim()
                val hasObjetivo = objetivoText.isNotEmpty()
                val hasFactor = factorText.isNotEmpty()
                if (hasObjetivo.xor(hasFactor)) {
                    _uiState.value = PerfilUiState.Error(
                        "Para usar corrección por glucosa, completa objetivo y factor"
                    )
                    return@launch
                }
                if ((hasObjetivo && (glucosaObjetivo == null || glucosaObjetivo <= 0)) ||
                    (hasFactor && (factorCorreccion == null || factorCorreccion <= 0f))
                ) {
                    _uiState.value = PerfilUiState.Error(
                        "Valores de corrección por glucosa no válidos"
                    )
                    return@launch
                }
                val profile = UsuarioProfile(
                    id = currentProfile?.id ?: 1, // Mantener ID existente o usar 1
                    nombre = _nombre.value.trim(),
                    gramosPorRacion = gramos,
                    ratioInsulina = ratio,
                    objetivoHidratosDia = objetivoHidratos,
                    objetivoRacionesDia = objetivoRaciones,
                    objetivoInsulinaDia = objetivoInsulina,
                    glucosaObjetivoMgdl = glucosaObjetivo,
                    factorCorreccionMgdlPorU = factorCorreccion,
                    aplicarCorreccionPorDefecto = _aplicarCorreccionPorDefecto.value,
                    recordatorio2hActivo = _recordatorio2hActivo.value,
                    nightscoutUrl = _nightscoutUrl.value.trim().ifEmpty { null },
                    nightscoutToken = _nightscoutToken.value.trim().ifEmpty { null }
                )
                repository.insertProfile(profile)
                _saveSuccess.value = true
            } catch (e: Exception) {
                _uiState.value = PerfilUiState.Error("Error al guardar: ${e.message}")
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun parseDecimal(value: String): Float? {
        return value.trim().replace(',', '.').toFloatOrNull()
    }
    
    fun resetSaveSuccess() {
        _saveSuccess.value = false
    }

    /**
     * Exporta los datos de la app a un archivo.
     * Se define como suspend para evitar que el stream se cierre prematuramente en la UI.
     */
    suspend fun exportData(outputStream: OutputStream, password: String) {
        _isBackupLoading.value = true
        _backupStatus.value = null
        try {
            backupManager.exportData(outputStream, password)
            _backupStatus.value = "Datos exportados correctamente"
        } catch (e: Exception) {
            _backupStatus.value = "Error al exportar: ${e.message}"
        } finally {
            _isBackupLoading.value = false
        }
    }

    /**
     * Exporta los datos a CSV.
     */
    suspend fun exportCsv(outputStream: OutputStream) {
        _isBackupLoading.value = true
        _backupStatus.value = null
        try {
            backupManager.exportCsv(outputStream)
            _backupStatus.value = "CSV exportado correctamente"
        } catch (e: Exception) {
            _backupStatus.value = "Error al exportar CSV: ${e.message}"
        } finally {
            _isBackupLoading.value = false
        }
    }

    /**
     * Importa los datos de la app desde un archivo.
     * Se define como suspend para evitar que el stream se cierre prematuramente en la UI.
     */
    suspend fun importData(inputStream: InputStream, password: String?) {
        _isBackupLoading.value = true
        _backupStatus.value = null
        try {
            backupManager.importData(inputStream, password)
            _backupStatus.value = "Datos importados correctamente. Reinicia la app si es necesario."
        } catch (e: Exception) {
            _backupStatus.value = "Error al importar: ${e.message}"
        } finally {
            _isBackupLoading.value = false
        }
    }

    fun clearBackupStatus() {
        _backupStatus.value = null
    }

    suspend fun createAutoBackup(context: Context): File? {
        _isBackupLoading.value = true
        _backupStatus.value = null
        return try {
            val password = BackupPasswordStore(context.applicationContext).getOrCreatePassword()
            val backupDir = context.getExternalFilesDir("backups")
                ?: File(context.filesDir, "backups")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale("es", "ES")).format(Date())
            val backupFile = File(backupDir, "auto_backup_$timestamp.json")

            backupFile.outputStream().use { outputStream ->
                backupManager.exportData(outputStream, password)
            }
            cleanupOldBackups(backupDir, keep = 7)
            _backupStatus.value = "Copia de seguridad creada correctamente"
            backupFile
        } catch (e: Exception) {
            _backupStatus.value = "Error al crear la copia de seguridad: ${e.message}"
            null
        } finally {
            _isBackupLoading.value = false
        }
    }

    private fun cleanupOldBackups(directory: File, keep: Int) {
        val backups = directory.listFiles { file ->
            file.isFile && file.name.startsWith("auto_backup_")
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backups.size <= keep) return
        backups.drop(keep).forEach { it.delete() }
    }
    
    /**
     * Factory para crear el ViewModel con dependencias.
     */
    class Factory(
        private val repository: UsuarioProfileRepository,
        private val backupManager: BackupManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PerfilViewModel::class.java)) {
                return PerfilViewModel(repository, backupManager) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
