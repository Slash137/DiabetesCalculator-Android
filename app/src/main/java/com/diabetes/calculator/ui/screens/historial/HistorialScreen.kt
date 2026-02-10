package com.diabetes.calculator.ui.screens.historial

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.diabetes.calculator.data.dao.RegistroComidaConItems
import com.diabetes.calculator.ui.components.AvisoMedicoCompacto
import com.diabetes.calculator.ui.theme.HidratosColor
import com.diabetes.calculator.ui.theme.InsulinaColor
import com.diabetes.calculator.ui.theme.RacionesColor
import com.diabetes.calculator.util.DateUtils

/**
 * Pantalla de historial de comidas con diseño Material You coherente.
 */
@Composable
fun HistorialScreen(
    viewModel: HistorialViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val dayFilter by viewModel.dayFilter.collectAsState()
    var showFilterMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RegistroComidaConItems?>(null) }
    var detailRegistro by remember { mutableStateOf<RegistroComidaConItems?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
        ) {
            AvisoMedicoCompacto(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Buscar en historial...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    OutlinedButton(
                        onClick = { showFilterMenu = true },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(dayFilter.label)
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DayFilter.values().forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(filter.label) },
                                onClick = {
                                    viewModel.updateDayFilter(filter)
                                    showFilterMenu = false
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
                        registros = state.registros,
                        onOpenDetail = { detailRegistro = it },
                        onDelete = { pendingDelete = it }
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
            onDismiss = { detailRegistro = null },
            onRequestDelete = {
                pendingDelete = detailRegistro
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
    registros: List<RegistroComidaConItems>,
    onOpenDetail: (RegistroComidaConItems) -> Unit,
    onDelete: (RegistroComidaConItems) -> Unit
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
                    RegistroCard(
                        registro = item.registro,
                        onOpenDetail = { onOpenDetail(item.registro) },
                        onDelete = { onDelete(item.registro) }
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
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ratioText = buildRatioText(registro)

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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!ratioText.isNullOrBlank()) {
                        Text(
                            text = ratioText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Eliminar",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            registro.items.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
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
                        text = "(${item.item.gramosConsumidos.toInt()}g)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!registro.registro.notas.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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

            Spacer(modifier = Modifier.height(16.dp))

            // Resumen de datos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataChip(
                    modifier = Modifier.weight(1f),
                    label = "HIDRATOS",
                    value = "${String.format("%.1f", registro.registro.hidratosTotales)}g",
                    color = HidratosColor,
                    isMain = true
                )
                DataChip(
                    modifier = Modifier.weight(1f),
                    label = "RACIONES",
                    value = String.format("%.1f", registro.registro.racionesCalculadas),
                    color = RacionesColor,
                    isMain = true
                )
                DataChip(
                    modifier = Modifier.weight(1.2f),
                    label = "INSULINA",
                    value = "${String.format("%.1f", registro.registro.unidadesInsulina)} U",
                    color = InsulinaColor,
                    isMain = true
                )
            }

            val glucosaAntes = registro.registro.glucosaAntesMgdl
            val glucosaDespues = registro.registro.glucosaDespues2hMgdl
            if (glucosaAntes != null || glucosaDespues != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlucosaInfo(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        label = "Antes",
                        value = glucosaAntes?.toString() ?: "—",
                        alignEnd = false
                    )
                    GlucosaInfo(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        label = "2h después",
                        value = glucosaDespues?.toString() ?: "Pendiente",
                        alignEnd = true
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RegistroDetalleBottomSheet(
    registro: RegistroComidaConItems,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    Text(
                        text = DateUtils.formatDateTime(registro.registro.fecha),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar detalle"
                    )
                }
            }

            val ratioText = buildRatioText(registro)
            if (!ratioText.isNullOrBlank()) {
                Text(
                    text = ratioText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataChip(
                    modifier = Modifier.weight(1f),
                    label = "HIDRATOS",
                    value = "${String.format("%.1f", registro.registro.hidratosTotales)}g",
                    color = HidratosColor,
                    isMain = true
                )
                DataChip(
                    modifier = Modifier.weight(1f),
                    label = "RACIONES",
                    value = String.format("%.1f", registro.registro.racionesCalculadas),
                    color = RacionesColor,
                    isMain = true
                )
                DataChip(
                    modifier = Modifier.weight(1.2f),
                    label = "INSULINA",
                    value = "${String.format("%.1f", registro.registro.unidadesInsulina)} U",
                    color = InsulinaColor,
                    isMain = true
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                            text = "${item.item.gramosConsumidos.toInt()} g",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRequestDelete) {
                    Text("Eliminar registro", color = MaterialTheme.colorScheme.error)
                }
            }
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
private fun DataChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    isMain: Boolean = false
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isMain) color.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
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
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = value,
                style = if (isMain) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (isMain) color else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun buildRatioText(registro: RegistroComidaConItems): String? {
    val raciones = registro.registro.racionesCalculadas
    val hidratos = registro.registro.hidratosTotales
    val ratioHc = registro.registro.ratioInsulinaHc
        ?: if (hidratos > 0f) {
            registro.registro.unidadesInsulina / hidratos
        } else {
            null
        }

    if (ratioHc == null || ratioHc <= 0f || ratioHc.isNaN()) return null
    if (raciones <= 0f || raciones.isNaN()) return null

    val gramosPorRacion = if (hidratos > 0f) hidratos / raciones else 0f
    if (gramosPorRacion <= 0f || gramosPorRacion.isNaN()) return null

    val ratioPorRacion = ratioHc * gramosPorRacion

    val formatU = if (ratioPorRacion >= 10f) "%.0f" else "%.1f"
    val formatG = if (1f / ratioHc >= 10f) "%.0f" else "%.2f"

    val uPorRacionText = String.format(formatU, ratioPorRacion)
    val gramosPorUnidadText = String.format(formatG, 1f / ratioHc)

    return "1 ración/$uPorRacionText U • 1 U/$gramosPorUnidadText g"
}

private data class ItemMetrics(
    val raciones: Float?,
    val insulina: Float?
)

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
        withStyle(SpanStyle(color = HidratosColor)) {
            append(hText)
        }
        withStyle(SpanStyle(color = separatorColor)) {
            append(" • ")
        }
        withStyle(SpanStyle(color = RacionesColor)) {
            append("$rText R")
        }
        withStyle(SpanStyle(color = separatorColor)) {
            append(" • ")
        }
        withStyle(SpanStyle(color = InsulinaColor)) {
            append("$uText U")
        }
    }
}

@Composable
private fun GlucosaInfo(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    alignEnd: Boolean = false
) {
    val arrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = arrangement
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = if (value != "—" && value != "Pend." && value != "Pendiente") "$value mg/dL" else value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
