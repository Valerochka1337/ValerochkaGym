package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymTopBar

/**
 * Вкладка «Анализы»: заглушка под будущий анализ результатов (графики, статистика).
 * Пока показывает только пояснение о том, что появится в следующих версиях.
 */
@Composable
fun AnalysisScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            GymTopBar(title = "Анализы", onOpenSettings = onOpenSettings)
            ComingSoon(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComingSoon(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_tab_analysis),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Скоро здесь появится анализ результатов",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "В следующих версиях — графики прогресса, объём по группам мышц и другая статистика по тренировкам.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
