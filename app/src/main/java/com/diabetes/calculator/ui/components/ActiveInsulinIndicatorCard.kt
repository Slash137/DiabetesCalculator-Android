package com.diabetes.calculator.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diabetes.calculator.domain.ActiveInsulinSnapshot
import java.util.Locale

@Composable
fun ActiveInsulinIndicatorCard(
    snapshot: ActiveInsulinSnapshot,
    isLoading: Boolean,
    title: String,
    modifier: Modifier = Modifier
) {
    val valueText = if (isLoading) {
        "Calculando…"
    } else {
        String.format(Locale.getDefault(), "%.1f U", snapshot.totalUnits)
    }

    val descriptionText = if (isLoading) {
        "Calculando…"
    } else if (snapshot.doseCount <= 0) {
        "Sin dosis activas fiables en las últimas 4 h"
    } else {
        val minutes = snapshot.minutesToZero ?: 0
        "${snapshot.doseCount} dosis fiables · $minutes min hasta 0 U"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = descriptionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}
