package com.valerochka1337.valerochkagym.ui.active

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseVariantSelectionSheet(
    exerciseName: String,
    variants: List<ExerciseVariantEntity>,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text("Выберите вариант выполнения", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                onClick = { onChoose(null) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$exerciseName, без варианта" },
            ) { Text("Без варианта") }
            variants.forEach { variant ->
                OutlinedButton(
                    onClick = { onChoose(variant.syncId) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$exerciseName, ${variant.name}" },
                ) { Text(variant.name) }
            }
        }
    }
}
