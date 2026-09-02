package com.valerochka1337.valerochkagym.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseVariantEditorSheet(
    exerciseName: String,
    variants: List<ExerciseVariantEntity>,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String?, String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var editingSyncId by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Варианты выполнения", style = MaterialTheme.typography.titleLarge)
            Text("$exerciseName — варианты влияют на отдельную историю результатов.", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            variants.forEach { variant ->
                OutlinedButton(
                    onClick = {
                        editingSyncId = variant.syncId
                        draft = variant.name
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "$exerciseName, ${variant.name}, ${if (variant.isArchived) "восстановить" else "архивировать"}"
                    },
                ) { Text("${variant.name} · изменить") }
                OutlinedButton(
                    onClick = { onArchive(variant.syncId, !variant.isArchived) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "$exerciseName, ${variant.name}, ${if (variant.isArchived) "восстановить" else "архивировать"}"
                    },
                ) { Text(if (variant.isArchived) "Восстановить" else "Архивировать") }
            }
            TextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (editingSyncId == null) "Новый вариант" else "Название варианта") }, singleLine = true)
            Button(
                onClick = { onSave(editingSyncId, draft) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (editingSyncId == null) "Сохранить вариант" else "Переименовать вариант") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
