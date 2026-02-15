package com.diabetes.calculator.ui.screens.perfil

import android.net.Uri
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.diabetes.calculator.ui.components.AvisoMedico
import com.diabetes.calculator.ui.screens.NightscoutStatus
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncSummary
import com.diabetes.calculator.util.BackupPasswordStore
import com.diabetes.calculator.util.DateUtils
import com.diabetes.calculator.util.NightscoutRetryPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Pantalla de configuración del perfil de usuario.
 * Permite crear o editar el perfil con los parámetros de cálculo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerfilScreen(
    viewModel: PerfilViewModel,
    onProfileSaved: () -> Unit = {},
    tabChangeSignal: Int = 0,
    nightscoutStatus: NightscoutStatus = NightscoutStatus(),
    pendingGlucoseCount: Int = 0,
    pendingMaxAttempts: Int = 0,
    onRefreshNightscout: () -> Unit = {},
    nightscoutRegistroSyncSummary: NightscoutRegistrosSyncSummary = NightscoutRegistrosSyncSummary(),
    nightscoutImportCount: Int = 0,
    onSyncRegistrosNow: () -> Unit = {},
    onResyncRegistros30d: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val nombre by viewModel.nombre.collectAsState()
    val gramosPorRacion by viewModel.gramosPorRacion.collectAsState()
    val ratioInsulina by viewModel.ratioInsulina.collectAsState()
    val objetivoHidratosDia by viewModel.objetivoHidratosDia.collectAsState()
    val objetivoRacionesDia by viewModel.objetivoRacionesDia.collectAsState()
    val objetivoInsulinaDia by viewModel.objetivoInsulinaDia.collectAsState()
    val glucosaObjetivoMgdl by viewModel.glucosaObjetivoMgdl.collectAsState()
    val factorCorreccionMgdlPorU by viewModel.factorCorreccionMgdlPorU.collectAsState()
    val aplicarCorreccionPorDefecto by viewModel.aplicarCorreccionPorDefecto.collectAsState()
    val recordatorio2hActivo by viewModel.recordatorio2hActivo.collectAsState()
    val nightscoutUrl by viewModel.nightscoutUrl.collectAsState()
    val nightscoutToken by viewModel.nightscoutToken.collectAsState()
    val nightscoutSyncRegistrosActivo by viewModel.nightscoutSyncRegistrosActivo.collectAsState()
    val nightscoutLinkOffsetMinutes by viewModel.nightscoutLinkOffsetMinutes.collectAsState()
    val nightscoutLinkOffsetUnits by viewModel.nightscoutLinkOffsetUnits.collectAsState()
    val factorHoraMadrugada by viewModel.factorHoraMadrugada.collectAsState()
    val factorHoraManana by viewModel.factorHoraManana.collectAsState()
    val factorHoraTarde by viewModel.factorHoraTarde.collectAsState()
    val factorHoraNoche by viewModel.factorHoraNoche.collectAsState()
    val factorEstresLeve by viewModel.factorEstresLeve.collectAsState()
    val factorEstresModerado by viewModel.factorEstresModerado.collectAsState()
    val factorEstresAlto by viewModel.factorEstresAlto.collectAsState()
    val factorEnfermedadLeve by viewModel.factorEnfermedadLeve.collectAsState()
    val factorEnfermedadModerada by viewModel.factorEnfermedadModerada.collectAsState()
    val factorEnfermedadAlta by viewModel.factorEnfermedadAlta.collectAsState()
    val cicloHormonalActivo by viewModel.cicloHormonalActivo.collectAsState()
    val factorCicloMenstruacion by viewModel.factorCicloMenstruacion.collectAsState()
    val factorCicloFolicular by viewModel.factorCicloFolicular.collectAsState()
    val factorCicloOvulacion by viewModel.factorCicloOvulacion.collectAsState()
    val factorCicloLutea by viewModel.factorCicloLutea.collectAsState()
    val factorEjercicioSuave by viewModel.factorEjercicioSuave.collectAsState()
    val factorEjercicioModerado by viewModel.factorEjercicioModerado.collectAsState()
    val factorEjercicioIntenso by viewModel.factorEjercicioIntenso.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()

    val isBackupLoading by viewModel.isBackupLoading.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var lastAutoBackupFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit, backupStatus) {
        lastAutoBackupFile = withContext(Dispatchers.IO) { findLatestAutoBackup(context) }
    }

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

    var showExportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }

    var showImportDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportLastBackupConfirm by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.updateRecordatorio2hActivo(false)
            scope.launch {
                snackbarHostState.showSnackbar("Permiso de notificaciones denegado")
            }
        }
    }

    val importLastBackup: () -> Unit = {
        val file = lastAutoBackupFile
        if (file != null) {
            scope.launch {
                try {
                    val password = BackupPasswordStore(context).getOrCreatePassword()
                    file.inputStream().use { inputStream ->
                        viewModel.importData(inputStream, password)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Error al abrir la copia de seguridad: ${e.message}")
                }
            }
        }
    }

    // Lanzador para Exportar
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    viewModel.exportData(outputStream, password)
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error al abrir archivo: ${e.message}")
            }
        }
    }

    // Lanzador para Exportar CSV
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    viewModel.exportCsv(outputStream)
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error al abrir archivo: ${e.message}")
            }
        }
    }

    // Lanzador para Importar
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    if (showExportDialog) {
        val canConfirm = exportPassword.length >= 6 && exportPassword == exportPasswordConfirm
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Proteger copia de seguridad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Introduce una contraseña para cifrar tus datos.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text("Contraseña") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = exportPasswordConfirm,
                        onValueChange = { exportPasswordConfirm = it },
                        label = { Text("Confirmar contraseña") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExportPassword = exportPassword
                        exportPassword = ""
                        exportPasswordConfirm = ""
                        showExportDialog = false
                        val date = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
                        exportLauncher.launch("diabetes_backup_$date.json")
                    },
                    enabled = canConfirm && !isBackupLoading
                ) {
                    Text("Exportar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        exportPassword = ""
                        exportPasswordConfirm = ""
                        showExportDialog = false
                    }
                ) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                importPassword = ""
                pendingImportUri = null
                showImportDialog = false
            },
            title = { Text("Importar datos") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Si la copia de seguridad está cifrada, introduce la contraseña.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Contraseña") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingImportUri
                        val password = importPassword.trim().ifEmpty { null }
                        importPassword = ""
                        pendingImportUri = null
                        showImportDialog = false
                        if (uri == null) return@TextButton
                        scope.launch {
                            try {
                                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                    viewModel.importData(inputStream, password)
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error al importar: ${e.message}")
                            }
                        }
                    },
                    enabled = !isBackupLoading
                ) {
                    Text("Importar archivo")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importPassword = ""
                        pendingImportUri = null
                        showImportDialog = false
                    }
                ) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showImportLastBackupConfirm) {
        val backupDate = lastAutoBackupFile?.let { DateUtils.formatDateTime(it.lastModified()) } ?: "—"
        AlertDialog(
            onDismissRequest = { showImportLastBackupConfirm = false },
            title = { Text("Restaurar última copia de seguridad") },
            text = {
                Text("Se reemplazarán tus datos actuales por la última copia de seguridad ($backupDate). ¿Continuar?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportLastBackupConfirm = false
                        importLastBackup()
                    },
                    enabled = !isBackupLoading && lastAutoBackupFile != null
                ) {
                    Text("Restaurar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportLastBackupConfirm = false }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    LaunchedEffect(backupStatus) {
        backupStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBackupStatus()
        }
    }

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            snackbarHostState.showSnackbar("Perfil guardado correctamente")
            viewModel.resetSaveSuccess()
        }
    }

    LaunchedEffect(tabChangeSignal) {
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    LaunchedEffect(uiState) {
        if (uiState is PerfilUiState.Error) {
            snackbarHostState.showSnackbar((uiState as PerfilUiState.Error).message)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is PerfilUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Cargando perfil...")
                }
            }
            else -> {
                PerfilContent(
                    nombre = nombre,
                    gramosPorRacion = gramosPorRacion,
                    ratioInsulina = ratioInsulina,
                    objetivoHidratosDia = objetivoHidratosDia,
                    objetivoRacionesDia = objetivoRacionesDia,
                    objetivoInsulinaDia = objetivoInsulinaDia,
                    glucosaObjetivoMgdl = glucosaObjetivoMgdl,
                    factorCorreccionMgdlPorU = factorCorreccionMgdlPorU,
                    factorHoraMadrugada = factorHoraMadrugada,
                    factorHoraManana = factorHoraManana,
                    factorHoraTarde = factorHoraTarde,
                    factorHoraNoche = factorHoraNoche,
                    factorEstresLeve = factorEstresLeve,
                    factorEstresModerado = factorEstresModerado,
                    factorEstresAlto = factorEstresAlto,
                    factorEnfermedadLeve = factorEnfermedadLeve,
                    factorEnfermedadModerada = factorEnfermedadModerada,
                    factorEnfermedadAlta = factorEnfermedadAlta,
                    cicloHormonalActivo = cicloHormonalActivo,
                    factorCicloMenstruacion = factorCicloMenstruacion,
                    factorCicloFolicular = factorCicloFolicular,
                    factorCicloOvulacion = factorCicloOvulacion,
                    factorCicloLutea = factorCicloLutea,
                    factorEjercicioSuave = factorEjercicioSuave,
                    factorEjercicioModerado = factorEjercicioModerado,
                    factorEjercicioIntenso = factorEjercicioIntenso,
                    aplicarCorreccionPorDefecto = aplicarCorreccionPorDefecto,
                    recordatorio2hActivo = recordatorio2hActivo,
                    isSaving = isSaving,
                    isNewProfile = uiState is PerfilUiState.Empty,
                    onNombreChange = viewModel::updateNombre,
                    onGramosChange = viewModel::updateGramosPorRacion,
                    onRatioChange = viewModel::updateRatioInsulina,
                    onObjetivoHidratosDiaChange = viewModel::updateObjetivoHidratosDia,
                    onObjetivoRacionesDiaChange = viewModel::updateObjetivoRacionesDia,
                    onObjetivoInsulinaDiaChange = viewModel::updateObjetivoInsulinaDia,
                    onGlucosaObjetivoMgdlChange = viewModel::updateGlucosaObjetivoMgdl,
                    onFactorCorreccionMgdlPorUChange = viewModel::updateFactorCorreccionMgdlPorU,
                    onFactorHoraMadrugadaChange = viewModel::updateFactorHoraMadrugada,
                    onFactorHoraMananaChange = viewModel::updateFactorHoraManana,
                    onFactorHoraTardeChange = viewModel::updateFactorHoraTarde,
                    onFactorHoraNocheChange = viewModel::updateFactorHoraNoche,
                    onFactorEstresLeveChange = viewModel::updateFactorEstresLeve,
                    onFactorEstresModeradoChange = viewModel::updateFactorEstresModerado,
                    onFactorEstresAltoChange = viewModel::updateFactorEstresAlto,
                    onFactorEnfermedadLeveChange = viewModel::updateFactorEnfermedadLeve,
                    onFactorEnfermedadModeradaChange = viewModel::updateFactorEnfermedadModerada,
                    onFactorEnfermedadAltaChange = viewModel::updateFactorEnfermedadAlta,
                    onCicloHormonalActivoChange = viewModel::updateCicloHormonalActivo,
                    onFactorCicloMenstruacionChange = viewModel::updateFactorCicloMenstruacion,
                    onFactorCicloFolicularChange = viewModel::updateFactorCicloFolicular,
                    onFactorCicloOvulacionChange = viewModel::updateFactorCicloOvulacion,
                    onFactorCicloLuteaChange = viewModel::updateFactorCicloLutea,
                    onFactorEjercicioSuaveChange = viewModel::updateFactorEjercicioSuave,
                    onFactorEjercicioModeradoChange = viewModel::updateFactorEjercicioModerado,
                    onFactorEjercicioIntensoChange = viewModel::updateFactorEjercicioIntenso,
                    onAplicarCorreccionPorDefectoChange = viewModel::updateAplicarCorreccionPorDefecto,
                    onRecordatorio2hChange = onRecordatorio2hChange@{ enabled ->
                        if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@onRecordatorio2hChange
                            }
                        }
                        viewModel.updateRecordatorio2hActivo(enabled)
                    },
                    onSave = viewModel::saveProfile,
                    canSave = viewModel.validateFields(),
                    onExport = {
                        exportPassword = ""
                        exportPasswordConfirm = ""
                        showExportDialog = true
                    },
                    onExportCsv = {
                        val date = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
                        exportCsvLauncher.launch("diabetes_export_$date.csv")
                    },
                    onImport = {
                        importPassword = ""
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                    },
                    isBackupLoading = isBackupLoading,
                    lastBackupLabel = lastAutoBackupFile?.let {
                        "Última copia de seguridad: ${DateUtils.formatDateTime(it.lastModified())}"
                    } ?: "Última copia de seguridad: —",
                    canImportLastBackup = lastAutoBackupFile != null,
                    onImportLastBackup = {
                        if (lastAutoBackupFile != null) {
                            showImportLastBackupConfirm = true
                        }
                    },
                    onCreateBackup = {
                        scope.launch {
                            viewModel.createAutoBackup(context)
                            lastAutoBackupFile = withContext(Dispatchers.IO) {
                                findLatestAutoBackup(context)
                            }
                        }
                    },
                    nightscoutUrl = nightscoutUrl,
                    nightscoutToken = nightscoutToken,
                    onNightscoutUrlChange = viewModel::updateNightscoutUrl,
                    onNightscoutTokenChange = viewModel::updateNightscoutToken,
                    nightscoutSyncRegistrosActivo = nightscoutSyncRegistrosActivo,
                    onNightscoutSyncRegistrosActivoChange = viewModel::updateNightscoutSyncRegistrosActivo,
                    nightscoutLinkOffsetMinutes = nightscoutLinkOffsetMinutes,
                    nightscoutLinkOffsetUnits = nightscoutLinkOffsetUnits,
                    onNightscoutLinkOffsetMinutesChange = viewModel::updateNightscoutLinkOffsetMinutes,
                    onNightscoutLinkOffsetUnitsChange = viewModel::updateNightscoutLinkOffsetUnits,
                    nightscoutStatus = nightscoutStatus,
                    pendingGlucoseCount = pendingGlucoseCount,
                    pendingMaxAttempts = pendingMaxAttempts,
                    onRefreshNightscout = onRefreshNightscout,
                    nightscoutRegistroSyncSummary = nightscoutRegistroSyncSummary,
                    nightscoutImportCount = nightscoutImportCount,
                    onSyncRegistrosNow = onSyncRegistrosNow,
                    onResyncRegistros30d = onResyncRegistros30d
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PerfilContent(
    modifier: Modifier = Modifier,
    nombre: String,
    gramosPorRacion: String,
    ratioInsulina: String,
    objetivoHidratosDia: String,
    objetivoRacionesDia: String,
    objetivoInsulinaDia: String,
    glucosaObjetivoMgdl: String,
    factorCorreccionMgdlPorU: String,
    factorHoraMadrugada: String,
    factorHoraManana: String,
    factorHoraTarde: String,
    factorHoraNoche: String,
    factorEstresLeve: String,
    factorEstresModerado: String,
    factorEstresAlto: String,
    factorEnfermedadLeve: String,
    factorEnfermedadModerada: String,
    factorEnfermedadAlta: String,
    cicloHormonalActivo: Boolean,
    factorCicloMenstruacion: String,
    factorCicloFolicular: String,
    factorCicloOvulacion: String,
    factorCicloLutea: String,
    factorEjercicioSuave: String,
    factorEjercicioModerado: String,
    factorEjercicioIntenso: String,
    aplicarCorreccionPorDefecto: Boolean,
    recordatorio2hActivo: Boolean,
    isSaving: Boolean,
    isNewProfile: Boolean,
    onNombreChange: (String) -> Unit,
    onGramosChange: (String) -> Unit,
    onRatioChange: (String) -> Unit,
    onObjetivoHidratosDiaChange: (String) -> Unit,
    onObjetivoRacionesDiaChange: (String) -> Unit,
    onObjetivoInsulinaDiaChange: (String) -> Unit,
    onGlucosaObjetivoMgdlChange: (String) -> Unit,
    onFactorCorreccionMgdlPorUChange: (String) -> Unit,
    onFactorHoraMadrugadaChange: (String) -> Unit,
    onFactorHoraMananaChange: (String) -> Unit,
    onFactorHoraTardeChange: (String) -> Unit,
    onFactorHoraNocheChange: (String) -> Unit,
    onFactorEstresLeveChange: (String) -> Unit,
    onFactorEstresModeradoChange: (String) -> Unit,
    onFactorEstresAltoChange: (String) -> Unit,
    onFactorEnfermedadLeveChange: (String) -> Unit,
    onFactorEnfermedadModeradaChange: (String) -> Unit,
    onFactorEnfermedadAltaChange: (String) -> Unit,
    onCicloHormonalActivoChange: (Boolean) -> Unit,
    onFactorCicloMenstruacionChange: (String) -> Unit,
    onFactorCicloFolicularChange: (String) -> Unit,
    onFactorCicloOvulacionChange: (String) -> Unit,
    onFactorCicloLuteaChange: (String) -> Unit,
    onFactorEjercicioSuaveChange: (String) -> Unit,
    onFactorEjercicioModeradoChange: (String) -> Unit,
    onFactorEjercicioIntensoChange: (String) -> Unit,
    onAplicarCorreccionPorDefectoChange: (Boolean) -> Unit,
    onRecordatorio2hChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    onExport: () -> Unit,
    onExportCsv: () -> Unit,
    onImport: () -> Unit,
    isBackupLoading: Boolean,
    lastBackupLabel: String,
    canImportLastBackup: Boolean,
    onImportLastBackup: () -> Unit,
    onCreateBackup: () -> Unit,
    nightscoutUrl: String,
    nightscoutToken: String,
    onNightscoutUrlChange: (String) -> Unit,
    onNightscoutTokenChange: (String) -> Unit,
    nightscoutSyncRegistrosActivo: Boolean,
    onNightscoutSyncRegistrosActivoChange: (Boolean) -> Unit,
    nightscoutLinkOffsetMinutes: String,
    nightscoutLinkOffsetUnits: String,
    onNightscoutLinkOffsetMinutesChange: (String) -> Unit,
    onNightscoutLinkOffsetUnitsChange: (String) -> Unit,
    nightscoutStatus: NightscoutStatus,
    pendingGlucoseCount: Int,
    pendingMaxAttempts: Int,
    onRefreshNightscout: () -> Unit,
    nightscoutRegistroSyncSummary: NightscoutRegistrosSyncSummary,
    nightscoutImportCount: Int,
    onSyncRegistrosNow: () -> Unit,
    onResyncRegistros30d: () -> Unit
) {
    var showContextFactorsDialog by remember { mutableStateOf(false) }
    var showDefaultsTable by remember { mutableStateOf(false) }
    var isSyncingRegistros by remember { mutableStateOf(false) }
    var syncBaseline by remember { mutableStateOf<NightscoutRegistrosSyncSummary?>(null) }
    val scrollState = rememberScrollState()

    LaunchedEffect(nightscoutRegistroSyncSummary, isSyncingRegistros) {
        val baseline = syncBaseline
        if (isSyncingRegistros && baseline != null && baseline != nightscoutRegistroSyncSummary) {
            isSyncingRegistros = false
            syncBaseline = null
        }
    }

    LaunchedEffect(isSyncingRegistros) {
        if (isSyncingRegistros) {
            delay(20_000)
            if (isSyncingRegistros) {
                isSyncingRegistros = false
                syncBaseline = null
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AvisoMedico()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .align(Alignment.CenterHorizontally),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isNewProfile) "Configura tu perfil" else "Editar perfil",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Ajusta tus parámetros para cálculos precisos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text(
                text = "Datos Personales y Cálculo",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            OutlinedTextField(
                value = nombre,
                onValueChange = onNombreChange,
                label = { Text("Tu nombre") },
                placeholder = { Text("Ej: María") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = gramosPorRacion,
                onValueChange = onGramosChange,
                label = { Text("Gramos por ración") },
                placeholder = { Text("Ej: 10") },
                supportingText = {
                    Text("Gramos de hidratos que equivalen a 1 ración")
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp),
                suffix = { Text("g HC") }
            )

            OutlinedTextField(
                value = ratioInsulina,
                onValueChange = onRatioChange,
                label = { Text("Ratio Insulina/Ración") },
                placeholder = { Text("Ej: 1.0") },
                supportingText = {
                    Text("Unidades de insulina rápida por ración")
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp),
                suffix = { Text("U") }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Objetivos diarios",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Opcionales. Te avisaremos cuando los superes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    BoxWithConstraints {
                        val isWide = maxWidth >= 520.dp
                        val spacing = 12.dp
                        val hidratosField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = objetivoHidratosDia,
                                onValueChange = onObjetivoHidratosDiaChange,
                                label = { Text("Hidratos") },
                                placeholder = { Text("Ej: 180") },
                                modifier = modifier,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                suffix = { Text("g") }
                            )
                        }
                        val racionesField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = objetivoRacionesDia,
                                onValueChange = onObjetivoRacionesDiaChange,
                                label = { Text("Raciones") },
                                placeholder = { Text("Ej: 18") },
                                modifier = modifier,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                        val insulinaField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = objetivoInsulinaDia,
                                onValueChange = onObjetivoInsulinaDiaChange,
                                label = { Text("Insulina") },
                                placeholder = { Text("Ej: 20") },
                                modifier = modifier,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                suffix = { Text("U") }
                            )
                        }

                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                hidratosField(Modifier.weight(1f))
                                racionesField(Modifier.weight(1f))
                                insulinaField(Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                hidratosField(Modifier.fillMaxWidth())
                                racionesField(Modifier.fillMaxWidth())
                                insulinaField(Modifier.fillMaxWidth())
                            }
                        }
                    }

                }
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
                        text = "Factores contextuales de dosis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configura ajustes por hora, estrés, enfermedad, ciclo y ejercicio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Hora: ${factorHoraMadrugada}/${factorHoraManana}/${factorHoraTarde}/${factorHoraNoche}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Estrés: ${factorEstresLeve}/${factorEstresModerado}/${factorEstresAlto}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Enfermedad: ${factorEnfermedadLeve}/${factorEnfermedadModerada}/${factorEnfermedadAlta}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (cicloHormonalActivo) {
                        Text(
                            text = "Ciclo: ${factorCicloMenstruacion}/${factorCicloFolicular}/${factorCicloOvulacion}/${factorCicloLutea}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Ejercicio: ${factorEjercicioSuave}/${factorEjercicioModerado}/${factorEjercicioIntenso}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showContextFactorsDialog = !showContextFactorsDialog },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showContextFactorsDialog) "Ocultar configuración avanzada" else "Configurar factores")
            }
        }

    }

    if (showContextFactorsDialog) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Rango recomendado: 0.5 a 1.5. La app aplica límite global ±40% al calcular.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Hora del día",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    BoxWithConstraints {
                        val isWide = maxWidth >= 520.dp
                        val spacing = 12.dp
                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                FactorDecimalField(
                                    value = factorHoraMadrugada,
                                    onValueChange = onFactorHoraMadrugadaChange,
                                    label = "Madrugada",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorHoraManana,
                                    onValueChange = onFactorHoraMananaChange,
                                    label = "Mañana",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                FactorDecimalField(
                                    value = factorHoraTarde,
                                    onValueChange = onFactorHoraTardeChange,
                                    label = "Tarde",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorHoraNoche,
                                    onValueChange = onFactorHoraNocheChange,
                                    label = "Noche",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                FactorDecimalField(
                                    value = factorHoraMadrugada,
                                    onValueChange = onFactorHoraMadrugadaChange,
                                    label = "Madrugada",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorHoraManana,
                                    onValueChange = onFactorHoraMananaChange,
                                    label = "Mañana",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorHoraTarde,
                                    onValueChange = onFactorHoraTardeChange,
                                    label = "Tarde",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorHoraNoche,
                                    onValueChange = onFactorHoraNocheChange,
                                    label = "Noche",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Text(
                        text = "Estrés",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    BoxWithConstraints {
                        val isWide = maxWidth >= 560.dp
                        val spacing = 12.dp
                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                FactorDecimalField(
                                    value = factorEstresLeve,
                                    onValueChange = onFactorEstresLeveChange,
                                    label = "Leve",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorEstresModerado,
                                    onValueChange = onFactorEstresModeradoChange,
                                    label = "Moderado",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorEstresAlto,
                                    onValueChange = onFactorEstresAltoChange,
                                    label = "Alto",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                FactorDecimalField(
                                    value = factorEstresLeve,
                                    onValueChange = onFactorEstresLeveChange,
                                    label = "Leve",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorEstresModerado,
                                    onValueChange = onFactorEstresModeradoChange,
                                    label = "Moderado",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorEstresAlto,
                                    onValueChange = onFactorEstresAltoChange,
                                    label = "Alto",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Text(
                        text = "Enfermedad",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    BoxWithConstraints {
                        val isWide = maxWidth >= 560.dp
                        val spacing = 12.dp
                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                FactorDecimalField(
                                    value = factorEnfermedadLeve,
                                    onValueChange = onFactorEnfermedadLeveChange,
                                    label = "Leve",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorEnfermedadModerada,
                                    onValueChange = onFactorEnfermedadModeradaChange,
                                    label = "Moderada",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorEnfermedadAlta,
                                    onValueChange = onFactorEnfermedadAltaChange,
                                    label = "Alta",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                FactorDecimalField(
                                    value = factorEnfermedadLeve,
                                    onValueChange = onFactorEnfermedadLeveChange,
                                    label = "Leve",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorEnfermedadModerada,
                                    onValueChange = onFactorEnfermedadModeradaChange,
                                    label = "Moderada",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorEnfermedadAlta,
                                    onValueChange = onFactorEnfermedadAltaChange,
                                    label = "Alta",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Activar ciclo hormonal",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Si está desactivado se usa x1.00.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cicloHormonalActivo,
                            onCheckedChange = onCicloHormonalActivoChange,
                            enabled = !isSaving,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    if (cicloHormonalActivo) {
                        Text(
                            text = "Ciclo hormonal",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        BoxWithConstraints {
                            val isWide = maxWidth >= 520.dp
                            val spacing = 12.dp
                            if (isWide) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing)
                                ) {
                                    FactorDecimalField(
                                        value = factorCicloMenstruacion,
                                        onValueChange = onFactorCicloMenstruacionChange,
                                        label = "Menstruación",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FactorDecimalField(
                                        value = factorCicloFolicular,
                                        onValueChange = onFactorCicloFolicularChange,
                                        label = "Folicular",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing)
                                ) {
                                    FactorDecimalField(
                                        value = factorCicloOvulacion,
                                        onValueChange = onFactorCicloOvulacionChange,
                                        label = "Ovulación",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FactorDecimalField(
                                        value = factorCicloLutea,
                                        onValueChange = onFactorCicloLuteaChange,
                                        label = "Lútea",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                    FactorDecimalField(
                                        value = factorCicloMenstruacion,
                                        onValueChange = onFactorCicloMenstruacionChange,
                                        label = "Menstruación",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    FactorDecimalField(
                                        value = factorCicloFolicular,
                                        onValueChange = onFactorCicloFolicularChange,
                                        label = "Folicular",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    FactorDecimalField(
                                        value = factorCicloOvulacion,
                                        onValueChange = onFactorCicloOvulacionChange,
                                        label = "Ovulación",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    FactorDecimalField(
                                        value = factorCicloLutea,
                                        onValueChange = onFactorCicloLuteaChange,
                                        label = "Lútea",
                                        suffix = "x",
                                        enabled = !isSaving,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Ejercicio",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    BoxWithConstraints {
                        val isWide = maxWidth >= 560.dp
                        val spacing = 12.dp
                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                FactorDecimalField(
                                    value = factorEjercicioSuave,
                                    onValueChange = onFactorEjercicioSuaveChange,
                                    label = "Suave",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorEjercicioModerado,
                                    onValueChange = onFactorEjercicioModeradoChange,
                                    label = "Moderado",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                FactorDecimalField(
                                    value = factorEjercicioIntenso,
                                    onValueChange = onFactorEjercicioIntensoChange,
                                    label = "Intenso",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                FactorDecimalField(
                                    value = factorEjercicioSuave,
                                    onValueChange = onFactorEjercicioSuaveChange,
                                    label = "Suave",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorEjercicioModerado,
                                    onValueChange = onFactorEjercicioModeradoChange,
                                    label = "Moderado",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FactorDecimalField(
                                    value = factorEjercicioIntenso,
                                    onValueChange = onFactorEjercicioIntensoChange,
                                    label = "Intenso",
                                    suffix = "x",
                                    enabled = !isSaving,
                                    modifier = Modifier.fillMaxWidth()
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
                            text = "Niveles y valores por defecto",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = { showDefaultsTable = !showDefaultsTable }
                        ) {
                            Text(if (showDefaultsTable) "Ocultar tabla" else "Mostrar tabla")
                        }
                    }
                    if (showDefaultsTable) {
                        FactorDefaultsTable()
                    }
                }
            }
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Corrección por glucosa",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Opcional. Se aplicará solo con Nightscout y ambos campos completos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    BoxWithConstraints {
                        val isWide = maxWidth >= 520.dp
                        val spacing = 12.dp
                        val objetivoField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = glucosaObjetivoMgdl,
                                onValueChange = onGlucosaObjetivoMgdlChange,
                                label = { Text("Glucosa objetivo") },
                                placeholder = { Text("Ej: 110") },
                                modifier = modifier,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                suffix = { Text("mg/dL") }
                            )
                        }
                        val factorField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = factorCorreccionMgdlPorU,
                                onValueChange = onFactorCorreccionMgdlPorUChange,
                                label = { Text("Factor de corrección") },
                                placeholder = { Text("Ej: 50") },
                                modifier = modifier,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                suffix = { Text("mg/dL por U") }
                            )
                        }

                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                objetivoField(Modifier.weight(1f))
                                factorField(Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                objetivoField(Modifier.fillMaxWidth())
                                factorField(Modifier.fillMaxWidth())
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dosis ajustada por defecto en nueva comida",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Switch(
                            checked = aplicarCorreccionPorDefecto,
                            onCheckedChange = onAplicarCorreccionPorDefectoChange,
                            enabled = !isSaving,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(16.dp)
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
                            text = "Recordatorio 2 h",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Aviso manual para medir glucosa 2 h después.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = recordatorio2hActivo,
                        onCheckedChange = onRecordatorio2hChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }

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
                    Text(
                        text = if (isNewProfile) "Comenzar" else "Guardar Cambios",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "Conexiones y Datos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Nightscout",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Sincroniza tu glucosa en tiempo real.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = nightscoutUrl,
                        onValueChange = onNightscoutUrlChange,
                        label = { Text("URL") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = nightscoutToken,
                        onValueChange = onNightscoutTokenChange,
                        label = { Text("Token de API (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp)
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sincronizar comidas y pinchazos",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Sube comidas de la app y reconcilia pinchazos desde Nightscout/Novopen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = nightscoutSyncRegistrosActivo,
                            onCheckedChange = onNightscoutSyncRegistrosActivoChange,
                            enabled = !isSaving,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    Text(
                        text = "Tolerancia de enlace dosis app ↔ Nightscout (coincidencia por hora y unidades).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BoxWithConstraints {
                        val isWide = maxWidth >= 520.dp
                        val spacing = 10.dp
                        val minutesField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = nightscoutLinkOffsetMinutes,
                                onValueChange = onNightscoutLinkOffsetMinutesChange,
                                label = { Text("Offset hora") },
                                modifier = modifier,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                suffix = { Text("min") }
                            )
                        }
                        val unitsField: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(
                                value = nightscoutLinkOffsetUnits,
                                onValueChange = onNightscoutLinkOffsetUnitsChange,
                                label = { Text("Offset dosis") },
                                modifier = modifier,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                suffix = { Text("U") }
                            )
                        }
                        if (isWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                minutesField(Modifier.weight(1f))
                                unitsField(Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                minutesField(Modifier.fillMaxWidth())
                                unitsField(Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Text(
                        text = "Recomendado: 15 min y 0.5 U",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (nightscoutSyncRegistrosActivo) {
                        val lastSyncRegistrosText = nightscoutRegistroSyncSummary.lastSuccessAt?.let {
                            "Último sync registros: ${DateUtils.formatDateTime(it)}"
                        } ?: "Último sync registros: —"
                        val lastSyncErrorText = nightscoutRegistroSyncSummary.lastErrorAt?.let {
                            "Último error registros: ${DateUtils.formatDateTime(it)}"
                        } ?: "Último error registros: —"

                        Text(
                            text = lastSyncRegistrosText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = lastSyncErrorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Registros Nightscout importados: $nightscoutImportCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Pendientes de subida (app -> Nightscout): ${nightscoutRegistroSyncSummary.pendingCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Fallidos de subida (app -> Nightscout): ${nightscoutRegistroSyncSummary.failedCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!nightscoutRegistroSyncSummary.lastErrorMessage.isNullOrBlank()) {
                            Text(
                                text = "Detalle error: ${nightscoutRegistroSyncSummary.lastErrorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (isSyncingRegistros) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Sincronizando registros...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    syncBaseline = nightscoutRegistroSyncSummary
                                    isSyncingRegistros = true
                                    onSyncRegistrosNow()
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isSyncingRegistros,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Sincronizar")
                            }
                            OutlinedButton(
                                onClick = {
                                    syncBaseline = nightscoutRegistroSyncSummary
                                    isSyncingRegistros = true
                                    onResyncRegistros30d()
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isSyncingRegistros,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Resync 30 días")
                            }
                        }
                        Text(
                            text = "Sincronizar sube comidas locales pendientes (sin subir dosis). Las dosis se toman de Nightscout/Novopen y se enlazan por tolerancia. Resync 30 días rehace esa reconciliación.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    val lastSuccessText = nightscoutStatus.lastSuccessAt?.let {
                        "Última actualización: ${DateUtils.formatDateTime(it)}"
                    } ?: "Última actualización: —"
                    val lastErrorText = nightscoutStatus.lastErrorAt?.let {
                        "Último error: ${DateUtils.formatDateTime(it)}"
                    } ?: "Último error: —"
                    val nextRetryMinutes = if (pendingGlucoseCount > 0) {
                        NightscoutRetryPolicy.nextDelayMinutes(pendingMaxAttempts)
                    } else {
                        null
                    }

                    Text(
                        text = lastSuccessText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = lastErrorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Reintentos en cola: $pendingGlucoseCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Fallos consecutivos: ${nightscoutStatus.consecutiveFailures}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Límite de reintentos: ${NightscoutRetryPolicy.MAX_ATTEMPTS}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = nextRetryMinutes?.let { "Próximo intento en ~${it} min" }
                            ?: "Backoff: ${NightscoutRetryPolicy.BASE_DELAY_MINUTES}-${NightscoutRetryPolicy.MAX_DELAY_MINUTES} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = onRefreshNightscout,
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Reintentar")
                    }
                }
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copia de seguridad",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Exporta tus datos o restáuralos desde un archivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onExport,
                            modifier = Modifier.weight(1f).height(48.dp),
                            enabled = !isBackupLoading,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.height(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Exportar")
                        }

                        OutlinedButton(
                            onClick = onImport,
                            modifier = Modifier.weight(1f).height(48.dp),
                            enabled = !isBackupLoading,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.height(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Importar")
                        }
                    }

                    OutlinedButton(
                        onClick = onExportCsv,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !isBackupLoading,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Exportar CSV")
                    }
                    Text(
                        text = "CSV con registros y alimentos (separado por \";\").",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCreateBackup,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            enabled = !isBackupLoading,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.height(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Guardar")
                        }

                        OutlinedButton(
                            onClick = onImportLastBackup,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            enabled = !isBackupLoading && canImportLastBackup,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.height(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restaurar")
                        }
                    }

                    Text(
                        text = lastBackupLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isBackupLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

    }
}

@Composable
private fun FactorDecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        suffix = { Text(suffix) }
    )
}

@Composable
private fun FactorDefaultsTable() {
    val rows = listOf(
        Triple("Hora", "Madrugada / Mañana / Tarde / Noche", "1.00 / 1.00 / 1.00 / 1.00"),
        Triple("Estrés", "Ninguno / Leve / Moderado / Alto", "1.00 / 1.10 / 1.20 / 1.30"),
        Triple("Enfermedad", "Ninguna / Leve / Moderada / Alta", "1.00 / 1.10 / 1.20 / 1.30"),
        Triple("Ciclo", "No aplicar / Menstruación / Folicular / Ovulación / Lútea", "1.00 / 0.95 / 1.00 / 1.05 / 1.15"),
        Triple("Ejercicio", "Ninguno / Suave / Moderado / Intenso", "1.00 / 0.90 / 0.80 / 0.70")
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FactorDefaultsRow(
                factor = "Factor",
                niveles = "Niveles",
                defaults = "Por defecto",
                isHeader = true
            )
            rows.forEachIndexed { index, (factor, niveles, defaults) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                FactorDefaultsRow(
                    factor = factor,
                    niveles = niveles,
                    defaults = defaults,
                    isHeader = false
                )
                if (index == rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun FactorDefaultsRow(
    factor: String,
    niveles: String,
    defaults: String,
    isHeader: Boolean
) {
    val textStyle = if (isHeader) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.bodySmall
    }
    val textColor = if (isHeader) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = factor,
            modifier = Modifier.weight(0.9f),
            style = textStyle,
            color = textColor,
            fontWeight = fontWeight
        )
        Text(
            text = niveles,
            modifier = Modifier.weight(1.8f),
            style = textStyle,
            color = textColor,
            fontWeight = fontWeight
        )
        Text(
            text = defaults,
            modifier = Modifier.weight(1.3f),
            style = textStyle,
            color = textColor,
            fontWeight = fontWeight
        )
    }
}

private fun findLatestAutoBackup(context: android.content.Context): File? {
    val backupDir = context.getExternalFilesDir("backups")
        ?: File(context.filesDir, "backups")
    if (!backupDir.exists()) return null

    return backupDir.listFiles { file ->
        file.isFile && file.name.startsWith("auto_backup_")
    }?.maxByOrNull { it.lastModified() }
}
