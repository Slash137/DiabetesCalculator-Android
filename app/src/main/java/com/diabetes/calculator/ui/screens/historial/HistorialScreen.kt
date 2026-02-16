package com.diabetes.calculator.ui.screens.historial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.work.WorkManager
import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.domain.ACTIVE_INSULIN_DURATION_MINUTES
import com.diabetes.calculator.domain.FactoresContextoInsulina
import com.diabetes.calculator.domain.FaseCicloHormonal
import com.diabetes.calculator.domain.FranjaHoraria
import com.diabetes.calculator.domain.NivelEjercicio
import com.diabetes.calculator.domain.NivelEnfermedad
import com.diabetes.calculator.domain.NivelEstres
import com.diabetes.calculator.util.DateUtils
import com.diabetes.calculator.ui.components.ScrollToTopForLazyList
import com.diabetes.calculator.work.NightscoutSyncWorker
import kotlinx.coroutines.delay
import java.util.Locale

private val HistorialHidratosColor = Color(0xFF4CAF50)
private val HistorialInsulinaColor = Color(0xFFF57C00)
private val HistorialRacionesColor = Color(0xFF2196F3)
private val HistorialWarningColor = Color(0xFFFF9800)
private const val ACTIVE_INSULIN_WINDOW_MILLIS = ACTIVE_INSULIN_DURATION_MINUTES * 60_000L

@Composable
private fun HistorialMedicalNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Recuerda consultar siempre con un profesional sanitario.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Pantalla de historial de comidas con diseño Material You coherente.
 */
