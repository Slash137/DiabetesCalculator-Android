package com.diabetes.calculator.ui.screens.nuevacomida

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.dao.PlantillaConItems
import com.diabetes.calculator.domain.FactoresContextoInsulina
import com.diabetes.calculator.domain.FaseCicloHormonal
import com.diabetes.calculator.domain.FranjaHoraria
import com.diabetes.calculator.domain.NivelEjercicio
import com.diabetes.calculator.domain.NivelEnfermedad
import com.diabetes.calculator.domain.NivelEstres
import com.diabetes.calculator.ui.components.AvisoMedico
import com.diabetes.calculator.ui.theme.HidratosColor
import com.diabetes.calculator.ui.theme.InsulinaColor
import com.diabetes.calculator.ui.theme.RacionesColor
import com.diabetes.calculator.ui.theme.WarningColor
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale

/**
 * Pantalla para registrar una nueva comida.
 * Muestra cálculos en tiempo real de hidratos, raciones e insulina.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuevaComidaScreen(
    viewModel: NuevaComidaViewModel,
    onNavigateToProfile: () -> Unit = {},
    currentGlucoseMgdl: Int? = null,
    tabChangeSignal: Int = 0
) {
    val uiState by viewModel.uiState.collectAsState()
    val items by viewModel.items.collectAsState()
    val calculo by viewModel.calculo.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val plantillas by viewModel.plantillas.collectAsState()
    val notas by viewModel.notas.collectAsState()
    val dosisConCorreccion by viewModel.dosisConCorreccion.collectAsState()
    val franjaHoraria by viewModel.franjaHoraria.collectAsState()
    val nivelEstres by viewModel.nivelEstres.collectAsState()
    val nivelEnfermedad by viewModel.nivelEnfermedad.collectAsState()
    val faseCiclo by viewModel.faseCiclo.collectAsState()
    val nivelEjercicio by viewModel.nivelEjercicio.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showPlantillasDialog by remember { mutableStateOf(false) }
    var showSavePlantillaDialog by remember { mutableStateOf(false) }
    var plantillaNombre by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    DisposableEffect(lifecycleOwner, snackbarHostState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            snackbarHostState.showSnackbar("Comida guardada correctamente")
            viewModel.resetSaveSuccess()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(tabChangeSignal) {
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    LaunchedEffect(currentGlucoseMgdl) {
        viewModel.updateGlucosaActual(currentGlucoseMgdl)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is NuevaComidaUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is NuevaComidaUiState.NoProfile -> {
                NoProfileView(
                    modifier = Modifier.fillMaxSize(),
                    onNavigateToProfile = onNavigateToProfile
                )
            }
            is NuevaComidaUiState.Ready -> {
                NuevaComidaContent(
                    alimentos = state.alimentos,
                    items = items,
                    calculo = calculo,
                    isSaving = isSaving,
                    searchQuery = searchQuery,
                    notas = notas,
                    dosisConCorreccion = dosisConCorreccion,
                    franjaHoraria = franjaHoraria,
                    nivelEstres = nivelEstres,
                    nivelEnfermedad = nivelEnfermedad,
                    faseCiclo = faseCiclo,
                    nivelEjercicio = nivelEjercicio,
                    cicloHormonalActivo = state.profile.cicloHormonalActivo,
                    plantillas = plantillas,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onNotasChange = viewModel::updateNotas,
                    onDosisConCorreccionChange = viewModel::updateDosisConCorreccion,
                    onFranjaHorariaChange = viewModel::updateFranjaHoraria,
                    onNivelEstresChange = viewModel::updateNivelEstres,
                    onNivelEnfermedadChange = viewModel::updateNivelEnfermedad,
                    onFaseCicloChange = viewModel::updateFaseCiclo,
                    onNivelEjercicioChange = viewModel::updateNivelEjercicio,
                    onAddItem = viewModel::addItem,
                    onRemoveItem = viewModel::removeItem,
                    onUpdateItemAlimento = viewModel::updateItemAlimento,
                    onUpdateItemGramos = viewModel::updateItemGramos,
                    onSave = viewModel::saveRegistro,
                    canSave = viewModel.canSave(),
                    onOpenPlantillas = { showPlantillasDialog = true },
                    onSavePlantilla = { showSavePlantillaDialog = true }
                )
            }
            is NuevaComidaUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showPlantillasDialog) {
        PlantillasDialog(
            plantillas = plantillas,
            gramosPorRacion = (uiState as? NuevaComidaUiState.Ready)?.profile?.gramosPorRacion ?: 10f,
            onDismiss = { showPlantillasDialog = false },
            onApply = {
                viewModel.applyPlantilla(it)
                showPlantillasDialog = false
            },
            onDelete = { plantillaId ->
                viewModel.deletePlantilla(plantillaId)
            }
        )
    }

    if (showSavePlantillaDialog) {
        AlertDialog(
            onDismissRequest = { showSavePlantillaDialog = false },
            title = { Text("Guardar plantilla") },
            text = {
                OutlinedTextField(
                    value = plantillaNombre,
                    onValueChange = { plantillaNombre = it },
                    label = { Text("Nombre") },
                    placeholder = { Text("Ej: Desayuno habitual") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.savePlantilla(plantillaNombre)
                        plantillaNombre = ""
                        showSavePlantillaDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePlantillaDialog = false }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun NoProfileView(
    modifier: Modifier = Modifier,
    onNavigateToProfile: () -> Unit
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.height(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Perfil no configurado",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Debes configurar tu perfil antes de registrar comidas",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNavigateToProfile) {
            Text("Ir a Perfil")
        }
    }
}

@Composable
private fun NuevaComidaContent(
    alimentos: List<Alimento>,
    items: List<ItemComidaTemporal>,
    calculo: CalculoActual,
    isSaving: Boolean,
    searchQuery: String,
    notas: String,
    dosisConCorreccion: Boolean,
    franjaHoraria: FranjaHoraria,
    nivelEstres: NivelEstres,
    nivelEnfermedad: NivelEnfermedad,
    faseCiclo: FaseCicloHormonal,
    nivelEjercicio: NivelEjercicio,
    cicloHormonalActivo: Boolean,
    plantillas: List<PlantillaConItems>,
    onSearchQueryChange: (String) -> Unit,
    onNotasChange: (String) -> Unit,
    onDosisConCorreccionChange: (Boolean) -> Unit,
    onFranjaHorariaChange: (FranjaHoraria) -> Unit,
    onNivelEstresChange: (NivelEstres) -> Unit,
    onNivelEnfermedadChange: (NivelEnfermedad) -> Unit,
    onFaseCicloChange: (FaseCicloHormonal) -> Unit,
    onNivelEjercicioChange: (NivelEjercicio) -> Unit,
    onAddItem: () -> Unit,
    onRemoveItem: (ItemComidaTemporal) -> Unit,
    onUpdateItemAlimento: (ItemComidaTemporal, Alimento) -> Unit,
    onUpdateItemGramos: (ItemComidaTemporal, String) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    onOpenPlantillas: () -> Unit,
    onSavePlantilla: () -> Unit
) {
    var showContextEditor by remember { mutableStateOf(false) }
    var showFactorBreakdown by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Composición de la comida",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenPlantillas,
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Plantillas")
                }
                OutlinedButton(
                    onClick = onSavePlantilla,
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Guardar plantilla")
                }
            }

            // Lista de items actuales
            items.forEachIndexed { index, item ->
                ItemComidaRow(
                    index = index,
                    item = item,
                    alimentosFiltrados = alimentos,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    onUpdateAlimento = { onUpdateItemAlimento(item, it) },
                    onUpdateGramos = { onUpdateItemGramos(item, it) },
                    onRemove = { onRemoveItem(item) },
                    isOnlyItem = items.size == 1,
                    enabled = !isSaving
                )
            }

            // Botón Sumar Alimento
            OutlinedButton(
                onClick = onAddItem,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Añadir alimento")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Factores contextuales",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ajustan la dosis final con límite de seguridad ±40%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val contextSummaryChips = buildContextSummaryChips(
                        franjaHoraria = franjaHoraria,
                        nivelEstres = nivelEstres,
                        nivelEnfermedad = nivelEnfermedad,
                        faseCiclo = faseCiclo,
                        nivelEjercicio = nivelEjercicio,
                        colors = MaterialTheme.colorScheme
                    )
                    if (contextSummaryChips.isNotEmpty()) {
                        ContextInfoChipsRow(chips = contextSummaryChips)
                    }
                    OutlinedButton(
                        onClick = { showContextEditor = true },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Editar factores")
                    }
                }
            }

            // Campo de Notas
            OutlinedTextField(
                value = notas,
                onValueChange = onNotasChange,
                label = { Text("Notas") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ej: Comida familiar, cena fuera...") },
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Tarjeta de resultados
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Resumen del cálculo",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CalculoSmallCard(
                            modifier = Modifier.weight(1f),
                            label = "Hidratos",
                            value = String.format("%.1f", calculo.hidratosTotales),
                            unit = "g",
                            color = HidratosColor
                        )
                        CalculoSmallCard(
                            modifier = Modifier.weight(1f),
                            label = "Raciones",
                            value = String.format("%.1f", calculo.raciones),
                            unit = "rac.",
                            color = RacionesColor
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                val modoDosis = if (dosisConCorreccion) {
                                    "Con corrección"
                                } else {
                                    "Sin corrección"
                                }
                                Text(
                                    text = "Insulina recomendada",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Dosis sugerida ($modoDosis)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            val dosisMostrada = if (dosisConCorreccion) {
                                calculo.unidadesInsulina
                            } else {
                                calculo.unidadesInsulinaSinCorreccion
                            }
                            Text(
                                text = "${String.format("%.1f", dosisMostrada)} U",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (kotlin.math.abs(calculo.unidadesCorreccion) >= 0.05f) {
                        Text(
                            text = "Insulina por comida (sin corrección glucosa): ${String.format("%.1f", calculo.unidadesInsulinaSinCorreccion)} U",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val glucosaActual = calculo.glucosaUsadaMgdl
                        val signo = if (calculo.unidadesCorreccion >= 0f) "+" else ""

                        if (glucosaActual != null) {
                            Text(
                                text = "Corrección por glucosa ($glucosaActual mg/dL): $signo${String.format("%.1f",calculo.unidadesCorreccion)} U",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Corrección por glucosa: $signo${String.format("%.1f", calculo.unidadesCorreccion)} U",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Factor contexto total: x${String.format(Locale.getDefault(), "%.2f", calculo.factorContextoTotalAplicado)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (calculo.factorContextoCapado) {
                        Text(
                            text = "Valor original: x${String.format(Locale.getDefault(), "%.2f", calculo.factorContextoTotalRaw)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Se aplicó límite de seguridad (x0.60 a x1.40).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Text(
                        text = "Corrección por glucosa alta",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    CorrectionModeSelector(
                        conCorreccion = dosisConCorreccion,
                        onChange = onDosisConCorreccionChange,
                        enabled = !isSaving
                    )

                    Button(
                        onClick = onSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = canSave && !isSaving,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.height(24.dp)
                            )
                        } else {
                            Text("Guardar Registro", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    TextButton(
                        onClick = { showFactorBreakdown = !showFactorBreakdown },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(if (showFactorBreakdown) "Ocultar detalle factores" else "Ver detalle factores")
                    }
                    if (showFactorBreakdown) {
                        val detailParts = buildList {
                            add("Hora x${String.format(Locale.getDefault(), "%.2f", calculo.factorHora)}")
                            if (calculo.nivelEstres != NivelEstres.NINGUNO) {
                                add("Estrés x${String.format(Locale.getDefault(), "%.2f", calculo.factorEstres)}")
                            }
                            if (calculo.nivelEnfermedad != NivelEnfermedad.NINGUNA) {
                                add("Enfermedad x${String.format(Locale.getDefault(), "%.2f", calculo.factorEnfermedad)}")
                            }
                            if (calculo.faseCiclo != FaseCicloHormonal.NO_APLICAR) {
                                add("Ciclo x${String.format(Locale.getDefault(), "%.2f", calculo.factorCiclo)}")
                            }
                            if (calculo.nivelEjercicio != NivelEjercicio.NINGUNO) {
                                add("Ejercicio x${String.format(Locale.getDefault(), "%.2f", calculo.factorEjercicio)}")
                            }
                        }.joinToString(" · ")
                        Text(
                            text = detailParts,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            AvisoMedico()
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showContextEditor) {
            ContextFactorsDialog(
                franjaHoraria = franjaHoraria,
                nivelEstres = nivelEstres,
                nivelEnfermedad = nivelEnfermedad,
                faseCiclo = faseCiclo,
                nivelEjercicio = nivelEjercicio,
                cicloHormonalActivo = cicloHormonalActivo,
                enabled = !isSaving,
                onFranjaHorariaChange = onFranjaHorariaChange,
                onNivelEstresChange = onNivelEstresChange,
                onNivelEnfermedadChange = onNivelEnfermedadChange,
                onFaseCicloChange = onFaseCicloChange,
                onNivelEjercicioChange = onNivelEjercicioChange,
                onDismiss = { showContextEditor = false }
            )
        }
    }
}

@Composable
private fun ContextFactorsDialog(
    franjaHoraria: FranjaHoraria,
    nivelEstres: NivelEstres,
    nivelEnfermedad: NivelEnfermedad,
    faseCiclo: FaseCicloHormonal,
    nivelEjercicio: NivelEjercicio,
    cicloHormonalActivo: Boolean,
    enabled: Boolean,
    onFranjaHorariaChange: (FranjaHoraria) -> Unit,
    onNivelEstresChange: (NivelEstres) -> Unit,
    onNivelEnfermedadChange: (NivelEnfermedad) -> Unit,
    onFaseCicloChange: (FaseCicloHormonal) -> Unit,
    onNivelEjercicioChange: (NivelEjercicio) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Factores contextuales",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }
                Text(
                    text = "Ajusta el contexto de esta comida sin perder el foco del registro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val colors = MaterialTheme.colorScheme

                ContextFactorSelector(
                    title = "Hora",
                    selected = franjaHoraria,
                    enabled = enabled,
                    options = FranjaHoraria.entries.map {
                        it to FactoresContextoInsulina.franjaLabel(it)
                    },
                    chipColor = { franjaChipColor(it, colors) },
                    onSelect = onFranjaHorariaChange
                )
                ContextFactorSelector(
                    title = "Estrés",
                    selected = nivelEstres,
                    enabled = enabled,
                    options = NivelEstres.entries.map {
                        it to FactoresContextoInsulina.estresLabel(it)
                    },
                    chipColor = { estresChipColor(it, colors) },
                    onSelect = onNivelEstresChange
                )
                ContextFactorSelector(
                    title = "Enfermedad",
                    selected = nivelEnfermedad,
                    enabled = enabled,
                    options = NivelEnfermedad.entries.map {
                        it to FactoresContextoInsulina.enfermedadLabel(it)
                    },
                    chipColor = { enfermedadChipColor(it, colors) },
                    onSelect = onNivelEnfermedadChange
                )
                if (cicloHormonalActivo) {
                    ContextFactorSelector(
                        title = "Ciclo hormonal",
                        selected = faseCiclo,
                        enabled = enabled,
                        options = FaseCicloHormonal.entries.map {
                            it to FactoresContextoInsulina.cicloLabel(it)
                        },
                        chipColor = { cicloChipColor(it, colors) },
                        onSelect = onFaseCicloChange
                    )
                }
                ContextFactorSelector(
                    title = "Ejercicio",
                    selected = nivelEjercicio,
                    enabled = enabled,
                    options = NivelEjercicio.entries.map {
                        it to FactoresContextoInsulina.ejercicioLabel(it)
                    },
                    chipColor = { ejercicioChipColor(it, colors) },
                    onSelect = onNivelEjercicioChange
                )

                Button(
                    onClick = onDismiss,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Aplicar")
                }
            }
        }
    }
}

@Composable
private fun CorrectionModeSelector(
    conCorreccion: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = conCorreccion,
            onClick = { onChange(true) },
            enabled = enabled,
            label = {
                Text(
                    text = "Dosis ajustada",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.weight(1f),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
        FilterChip(
            selected = !conCorreccion,
            onClick = { onChange(false) },
            enabled = enabled,
            label = {
                Text(
                    text = "Dosis sin ajustar",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun <T> ContextFactorSelector(
    title: String,
    selected: T,
    enabled: Boolean,
    options: List<Pair<T, String>>,
    chipColor: (T) -> Color,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val color = chipColor(value)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = color.copy(alpha = 0.10f),
                        labelColor = color,
                        selectedContainerColor = color.copy(alpha = 0.24f),
                        selectedLabelColor = color
                    )
                )
            }
        }
    }
}

private data class ContextSummaryChip(
    val label: String,
    val color: Color
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ContextInfoChipsRow(chips: List<ContextSummaryChip>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            ContextInfoChip(
                label = chip.label,
                color = chip.color
            )
        }
    }
}

@Composable
private fun ContextInfoChip(
    label: String,
    color: Color
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

private fun buildContextSummaryChips(
    franjaHoraria: FranjaHoraria,
    nivelEstres: NivelEstres,
    nivelEnfermedad: NivelEnfermedad,
    faseCiclo: FaseCicloHormonal,
    nivelEjercicio: NivelEjercicio,
    colors: ColorScheme
): List<ContextSummaryChip> {
    return buildList {
        add(
            ContextSummaryChip(
                label = "Hora: ${FactoresContextoInsulina.franjaLabel(franjaHoraria)}",
                color = franjaChipColor(franjaHoraria, colors)
            )
        )
        if (nivelEstres != NivelEstres.NINGUNO) {
            add(
                ContextSummaryChip(
                    label = "Estrés: ${FactoresContextoInsulina.estresLabel(nivelEstres)}",
                    color = estresChipColor(nivelEstres, colors)
                )
            )
        }
        if (nivelEnfermedad != NivelEnfermedad.NINGUNA) {
            add(
                ContextSummaryChip(
                    label = "Enfermedad: ${FactoresContextoInsulina.enfermedadLabel(nivelEnfermedad)}",
                    color = enfermedadChipColor(nivelEnfermedad, colors)
                )
            )
        }
        if (faseCiclo != FaseCicloHormonal.NO_APLICAR) {
            add(
                ContextSummaryChip(
                    label = "Ciclo: ${FactoresContextoInsulina.cicloLabel(faseCiclo)}",
                    color = cicloChipColor(faseCiclo, colors)
                )
            )
        }
        if (nivelEjercicio != NivelEjercicio.NINGUNO) {
            add(
                ContextSummaryChip(
                    label = "Ejercicio: ${FactoresContextoInsulina.ejercicioLabel(nivelEjercicio)}",
                    color = ejercicioChipColor(nivelEjercicio, colors)
                )
            )
        }
    }
}

private fun franjaChipColor(
    franja: FranjaHoraria,
    colors: ColorScheme
): Color {
    return when (franja) {
        FranjaHoraria.MADRUGADA -> colors.tertiary
        FranjaHoraria.MANANA -> WarningColor
        FranjaHoraria.TARDE -> HidratosColor
        FranjaHoraria.NOCHE -> colors.primary
    }
}

private fun estresChipColor(
    nivel: NivelEstres,
    colors: ColorScheme
): Color {
    return when (nivel) {
        NivelEstres.NINGUNO -> colors.outline
        NivelEstres.LEVE -> colors.tertiary
        NivelEstres.MODERADO -> moderateSeverityColor(colors)
        NivelEstres.ALTO -> highSeverityColor(colors)
    }
}

private fun enfermedadChipColor(
    nivel: NivelEnfermedad,
    colors: ColorScheme
): Color {
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
): Color {
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
): Color {
    return when (nivel) {
        NivelEjercicio.NINGUNO -> colors.outline
        NivelEjercicio.SUAVE -> colors.tertiary
        NivelEjercicio.MODERADO -> moderateSeverityColor(colors)
        NivelEjercicio.INTENSO -> highSeverityColor(colors)
    }
}

private fun moderateSeverityColor(colors: ColorScheme): Color {
    val isLightPalette = colors.error.luminance() < 0.30f
    return if (isLightPalette) WarningColor else colors.error
}

private fun highSeverityColor(colors: ColorScheme): Color {
    val errorBase = colors.error
    return if (errorBase.luminance() < 0.30f) {
        errorBase
    } else {
        moreReddish(errorBase)
    }
}

private fun moreReddish(base: Color): Color {
    return Color(
        red = (base.red + 0.08f).coerceAtMost(1f),
        green = (base.green * 0.72f).coerceIn(0f, 1f),
        blue = (base.blue * 0.72f).coerceIn(0f, 1f),
        alpha = 1f
    )
}

@Composable
private fun CalculoSmallCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    color: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ItemComidaRow(
    index: Int,
    item: ItemComidaTemporal,
    alimentosFiltrados: List<Alimento>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onUpdateAlimento: (Alimento) -> Unit,
    onUpdateGramos: (String) -> Unit,
    onRemove: () -> Unit,
    isOnlyItem: Boolean,
    enabled: Boolean
) {
    var showSearchDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Alimento ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                if (!isOnlyItem) {
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Quitar",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(0.65f)) {
                    OutlinedTextField(
                        value = item.alimento?.nombre ?: "",
                        onValueChange = {},
                        label = { Text("Alimento") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        placeholder = { Text("Elegir...") },
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledIndicatorColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Box(
                        modifier = Modifier.matchParentSize().clickable(enabled = enabled) {
                            if (searchQuery.isNotBlank()) {
                                onSearchQueryChange("")
                            }
                            showSearchDialog = true
                        }
                    )
                }

                OutlinedTextField(
                    value = item.gramosStr,
                    onValueChange = onUpdateGramos,
                    label = { Text("Gramos") },
                    modifier = Modifier.weight(0.35f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("g") },
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            if (item.hidratos > 0 || !item.alimento?.nota.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!item.alimento?.nota.isNullOrBlank()) {
                        Text(
                            text = "Nota: ${item.alimento!!.nota}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    
                    if (item.hidratos > 0) {
                        Text(
                            text = "${String.format("%.1f", item.hidratos)}g HC",
                            style = MaterialTheme.typography.labelMedium,
                            color = HidratosColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showSearchDialog) {
        Dialog(onDismissRequest = { showSearchDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(550.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Seleccionar Alimento", 
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Buscar...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(alimentosFiltrados.size) { i ->
                            val alimento = alimentosFiltrados[i]
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text(alimento.nombre, fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Text("${alimento.hidratosPor100g}g HC por 100g")
                                },
                                trailingContent = {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                modifier = Modifier.clickable {
                                    onUpdateAlimento(alimento)
                                    showSearchDialog = false
                                }
                            )
                            if (i < alimentosFiltrados.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { showSearchDialog = false },
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
                    ) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlantillasDialog(
    plantillas: List<PlantillaConItems>,
    gramosPorRacion: Float,
    onDismiss: () -> Unit,
    onApply: (PlantillaConItems) -> Unit,
    onDelete: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }
    val filtered = remember(plantillas, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            plantillas
        } else {
            plantillas.filter { it.plantilla.nombre.contains(trimmed, ignoreCase = true) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .heightIn(min = 320.dp, max = 580.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Plantillas",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (plantillas.isEmpty()) {
                                "Sin plantillas guardadas"
                            } else {
                                "${plantillas.size} plantillas guardadas"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar plantilla") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (plantillas.isEmpty()) {
                                "No hay plantillas guardadas."
                            } else {
                                "No se encontraron plantillas con ese nombre."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered.size) { index ->
                            val plantilla = filtered[index]
                            val totalHidratos = plantilla.items.sumOf {
                                (it.item.gramos * (it.alimento.hidratosPor100g / 100f)).toDouble()
                            }.toFloat()
                            val totalRaciones = if (gramosPorRacion > 0f) {
                                totalHidratos / gramosPorRacion
                            } else {
                                0f
                            }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = plantilla.plantilla.nombre,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${plantilla.items.size} alimentos",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TemplateStat(
                                            modifier = Modifier.weight(1f),
                                            label = "HIDRATOS",
                                            value = "${format1(totalHidratos)} g",
                                            color = HidratosColor
                                        )
                                        TemplateStat(
                                            modifier = Modifier.weight(1f),
                                            label = "RACIONES",
                                            value = format1(totalRaciones),
                                            color = RacionesColor
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val preview = plantilla.items.take(3)
                                        preview.forEach { item ->
                                            val hidratosItem = item.item.gramos * (item.alimento.hidratosPor100g / 100f)
                                            val racionesItem = if (gramosPorRacion > 0f) {
                                                hidratosItem / gramosPorRacion
                                            } else {
                                                0f
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = buildAnnotatedString {
                                                        withStyle(
                                                            SpanStyle(color = MaterialTheme.colorScheme.primary)
                                                        ) {
                                                            append(item.alimento.nombre)
                                                        }
                                                        withStyle(
                                                            SpanStyle(color = MaterialTheme.colorScheme.outline)
                                                        ) {
                                                            append(" • ")
                                                        }
                                                        withStyle(
                                                            SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        ) {
                                                            append("${format1(item.item.gramos)} g")
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = buildAnnotatedString {
                                                        withStyle(SpanStyle(color = HidratosColor)) {
                                                            append("${format1(hidratosItem)} g HC")
                                                        }
                                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                                                            append(" • ")
                                                        }
                                                        withStyle(SpanStyle(color = RacionesColor)) {
                                                            append("${format1(racionesItem)} R")
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                        val remaining = plantilla.items.size - preview.size
                                        if (remaining > 0) {
                                            Text(
                                                text = "y $remaining más",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { onApply(plantilla) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Aplicar")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                pendingDeleteId = plantilla.plantilla.id
                                                pendingDeleteName = plantilla.plantilla.nombre
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Eliminar")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = {
                pendingDeleteId = null
                pendingDeleteName = ""
            },
            title = { Text("Eliminar plantilla") },
            text = {
                Text(
                    text = "Se eliminará la plantilla \"$pendingDeleteName\". ¿Continuar?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId?.let { onDelete(it) }
                        pendingDeleteId = null
                        pendingDeleteName = ""
                    }
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        pendingDeleteName = ""
                    }
                ) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

@Composable
private fun TemplateStat(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    isMain: Boolean = true
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
