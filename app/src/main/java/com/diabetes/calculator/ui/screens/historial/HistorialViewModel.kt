package com.diabetes.calculator.ui.screens.historial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.entity.UnidadConsumoAlimento
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncService
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroLibreviewSyncRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.data.repository.LibreviewRegistrosSyncService
import com.diabetes.calculator.data.repository.LibreviewRepository
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.work.LibreviewSyncWorker
import com.diabetes.calculator.work.NightscoutSyncWorker
import com.diabetes.calculator.work.Recordatorio2hScheduler
import kotlin.math.max
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
    private val nightscoutRepository: NightscoutRepository,
    private val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository,
    private val registroLibreviewSyncRepository: RegistroLibreviewSyncRepository,
    private val workManager: WorkManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<HistorialUiState>(HistorialUiState.Loading)
    val uiState: StateFlow<HistorialUiState> = _uiState.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dayFilter = MutableStateFlow(DayFilter.ALL)
    val dayFilter: StateFlow<DayFilter> = _dayFilter.asStateFlow()

    private val _doseStatusFilter = MutableStateFlow(DoseStatusFilter.ALL)
    val doseStatusFilter: StateFlow<DoseStatusFilter> = _doseStatusFilter.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val factorCorreccionFallback: StateFlow<Float?> = usuarioRepository.profile
        .map { profile ->
            profile?.factorCorreccionMgdlPorU?.takeIf { it > 0f && !it.isNaN() }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null
        )

    val libreviewFailedRegistroIds: StateFlow<Set<Int>> = registroLibreviewSyncRepository.failedRegistroIds
        .map { it.toSet() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptySet()
        )

    val nightscoutPendingRegistroIds: StateFlow<Set<Int>> = registroNightscoutSyncRepository.pendingRegistroIds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptySet()
        )

    private suspend fun buildLibreviewSyncService(): LibreviewRegistrosSyncService {
        val profile = usuarioRepository.getProfileSync()
        val linkMinutes = max(
            profile?.nightscoutLinkOffsetMinutes?.coerceIn(0, 180) ?: SyncLinkTolerance.WINDOW_MINUTES,
            SyncLinkTolerance.WINDOW_MINUTES
        )
        val linkUnits = max(
            profile?.nightscoutLinkOffsetUnits?.coerceIn(0f, 5f) ?: SyncLinkTolerance.WINDOW_UNITS,
            SyncLinkTolerance.WINDOW_UNITS
        )
        return LibreviewRegistrosSyncService(
            registroRepository = repository,
            queueRepository = registroLibreviewSyncRepository,
            libreviewRepository = LibreviewRepository(),
            linkMatchDeltaMillis = linkMinutes * 60_000L,
            linkMatchInsulinDelta = linkUnits
        )
    }

    init {
        observeRegistros()
        reconcileLocalDuplicatesNow()
    }

    private fun reconcileLocalDuplicatesNow() {
        viewModelScope.launch {
            val profile = usuarioRepository.getProfileSync()
            val linkMinutes = max(
                profile?.nightscoutLinkOffsetMinutes?.coerceIn(0, 180) ?: SyncLinkTolerance.WINDOW_MINUTES,
                SyncLinkTolerance.WINDOW_MINUTES
            )
            val linkUnits = max(
                profile?.nightscoutLinkOffsetUnits?.coerceIn(0f, 5f) ?: SyncLinkTolerance.WINDOW_UNITS,
                SyncLinkTolerance.WINDOW_UNITS
            )
            NightscoutRegistrosSyncService(
                registroRepository = repository,
                queueRepository = registroNightscoutSyncRepository,
                tombstoneRepository = nightscoutTreatmentTombstoneRepository,
                nightscoutRepository = nightscoutRepository,
                libreviewQueueRepository = registroLibreviewSyncRepository
            ).reconcileLocalDuplicatesOnly(
                linkOffsetMinutes = linkMinutes,
                linkOffsetUnits = linkUnits
            )
        }
    }
    
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeRegistros() {
        viewModelScope.launch {
            val debouncedQueryFlow = _searchQuery
                .map { it.trim() }
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()

            combine(debouncedQueryFlow, _dayFilter, _doseStatusFilter) { query, dayFilter, doseFilter ->
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

    fun refreshSyncNow() {
        viewModelScope.launch {
            if (_isRefreshing.value) return@launch
            _isRefreshing.value = true
            try {
                val profile = usuarioRepository.getProfileSync()
                if (profile != null) {
                    val canSyncNightscout =
                        profile.nightscoutSyncRegistrosActivo && !profile.nightscoutUrl.isNullOrBlank()
                    if (canSyncNightscout) {
                        NightscoutSyncWorker.enqueueNow(workManager, forceManual = true)
                    }
                    if (profile.libreviewSyncActivo) {
                        LibreviewSyncWorker.enqueueNow(workManager, forceManual = true)
                    }
                }
                delay(450L)
            } finally {
                _isRefreshing.value = false
            }
        }
    }
    
    fun deleteRegistro(id: Int) {
        viewModelScope.launch {
            val libreviewSyncService = buildLibreviewSyncService()
            val registro = repository.getRegistroRawById(id)
            val profile = usuarioRepository.getProfileSync()
            val hasLibreviewLink = registro?.libreviewCarbsRecordNumber != null ||
                registro?.libreviewInsulinRecordNumber != null

            if (registro != null && hasLibreviewLink && profile?.libreviewSyncActivo == true) {
                // Si ya existe enlace remoto, encolamos delete remoto.
                libreviewSyncService.enqueueDeleteForRegistro(registro)
                LibreviewSyncWorker.enqueueNow(workManager, forceManual = true)
            } else {
                // Si aún no se subió, limpiamos colas locales inmediatamente.
                registroLibreviewSyncRepository.deleteByRegistroId(id)
            }

            // Al borrar localmente, no debe quedar pendiente de subida a Nightscout.
            registroNightscoutSyncRepository.deleteByRegistroId(id)

            val treatmentId = registro?.nightscoutTreatmentId
            if (!treatmentId.isNullOrBlank()) {
                nightscoutTreatmentTombstoneRepository.add(treatmentId)
                if (profile?.nightscoutSyncRegistrosActivo == true && !profile.nightscoutUrl.isNullOrBlank()) {
                    NightscoutSyncWorker.enqueueNow(workManager, forceManual = true)
                }
            }
            repository.deleteById(id)
        }
    }

    fun updateDoseStatus(registroId: Int, status: EstadoDosis) {
        viewModelScope.launch {
            val libreviewSyncService = buildLibreviewSyncService()
            if (status == EstadoDosis.OMITIDA) {
                val registro = repository.getRegistroRawById(registroId)
                val treatmentId = registro?.nightscoutTreatmentId
                if (!treatmentId.isNullOrBlank()) {
                    nightscoutTreatmentTombstoneRepository.add(treatmentId)
                }
            }
            repository.updateDosisEstado(registroId, status)

            val profile = usuarioRepository.getProfileSync()
            val updatedRegistro = repository.getRegistroRawById(registroId)

            if (status == EstadoDosis.OMITIDA) {
                Recordatorio2hScheduler.cancel(workManager, registroId)
            } else {
                if (profile?.recordatorio2hActivo == true && updatedRegistro != null) {
                    val triggerAtMillis = when (status) {
                        EstadoDosis.APLICADA -> {
                            (updatedRegistro.dosisConfirmadaAt ?: System.currentTimeMillis()) + TWO_HOURS_MS
                        }
                        EstadoDosis.PENDIENTE -> {
                            updatedRegistro.fecha + TWO_HOURS_MS
                        }
                        EstadoDosis.OMITIDA -> null
                    }
                    if (triggerAtMillis != null) {
                        Recordatorio2hScheduler.schedule(
                            workManager = workManager,
                            registroId = registroId,
                            triggerAtMillis = triggerAtMillis
                        )
                    }
                }
            }

            if (status == EstadoDosis.OMITIDA) {
                repository.clearNightscoutLink(registroId)
            }

            if (status == EstadoDosis.APLICADA &&
                profile?.nightscoutSyncRegistrosActivo == true &&
                !profile.nightscoutUrl.isNullOrBlank() &&
                updatedRegistro != null &&
                isLocalDoseOnlyRegistro(updatedRegistro)
            ) {
                registroNightscoutSyncRepository.upsertPending(registroId)
                NightscoutSyncWorker.enqueueNow(workManager, forceManual = true)
            }

            if (profile?.libreviewSyncActivo == true && updatedRegistro != null) {
                if (status == EstadoDosis.OMITIDA) {
                    libreviewSyncService.enqueueDeleteForRegistro(updatedRegistro)
                } else {
                    libreviewSyncService.enqueueUpsertForRegistro(registroId)
                }
                LibreviewSyncWorker.enqueueNow(workManager, forceManual = true)
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
            val libreviewSyncService = buildLibreviewSyncService()
            val previousTreatmentId = repository.getRegistroRawById(registroId)
                ?.nightscoutTreatmentId
                ?.takeIf { it.isNotBlank() }
            repository.updateDoseForLink(registroId, unidades, confirmadaAt)
            if (previousTreatmentId != null) {
                nightscoutTreatmentTombstoneRepository.add(previousTreatmentId)
            }
            registroNightscoutSyncRepository.upsertPending(registroId)
            NightscoutSyncWorker.enqueueNowForAnchor(
                workManager = workManager,
                anchorMillis = confirmadaAt ?: System.currentTimeMillis()
            )
            val profile = usuarioRepository.getProfileSync()
            if (profile?.libreviewSyncActivo == true) {
                libreviewSyncService.enqueueUpsertForRegistro(
                    registroId = registroId,
                    allowPendingInsulin = true
                )
                LibreviewSyncWorker.enqueueNow(
                    workManager = workManager,
                    forceManual = true,
                    targetRegistroId = registroId
                )
            }
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

    private fun isLocalDoseOnlyRegistro(registro: com.diabetes.calculator.data.entity.RegistroComida): Boolean {
        if (OrigenRegistro.fromValue(registro.origenRegistro) != OrigenRegistro.LOCAL) return false
        val units = registro.unidadesInsulina
        if (!units.isFinite() || units <= 0f) return false
        return registro.hidratosTotales <= 0f && registro.racionesCalculadas <= 0f
    }

    class Factory(
        private val repository: RegistroComidaRepository,
        private val plantillaRepository: PlantillaRepository,
        private val usuarioRepository: UsuarioProfileRepository,
        private val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository,
        private val nightscoutRepository: NightscoutRepository,
        private val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository,
        private val registroLibreviewSyncRepository: RegistroLibreviewSyncRepository,
        private val workManager: WorkManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HistorialViewModel::class.java)) {
                return HistorialViewModel(
                    repository,
                    plantillaRepository,
                    usuarioRepository,
                    nightscoutTreatmentTombstoneRepository,
                    nightscoutRepository,
                    registroNightscoutSyncRepository,
                    registroLibreviewSyncRepository,
                    workManager
                ) as T
            }
            throw IllegalArgumentException("Clase de ViewModel desconocida")
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 180L
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
    }
}
