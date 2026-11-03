package com.pleos.cooldown.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.domain.model.DrivingEvent
import com.pleos.cooldown.presentation.theme.*
import java.time.format.DateTimeFormatter

@Composable
fun ViolationCard(
    event: DrivingEvent,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        event.violation.score <= -30 -> ViolationCritical.copy(alpha = 0.2f)
        event.violation.score < 0 -> ViolationMinor.copy(alpha = 0.2f)
        else -> PositiveAction.copy(alpha = 0.2f)
    }

    val textColor = when {
        event.violation.score <= -30 -> ViolationCritical
        event.violation.score < 0 -> ViolationMinor
        else -> PositiveAction
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = event.violation.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
                Text(
                    text = event.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Text(
                text = if (event.violation.score > 0) "+${event.violation.score}" else event.violation.score.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = textColor
            )
        }
    }
}
