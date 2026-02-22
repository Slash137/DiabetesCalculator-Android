package com.diabetes.calculator.ui.screens.estadisticas

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diabetes.calculator.ui.theme.HidratosColor
import com.diabetes.calculator.ui.theme.InsulinaColor
import com.diabetes.calculator.ui.theme.RacionesColor
import com.diabetes.calculator.work.Recordatorio2hNotificationHelper
import kotlinx.coroutines.delay
import java.util.Locale

private const val DISCLAIMER_TAP_WINDOW_MS = 1_200L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EstadisticasScreen(
    viewModel: EstadisticasViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val informeIaState by viewModel.informeIaState.collectAsState()
    val period by viewModel.period.collectAsState()
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Vista global de tus registros",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PeriodSelector(
                selected = period,
                onSelect = viewModel::updatePeriod
            )

            when (val state = uiState) {
                is EstadisticasUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is EstadisticasUiState.Empty -> {
                    EmptyStatsCard(period = state.period)
                }

                is EstadisticasUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                is EstadisticasUiState.Success -> {
                    StatsContent(resumen = state.resumen)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (informeIaState is InformeIaUiState.Conversation) {
        val density = LocalDensity.current
        val isImeVisible = WindowInsets.ime.getBottom(density) > 0
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                !(isImeVisible && target == SheetValue.Hidden)
            }
        )
        val chatState = (informeIaState as InformeIaUiState.Conversation).chat
        ModalBottomSheet(
            onDismissRequest = {
                if (!isImeVisible) {
                    viewModel.dismissInformeIa()
                }
            },
            sheetState = sheetState
        ) {
            InformeIaBottomSheetContent(
                chat = chatState,
                onRefresh = viewModel::refreshInformeIaConversation,
                onDraftChange = viewModel::updateInformeIaDraft,
                onSend = viewModel::sendInformeIaMessage
            )
        }
    }
}

@Composable
private fun InformeIaBottomSheetContent(
    chat: InformeIaConversationUi,
    onRefresh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.94f)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRefresh,
                enabled = !chat.isLoading
            ) {
                Text("Refrescar conversación")
            }
        }

        HorizontalDivider()

        val conversationAreaModifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = true)
        val conversationListState = rememberLazyListState()

        LaunchedEffect(chat.messages.size, chat.isLoading) {
            val targetIndex = when {
                chat.messages.isEmpty() -> null
                chat.isLoading && chat.messages.lastOrNull()?.role == InformeIaRole.USER ->
                    chat.messages.lastIndex
                !chat.isLoading &&
                    chat.messages.lastOrNull()?.role == InformeIaRole.ASSISTANT &&
                    chat.messages.size >= 2 &&
                    chat.messages[chat.messages.lastIndex - 1].role == InformeIaRole.USER ->
                    chat.messages.lastIndex - 1
                else -> null
            }
            targetIndex?.let { index ->
                delay(40)
                conversationListState.animateScrollToItem(index = index)
            }
        }

        if (chat.messages.isEmpty() && chat.isLoading) {
            Box(
                modifier = conversationAreaModifier,
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Analizando tus estadísticas...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Generando el informe inicial",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = conversationAreaModifier,
                state = conversationListState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(chat.messages) { _, message ->
                    InformeIaMessageBubble(message = message)
                }

                if (chat.isLoading) {
                    item(key = "ia_typing_indicator") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "La IA está escribiendo...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        chat.errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = chat.draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 120.dp),
                enabled = !chat.isLoading,
                placeholder = { Text("Escribe una pregunta...") }
            )
            IconButton(
                onClick = onSend,
                enabled = !chat.isLoading && chat.draft.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar mensaje"
                )
            }
        }
    }
}