@Composable
fun HistorialScreen(
    viewModel: HistorialViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val dayFilter by viewModel.dayFilter.collectAsState()
    val doseStatusFilter by viewModel.doseStatusFilter.collectAsState()
    val factorCorreccionFallback by viewModel.factorCorreccionFallback.collectAsState()
    var showDayFilterMenu by remember { mutableStateOf(false) }
    var showDoseStatusMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RegistroComidaConItems?>(null) }
    var detailRegistro by remember { mutableStateOf<RegistroComidaConItems?>(null) }
    var plantillaRegistro by remember { mutableStateOf<RegistroComidaConItems?>(null) }
    var plantillaNombre by remember { mutableStateOf("") }
    var cargandoGlucosaRetroactiva by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val nowMillis by rememberCurrentTimeTicker()
    val successRegistros = (uiState as? HistorialUiState.Success)?.registros.orEmpty()
    val activeInsulinStatesByRegistroId = remember(uiState, nowMillis) {
        successRegistros.associate { registro ->
            registro.registro.id to activeInsulinCardStateNow(
                registro = registro.registro,
                nowMillis = nowMillis
            )
        }
    }
    val activeDoseCount = remember(activeInsulinStatesByRegistroId) {
        activeInsulinStatesByRegistroId.values.count { it != null }
    }
    val detailActiveInsulinState = detailRegistro
        ?.let { activeInsulinStatesByRegistroId[it.registro.id] }

    val onUpdateDoseStatus: (RegistroComidaConItems, EstadoDosis) -> Unit = { registro, nuevoEstado ->
        val anterior = EstadoDosis.fromValue(registro.registro.dosisEstado)
        if (anterior == nuevoEstado) {
            Unit
        } else {
            val previousDetail = detailRegistro
            val confirmedAt = if (nuevoEstado == EstadoDosis.APLICADA) System.currentTimeMillis() else null
            viewModel.updateDoseStatus(registro.registro.id, nuevoEstado)

            if (previousDetail?.registro?.id == registro.registro.id) {
                detailRegistro = previousDetail.copy(
                    registro = previousDetail.registro.copy(
                        dosisEstado = nuevoEstado.value,
                        dosisConCorreccion = if (nuevoEstado == EstadoDosis.APLICADA) {
                            previousDetail.registro.dosisConCorreccion
                        } else {
                            null
                        },
                        dosisConfirmadaAt = confirmedAt
                    )
                )
            }
        }
    }

    val onUpdateDoseCorrection: (RegistroComidaConItems, Boolean?) -> Unit = { registro, conCorreccion ->
        viewModel.updateDoseCorrection(registro.registro.id, conCorreccion)
        val previousDetail = detailRegistro
        if (previousDetail?.registro?.id == registro.registro.id &&
            EstadoDosis.fromValue(previousDetail.registro.dosisEstado) == EstadoDosis.APLICADA
        ) {
            detailRegistro = previousDetail.copy(
                registro = previousDetail.registro.copy(dosisConCorreccion = conCorreccion)
            )
        }
    }

    val onUpdateDoseForLink: (RegistroComidaConItems, Float, Long?) -> Unit =
        { registro, unidades, confirmadaAt ->
            viewModel.updateDoseForLink(registro.registro.id, unidades, confirmadaAt)
            NightscoutSyncWorker.enqueueNow(
                workManager = WorkManager.getInstance(context),
                forceManual = true
            )
            val prev = detailRegistro
            if (prev?.registro?.id == registro.registro.id) {
                detailRegistro = prev.copy(
                    registro = prev.registro.copy(
                        unidadesInsulina = unidades,
                        dosisConfirmadaAt = confirmadaAt
                    )
                )
            }
        }

    LaunchedEffect(
        detailRegistro?.registro?.id,
        detailRegistro?.registro?.origenRegistro
    ) {
        val current = detailRegistro
        if (current == null) {
            cargandoGlucosaRetroactiva = false
            return@LaunchedEffect
        }
        val isNightscoutImport =
            OrigenRegistro.fromValue(current.registro.origenRegistro) == OrigenRegistro.NIGHTSCOUT_IMPORT
        if (isNightscoutImport) {
            if (current.registro.glucosaAntesMgdl != null && current.registro.glucosaDespues2hMgdl != null) {
                cargandoGlucosaRetroactiva = false
                return@LaunchedEffect
            }
            cargandoGlucosaRetroactiva = true
            val (antes, despues) = viewModel.hydrateNightscoutImportGlucose(current)
            val latest = detailRegistro
            if (latest?.registro?.id == current.registro.id) {
                detailRegistro = latest.copy(
                    registro = latest.registro.copy(
                        glucosaAntesMgdl = antes ?: latest.registro.glucosaAntesMgdl,
                        glucosaDespues2hMgdl = despues ?: latest.registro.glucosaDespues2hMgdl
                    )
                )
            }
            cargandoGlucosaRetroactiva = false
            return@LaunchedEffect
        }

        cargandoGlucosaRetroactiva = false
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
        ) {
            HistorialMedicalNotice(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar en historial...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showDayFilterMenu = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(dayFilter.label)
                        }
                        DropdownMenu(
                            expanded = showDayFilterMenu,
                            onDismissRequest = { showDayFilterMenu = false }
                        ) {
                            DayFilter.values().forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.label) },
                                    onClick = {
                                        viewModel.updateDayFilter(filter)
                                        showDayFilterMenu = false
                                    },
                                    trailingIcon = {
                                        if (filter == dayFilter) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showDoseStatusMenu = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Medication,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(doseStatusFilter.label)
                        }
                        DropdownMenu(
                            expanded = showDoseStatusMenu,
                            onDismissRequest = { showDoseStatusMenu = false }
                        ) {
                            DoseStatusFilter.values().forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.label) },
                                    onClick = {
                                        viewModel.updateDoseStatusFilter(filter)
                                        showDoseStatusMenu = false
                                    },
                                    trailingIcon = {
                                        if (filter == doseStatusFilter) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            when (val state = uiState) {
                is HistorialUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is HistorialUiState.Empty -> {
                    EmptyHistorialView(
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is HistorialUiState.Success -> {
                    HistorialList(
                        listState = listState,
                        registros = state.registros,
                        activeInsulinStatesByRegistroId = activeInsulinStatesByRegistroId,
                        activeDoseCount = activeDoseCount,
                        onOpenDetail = { detailRegistro = it }
                    )
                }
                is HistorialUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (uiState is HistorialUiState.Success) {
            ScrollToTopForLazyList(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
            )
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar registro") },
            text = { Text("¿Seguro que quieres eliminar este registro? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = pendingDelete
                        if (target != null) {
                            viewModel.deleteRegistro(target.registro.id)
                            if (detailRegistro?.registro?.id == target.registro.id) {
                                detailRegistro = null
                            }
                        }
                        pendingDelete = null
                    }
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (detailRegistro != null) {
            RegistroDetalleBottomSheet(
                registro = detailRegistro!!,
                factorCorreccionFallbackMgdlPorU = factorCorreccionFallback,
                onDismiss = { detailRegistro = null },
                onRequestDelete = {
                    pendingDelete = detailRegistro
                },
                onRequestCreateTemplate = {
                    val current = detailRegistro ?: return@RegistroDetalleBottomSheet
                    plantillaRegistro = current
                    plantillaNombre = "Plantilla ${DateUtils.formatDateTime(current.registro.fecha)}"
                },
                onUpdateDoseStatus = { nuevoEstado ->
                    val current = detailRegistro ?: return@RegistroDetalleBottomSheet
                    onUpdateDoseStatus(current, nuevoEstado)
                },
            onUpdateDoseCorrection = { conCorreccion ->
                val current = detailRegistro ?: return@RegistroDetalleBottomSheet
                onUpdateDoseCorrection(current, conCorreccion)
            },
                onUpdateDoseForLink = { unidades, confirmadaAt ->
                    val current = detailRegistro ?: return@RegistroDetalleBottomSheet
                    onUpdateDoseForLink(current, unidades, confirmadaAt)
                },
                activeInsulinState = detailActiveInsulinState,
                activeDoseCount = activeDoseCount,
                cargandoGlucosaRetroactiva = cargandoGlucosaRetroactiva
            )
    }

    if (plantillaRegistro != null) {
        AlertDialog(
            onDismissRequest = { plantillaRegistro = null },
            title = { Text("Crear plantilla") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Guarda este registro como plantilla reutilizable.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = plantillaNombre,
                        onValueChange = { plantillaNombre = it },
                        label = { Text("Nombre de la plantilla") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = plantillaRegistro
                        if (target != null) {
                            viewModel.createPlantillaFromRegistro(target, plantillaNombre)
                        }
                        plantillaRegistro = null
                    },
                    enabled = plantillaNombre.trim().isNotBlank()
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { plantillaRegistro = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun EmptyHistorialView(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.height(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Historial vacío",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tus registros aparecerán aquí para ayudarte a llevar un control.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun HistorialList(
    listState: LazyListState,
    registros: List<RegistroComidaConItems>,
    activeInsulinStatesByRegistroId: Map<Int, ActiveInsulinCardState?>,
    activeDoseCount: Int,
    onOpenDetail: (RegistroComidaConItems) -> Unit
) {
    val expandedDays = remember { mutableStateMapOf<Long, Boolean>() }
    val grouped = remember(registros) {
        registros
            .groupBy { DateUtils.getStartOfDay(it.registro.fecha) }
            .toSortedMap(compareByDescending { it })
    }
    val displayItems = buildList {
        grouped.forEach { (dayStart, registrosDia) ->
            add(HistorialListItem.Header(dayStart))
            val isExpanded = expandedDays[dayStart] ?: true
            if (isExpanded) {
                registrosDia.forEach { registro ->
                    add(HistorialListItem.Registro(registro))
                }
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp
        )
    ) {
        items(displayItems, key = { item ->
            when (item) {
                is HistorialListItem.Header -> "header_${item.dayStart}"
                is HistorialListItem.Registro -> "registro_${item.registro.registro.id}"
            }
        }) { item ->
            when (item) {
                is HistorialListItem.Header -> {
                    val isExpanded = expandedDays[item.dayStart] ?: true
                    DayHeader(
                        dayStart = item.dayStart,
                        isExpanded = isExpanded,
                        onToggle = {
                            expandedDays[item.dayStart] = !(expandedDays[item.dayStart] ?: true)
                        }
                    )
                }
                is HistorialListItem.Registro -> {
                    val activeInsulinState = activeInsulinStatesByRegistroId[item.registro.registro.id]
                    RegistroCard(
                        registro = item.registro,
                        activeInsulinState = activeInsulinState,
                        activeDoseCount = activeDoseCount,
                        onOpenDetail = { onOpenDetail(item.registro) }
                    )
                }
            }
        }
    }
}

private sealed class HistorialListItem {
    data class Header(val dayStart: Long) : HistorialListItem()
    data class Registro(val registro: RegistroComidaConItems) : HistorialListItem()
}

@Composable
private fun DayHeader(
    dayStart: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = DateUtils.getRelativeDayLabel(dayStart),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Colapsar día" else "Expandir día"
            )
        }
    }
}

@Composable
private fun RegistroCard(
    registro: RegistroComidaConItems,
    activeInsulinState: ActiveInsulinCardState?,
    activeDoseCount: Int,
    onOpenDetail: () -> Unit
) {
    val origenRegistro = OrigenRegistro.fromValue(registro.registro.origenRegistro)
    val isNightscoutImport = origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT
    val isNightscoutLinked = !registro.registro.nightscoutTreatmentId.isNullOrBlank()
    val estadoDosis = EstadoDosis.fromValue(registro.registro.dosisEstado)
    val insulinBreakdown = calculateInsulinBreakdown(registro)
    val activeIntensity = activeInsulinState?.intensity?.coerceIn(0f, 1f) ?: 0f
    val hasActiveInsulin = activeInsulinState != null
    val nightscoutState = nightscoutSyncState(
        origenRegistro = origenRegistro,
        isNightscoutLinked = isNightscoutLinked,
        estadoDosis = estadoDosis
    )
    val baseCardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val highlightedCardColor = lerp(
        start = baseCardColor,
        stop = MaterialTheme.colorScheme.primaryContainer,
        fraction = 0.14f + (0.24f * activeIntensity)
    )
    val activeCardInnerSurface = lerp(
        start = highlightedCardColor,
        stop = MaterialTheme.colorScheme.surface,
        fraction = 0.12f
    )
    val activeChipLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
    val activeHidratosValueColor = lerp(HistorialHidratosColor, MaterialTheme.colorScheme.onSurface, 0.34f)
    val activeRacionesValueColor = lerp(HistorialRacionesColor, MaterialTheme.colorScheme.onSurface, 0.34f)
    val activeInsulinaValueColor = lerp(HistorialInsulinaColor, MaterialTheme.colorScheme.onSurface, 0.34f)
    val cardModifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpenDetail)
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = if (hasActiveInsulin) {
                highlightedCardColor
            } else {
                baseCardColor
            }
        ),
        border = if (hasActiveInsulin) {
            BorderStroke(
                1.25.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f + (0.18f * activeIntensity))
            )
        } else {
            null
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (hasActiveInsulin) 12.dp else 16.dp
                )
        ) {
            val isCompactWidth = maxWidth <= 360.dp

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isNightscoutImport) {
                    if (isCompactWidth) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.height(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = DateUtils.formatDateTime(registro.registro.fecha),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = HistorialInsulinaColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = "${String.format("%.1f", registro.registro.unidadesInsulina)} U",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HistorialInsulinaColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            NightscoutSyncChip(state = nightscoutState)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.height(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = DateUtils.formatDateTime(registro.registro.fecha),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                color = HistorialInsulinaColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = "${String.format("%.1f", registro.registro.unidadesInsulina)} U",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HistorialInsulinaColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            NightscoutSyncChip(state = nightscoutState)
                        }
                    }
                } else {
                    if (isCompactWidth) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.height(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = DateUtils.getRelativeDate(registro.registro.fecha),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            DoseStatusBadge(estado = estadoDosis)
                            Spacer(modifier = Modifier.width(8.dp))
                            NightscoutSyncChip(state = nightscoutState)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.height(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = DateUtils.getRelativeDate(registro.registro.fecha),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                DoseStatusBadge(estado = estadoDosis)
                            }
                            NightscoutSyncChip(state = nightscoutState)
                        }
                    }
                }

                if (registro.items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        registro.items.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Restaurant,
                                    contentDescription = null,
                                    modifier = Modifier.height(12.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${item.alimento.nombre}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "(${cantidadConsumidaLabel(item.item.cantidadConsumida, item.item.unidadConsumida, item.item.gramosConsumidos)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (!registro.registro.notas.isNullOrBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (hasActiveInsulin) {
                                activeCardInnerSurface
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = registro.registro.notas!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                if (!isNightscoutImport) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DataChip(
                            modifier = Modifier.weight(1f),
                            label = "HIDRATOS",
                            value = "${String.format("%.1f", registro.registro.hidratosTotales)}g",
                            color = HistorialHidratosColor,
                            containerColorOverride = if (hasActiveInsulin) activeCardInnerSurface else null,
                            labelColorOverride = if (hasActiveInsulin) activeChipLabelColor else null,
                            valueColorOverride = if (hasActiveInsulin) activeHidratosValueColor else null,
                            isMain = true
                        )
                        DataChip(
                            modifier = Modifier.weight(1f),
                            label = "RACIONES",
                            value = String.format("%.1f", registro.registro.racionesCalculadas),
                            color = HistorialRacionesColor,
                            containerColorOverride = if (hasActiveInsulin) activeCardInnerSurface else null,
                            labelColorOverride = if (hasActiveInsulin) activeChipLabelColor else null,
                            valueColorOverride = if (hasActiveInsulin) activeRacionesValueColor else null,
                            isMain = true
                        )
                        DataChip(
                            modifier = Modifier.weight(1.2f),
                            label = "INSULINA",
                            value = "${String.format("%.1f", insulinBreakdown.total)} U",
                            color = HistorialInsulinaColor,
                            containerColorOverride = if (hasActiveInsulin) activeCardInnerSurface else null,
                            labelColorOverride = if (hasActiveInsulin) activeChipLabelColor else null,
                            valueColorOverride = if (hasActiveInsulin) activeInsulinaValueColor else null,
                            isMain = true
                        )
                    }
                }

                if (hasActiveInsulin) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        ActiveInsulinSourceChip(
                            activeUnits = activeInsulinState!!.activeUnits,
                            doseCount = activeDoseCount,
                            minutesRemaining = activeInsulinState.minutesRemaining,
                            intensity = activeIntensity
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RegistroDetalleBottomSheet(
    registro: RegistroComidaConItems,
    factorCorreccionFallbackMgdlPorU: Float?,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestCreateTemplate: () -> Unit,
    onUpdateDoseStatus: (EstadoDosis) -> Unit,
    onUpdateDoseCorrection: (Boolean?) -> Unit,
    onUpdateDoseForLink: (Float, Long?) -> Unit,
    activeInsulinState: ActiveInsulinCardState?,
    activeDoseCount: Int,
    cargandoGlucosaRetroactiva: Boolean = false
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val estadoDosis = EstadoDosis.fromValue(registro.registro.dosisEstado)
            val origenRegistro = OrigenRegistro.fromValue(registro.registro.origenRegistro)
            val isNightscoutImport = origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT
            val isNightscoutLinked = !registro.registro.nightscoutTreatmentId.isNullOrBlank()
            val nightscoutState = nightscoutSyncState(
                origenRegistro = origenRegistro,
                isNightscoutLinked = isNightscoutLinked,
                estadoDosis = estadoDosis
            )
            var enlaceHora by remember {
                mutableStateOf(DateUtils.formatTime(registro.registro.dosisConfirmadaAt ?: registro.registro.fecha))
            }
            var enlaceDosis by remember {
                mutableStateOf(String.format(Locale.getDefault(), "%.1f", registro.registro.unidadesInsulina))
            }
            var enlaceAjusteExpandido by remember(registro.registro.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Detalle del registro",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = DateUtils.formatDateTime(registro.registro.fecha),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isNightscoutImport &&
                            estadoDosis == EstadoDosis.APLICADA &&
                            registro.registro.dosisConfirmadaAt != null
                        ) {
                            Text(
                                text = " • ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Confirmada ${DateUtils.formatTime(registro.registro.dosisConfirmadaAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                NightscoutSyncChip(state = nightscoutState)
//                IconButton(onClick = onDismiss) {
//                    Icon(
//                        imageVector = Icons.Default.Close,
//                        contentDescription = "Cerrar detalle"
//                    )
//                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isNightscoutImport) {
                DataChip(
                    modifier = Modifier.fillMaxWidth(),
                    label = "DOSIS",
                    value = "${String.format("%.1f", registro.registro.unidadesInsulina)} U",
                    color = HistorialInsulinaColor,
                    isMain = true
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Glucosa",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (cargandoGlucosaRetroactiva) {
                    Text(
                        text = "Buscando glucosa retroactiva...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatDetailRow(
                    label = "Antes",
                    value = registro.registro.glucosaAntesMgdl?.let { "$it mg/dL" } ?: "—"
                )
                StatDetailRow(
                    label = "2h después",
                    value = registro.registro.glucosaDespues2hMgdl?.let { "$it mg/dL" } ?: "Pendiente"
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Notas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = registro.registro.notas?.takeIf { it.isNotBlank() } ?: "Sin notas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRequestDelete) {
                        Text("Eliminar registro", color = MaterialTheme.colorScheme.error)
                    }
                }
                return@Column
            }

            val ratioText = buildRatioText(
                registro = registro,
                fallbackFactorCorreccionMgdlPorU = factorCorreccionFallbackMgdlPorU
            )
            if (!isNightscoutImport && !ratioText.isNullOrBlank()) {
                Text(
                    text = ratioText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val insulinBreakdown = calculateInsulinBreakdown(registro)
            if (isNightscoutImport) {
                DataChip(
                    modifier = Modifier.fillMaxWidth(),
                    label = "DOSIS",
                    value = "${String.format("%.1f", insulinBreakdown.total)} U",
                    color = HistorialInsulinaColor,
                    isMain = true
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DataChip(
                        modifier = Modifier.weight(1f),
                        label = "HIDRATOS",
                        value = "${String.format("%.1f", registro.registro.hidratosTotales)}g",
                        color = HistorialHidratosColor,
                        isMain = true
                    )
                    DataChip(
                        modifier = Modifier.weight(1f),
                        label = "RACIONES",
                        value = String.format("%.1f", registro.registro.racionesCalculadas),
                        color = HistorialRacionesColor,
                        isMain = true
                    )
                    DataChip(
                        modifier = Modifier.weight(1.2f),
                        label = "INSULINA",
                        value = "${String.format("%.1f", insulinBreakdown.total)} U",
                        color = HistorialInsulinaColor,
                        isMain = true
                    )
                }
            }

            val contextoBadges = buildContextoBadges(
                registro = registro,
                colors = MaterialTheme.colorScheme
            )
            if (contextoBadges.isNotEmpty()) {
                Text(
                    text = "Factores contextuales",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    contextoBadges.forEach { badge ->
                        ContextBadgeChip(
                            label = badge.label,
                            color = badge.color
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Estado de dosis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                DoseStatusSelector(
                    estado = estadoDosis,
                    onStatusSelected = onUpdateDoseStatus
                )
            }

            if (activeInsulinState != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ActiveInsulinSourceChip(
                        activeUnits = activeInsulinState.activeUnits,
                        doseCount = activeDoseCount,
                        minutesRemaining = activeInsulinState.minutesRemaining,
                        intensity = activeInsulinState.intensity
                    )
                }
            }

            Text(
                text = "Desglose de insulina",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (isNightscoutImport) {
                StatDetailRow(
                    label = "Dosis",
                    value = formatUnits(insulinBreakdown.total)
                )
            } else {
                StatDetailRow(
                    label = "Alimentos",
                    value = formatUnits(insulinBreakdown.comida)
                )
                StatDetailRow(
                    label = "Corrección",
                    value = formatSignedUnits(insulinBreakdown.correccion)
                )
            }
            registro.registro.factorContextoTotalAplicado?.let { factorAplicado ->
                StatDetailRow(
                    label = "Factor contextual",
                    value = "×${String.format(Locale.getDefault(), "%.2f", factorAplicado)}"
                )
            }
            if (registro.registro.factorContextoCapado) {
                val raw = registro.registro.factorContextoTotalRaw
                val capText = if (raw != null) {
                    "Sí (×${String.format(Locale.getDefault(), "%.2f", raw)})"
                } else {
                    "Sí"
                }
                StatDetailRow(
                    label = "Límite de seguridad",
                    value = capText
                )
            }

            if (!isNightscoutImport) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (estadoDosis == EstadoDosis.APLICADA) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Insulina total",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            DoseCorrectionSelector(
                                conCorreccion = registro.registro.dosisConCorreccion,
                                onSelection = onUpdateDoseCorrection
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(11.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Text(
                    text = "Alimentos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                registro.items.forEach { item ->
                    val itemMetrics = calculateItemMetrics(
                        registro = registro,
                        itemHidratos = item.item.hidratosCalculados
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.alimento.nombre,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = cantidadConsumidaLabel(
                                    item.item.cantidadConsumida,
                                    item.item.unidadConsumida,
                                    item.item.gramosConsumidos
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = buildItemMetricsText(
                                hidratos = item.item.hidratosCalculados,
                                raciones = itemMetrics.raciones,
                                insulina = itemMetrics.insulina,
                                separatorColor = MaterialTheme.colorScheme.outline
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            val glucosaAntes = registro.registro.glucosaAntesMgdl
            val glucosaDespues = registro.registro.glucosaDespues2hMgdl
            if (glucosaAntes != null || glucosaDespues != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Glucosa",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                StatDetailRow(
                    label = "Antes",
                    value = glucosaAntes?.let { "$it mg/dL" } ?: "—"
                )
                StatDetailRow(
                    label = "2h después",
                    value = glucosaDespues?.let { "$it mg/dL" } ?: "Pendiente"
                )
            }

            if (!registro.registro.notas.isNullOrBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Notas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = registro.registro.notas!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isNightscoutImport) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val enlaceError = remember(enlaceHora, enlaceDosis) {
                    val parts = enlaceHora.split(":")
                    if (parts.size != 2) return@remember "Formato hora HH:mm"
                    val h = parts[0].toIntOrNull()
                    val m = parts[1].toIntOrNull()
                    if (h == null || m == null || h !in 0..23 || m !in 0..59) "Hora inválida" else null
                }
                val dosisRemota = registro.registro.unidadesInsulinaRemota
                val mostrarDiferenciaDosis = dosisRemota != null &&
                    kotlin.math.abs(dosisRemota - registro.registro.unidadesInsulina) > 0.5f
                val enlaceDosisValue = enlaceDosis.replace(',', '.').toFloatOrNull()

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { enlaceAjusteExpandido = !enlaceAjusteExpandido }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Ajuste para enlace Nightscout",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = if (enlaceAjusteExpandido) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (enlaceAjusteExpandido) "Colapsar ajuste" else "Expandir ajuste"
                            )
                        }

                        AnimatedVisibility(visible = enlaceAjusteExpandido) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .padding(bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (mostrarDiferenciaDosis) {
                                    StatDetailRow(
                                        label = "Dosis app",
                                        value = formatUnits(registro.registro.unidadesInsulina)
                                    )
                                    StatDetailRow(
                                        label = "Dosis Nightscout",
                                        value = formatUnits(dosisRemota!!)
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }

                                OutlinedTextField(
                                    value = enlaceHora,
                                    onValueChange = { enlaceHora = it },
                                    label = { Text("Hora confirmada (HH:mm)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = enlaceDosis,
                                    onValueChange = { enlaceDosis = it },
                                    label = { Text("Dosis para enlace (U)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        val parts = enlaceHora.split(":")
                                        val h = parts[0].toInt()
                                        val m = parts[1].toInt()
                                        val calendar = java.util.Calendar.getInstance().apply {
                                            timeInMillis = registro.registro.fecha
                                            set(java.util.Calendar.HOUR_OF_DAY, h)
                                            set(java.util.Calendar.MINUTE, m)
                                            set(java.util.Calendar.SECOND, 0)
                                            set(java.util.Calendar.MILLISECOND, 0)
                                        }
                                        val confirmAt = calendar.timeInMillis
                                        val units = enlaceDosisValue ?: registro.registro.unidadesInsulina
                                        onUpdateDoseForLink(units, confirmAt)
                                    },
                                    enabled = enlaceError == null && enlaceDosisValue != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Guardar ajuste de enlace")
                                }
                                if (enlaceError != null || enlaceDosisValue == null) {
                                    Text(
                                        text = enlaceError ?: "Dosis inválida",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onRequestCreateTemplate) {
                    Text("Crear plantilla")
                }
                TextButton(onClick = onRequestDelete) {
                    Text("Eliminar registro", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NightscoutSyncChip(
    state: NightscoutSyncState,
    modifier: Modifier = Modifier
) {
    val (icon, color, description) = when (state) {
        NightscoutSyncState.LINKED -> Triple(
            Icons.Default.Cloud,
            MaterialTheme.colorScheme.secondary,
            "Registro enlazado con Nightscout"
        )
        NightscoutSyncState.NOT_LINKED -> Triple(
            Icons.Default.CloudOff,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Registro no enlazado con Nightscout"
        )
        NightscoutSyncState.SERVER -> Triple(
            Icons.Default.CloudQueue,
            MaterialTheme.colorScheme.tertiary,
            "Registro importado desde Nightscout"
        )
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun ActiveInsulinSourceChip(
    activeUnits: Float,
    doseCount: Int,
    minutesRemaining: Int,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.primary
    val normalized = intensity.coerceIn(0f, 1f)
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.10f + (0.16f * normalized)),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = "Aporta insulina activa",
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${String.format(Locale.getDefault(), "%.1f", activeUnits)} U activas · $doseCount dosis · ${minutesRemaining} min restantes",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun rememberCurrentTimeTicker(
    refreshMs: Long = 60_000L
): State<Long> {
    return produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(refreshMs)
            value = System.currentTimeMillis()
        }
    }
}

private enum class NightscoutSyncState {
    LINKED,
    NOT_LINKED,
    SERVER
}

private data class ActiveInsulinCardState(
    val activeUnits: Float,
    val intensity: Float,
    val minutesRemaining: Int
)

private fun activeInsulinCardStateNow(
    registro: RegistroComida,
    nowMillis: Long
): ActiveInsulinCardState? {
    if (EstadoDosis.fromValue(registro.dosisEstado) != EstadoDosis.APLICADA) return null
    val reliable = OrigenRegistro.fromValue(registro.origenRegistro) == OrigenRegistro.NIGHTSCOUT_IMPORT ||
        !registro.nightscoutTreatmentId.isNullOrBlank()
    if (!reliable) return null

    val units = registro.unidadesInsulina
    if (!units.isFinite() || units <= 0f) return null

    val eventTime = registro.dosisConfirmadaAt ?: registro.fecha
    val elapsed = nowMillis - eventTime
    if (elapsed <= 0L || elapsed >= ACTIVE_INSULIN_WINDOW_MILLIS) return null

    val remainingMillis = (ACTIVE_INSULIN_WINDOW_MILLIS - elapsed).coerceAtLeast(0L)
    val intensity = (remainingMillis.toFloat() / ACTIVE_INSULIN_WINDOW_MILLIS.toFloat())
        .coerceIn(0f, 1f)
    val activeUnits = units * intensity
    val minutesRemaining = ((remainingMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

    return ActiveInsulinCardState(
        activeUnits = activeUnits,
        intensity = intensity,
        minutesRemaining = minutesRemaining
    )
}

private fun nightscoutSyncState(
    origenRegistro: OrigenRegistro,
    isNightscoutLinked: Boolean,
    estadoDosis: EstadoDosis
): NightscoutSyncState {
    return when {
        origenRegistro == OrigenRegistro.NIGHTSCOUT_IMPORT -> NightscoutSyncState.SERVER
        estadoDosis == EstadoDosis.OMITIDA -> NightscoutSyncState.NOT_LINKED
        isNightscoutLinked -> NightscoutSyncState.LINKED
        else -> NightscoutSyncState.NOT_LINKED
    }
}

@Composable
private fun DoseStatusBadge(
    estado: EstadoDosis
) {
    val color = when (estado) {
        EstadoDosis.PENDIENTE -> MaterialTheme.colorScheme.onSurfaceVariant
        EstadoDosis.APLICADA -> MaterialTheme.colorScheme.primary
        EstadoDosis.OMITIDA -> MaterialTheme.colorScheme.error
    }

    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = doseStatusIcon(estado),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = estado.label,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun StatDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DoseStatusSelector(
    estado: EstadoDosis,
    onStatusSelected: (EstadoDosis) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val color = when (estado) {
        EstadoDosis.PENDIENTE -> MaterialTheme.colorScheme.onSurfaceVariant
        EstadoDosis.APLICADA -> MaterialTheme.colorScheme.primary
        EstadoDosis.OMITIDA -> MaterialTheme.colorScheme.error
    }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(estado.label) },
            leadingIcon = {
                Icon(
                    imageVector = doseStatusIcon(estado),
                    contentDescription = null
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = color.copy(alpha = 0.14f),
                labelColor = color,
                leadingIconContentColor = color,
                trailingIconContentColor = color
            )
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            EstadoDosis.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onStatusSelected(option)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = doseStatusIcon(option),
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (option == estado) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

private enum class DoseCorrectionOption(
    val label: String,
    val value: Boolean?
) {
    WITH_CORRECTION("Dosis ajustada", true),
    WITHOUT_CORRECTION("Dosis sin ajustar", false),
    UNSPECIFIED("Sin marcar", null)
}

@Composable
private fun DoseCorrectionSelector(
    conCorreccion: Boolean?,
    onSelection: (Boolean?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = DoseCorrectionOption.values().firstOrNull { it.value == conCorreccion }
        ?: DoseCorrectionOption.UNSPECIFIED
    val color = when (conCorreccion) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.tertiary
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(selected.label) },
            leadingIcon = {
                Icon(
                    imageVector = doseCorrectionIcon(conCorreccion),
                    contentDescription = null
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = color.copy(alpha = 0.14f),
                labelColor = color,
                leadingIconContentColor = color,
                trailingIconContentColor = color
            )
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DoseCorrectionOption.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelection(option.value)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = doseCorrectionIcon(option.value),
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (option == selected) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

private fun doseStatusIcon(estado: EstadoDosis) = when (estado) {
    EstadoDosis.PENDIENTE -> Icons.Default.Schedule
    EstadoDosis.APLICADA -> Icons.Default.CheckCircle
    EstadoDosis.OMITIDA -> Icons.Default.Cancel
}

private fun doseCorrectionIcon(conCorreccion: Boolean?) = when (conCorreccion) {
    true -> Icons.Default.Add
    false -> Icons.Default.Remove
    null -> Icons.Default.HelpOutline
}

@Composable
private fun DataChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    valueIcon: ImageVector? = null,
    valueIconTint: Color? = null,
    containerColorOverride: Color? = null,
    labelColorOverride: Color? = null,
    valueColorOverride: Color? = null,
    isMain: Boolean = false
) {
    val density = LocalDensity.current
    var valueTextWidthPx by remember(value) { mutableStateOf(0) }
    val valueIconSize = 14.dp

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = containerColorOverride ?: if (isMain) {
                color.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColorOverride ?: MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val textWidthDp = with(density) { valueTextWidthPx.toDp() }
                val textStartDp = ((maxWidth - textWidthDp) / 2f).coerceAtLeast(0.dp)
                val iconOffsetFromStart =
                    ((textStartDp / 2f) - (valueIconSize / 2f)).coerceAtLeast(0.dp)

                Text(
                    text = value,
                    style = if (isMain) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = valueColorOverride ?: if (isMain) color else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    onTextLayout = { layoutResult ->
                        valueTextWidthPx = layoutResult.size.width
                    }
                )
                if (valueIcon != null) {
                    Icon(
                        imageVector = valueIcon,
                        contentDescription = null,
                        tint = valueIconTint ?: valueColorOverride ?: if (isMain) color else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = iconOffsetFromStart)
                            .size(valueIconSize)
                    )
                }
            }
        }
    }
}

private fun buildRatioText(
    registro: RegistroComidaConItems,
    fallbackFactorCorreccionMgdlPorU: Float? = null
): String? {
    val raciones = registro.registro.racionesCalculadas
    val hidratos = registro.registro.hidratosTotales
    val ratioHc = registro.registro.ratioInsulinaHc
        ?: if (hidratos > 0f) {
            registro.registro.unidadesInsulina / hidratos
        } else {
            null
        }
    val parts = mutableListOf<String>()

    if (ratioHc != null && ratioHc > 0f && !ratioHc.isNaN() &&
        raciones > 0f && !raciones.isNaN() &&
        hidratos > 0f && !hidratos.isNaN()
    ) {
        val gramosPorRacion = hidratos / raciones
        if (gramosPorRacion > 0f && !gramosPorRacion.isNaN()) {
            val ratioPorRacion = ratioHc * gramosPorRacion
            val formatU = if (ratioPorRacion >= 10f) "%.0f" else "%.1f"
            val formatG = if ((1f / ratioHc) >= 10f) "%.0f" else "%.2f"
            val uPorRacionText = String.format(formatU, ratioPorRacion)
            val gramosPorUnidadText = String.format(formatG, 1f / ratioHc)
            parts += "1 ración/$uPorRacionText U"
            parts += "1 U/$gramosPorUnidadText g"
        }
    }

    val factorCorreccion = registro.registro.factorCorreccionMgdlPorUUsado
        ?: fallbackFactorCorreccionMgdlPorU
    val factorText = if (factorCorreccion != null && factorCorreccion > 0f && !factorCorreccion.isNaN()) {
        "FC ${formatFactorCorreccion(factorCorreccion)} mg/dL/U"
    } else {
        "FC N/D"
    }
    parts += factorText

    return if (parts.isEmpty()) null else parts.joinToString(" • ")
}

@Composable
private fun ContextBadgeChip(
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private data class ContextBadgeData(
    val label: String,
    val color: androidx.compose.ui.graphics.Color
)

private fun buildContextoBadges(
    registro: RegistroComidaConItems,
    colors: ColorScheme
): List<ContextBadgeData> {
    return buildList {
        FranjaHoraria.fromStorage(registro.registro.franjaHorariaUsada)?.let { franja ->
            add(
                ContextBadgeData(
                    label = "Hora: ${FactoresContextoInsulina.franjaLabel(franja)}",
                    color = franjaChipColor(franja, colors)
                )
            )
        }
        NivelEstres.fromStorage(registro.registro.nivelEstresUsado)
            ?.takeIf { it != NivelEstres.NINGUNO }
            ?.let { estres ->
                add(
                    ContextBadgeData(
                        label = "Estrés: ${FactoresContextoInsulina.estresLabel(estres)}",
                        color = estresChipColor(estres, colors)
                    )
                )
            }
        NivelEnfermedad.fromStorage(registro.registro.nivelEnfermedadUsado)
            ?.takeIf { it != NivelEnfermedad.NINGUNA }
            ?.let { enfermedad ->
                add(
                    ContextBadgeData(
                        label = "Enfermedad: ${FactoresContextoInsulina.enfermedadLabel(enfermedad)}",
                        color = enfermedadChipColor(enfermedad, colors)
                    )
                )
            }
        FaseCicloHormonal.fromStorage(registro.registro.faseCicloUsada)
            ?.takeIf { it != FaseCicloHormonal.NO_APLICAR }
            ?.let { fase ->
                add(
                    ContextBadgeData(
                        label = "Ciclo: ${FactoresContextoInsulina.cicloLabel(fase)}",
                        color = cicloChipColor(fase, colors)
                    )
                )
            }
        NivelEjercicio.fromStorage(registro.registro.nivelEjercicioUsado)
            ?.takeIf { it != NivelEjercicio.NINGUNO }
            ?.let { ejercicio ->
                add(
                    ContextBadgeData(
                        label = "Ejercicio: ${FactoresContextoInsulina.ejercicioLabel(ejercicio)}",
                        color = ejercicioChipColor(ejercicio, colors)
                    )
                )
            }
    }
}

private fun franjaChipColor(
    franja: FranjaHoraria,
    colors: ColorScheme
): androidx.compose.ui.graphics.Color {
    return when (franja) {
        FranjaHoraria.MADRUGADA -> colors.tertiary
        FranjaHoraria.MANANA -> HistorialWarningColor
        FranjaHoraria.TARDE -> HistorialHidratosColor
        FranjaHoraria.NOCHE -> colors.primary
    }
}

private fun estresChipColor(
    nivel: NivelEstres,
    colors: ColorScheme
): androidx.compose.ui.graphics.Color {
    return when (nivel) {
        NivelEstres.NINGUNO -> colors.outline
        NivelEstres.LEVE -> colors.tertiary
        NivelEstres.MODERADO -> moderateSeverityColor(colors)
        NivelEstres.ALTO -> highSeverityColor(colors)
    }
}

private fun moreReddish(base: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(
        red = (base.red + 0.08f).coerceAtMost(1f),
        green = (base.green * 0.72f).coerceIn(0f, 1f),
        blue = (base.blue * 0.72f).coerceIn(0f, 1f),
        alpha = 1f
    )
}

private fun enfermedadChipColor(
    nivel: NivelEnfermedad,
    colors: ColorScheme
): androidx.compose.ui.graphics.Color {
    return when (nivel) {
        NivelEnfermedad.NINGUNA -> colors.outline
        NivelEnfermedad.LEVE -> colors.tertiary
        NivelEnfermedad.MODERADA -> moderateSeverityColor(colors)
        NivelEnfermedad.ALTA -> highSeverityColor(colors)
    }
}

private fun cicloChipColor(
    fase: FaseCicloHormonal,
    colors: ColorScheme
): androidx.compose.ui.graphics.Color {
    return when (fase) {
        FaseCicloHormonal.NO_APLICAR -> colors.outline
        FaseCicloHormonal.MENSTRUACION -> colors.error
        FaseCicloHormonal.FOLICULAR -> colors.secondary
        FaseCicloHormonal.OVULACION -> colors.primary
        FaseCicloHormonal.LUTEA -> colors.tertiary
    }
}

private fun ejercicioChipColor(
    nivel: NivelEjercicio,
    colors: ColorScheme
): androidx.compose.ui.graphics.Color {
    return when (nivel) {
        NivelEjercicio.NINGUNO -> colors.outline
        NivelEjercicio.SUAVE -> colors.tertiary
        NivelEjercicio.MODERADO -> moderateSeverityColor(colors)
        NivelEjercicio.INTENSO -> highSeverityColor(colors)
    }
}

private fun moderateSeverityColor(colors: ColorScheme): androidx.compose.ui.graphics.Color {
    val isLightPalette = colors.error.luminance() < 0.30f
    return if (isLightPalette) HistorialWarningColor else colors.error
}

private fun highSeverityColor(colors: ColorScheme): androidx.compose.ui.graphics.Color {
    val errorBase = colors.error
    return if (errorBase.luminance() < 0.30f) {
        errorBase
    } else {
        moreReddish(errorBase)
    }
}

private fun formatFactorCorreccion(value: Float): String {
    val isInteger = kotlin.math.abs(value - value.toInt().toFloat()) < 0.05f
    return if (isInteger) {
        String.format("%.0f", value)
    } else {
        String.format("%.1f", value)
    }
}

private fun cantidadConsumidaLabel(
    cantidadConsumida: Float,
    unidadConsumida: String?,
    gramosConsumidos: Float
): String {
    val cantidad = if (cantidadConsumida > 0f) cantidadConsumida else gramosConsumidos
    val unidad = when (unidadConsumida) {
        "ml" -> "ml"
        "unidad" -> "ud"
        else -> "g"
    }
    val isInteger = kotlin.math.abs(cantidad - cantidad.toInt().toFloat()) < 0.05f
    val valor = if (isInteger) String.format("%.0f", cantidad) else String.format("%.1f", cantidad)
    return "$valor $unidad"
}

private data class ItemMetrics(
    val raciones: Float?,
    val insulina: Float?
)

private data class InsulinBreakdown(
    val comida: Float,
    val correccion: Float,
    val total: Float
)

private fun calculateInsulinBreakdown(
    registro: RegistroComidaConItems
): InsulinBreakdown {
    val totalGuardado = registro.registro.unidadesInsulina
    val hidratos = registro.registro.hidratosTotales
    val ratioHc = registro.registro.ratioInsulinaHc
    val factorContexto = registro.registro.factorContextoTotalAplicado
        ?.takeIf { it.isFinite() && it > 0f }
        ?: 1f

    val comidaBaseRaw = if (ratioHc != null && ratioHc > 0f && !ratioHc.isNaN() && hidratos > 0f) {
        hidratos * ratioHc
    } else {
        totalGuardado / factorContexto
    }
    val comida = roundToHalf((comidaBaseRaw * factorContexto).coerceAtLeast(0f))

    val correccionRaw = registro.registro.unidadesCorreccionSugerida ?: ((totalGuardado / factorContexto) - comidaBaseRaw)
    val correccion = if (kotlin.math.abs(correccionRaw) < 0.05f) 0f else correccionRaw
    val totalMostrado = if (registro.registro.dosisConCorreccion == false) {
        comida
    } else {
        totalGuardado
    }

    return InsulinBreakdown(
        comida = comida,
        correccion = correccion,
        total = totalMostrado
    )
}

private fun formatUnits(value: Float): String = "${String.format("%.1f", value)} U"

private fun formatSignedUnits(value: Float): String {
    val magnitude = String.format("%.1f", kotlin.math.abs(value))
    return if (value >= 0f) "+$magnitude U" else "-$magnitude U"
}

private fun roundToHalf(value: Float): Float = kotlin.math.round(value * 2f) / 2f

private fun calculateItemMetrics(
    registro: RegistroComidaConItems,
    itemHidratos: Float
): ItemMetrics {
    if (itemHidratos <= 0f) return ItemMetrics(raciones = 0f, insulina = 0f)

    val totalHidratos = registro.registro.hidratosTotales
    val totalRaciones = registro.registro.racionesCalculadas
    val totalInsulina = registro.registro.unidadesInsulina

    val racionesItem = if (totalHidratos > 0f && totalRaciones >= 0f) {
        (itemHidratos / totalHidratos) * totalRaciones
    } else {
        null
    }

    val ratioInsulinaHc = registro.registro.ratioInsulinaHc
    val insulinaItem = when {
        ratioInsulinaHc != null && ratioInsulinaHc > 0f && !ratioInsulinaHc.isNaN() -> {
            itemHidratos * ratioInsulinaHc
        }
        totalHidratos > 0f -> {
            (itemHidratos / totalHidratos) * totalInsulina
        }
        else -> null
    }

    return ItemMetrics(
        raciones = racionesItem,
        insulina = insulinaItem
    )
}

private fun buildItemMetricsText(
    hidratos: Float,
    raciones: Float?,
    insulina: Float?,
    separatorColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    val rText = raciones?.let { String.format("%.2f", it) } ?: "N/D"
    val uText = insulina?.let { String.format("%.2f", it) } ?: "N/D"
    val hText = "${String.format("%.1f", hidratos)} g HC"

    return buildAnnotatedString {
        withStyle(SpanStyle(color = HistorialHidratosColor)) {
            append(hText)
        }
        withStyle(SpanStyle(color = separatorColor)) {
            append(" • ")
        }
        withStyle(SpanStyle(color = HistorialRacionesColor)) {
            append("$rText R")
        }
        withStyle(SpanStyle(color = separatorColor)) {
            append(" • ")
        }
        withStyle(SpanStyle(color = HistorialInsulinaColor)) {
            append("$uText U")
        }
    }
}
