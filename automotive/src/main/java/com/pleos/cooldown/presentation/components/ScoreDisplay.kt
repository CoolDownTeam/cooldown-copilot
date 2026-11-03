package com.pleos.cooldown.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.domain.model.ScoreGrade
import com.pleos.cooldown.presentation.theme.*

@Composable
fun ScoreDisplay(
    score: Int,
    grade: ScoreGrade,
    modifier: Modifier = Modifier
) {
    val scoreColor = when (grade) {
        ScoreGrade.EXCELLENT -> ScoreExcellent
        ScoreGrade.GOOD -> ScoreGood
        ScoreGrade.AVERAGE -> ScoreAverage
        ScoreGrade.POOR -> ScorePoor
        ScoreGrade.BAD -> ScoreBad
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 점수
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = scoreColor,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 등급
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = grade.emoji,
                style = MaterialTheme.typography.headlineMedium,
                color = scoreColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = grade.label,
                style = MaterialTheme.typography.headlineSmall,
                color = TextSecondary
            )
        }
    }
}
