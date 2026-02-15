package com.diabetes.calculator.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import com.diabetes.calculator.DiabetesApp
import com.diabetes.calculator.ui.screens.estadisticas.EstadisticasScreen
import com.diabetes.calculator.ui.screens.estadisticas.EstadisticasViewModel
import com.diabetes.calculator.ui.screens.alimentos.AlimentosScreen
import com.diabetes.calculator.ui.screens.alimentos.AlimentosViewModel
import com.diabetes.calculator.ui.screens.historial.HistorialScreen
import com.diabetes.calculator.ui.screens.historial.HistorialViewModel
import com.diabetes.calculator.ui.screens.nuevacomida.NuevaComidaScreen
import com.diabetes.calculator.ui.screens.nuevacomida.NuevaComidaViewModel
import com.diabetes.calculator.ui.screens.perfil.PerfilScreen
import com.diabetes.calculator.ui.screens.perfil.PerfilViewModel
import com.diabetes.calculator.ui.screens.NightscoutViewModel
import com.diabetes.calculator.ui.screens.NightscoutUiState
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncSummary
import com.diabetes.calculator.work.NightscoutSyncWorker
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Rutas de navegación de la aplicación.
 */
sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object NuevaComida : Screen(
        route = "nueva_comida",
        title = "Nueva",
        selectedIcon = Icons.Filled.Add,
        unselectedIcon = Icons.Outlined.Add
    )
    object Alimentos : Screen(
        route = "alimentos",
        title = "Alimentos",
        selectedIcon = Icons.Filled.Restaurant,
        unselectedIcon = Icons.Outlined.Restaurant
    )
    object Historial : Screen(
        route = "historial",
        title = "Historial",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )
    object Perfil : Screen(
        route = "perfil",
        title = "Perfil",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}

private enum class GestureAxis {
    Horizontal,
    Vertical
}

