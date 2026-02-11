package com.diabetes.calculator.ui.screens.alimentos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.unit.dp
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.ui.theme.HidratosColor

/**
 * Pantalla de listado de alimentos.
 * Permite ver, añadir, editar y eliminar alimentos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlimentosScreen(
    viewModel: AlimentosViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val showDialog by viewModel.showDialog.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var pendingDelete by remember { mutableStateOf<Alimento?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    
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
                // Barra de búsqueda con estilo coherente
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
        
        if (showDialog) {
            AlimentoDialog(viewModel = viewModel)
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AlimentosList(
    alimentos: List<Alimento>,
    onEdit: (Alimento) -> Unit,
    onDelete: (Alimento) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp
        )
    ) {
        items(alimentos, key = { it.id }) { alimento ->
            AlimentoCard(
                alimento = alimento,
                onEdit = { onEdit(alimento) },
                onDelete = { onDelete(alimento) }
            )
        }
        item { Spacer(modifier = Modifier.height(80.dp)) } // Espacio para el FAB
    }
}

@Composable
private fun AlimentoCard(
    alimento: Alimento,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${alimento.hidratosPor100g}g HC",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HidratosColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " / 100g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
private fun AlimentoDialog(viewModel: AlimentosViewModel) {
    val editingAlimento by viewModel.editingAlimento.collectAsState()
    val nombre by viewModel.dialogNombre.collectAsState()
    val hidratos by viewModel.dialogHidratos.collectAsState()
    val fuente by viewModel.dialogFuente.collectAsState()
    val nota by viewModel.dialogNota.collectAsState()
    
    val isEditing = editingAlimento != null
    val canSave = nombre.isNotBlank() &&
        ((hidratos.trim().replace(',', '.').toFloatOrNull() ?: -1f) >= 0f)
    
    AlertDialog(
        onDismissRequest = viewModel::closeDialog,
        title = { 
            Text(if (isEditing) "Editar alimento" else "Nuevo alimento") 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = viewModel::updateDialogNombre,
                    label = { Text("Nombre") },
                    placeholder = { Text("Ej: Manzana") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                OutlinedTextField(
                    value = hidratos,
                    onValueChange = viewModel::updateDialogHidratos,
                    label = { Text("Hidratos por 100g") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    suffix = { Text("g") },
                    shape = RoundedCornerShape(12.dp)
                )
                
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
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::saveAlimento,
                enabled = canSave
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeDialog) {
                Text("Cancelar")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}
