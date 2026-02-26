package com.diabetes.calculator.ui.screens.perfil

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.util.BackupManager
import com.diabetes.calculator.util.BackupPasswordStore
import com.diabetes.calculator.work.LibreviewSyncWorker
import com.diabetes.calculator.work.NightscoutSyncWorker
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
    private val backupManager: BackupManager,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<PerfilUiState>(PerfilUiState.Loading)
    val uiState: StateFlow<PerfilUiState> = _uiState.asStateFlow()

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

    private val _nightscoutSyncRegistrosActivo = MutableStateFlow(false)
    val nightscoutSyncRegistrosActivo: StateFlow<Boolean> = _nightscoutSyncRegistrosActivo.asStateFlow()

    private val _nightscoutLinkOffsetMinutes = MutableStateFlow("15")
    val nightscoutLinkOffsetMinutes: StateFlow<String> = _nightscoutLinkOffsetMinutes.asStateFlow()

    private val _nightscoutLinkOffsetUnits = MutableStateFlow(SyncLinkTolerance.WINDOW_UNITS.toString())
    val nightscoutLinkOffsetUnits: StateFlow<String> = _nightscoutLinkOffsetUnits.asStateFlow()

    private val _libreviewSyncActivo = MutableStateFlow(true)
    val libreviewSyncActivo: StateFlow<Boolean> = _libreviewSyncActivo.asStateFlow()

    private val _libreviewRegionOverride = MutableStateFlow("")
    val libreviewRegionOverride: StateFlow<String> = _libreviewRegionOverride.asStateFlow()

    private val _libreviewEmail = MutableStateFlow("")
    val libreviewEmail: StateFlow<String> = _libreviewEmail.asStateFlow()

    private val _libreviewPassword = MutableStateFlow("")
    val libreviewPassword: StateFlow<String> = _libreviewPassword.asStateFlow()

    private val _factorHoraMadrugada = MutableStateFlow("1.0")
    val factorHoraMadrugada: StateFlow<String> = _factorHoraMadrugada.asStateFlow()

    private val _factorHoraManana = MutableStateFlow("1.0")
    val factorHoraManana: StateFlow<String> = _factorHoraManana.asStateFlow()

    private val _factorHoraTarde = MutableStateFlow("1.0")
    val factorHoraTarde: StateFlow<String> = _factorHoraTarde.asStateFlow()

    private val _factorHoraNoche = MutableStateFlow("1.0")
    val factorHoraNoche: StateFlow<String> = _factorHoraNoche.asStateFlow()

    private val _factorEstresLeve = MutableStateFlow("1.10")
    val factorEstresLeve: StateFlow<String> = _factorEstresLeve.asStateFlow()

    private val _factorEstresModerado = MutableStateFlow("1.20")
    val factorEstresModerado: StateFlow<String> = _factorEstresModerado.asStateFlow()

    private val _factorEstresAlto = MutableStateFlow("1.30")
    val factorEstresAlto: StateFlow<String> = _factorEstresAlto.asStateFlow()

    private val _factorEnfermedadLeve = MutableStateFlow("1.10")
    val factorEnfermedadLeve: StateFlow<String> = _factorEnfermedadLeve.asStateFlow()

    private val _factorEnfermedadModerada = MutableStateFlow("1.20")
    val factorEnfermedadModerada: StateFlow<String> = _factorEnfermedadModerada.asStateFlow()

    private val _factorEnfermedadAlta = MutableStateFlow("1.30")
    val factorEnfermedadAlta: StateFlow<String> = _factorEnfermedadAlta.asStateFlow()

    private val _cicloHormonalActivo = MutableStateFlow(false)
    val cicloHormonalActivo: StateFlow<Boolean> = _cicloHormonalActivo.asStateFlow()

    private val _factorCicloMenstruacion = MutableStateFlow("0.95")
    val factorCicloMenstruacion: StateFlow<String> = _factorCicloMenstruacion.asStateFlow()

    private val _factorCicloFolicular = MutableStateFlow("1.00")
    val factorCicloFolicular: StateFlow<String> = _factorCicloFolicular.asStateFlow()

    private val _factorCicloOvulacion = MutableStateFlow("1.05")
    val factorCicloOvulacion: StateFlow<String> = _factorCicloOvulacion.asStateFlow()

    private val _factorCicloLutea = MutableStateFlow("1.15")
    val factorCicloLutea: StateFlow<String> = _factorCicloLutea.asStateFlow()

    private val _factorEjercicioSuave = MutableStateFlow("0.90")
    val factorEjercicioSuave: StateFlow<String> = _factorEjercicioSuave.asStateFlow()

    private val _factorEjercicioModerado = MutableStateFlow("0.80")
    val factorEjercicioModerado: StateFlow<String> = _factorEjercicioModerado.asStateFlow()

    private val _factorEjercicioIntenso = MutableStateFlow("0.70")
    val factorEjercicioIntenso: StateFlow<String> = _factorEjercicioIntenso.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    private val _isBackupLoading = MutableStateFlow(false)
    val isBackupLoading: StateFlow<Boolean> = _isBackupLoading.asStateFlow()

    private var profileJob: Job? = null
    private var libreviewCredentialsDirty = false

    init {
        loadProfile()
    }

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
                    _nightscoutSyncRegistrosActivo.value = profile.nightscoutSyncRegistrosActivo
                    _nightscoutLinkOffsetMinutes.value = profile.nightscoutLinkOffsetMinutes.toString()
                    _nightscoutLinkOffsetUnits.value = profile.nightscoutLinkOffsetUnits
                        .coerceAtLeast(SyncLinkTolerance.WINDOW_UNITS)
                        .toString()
                    _libreviewSyncActivo.value = profile.libreviewSyncActivo
                    _libreviewRegionOverride.value = profile.libreviewRegionOverride ?: ""
                    _libreviewEmail.value = repository.getLibreviewEmail().orEmpty()
                    _libreviewPassword.value = repository.getLibreviewPassword().orEmpty()
                    libreviewCredentialsDirty = false

                    _factorHoraMadrugada.value = profile.factorHoraMadrugada.toString()
                    _factorHoraManana.value = profile.factorHoraManana.toString()
                    _factorHoraTarde.value = profile.factorHoraTarde.toString()
                    _factorHoraNoche.value = profile.factorHoraNoche.toString()
                    _factorEstresLeve.value = profile.factorEstresLeve.toString()
                    _factorEstresModerado.value = profile.factorEstresModerado.toString()
                    _factorEstresAlto.value = profile.factorEstresAlto.toString()
                    _factorEnfermedadLeve.value = profile.factorEnfermedadLeve.toString()
                    _factorEnfermedadModerada.value = profile.factorEnfermedadModerada.toString()
                    _factorEnfermedadAlta.value = profile.factorEnfermedadAlta.toString()
                    _cicloHormonalActivo.value = profile.cicloHormonalActivo
                    _factorCicloMenstruacion.value = profile.factorCicloMenstruacion.toString()
                    _factorCicloFolicular.value = profile.factorCicloFolicular.toString()
                    _factorCicloOvulacion.value = profile.factorCicloOvulacion.toString()
                    _factorCicloLutea.value = profile.factorCicloLutea.toString()
                    _factorEjercicioSuave.value = profile.factorEjercicioSuave.toString()
                    _factorEjercicioModerado.value = profile.factorEjercicioModerado.toString()
                    _factorEjercicioIntenso.value = profile.factorEjercicioIntenso.toString()

                    _uiState.value = PerfilUiState.Success(profile)
                } else {
                    _gramosPorRacion.value = "10"
                    _ratioInsulina.value = "1.0"
                    _objetivoHidratosDia.value = ""
                    _objetivoRacionesDia.value = ""
                    _objetivoInsulinaDia.value = ""
                    _glucosaObjetivoMgdl.value = ""
                    _factorCorreccionMgdlPorU.value = ""
                    _aplicarCorreccionPorDefecto.value = true
                    _recordatorio2hActivo.value = false
                    _nightscoutSyncRegistrosActivo.value = false
                    _nightscoutLinkOffsetMinutes.value = "15"
                    _nightscoutLinkOffsetUnits.value = SyncLinkTolerance.WINDOW_UNITS.toString()
                    _libreviewSyncActivo.value = true
                    _libreviewRegionOverride.value = ""
                    _libreviewEmail.value = repository.getLibreviewEmail().orEmpty()
                    _libreviewPassword.value = repository.getLibreviewPassword().orEmpty()
                    libreviewCredentialsDirty = false
                    _factorHoraMadrugada.value = "1.0"
                    _factorHoraManana.value = "1.0"
                    _factorHoraTarde.value = "1.0"
                    _factorHoraNoche.value = "1.0"
                    _factorEstresLeve.value = "1.10"
                    _factorEstresModerado.value = "1.20"
                    _factorEstresAlto.value = "1.30"
                    _factorEnfermedadLeve.value = "1.10"
                    _factorEnfermedadModerada.value = "1.20"
                    _factorEnfermedadAlta.value = "1.30"
                    _cicloHormonalActivo.value = false
                    _factorCicloMenstruacion.value = "0.95"
                    _factorCicloFolicular.value = "1.00"
                    _factorCicloOvulacion.value = "1.05"
                    _factorCicloLutea.value = "1.15"
                    _factorEjercicioSuave.value = "0.90"
                    _factorEjercicioModerado.value = "0.80"
                    _factorEjercicioIntenso.value = "0.70"
                    _uiState.value = PerfilUiState.Empty
                }
            }
        }
    }

    fun updateNombre(value: String) {
        _nombre.value = value
    }

    fun updateGramosPorRacion(value: String) {
        if (isDecimalInput(value)) {
            _gramosPorRacion.value = value
        }
    }

    fun updateRatioInsulina(value: String) {
        if (isDecimalInput(value)) {
            _ratioInsulina.value = value
        }
    }

    fun updateObjetivoHidratosDia(value: String) {
        if (isDecimalInput(value)) {
            _objetivoHidratosDia.value = value
        }
    }

    fun updateObjetivoRacionesDia(value: String) {
        if (isDecimalInput(value)) {
            _objetivoRacionesDia.value = value
        }
    }

    fun updateObjetivoInsulinaDia(value: String) {
        if (isDecimalInput(value)) {
            _objetivoInsulinaDia.value = value
        }
    }

    fun updateGlucosaObjetivoMgdl(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
            _glucosaObjetivoMgdl.value = value
        }
    }

    fun updateFactorCorreccionMgdlPorU(value: String) {
        if (isDecimalInput(value)) {
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

    fun updateNightscoutSyncRegistrosActivo(value: Boolean) {
        _nightscoutSyncRegistrosActivo.value = value
    }

    fun updateNightscoutLinkOffsetMinutes(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
            _nightscoutLinkOffsetMinutes.value = value
        }
    }

    fun updateNightscoutLinkOffsetUnits(value: String) {
        if (isDecimalInput(value)) {
            _nightscoutLinkOffsetUnits.value = value
        }
    }

    fun updateLibreviewSyncActivo(value: Boolean) {
        _libreviewSyncActivo.value = value
    }

    fun updateLibreviewRegionOverride(value: String) {
        _libreviewRegionOverride.value = value.uppercase(Locale.ROOT)
    }

    fun updateLibreviewEmail(value: String) {
        _libreviewEmail.value = value
        libreviewCredentialsDirty = true
        persistLibreviewCredentialsDraft()
    }

    fun updateLibreviewPassword(value: String) {
        _libreviewPassword.value = value
        libreviewCredentialsDirty = true
        persistLibreviewCredentialsDraft()
    }

    fun persistLibreviewCredentialsDraft(): Boolean {
        val email = _libreviewEmail.value.trim().ifEmpty { null }
        val password = _libreviewPassword.value.trim().ifEmpty { null }
        val previousEmail = repository.getLibreviewEmail()?.trim()
        val previousPassword = repository.getLibreviewPassword()?.trim()
        val credentialsChanged = email != previousEmail || password != previousPassword

        if (credentialsChanged) {
            repository.clearLibreviewSessionSecrets()
        }
        repository.setLibreviewCredentials(email = email, password = password)
        return credentialsChanged
    }

    fun updateFactorHoraMadrugada(value: String) {
        if (isDecimalInput(value)) _factorHoraMadrugada.value = value
    }

    fun updateFactorHoraManana(value: String) {
        if (isDecimalInput(value)) _factorHoraManana.value = value
    }

    fun updateFactorHoraTarde(value: String) {
        if (isDecimalInput(value)) _factorHoraTarde.value = value
    }

    fun updateFactorHoraNoche(value: String) {
        if (isDecimalInput(value)) _factorHoraNoche.value = value
    }

    fun updateFactorEstresLeve(value: String) {
        if (isDecimalInput(value)) _factorEstresLeve.value = value
    }

    fun updateFactorEstresModerado(value: String) {
        if (isDecimalInput(value)) _factorEstresModerado.value = value
    }

    fun updateFactorEstresAlto(value: String) {
        if (isDecimalInput(value)) _factorEstresAlto.value = value
    }

    fun updateFactorEnfermedadLeve(value: String) {
        if (isDecimalInput(value)) _factorEnfermedadLeve.value = value
    }

    fun updateFactorEnfermedadModerada(value: String) {
        if (isDecimalInput(value)) _factorEnfermedadModerada.value = value
    }

    fun updateFactorEnfermedadAlta(value: String) {
        if (isDecimalInput(value)) _factorEnfermedadAlta.value = value
    }

    fun updateCicloHormonalActivo(value: Boolean) {
        _cicloHormonalActivo.value = value
    }

    fun updateFactorCicloMenstruacion(value: String) {
        if (isDecimalInput(value)) _factorCicloMenstruacion.value = value
    }

    fun updateFactorCicloFolicular(value: String) {
        if (isDecimalInput(value)) _factorCicloFolicular.value = value
    }

    fun updateFactorCicloOvulacion(value: String) {
        if (isDecimalInput(value)) _factorCicloOvulacion.value = value
    }

    fun updateFactorCicloLutea(value: String) {
        if (isDecimalInput(value)) _factorCicloLutea.value = value
    }

    fun updateFactorEjercicioSuave(value: String) {
        if (isDecimalInput(value)) _factorEjercicioSuave.value = value
    }

    fun updateFactorEjercicioModerado(value: String) {
        if (isDecimalInput(value)) _factorEjercicioModerado.value = value
    }

    fun updateFactorEjercicioIntenso(value: String) {
        if (isDecimalInput(value)) _factorEjercicioIntenso.value = value
    }

    fun validateFields(): Boolean {
        val nombreVal = _nombre.value.trim()
        val gramosVal = parseDecimal(_gramosPorRacion.value)
        val ratioVal = parseDecimal(_ratioInsulina.value)

        return nombreVal.isNotEmpty() &&
            gramosVal != null && gramosVal > 0 &&
            ratioVal != null && ratioVal > 0 &&
            validateNightscoutLinkOffsets() &&
            validateContextFactors()
    }

    fun saveProfile() {
        val credentialsChanged = libreviewCredentialsDirty || persistLibreviewCredentialsDraft()
        if (!validateFields()) {
            _uiState.value = PerfilUiState.Error("Por favor, completa todos los campos correctamente")
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val currentProfile = repository.getProfileSync()
                val wasSyncActive = currentProfile?.nightscoutSyncRegistrosActivo == true
                val wasLibreviewSyncActive = currentProfile?.libreviewSyncActivo == true
                val gramos = parseDecimal(_gramosPorRacion.value)
                val ratio = parseDecimal(_ratioInsulina.value)
                val objetivoHidratos = parseDecimal(_objetivoHidratosDia.value)
                val objetivoRaciones = parseDecimal(_objetivoRacionesDia.value)
                val objetivoInsulina = parseDecimal(_objetivoInsulinaDia.value)
                val glucosaObjetivo = _glucosaObjetivoMgdl.value.trim().toIntOrNull()
                val factorCorreccion = parseDecimal(_factorCorreccionMgdlPorU.value)
                val linkOffsetMinutes = _nightscoutLinkOffsetMinutes.value.trim().toIntOrNull()
                val linkOffsetUnits = parseDecimal(_nightscoutLinkOffsetUnits.value)
                val libreviewRegionOverride = _libreviewRegionOverride.value
                    .trim()
                    .uppercase(Locale.ROOT)
                    .ifEmpty { null }

                val factorHoraMadrugada = parsePositiveFactor(_factorHoraMadrugada.value)
                val factorHoraManana = parsePositiveFactor(_factorHoraManana.value)
                val factorHoraTarde = parsePositiveFactor(_factorHoraTarde.value)
                val factorHoraNoche = parsePositiveFactor(_factorHoraNoche.value)
                val factorEstresLeve = parsePositiveFactor(_factorEstresLeve.value)
                val factorEstresModerado = parsePositiveFactor(_factorEstresModerado.value)
                val factorEstresAlto = parsePositiveFactor(_factorEstresAlto.value)
                val factorEnfermedadLeve = parsePositiveFactor(_factorEnfermedadLeve.value)
                val factorEnfermedadModerada = parsePositiveFactor(_factorEnfermedadModerada.value)
                val factorEnfermedadAlta = parsePositiveFactor(_factorEnfermedadAlta.value)
                val factorCicloMenstruacion = parsePositiveFactor(_factorCicloMenstruacion.value)
                val factorCicloFolicular = parsePositiveFactor(_factorCicloFolicular.value)
                val factorCicloOvulacion = parsePositiveFactor(_factorCicloOvulacion.value)
                val factorCicloLutea = parsePositiveFactor(_factorCicloLutea.value)
                val factorEjercicioSuave = parsePositiveFactor(_factorEjercicioSuave.value)
                val factorEjercicioModerado = parsePositiveFactor(_factorEjercicioModerado.value)
                val factorEjercicioIntenso = parsePositiveFactor(_factorEjercicioIntenso.value)

                if (gramos == null || ratio == null) {
                    _uiState.value = PerfilUiState.Error("Formato numérico no válido")
                    return@launch
                }

                val contextFactors = listOf(
                    factorHoraMadrugada,
                    factorHoraManana,
                    factorHoraTarde,
                    factorHoraNoche,
                    factorEstresLeve,
                    factorEstresModerado,
                    factorEstresAlto,
                    factorEnfermedadLeve,
                    factorEnfermedadModerada,
                    factorEnfermedadAlta,
                    factorCicloMenstruacion,
                    factorCicloFolicular,
                    factorCicloOvulacion,
                    factorCicloLutea,
                    factorEjercicioSuave,
                    factorEjercicioModerado,
                    factorEjercicioIntenso
                )

                if (contextFactors.any { it == null || it <= 0f }) {
                    _uiState.value = PerfilUiState.Error(
                        "Los factores contextuales deben ser numéricos y mayores que 0"
                    )
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
                if (linkOffsetMinutes == null || linkOffsetMinutes < 0 || linkOffsetMinutes > 180) {
                    _uiState.value = PerfilUiState.Error(
                        "Ventana de hora global no válida (0-180 min)"
                    )
                    return@launch
                }
                if (
                    linkOffsetUnits == null ||
                    linkOffsetUnits < SyncLinkTolerance.WINDOW_UNITS ||
                    linkOffsetUnits > 5f
                ) {
                    _uiState.value = PerfilUiState.Error(
                        "Ventana de dosis global no válida (1-5 U)"
                    )
                    return@launch
                }
                if (!libreviewRegionOverride.isNullOrBlank() &&
                    !libreviewRegionOverride.matches(Regex("^[A-Z]{2}$"))
                ) {
                    _uiState.value = PerfilUiState.Error(
                        "Región LibreView no válida (usa código ISO de 2 letras)"
                    )
                    return@launch
                }

                val profile = UsuarioProfile(
                    id = currentProfile?.id ?: 1,
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
                    nightscoutToken = _nightscoutToken.value.trim().ifEmpty { null },
                    nightscoutSyncRegistrosActivo = _nightscoutSyncRegistrosActivo.value,
                    nightscoutSyncBackfillDoneAt = currentProfile?.nightscoutSyncBackfillDoneAt,
                    nightscoutLinkOffsetMinutes = linkOffsetMinutes,
                    nightscoutLinkOffsetUnits = linkOffsetUnits,
                    libreviewSyncActivo = _libreviewSyncActivo.value,
                    libreviewRegionOverride = libreviewRegionOverride,
                    libreviewBackfillDoneAt = currentProfile?.libreviewBackfillDoneAt,
                    factorHoraMadrugada = factorHoraMadrugada!!,
                    factorHoraManana = factorHoraManana!!,
                    factorHoraTarde = factorHoraTarde!!,
                    factorHoraNoche = factorHoraNoche!!,
                    factorEstresLeve = factorEstresLeve!!,
                    factorEstresModerado = factorEstresModerado!!,
                    factorEstresAlto = factorEstresAlto!!,
                    factorEnfermedadLeve = factorEnfermedadLeve!!,
                    factorEnfermedadModerada = factorEnfermedadModerada!!,
                    factorEnfermedadAlta = factorEnfermedadAlta!!,
                    cicloHormonalActivo = _cicloHormonalActivo.value,
                    factorCicloMenstruacion = factorCicloMenstruacion!!,
                    factorCicloFolicular = factorCicloFolicular!!,
                    factorCicloOvulacion = factorCicloOvulacion!!,
                    factorCicloLutea = factorCicloLutea!!,
                    factorEjercicioSuave = factorEjercicioSuave!!,
                    factorEjercicioModerado = factorEjercicioModerado!!,
                    factorEjercicioIntenso = factorEjercicioIntenso!!
                )
                repository.insertProfile(profile)
                if (!wasSyncActive && profile.nightscoutSyncRegistrosActivo) {
                    NightscoutSyncWorker.enqueuePeriodic(workManager)
                    NightscoutSyncWorker.enqueueNow(workManager)
                }
                val libreviewRegionChanged = profile.libreviewRegionOverride != currentProfile?.libreviewRegionOverride
                if (!wasLibreviewSyncActive && profile.libreviewSyncActivo) {
                    LibreviewSyncWorker.enqueuePeriodic(workManager)
                    LibreviewSyncWorker.enqueueNow(workManager, forceManual = true)
                } else if (profile.libreviewSyncActivo && (credentialsChanged || libreviewRegionChanged)) {
                    LibreviewSyncWorker.enqueueNow(workManager, forceManual = true)
                }
                libreviewCredentialsDirty = false
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

    private fun parsePositiveFactor(value: String): Float? {
        return parseDecimal(value)?.takeIf { it > 0f }
    }

    private fun isDecimalInput(value: String): Boolean {
        return value.isEmpty() || value.matches(Regex("^\\d*([\\.,]\\d*)?$"))
    }

    private fun validateContextFactors(): Boolean {
        val factors = listOf(
            _factorHoraMadrugada.value,
            _factorHoraManana.value,
            _factorHoraTarde.value,
            _factorHoraNoche.value,
            _factorEstresLeve.value,
            _factorEstresModerado.value,
            _factorEstresAlto.value,
            _factorEnfermedadLeve.value,
            _factorEnfermedadModerada.value,
            _factorEnfermedadAlta.value,
            _factorCicloMenstruacion.value,
            _factorCicloFolicular.value,
            _factorCicloOvulacion.value,
            _factorCicloLutea.value,
            _factorEjercicioSuave.value,
            _factorEjercicioModerado.value,
            _factorEjercicioIntenso.value
        )
        return factors.all { parsePositiveFactor(it) != null }
    }

    private fun validateNightscoutLinkOffsets(): Boolean {
        val minutes = _nightscoutLinkOffsetMinutes.value.trim().toIntOrNull()
        val units = parseDecimal(_nightscoutLinkOffsetUnits.value)
        val minutesOk = minutes != null && minutes in 0..180
        val unitsOk = units != null && units >= SyncLinkTolerance.WINDOW_UNITS && units <= 5f
        return minutesOk && unitsOk
    }

    fun resetSaveSuccess() {
        _saveSuccess.value = false
    }

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

    class Factory(
        private val repository: UsuarioProfileRepository,
        private val backupManager: BackupManager,
        private val workManager: WorkManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PerfilViewModel::class.java)) {
                return PerfilViewModel(repository, backupManager, workManager) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }
}