// Lista de pantallas para la barra de navegación
private val bottomNavScreens = listOf(
    Screen.NuevaComida,
    Screen.Historial,
    Screen.Alimentos,
    Screen.Perfil
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiabetesNavGraph(app: DiabetesApp) {
    val pagerState = rememberPagerState(initialPage = 0) { bottomNavScreens.size }
    val coroutineScope = rememberCoroutineScope()
    val viewConfig = LocalViewConfiguration.current
    var pagerUserScrollEnabled by remember { mutableStateOf(true) }
    var showStats by remember { mutableStateOf(false) }
    val horizontalDominance = 1.6f
    val verticalOverrideDominance = 1.4f
    val pagerNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!pagerUserScrollEnabled) return Offset.Zero
                return if (abs(available.x) > abs(available.y) * horizontalDominance) {
                    Offset(available.x, 0f)
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pagerUserScrollEnabled) return Velocity.Zero
                return if (abs(available.x) > abs(available.y) * horizontalDominance) {
                    Velocity(available.x, 0f)
                } else {
                    Velocity.Zero
                }
            }
        }
    }
    val pagerGestureModifier = Modifier.pointerInput(viewConfig) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var totalY = 0f
            var axis: GestureAxis? = null
            pagerUserScrollEnabled = true

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                totalX += delta.x
                totalY += delta.y

                if (axis == null) {
                    val absX = abs(totalX)
                    val absY = abs(totalY)
                    if (absX > viewConfig.touchSlop || absY > viewConfig.touchSlop) {
                        axis = if (absX > absY * horizontalDominance) {
                            GestureAxis.Horizontal
                        } else {
                            GestureAxis.Vertical
                        }
                        pagerUserScrollEnabled = axis == GestureAxis.Horizontal
                    }
                } else if (axis == GestureAxis.Horizontal) {
                    val absX = abs(totalX)
                    val absY = abs(totalY)
                    if (absY > absX * verticalOverrideDominance &&
                        absY > viewConfig.touchSlop * 1.5f
                    ) {
                        axis = GestureAxis.Vertical
                        pagerUserScrollEnabled = false
                    }
                }
            }

            pagerUserScrollEnabled = true
        }
    }
    
    // ViewModel global para Nightscout
    val nsViewModel: NightscoutViewModel = viewModel(
        factory = NightscoutViewModel.Factory(app.usuarioRepository, app.nightscoutRepository)
    )
    val nsState by nsViewModel.glucoseState.collectAsState()
    val nsStatus by nsViewModel.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, nsViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                nsViewModel.onAppForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // Estado de snackbars
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = showStats) {
        showStats = false
    }
    
    Scaffold(
        topBar = {
            val currentScreen = bottomNavScreens[pagerState.currentPage]
            TopAppBar(
                title = {
                    if (showStats) {
                        Text("Estadísticas")
                    } else {
                        Column {
                            Text(currentScreen.title)

                            // Subtítulo de glucosa
                            when (val state = nsState) {
                                is NightscoutUiState.Success -> {
                                    val arrow = nsViewModel.getTrendArrow(state.entry.direction)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Bloodtype,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(6.dp))
                                        Text(
                                            text = "${state.entry.sgv} mg/dL $arrow",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                is NightscoutUiState.Loading -> {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Actualizando glucosa...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                is NightscoutUiState.Error -> {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(6.dp))
                                        Text(
                                            text = "Error de Nightscout",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                else -> {} // En espera o no configurado
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (showStats) {
                        IconButton(onClick = { showStats = false }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Volver"
                            )
                        }
                    }
                },
                actions = {
                    if (!showStats) {
                        IconButton(onClick = { showStats = true }) {
                            Icon(
                                imageVector = Icons.Filled.BarChart,
                                contentDescription = "Abrir estadísticas"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!showStats) {
                NavigationBar {
                    bottomNavScreens.forEachIndexed { index, screen ->
                        val selected = pagerState.currentPage == index

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon
                                    else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        )
                        if (index == 1) {
                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (showStats) {
            val viewModel: EstadisticasViewModel = viewModel(
                factory = EstadisticasViewModel.Factory(
                    app.registroRepository,
                    app.usuarioRepository,
                    app.nightscoutRepository
                )
            )
            EstadisticasScreen(
                viewModel = viewModel,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            )
        } else {
            HorizontalPager(
                state = pagerState,
                pageNestedScrollConnection = pagerNestedScrollConnection,
                userScrollEnabled = pagerUserScrollEnabled,
                beyondBoundsPageCount = 0,
                modifier = Modifier
                    .then(pagerGestureModifier)
                    .padding(innerPadding)
                    .fillMaxSize()
            ) { page ->
                when (bottomNavScreens[page]) {
                    Screen.NuevaComida -> {
                        val viewModel: NuevaComidaViewModel = viewModel(
                            factory = NuevaComidaViewModel.Factory(
                                app.usuarioRepository,
                                app.alimentoRepository,
                                app.registroRepository,
                                app.nightscoutRepository,
                                app.plantillaRepository,
                                app.pendingGlucoseRepository,
                                app.registroNightscoutSyncRepository,
                                WorkManager.getInstance(app)
                            )
                        )
                        NuevaComidaScreen(
                            viewModel = viewModel,
                            currentGlucoseMgdl = (nsState as? NightscoutUiState.Success)?.entry?.sgv,
                            tabChangeSignal = pagerState.currentPage,
                            onNavigateToProfile = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(3)
                                }
                            }
                        )
                    }
                    Screen.Alimentos -> {
                        val viewModel: AlimentosViewModel = viewModel(
                            factory = AlimentosViewModel.Factory(app.alimentoRepository)
                        )
                        AlimentosScreen(viewModel = viewModel)
                    }
                    Screen.Historial -> {
                        val viewModel: HistorialViewModel = viewModel(
                            factory = HistorialViewModel.Factory(
                                app.registroRepository,
                                app.plantillaRepository,
                                app.usuarioRepository,
                                app.nightscoutTreatmentTombstoneRepository,
                                app.nightscoutRepository
                            )
                        )
                        HistorialScreen(viewModel = viewModel)
                    }
                    Screen.Perfil -> {
                        val pendingGlucose by app.pendingGlucoseRepository.pending.collectAsState(initial = emptyList())
                        val nightscoutImportCount by app.registroRepository.nightscoutImportCount.collectAsState(initial = 0)
                        val registroSyncSummary by app.registroNightscoutSyncRepository.summary.collectAsState(
                            initial = NightscoutRegistrosSyncSummary()
                        )
                        val pendingMaxAttempts = pendingGlucose.maxOfOrNull { it.attempts } ?: 0
                        val viewModel: PerfilViewModel = viewModel(
                            factory = PerfilViewModel.Factory(
                                app.usuarioRepository,
                                app.backupManager,
                                WorkManager.getInstance(app)
                            )
                        )
                        PerfilScreen(
                            viewModel = viewModel,
                            tabChangeSignal = pagerState.currentPage,
                            nightscoutStatus = nsStatus,
                            pendingGlucoseCount = pendingGlucose.size,
                            pendingMaxAttempts = pendingMaxAttempts,
                            onRefreshNightscout = nsViewModel::refreshNow,
                            nightscoutRegistroSyncSummary = registroSyncSummary,
                            nightscoutImportCount = nightscoutImportCount,
                            onSyncRegistrosNow = {
                                NightscoutSyncWorker.enqueueNow(
                                    workManager = WorkManager.getInstance(app),
                                    forceManual = true
                                )
                            },
                            onResyncRegistros30d = {
                                NightscoutSyncWorker.enqueueResync30Days(WorkManager.getInstance(app))
                            }
                        )
                    }
                }
            }
        }
    }
}