@Composable
private fun InformeIaMessageBubble(message: InformeIaMessage) {
    val isUser = message.role == InformeIaRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else {
                MarkdownReport(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun MarkdownReport(
    text: String,
    modifier: Modifier = Modifier
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val lines = remember(text) { text.replace("\r\n", "\n").split('\n') }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                line.startsWith("### ") -> {
                    Text(
                        text = markdownInline(line.removePrefix("### ").trim(), codeBackground),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                line.startsWith("## ") -> {
                    Text(
                        text = markdownInline(line.removePrefix("## ").trim(), codeBackground),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                line.startsWith("# ") -> {
                    Text(
                        text = markdownInline(line.removePrefix("# ").trim(), codeBackground),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    MarkdownListRow(
                        marker = "•",
                        content = line.drop(2).trim(),
                        codeBackground = codeBackground
                    )
                }

                line.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                    val marker = line.takeWhile { it.isDigit() } + "."
                    val content = line.replaceFirst(Regex("^\\d+[.)]\\s+"), "").trim()
                    MarkdownListRow(
                        marker = marker,
                        content = content,
                        codeBackground = codeBackground
                    )
                }

                line.startsWith("> ") -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Text(
                            text = markdownInline(line.removePrefix("> ").trim(), codeBackground),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                else -> {
                    Text(
                        text = markdownInline(line.trim(), codeBackground),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownListRow(
    marker: String,
    content: String,
    codeBackground: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = markdownInline(content, codeBackground),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun markdownInline(
    text: String,
    codeBackground: Color
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var index = 0

    while (index < text.length) {
        val boldStart = text.indexOf("**", startIndex = index).takeIf { it >= 0 }
        val codeStart = text.indexOf('`', startIndex = index).takeIf { it >= 0 }
        val nextStart = listOfNotNull(boldStart, codeStart).minOrNull()

        if (nextStart == null) {
            builder.append(text.substring(index))
            break
        }

        if (nextStart > index) {
            builder.append(text.substring(index, nextStart))
        }

        if (boldStart == nextStart) {
            val end = text.indexOf("**", startIndex = nextStart + 2)
            if (end > nextStart + 2) {
                val spanStart = builder.length
                builder.append(text.substring(nextStart + 2, end))
                builder.addStyle(
                    style = SpanStyle(fontWeight = FontWeight.SemiBold),
                    start = spanStart,
                    end = builder.length
                )
                index = end + 2
            } else {
                builder.append("**")
                index = nextStart + 2
            }
        } else {
            val end = text.indexOf('`', startIndex = nextStart + 1)
            if (end > nextStart + 1) {
                val spanStart = builder.length
                builder.append(text.substring(nextStart + 1, end))
                builder.addStyle(
                    style = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground
                    ),
                    start = spanStart,
                    end = builder.length
                )
                index = end + 1
            } else {
                builder.append("`")
                index = nextStart + 1
            }
        }
    }

    return buildAnnotatedString {
        append(builder.toAnnotatedString())
    }
}

@Composable
private fun PeriodSelector(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit
) {
    val periods = StatsPeriod.values().toList()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp

        if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    periods.take(2).forEach { period ->
                        PeriodChip(
                            modifier = Modifier.weight(1f),
                            period = period,
                            selected = period == selected,
                            onSelect = onSelect
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    periods.drop(2).forEach { period ->
                        PeriodChip(
                            modifier = Modifier.weight(1f),
                            period = period,
                            selected = period == selected,
                            onSelect = onSelect
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                periods.forEach { period ->
                    PeriodChip(
                        modifier = Modifier.weight(1f),
                        period = period,
                        selected = period == selected,
                        onSelect = onSelect
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodChip(
    modifier: Modifier,
    period: StatsPeriod,
    selected: Boolean,
    onSelect: (StatsPeriod) -> Unit
) {
    FilterChip(
        modifier = modifier.heightIn(min = 42.dp),
        selected = selected,
        onClick = { onSelect(period) },
        label = {
            Text(
                text = period.label.uppercase(Locale.getDefault()),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
private fun EmptyStatsCard(period: StatsPeriod) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "No hay datos para ${period.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Registra algunas comidas y aquí aparecerán tus métricas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatsContent(resumen: EstadisticasResumen) {
    val context = LocalContext.current
    var disclaimerTapCount by remember { mutableIntStateOf(0) }
    var lastDisclaimerTapAtMillis by remember { mutableLongStateOf(0L) }

    SectionCard(title = "Resumen") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HighlightMetric(
                modifier = Modifier.weight(1f),
                title = "Comidas",
                value = resumen.totalRegistros.toString(),
                color = MaterialTheme.colorScheme.primary
            )
            HighlightMetric(
                modifier = Modifier.weight(1f),
                title = "Hidratos",
                value = "${format1(resumen.hidratosTotales)} g",
                color = HidratosColor
            )
            HighlightMetric(
                modifier = Modifier.weight(1f),
                title = "Insulina",
                value = "${format1(resumen.insulinaTotal)} U",
                color = InsulinaColor
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        StatRow("Comidas/día", format2(resumen.comidasPorDia))
        StatRow("Días con registros", "${resumen.diasConRegistros}/${resumen.totalDiasPeriodo}")
        StatRow("Días sin registros", resumen.diasSinRegistros.toString())
        StatRow("Pinchazos externos", resumen.pinchazosExternos.toString())
        StatRow("Insulina externa", "${format1(resumen.insulinaExternaTotal)} U")
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        StatRow("Hidratos por comida", "${format1(resumen.hidratosPorComida)} g")
        StatRow("Raciones por comida", format1(resumen.racionesPorComida))
        StatRow("Insulina por comida", "${format1(resumen.insulinaPorComida)} U")
    }

    SectionCard(title = "Ratios") {
        StatRow(
            "Efectivo (U/ración)",
            resumen.ratioEfectivoURacion?.let { format2(it) } ?: "N/D"
        )
        StatRow(
            "Efectivo (U/g HC)",
            resumen.ratioEfectivoUG?.let { format3(it) } ?: "N/D"
        )
        StatRow(
            "Equivalencia (g por 1U)",
            resumen.gramosPorUnidad?.let { "${format2(it)} g" } ?: "N/D"
        )
        StatRow(
            "Ratio del perfil (U/ración)",
            resumen.ratioConfiguradoURacion?.let { format2(it) } ?: "No configurado"
        )
        StatRow(
            "Desviación vs. perfil",
            resumen.desviacionRatioPct?.let { "${signedPercent(it)} %" } ?: "N/D"
        )
    }

    SectionCard(title = "Tendencias (últimos 7 días)") {
        StatRow(
            "Hidratos",
            resumen.tendenciaHidratosPct?.let { "${signedPercent(it)} %" } ?: "N/D"
        )
        StatRow(
            "Insulina",
            resumen.tendenciaInsulinaPct?.let { "${signedPercent(it)} %" } ?: "N/D"
        )
        StatRow(
            "Ratio U/g",
            resumen.tendenciaRatioUGPct?.let { "${signedPercent(it)} %" } ?: "N/D"
        )
        StatRow(
            "Delta 2 h",
            resumen.tendenciaDelta2hPct?.let { "${signedPercent(it)} %" } ?: "N/D"
        )
    }

    SectionCard(title = "Glucosa (antes vs. 2h después)") {
        StatRow(
            "Media antes",
            resumen.glucosaAntesMedia?.let { "${format1(it)} mg/dL" } ?: "N/D"
        )
        StatRow(
            "Media 2h después",
            resumen.glucosaDespuesMedia?.let { "${format1(it)} mg/dL" } ?: "N/D"
        )
        StatRow(
            "Delta medio 2h después",
            resumen.delta2hMedia?.let { "${signed1(it)} mg/dL" } ?: "N/D"
        )
        StatRow(
            "Variabilidad delta",
            resumen.delta2hStdDev?.let { "${format1(it)} mg/dL" } ?: "N/D"
        )
        StatRow(
            "% 2h después rango (80-180)",
            resumen.porcentaje2hEnRango?.let { "${format1(it)}%" } ?: "N/D"
        )
        StatRow("Registros con glucosa", resumen.registrosConGlucosa.toString())
    }

    SectionCard(title = "Corrección en dosis aplicada") {
        StatRow("Dosis aplicadas", resumen.dosisAplicadas.toString())
        StatRow("Con corrección", resumen.dosisConCorreccion.toString())
        StatRow("Sin corrección", resumen.dosisSinCorreccion.toString())
        StatRow("Sin marcar", resumen.dosisSinMarcarCorreccion.toString())
        StatRow(
            "% aplicadas con corrección",
            resumen.porcentajeConCorreccion?.let { "${format1(it)}%" } ?: "N/D"
        )
        StatRow(
            "Insulina media (con corrección)",
            resumen.insulinaMediaConCorreccion?.let { "${format1(it)} U" } ?: "N/D"
        )
        StatRow(
            "Insulina media (sin corrección)",
            resumen.insulinaMediaSinCorreccion?.let { "${format1(it)} U" } ?: "N/D"
        )
    }

    SectionCard(title = "Nightscout avanzado") {
        when {
            !resumen.nightscoutConfigured -> {
                Text(
                    text = "Configura Nightscout en Perfil para activar TIR, TBR/TAR, CV y GMI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            resumen.nightscoutLoading -> {
                Text(
                    text = "Calculando métricas desde Nightscout...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            resumen.nightscoutStats != null -> {
                val ns = resumen.nightscoutStats
                StatRow("Muestras", ns.muestras.toString())
                StatRow("Glucosa media", "${format1(ns.glucosaMedia)} mg/dL")
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                StatRow("TIR 70-180", "${format1(ns.tir70_180)}%")
                StatRow("TBR <70", "${format1(ns.tbrBelow70)}%")
                StatRow("TAR >180", "${format1(ns.tarAbove180)}%")
                StatRow("TBR <54", "${format1(ns.tbrBelow54)}%")
                StatRow("TAR >250", "${format1(ns.tarAbove250)}%")
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                StatRow("CV glucémico", ns.cv?.let { "${format1(it)}%" } ?: "N/D")
                StatRow("GMI estimado", ns.gmi?.let { "${format2(it)}%" } ?: "N/D")
            }

            else -> {
                Text(
                    text = resumen.nightscoutError ?: "Sin datos de Nightscout en el periodo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    SectionCard(title = "Patrón por franja horaria") {
        resumen.franjaDistribution.forEach { franja ->
            DistributionRow(
                label = franja.label,
                count = franja.comidas,
                percent = franja.porcentaje
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    SectionCard(title = "Patrón por día de la semana") {
        val maxMeals = (resumen.weekdayStats.maxOfOrNull { it.comidas } ?: 1).coerceAtLeast(1)
        resumen.weekdayStats.forEach { day ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = day.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${day.comidas} comidas | ${format1(day.hidratosMedios)} g | ${format1(day.insulinaMedia)} U",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { day.comidas.toFloat() / maxMeals.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = RacionesColor.copy(alpha = 0.75f),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    SectionCard(title = "Top alimentos consumidos") {
        if (resumen.topAlimentos.isEmpty()) {
            Text(
                text = "Sin datos suficientes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            resumen.topAlimentos.forEachIndexed { index, food ->
                Text(
                    text = "${index + 1}. ${food.nombre}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${food.usos} usos | ${format1(food.gramosTotales)} g | ${format1(food.hidratosTotales)} g HC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (index != resumen.topAlimentos.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    SectionCard(title = "Alimentos con mayor subida 2h después") {
        if (resumen.alimentosConMayorDelta.isEmpty()) {
                Text(
                    text = "Necesitas al menos 2 muestras por alimento con glucosa antes y 2h después.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        } else {
            resumen.alimentosConMayorDelta.forEachIndexed { index, food ->
                Text(
                    text = "${index + 1}. ${food.nombre}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${signed1(food.deltaMedio)} mg/dL | ${food.muestras} muestras",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (food.deltaMedio > 0f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                )
                if (index != resumen.alimentosConMayorDelta.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    SectionCard(title = "Comidas similares") {
        if (resumen.similarMeals.isEmpty()) {
                Text(
                    text = "Aún no hay suficientes repeticiones para comparar comidas similares.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        } else {
            resumen.similarMeals.forEachIndexed { index, meal ->
                Text(
                    text = "${index + 1}. ${meal.titulo}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${meal.muestras} muestras | ${format1(meal.hidratosMedios)} g HC | ${format1(meal.insulinaMedia)} U",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val ratioText = meal.ratioUG?.let { "Ratio ${format3(it)} U/g" } ?: "Ratio N/D"
                val deltaText = meal.delta2hMedio?.let { "Delta 2h ${signed1(it)} mg/dL" } ?: "Delta 2h N/D"
                val varText = meal.delta2hStdDev?.let { "Var ${format1(it)}" } ?: "Var N/D"
                Text(
                    text = "$ratioText | $deltaText | $varText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (index != resumen.similarMeals.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    SectionCard(title = "Calidad de datos") {
        StatRow("Registros con notas", resumen.registrosConNotas.toString())
        StatRow(
            "Cobertura de días",
            "${resumen.diasConRegistros}/${resumen.totalDiasPeriodo} días (${coveragePct(resumen)}%)"
        )
    }

    Text(
        text = "Estas estadísticas son orientativas y no sustituyen el criterio médico.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val nowMillis = System.currentTimeMillis()
                disclaimerTapCount = if (nowMillis - lastDisclaimerTapAtMillis > DISCLAIMER_TAP_WINDOW_MS) {
                    1
                } else {
                    disclaimerTapCount + 1
                }
                lastDisclaimerTapAtMillis = nowMillis
                if (disclaimerTapCount >= 5) {
                    disclaimerTapCount = 0
                    lastDisclaimerTapAtMillis = 0L
                    Recordatorio2hNotificationHelper.showTestNotificationNow(context)
                    Toast.makeText(context, "Notificación de prueba enviada", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(top = 4.dp)
    )
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun HighlightMetric(
    modifier: Modifier,
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatRow(
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
private fun DistributionRow(
    label: String,
    count: Int,
    percent: Float
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "$count (${format1(percent)}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

private fun format1(value: Float): String = String.format(Locale.US, "%.1f", value)
private fun format2(value: Float): String = String.format(Locale.US, "%.2f", value)
private fun format3(value: Float): String = String.format(Locale.US, "%.3f", value)
private fun signed1(value: Float): String = String.format(Locale.US, "%+.1f", value)
private fun signedPercent(value: Float): String = String.format(Locale.US, "%+.1f", value)

private fun coveragePct(resumen: EstadisticasResumen): String {
    if (resumen.totalDiasPeriodo <= 0) return "0.0"
    val pct = (resumen.diasConRegistros.toFloat() / resumen.totalDiasPeriodo.toFloat()) * 100f
    return format1(pct)
}
