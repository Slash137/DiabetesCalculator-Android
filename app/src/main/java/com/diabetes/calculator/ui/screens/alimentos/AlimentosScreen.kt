package com.diabetes.calculator.ui.screens.alimentos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.EstadoFisicoAlimento
import com.diabetes.calculator.data.entity.TipoMedicionAlimento
import com.diabetes.calculator.data.entity.estadoFisicoNormalizado
import com.diabetes.calculator.data.entity.tipoMedicionNormalizado
import com.diabetes.calculator.data.entity.usaReferenciaPor100ml
import com.diabetes.calculator.ui.theme.HidratosColor

/**
 * Pantalla de listado de alimentos.
 * Permite ver, añadir, editar y eliminar alimentos.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlimentosScreen(
    viewModel: AlimentosViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val showDialog by viewModel.showDialog.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedAlimentoId by viewModel.selectedAlimentoId.collectAsState()
    var pendingDelete by remember { mutableStateOf<Alimento?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val alimentosActuales = (uiState as? AlimentosUiState.Success)?.alimentos.orEmpty()
    val selectedAlimento = alimentosActuales.firstOrNull { it.id == selectedAlimentoId }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
            ) {
                if (showDialog) {
                    AlimentoEditorScreen(
                        viewModel = viewModel,
                        onBack = viewModel::closeDialog
                    )
                } else if (selectedAlimento != null) {
                    AlimentoDetailScreen(
                        alimento = selectedAlimento,
                        onBack = viewModel::closeDetail,
                        onEdit = { viewModel.openEditDialog(selectedAlimento) },
                        onDelete = { pendingDelete = selectedAlimento }
                    )
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        label = { Text("Buscar alimento") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    when (val state = uiState) {
                        is AlimentosUiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        is AlimentosUiState.Empty -> {
                            EmptyAlimentosView(
                                modifier = Modifier.fillMaxSize(),
                                onAddClick = viewModel::openAddDialog
                            )
                        }

                        is AlimentosUiState.Success -> {
                            AlimentosList(
                                alimentos = state.alimentos,
                                onOpen = viewModel::openDetail,
                                onEdit = viewModel::openEditDialog,
                                onDelete = { pendingDelete = it }
                            )
                        }

                        is AlimentosUiState.Error -> {
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
        }

        if (selectedAlimento == null && !showDialog) {
            FloatingActionButton(
                onClick = viewModel::openAddDialog,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Añadir alimento")
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar alimento") },
            text = { Text("¿Seguro que quieres eliminar este alimento? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = pendingDelete
                        if (target != null) {
                            viewModel.deleteAlimento(target)
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
}

@Composable
private fun EmptyAlimentosView(
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Restaurant,
            contentDescription = null,
            modifier = Modifier.height(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Tu biblioteca está vacía",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Añade alimentos comunes para calcular tus comidas más rápido.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onAddClick) {
            Text("Crear primer alimento")
        }
    }
}

@Composable
private fun AlimentosList(
    alimentos: List<Alimento>,
    onOpen: (Alimento) -> Unit,
    onEdit: (Alimento) -> Unit,
    onDelete: (Alimento) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp
        )
    ) {
        items(alimentos, key = { it.id }) { alimento ->
            AlimentoCard(
                alimento = alimento,
                onOpen = { onOpen(alimento) },
                onEdit = { onEdit(alimento) },
                onDelete = { onDelete(alimento) }
            )
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun AlimentoCard(
    alimento: Alimento,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alimento.nombre,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hidratosResumen(alimento),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HidratosColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${tipoLabel(alimento.tipoMedicionNormalizado())} • ${estadoLabel(alimento.estadoFisicoNormalizado())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                val fuenteText = alimento.fuente.takeIf { it.isNotBlank() }
                val notaText = alimento.nota?.takeIf { it.isNotBlank() }
                if (fuenteText != null || notaText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = listOfNotNull(fuenteText, notaText).joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editar",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(20.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AlimentoDetailScreen(
    alimento: Alimento,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
                Text(
                    text = "Ficha de alimento",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Identificación", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                DetailRow("Nombre", alimento.nombre)
                DetailRow("Fuente", alimento.fuente.ifBlank { "-" })
                DetailRow("Nota", alimento.nota?.ifBlank { "-" } ?: "-")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Medición", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                DetailRow("Tipo", tipoLabel(alimento.tipoMedicionNormalizado()))
                DetailRow("Estado", estadoLabel(alimento.estadoFisicoNormalizado()))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Nutrición", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (alimento.usaReferenciaPor100ml()) {
                    DetailRow("HC por 100ml", "${formatDecimal(alimento.hidratosPor100ml ?: 0f)} g")
                } else {
                    DetailRow("HC por 100g", "${formatDecimal(alimento.hidratosPor100g)} g")
                }
                if (alimento.hidratosPor100ml != null && !alimento.usaReferenciaPor100ml()) {
                    DetailRow("HC por 100ml (extra)", "${formatDecimal(alimento.hidratosPor100ml)} g")
                }
            }
        }

        if (alimento.tipoMedicionNormalizado() == TipoMedicionAlimento.UNIDAD) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Equivalencias", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    DetailRow("Unidad", alimento.unidadNombre ?: "-")
                    if (alimento.estadoFisicoNormalizado() == EstadoFisicoAlimento.LIQUIDO) {
                        DetailRow("ml por unidad", alimento.mlPorUnidad?.let { formatDecimal(it) }?.plus(" ml") ?: "-")
                    } else {
                        DetailRow("g por unidad", alimento.gramosPorUnidad?.let { formatDecimal(it) }?.plus(" g") ?: "-")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AlimentoEditorScreen(
    viewModel: AlimentosViewModel,
    onBack: () -> Unit
) {
    val editingAlimento by viewModel.editingAlimento.collectAsState()
    val nombre by viewModel.dialogNombre.collectAsState()
    val hidratos100g by viewModel.dialogHidratos100g.collectAsState()
    val hidratos100ml by viewModel.dialogHidratos100ml.collectAsState()
    val tipoMedicion by viewModel.dialogTipoMedicion.collectAsState()
    val estadoFisico by viewModel.dialogEstadoFisico.collectAsState()
    val unidadPreset by viewModel.dialogUnidadPreset.collectAsState()
    val unidadCustom by viewModel.dialogUnidadCustom.collectAsState()
    val gramosPorUnidad by viewModel.dialogGramosPorUnidad.collectAsState()
    val mlPorUnidad by viewModel.dialogMlPorUnidad.collectAsState()
    val fuente by viewModel.dialogFuente.collectAsState()
    val nota by viewModel.dialogNota.collectAsState()

    val isEditing = editingAlimento != null
    val canSave = viewModel.canSaveDialog()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
                Text(
                    text = if (isEditing) "Editar alimento" else "Nuevo alimento",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = viewModel::saveAlimento,
                enabled = canSave,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Guardar")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = viewModel::updateDialogNombre,
                    label = { Text("Nombre") },
                    placeholder = { Text("Ej: Manzana") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    text = "Tipo de medición",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TipoMedicionAlimento.all.forEach { option ->
                        FilterChip(
                            selected = tipoMedicion == option,
                            onClick = { viewModel.updateDialogTipoMedicion(option) },
                            label = { Text(tipoLabel(option)) }
                        )
                    }
                }

                if (tipoMedicion == TipoMedicionAlimento.UNIDAD) {
                    Text(
                        text = "Estado físico",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EstadoFisicoAlimento.all.forEach { option ->
                            FilterChip(
                                selected = estadoFisico == option,
                                onClick = { viewModel.updateDialogEstadoFisico(option) },
                                label = { Text(estadoLabel(option)) }
                            )
                        }
                    }
                }

                if (tipoMedicion == TipoMedicionAlimento.GRAMOS ||
                    (tipoMedicion == TipoMedicionAlimento.UNIDAD && estadoFisico != EstadoFisicoAlimento.LIQUIDO)
                ) {
                    OutlinedTextField(
                        value = hidratos100g,
                        onValueChange = viewModel::updateDialogHidratos100g,
                        label = { Text("Hidratos por 100g") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        suffix = { Text("g") },
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (tipoMedicion == TipoMedicionAlimento.ML ||
                    (tipoMedicion == TipoMedicionAlimento.UNIDAD && estadoFisico == EstadoFisicoAlimento.LIQUIDO)
                ) {
                    OutlinedTextField(
                        value = hidratos100ml,
                        onValueChange = viewModel::updateDialogHidratos100ml,
                        label = { Text("Hidratos por 100ml") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        suffix = { Text("g") },
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (tipoMedicion == TipoMedicionAlimento.UNIDAD) {
                    Text(
                        text = "Nombre de unidad",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val opcionesUnidad = AlimentosViewModel.UNIDADES_RAPIDAS +
                            AlimentosViewModel.UNIDAD_PERSONALIZADA
                        opcionesUnidad.forEach { unidad ->
                            FilterChip(
                                selected = unidadPreset == unidad,
                                onClick = { viewModel.updateDialogUnidadPreset(unidad) },
                                label = {
                                    Text(
                                        if (unidad == AlimentosViewModel.UNIDAD_PERSONALIZADA) {
                                            "Personalizado"
                                        } else {
                                            unidad
                                        }
                                    )
                                }
                            )
                        }
                    }
                    if (unidadPreset == AlimentosViewModel.UNIDAD_PERSONALIZADA) {
                        OutlinedTextField(
                            value = unidadCustom,
                            onValueChange = viewModel::updateDialogUnidadCustom,
                            label = { Text("Unidad personalizada") },
                            placeholder = { Text("Ej: botellín") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    if (estadoFisico == EstadoFisicoAlimento.LIQUIDO) {
                        OutlinedTextField(
                            value = mlPorUnidad,
                            onValueChange = viewModel::updateDialogMlPorUnidad,
                            label = { Text("ml por unidad") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            suffix = { Text("ml") },
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = gramosPorUnidad,
                            onValueChange = viewModel::updateDialogGramosPorUnidad,
                            label = { Text("g por unidad") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            suffix = { Text("g") },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = fuente,
                    onValueChange = viewModel::updateDialogFuente,
                    label = { Text("Fuente") },
                    placeholder = { Text("Ej: Etiqueta, App...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = nota,
                    onValueChange = viewModel::updateDialogNota,
                    label = { Text("Nota (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

private fun tipoLabel(tipo: String): String {
    return when (tipo) {
        TipoMedicionAlimento.GRAMOS -> "Peso (g)"
        TipoMedicionAlimento.ML -> "Volumen (ml)"
        TipoMedicionAlimento.UNIDAD -> "Por unidad"
        else -> tipo
    }
}

private fun estadoLabel(estado: String): String {
    return when (estado) {
        EstadoFisicoAlimento.SOLIDO -> "Sólido"
        EstadoFisicoAlimento.SOLIDO_BLANDO -> "Sólido blando"
        EstadoFisicoAlimento.LIQUIDO -> "Líquido"
        else -> estado
    }
}

private fun hidratosResumen(alimento: Alimento): String {
    return if (alimento.usaReferenciaPor100ml()) {
        "${formatDecimal(alimento.hidratosPor100ml ?: 0f)}g HC / 100ml"
    } else {
        "${formatDecimal(alimento.hidratosPor100g)}g HC / 100g"
    }
}

private fun formatDecimal(value: Float): String {
    return if (value % 1f == 0f) {
        String.format("%.0f", value)
    } else {
        String.format("%.1f", value)
    }
}
