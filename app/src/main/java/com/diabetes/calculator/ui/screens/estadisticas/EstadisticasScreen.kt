package com.diabetes.calculator.ui.screens.estadisticas

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diabetes.calculator.ui.theme.HidratosColor
import com.diabetes.calculator.ui.theme.InsulinaColor
import com.diabetes.calculator.ui.theme.RacionesColor
import java.util.Locale

@Composable
fun EstadisticasScreen(
    viewModel: EstadisticasViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val period by viewModel.period.collectAsState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
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
