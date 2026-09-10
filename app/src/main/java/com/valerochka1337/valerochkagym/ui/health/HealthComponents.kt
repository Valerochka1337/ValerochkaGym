package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.ui.components.GlowBackground

@Composable
internal fun HealthPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
  GlowBackground {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      val padding = if (maxWidth < 600.dp) 16.dp else 24.dp
      Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onBack, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
          }
          Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
      }
    }
  }
}

@Composable
internal fun HealthChoice(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  FilterChip(
      selected,
      onClick,
      label = { Text(text) },
      enabled = enabled,
      modifier =
          Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            contentDescription = text
          },
  )
}

internal fun healthKindLabel(kind: HealthRecordKind): String =
    when (kind) {
      HealthRecordKind.REPORT -> "Отчёт"
      HealthRecordKind.OBSERVATION -> "Наблюдение"
      HealthRecordKind.RESTRICTION -> "Ограничение"
    }

internal fun healthRecordLabel(payload: HealthPayload?): String =
    when (payload) {
      is HealthPayload.Report -> payload.title
      is HealthPayload.Observation -> "${payload.metricNameOriginal}: ${payload.valueOriginal}"
      is HealthPayload.Restriction -> payload.textOriginal
      null -> "Удалённая запись"
    }
