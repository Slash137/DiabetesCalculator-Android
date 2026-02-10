package com.diabetes.calculator.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.diabetes.calculator.data.model.NightscoutEntry
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Estado de la glucosa de Nightscout.
 */
sealed class NightscoutUiState {
    object Idle : NightscoutUiState()
    object Loading : NightscoutUiState()
    data class Success(val entry: NightscoutEntry) : NightscoutUiState()
    data class Error(val message: String) : NightscoutUiState()
}

data class NightscoutStatus(
    val lastSuccessAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastErrorMessage: String? = null,
    val consecutiveFailures: Int = 0
)

/**
 * ViewModel global para gestionar la monitorización de glucosa vía Nightscout.
 */
class NightscoutViewModel(
    private val profileRepository: UsuarioProfileRepository,
    private val nightscoutRepository: NightscoutRepository
) : ViewModel() {

    private val _glucoseState = MutableStateFlow<NightscoutUiState>(NightscoutUiState.Idle)
    val glucoseState: StateFlow<NightscoutUiState> = _glucoseState.asStateFlow()

    private val _status = MutableStateFlow(NightscoutStatus())
    val status: StateFlow<NightscoutStatus> = _status.asStateFlow()

    private var pollingJob: Job? = null
    private var currentUrl: String? = null
    private var currentToken: String? = null

    init {
        startPolling()
    }

    /**
     * Inicia el ciclo de actualización de glucosa.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // Usamos collectLatest para cancelar el bucle anterior si el perfil cambia
            profileRepository.profile.collectLatest { profile ->
                val url = profile?.nightscoutUrl?.trim().orEmpty()
                if (url.isNotBlank()) {
                    currentUrl = url
                    currentToken = profile?.nightscoutToken

                    refreshCurrentGlucose()

                    // Bucle de actualización cada 1 minuto para mayor precisión
                    while (currentCoroutineContext().isActive) {
                        delay(POLL_INTERVAL_MS)
                        refreshCurrentGlucose()
                    }
                } else {
                    currentUrl = null
                    currentToken = null
                    _glucoseState.value = NightscoutUiState.Idle
                }
            }
        }
    }

    /**
     * Reintenta conexión al volver a primer plano.
     */
    fun onAppForeground() {
        if (pollingJob?.isActive != true) {
            startPolling()
        }
        refreshNow()
    }

    /**
     * Fuerza un refresco puntual con la configuración actual.
     */
    fun refreshNow() {
        viewModelScope.launch {
            val profile = profileRepository.getProfileSync()
            val fallbackUrl = profile?.nightscoutUrl?.trim().orEmpty()
            val url = currentUrl?.takeIf { it.isNotBlank() } ?: fallbackUrl
            val token = currentToken ?: profile?.nightscoutToken

            if (url.isNotBlank()) {
                currentUrl = url
                currentToken = token
                refreshGlucose(url, token)
            } else {
                _glucoseState.value = NightscoutUiState.Idle
            }
        }
    }

    private suspend fun refreshCurrentGlucose() {
        val url = currentUrl?.takeIf { it.isNotBlank() } ?: return
        refreshGlucose(url, currentToken)
    }

    /**
     * Fuerza una actualización de la glucosa.
     */
    suspend fun refreshGlucose(url: String, token: String?) {
        _glucoseState.value = NightscoutUiState.Loading
        val entry = nightscoutRepository.getLatestGlucose(url, token)
        if (entry != null) {
            _glucoseState.value = NightscoutUiState.Success(entry)
            _status.value = _status.value.copy(
                lastSuccessAt = System.currentTimeMillis(),
                lastErrorMessage = null,
                lastErrorAt = null,
                consecutiveFailures = 0
            )
        } else {
            _glucoseState.value = NightscoutUiState.Error("No se pudo conectar con Nightscout")
            _status.value = _status.value.copy(
                lastErrorAt = System.currentTimeMillis(),
                lastErrorMessage = "No se pudo conectar con Nightscout",
                consecutiveFailures = _status.value.consecutiveFailures + 1
            )
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }

    /**
     * Retorna la flecha de tendencia según el string de Nightscout.
     */
    fun getTrendArrow(direction: String?): String {
        return when (direction) {
            "TripleUp" -> "⇈"
            "DoubleUp" -> "↑↑"
            "SingleUp" -> "↑"
            "FortyFiveUp" -> "↗"
            "Flat" -> "→"
            "FortyFiveDown" -> "↘"
            "SingleDown" -> "↓"
            "DoubleDown" -> "↓↓"
            "TripleDown" -> "⇊"
            else -> ""
        }
    }

    /**
     * Factory para crear el ViewModel.
     */
    class Factory(
        private val profileRepository: UsuarioProfileRepository,
        private val nightscoutRepository: NightscoutRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NightscoutViewModel::class.java)) {
                return NightscoutViewModel(profileRepository, nightscoutRepository) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
    }
}
