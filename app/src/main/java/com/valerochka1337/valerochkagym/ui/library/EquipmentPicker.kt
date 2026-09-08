package com.valerochka1337.valerochkagym.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EquipmentPicker(
    requirements: ExerciseEquipmentRequirements,
    onRequirementsChange: (ExerciseEquipmentRequirements) -> Unit,
    enabled: Boolean = true,
) {
  var expanded by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  val haptics = gymHaptics()
  val selectedIds =
      (requirements as? ExerciseEquipmentRequirements.Required)?.equipmentIds.orEmpty()
  val selected =
      remember(selectedIds, LocalEquipmentCatalog.state.collectAsState().value) {
        LocalEquipmentCatalog.alphabeticalEntries.filter { it.id in selectedIds }
      }
  val results =
      remember(query, LocalEquipmentCatalog.state.collectAsState().value) {
        LocalEquipmentCatalog.search(query)
      }

  fun toggle(id: String) {
    val updated = if (id in selectedIds) selectedIds - id else selectedIds + id
    haptics.toggle(id in updated)
    onRequirementsChange(
        if (updated.isEmpty()) ExerciseEquipmentRequirements.ExplicitNone
        else ExerciseEquipmentRequirements.Required(updated)
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(
        selected = requirements is ExerciseEquipmentRequirements.ExplicitNone,
        onClick = {
          haptics.toggle(true)
          onRequirementsChange(ExerciseEquipmentRequirements.ExplicitNone)
        },
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        label = { Text("Без оборудования") },
    )
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = {
          if (enabled) {
            haptics.tap()
            if (it) query = ""
            expanded = it
          }
        },
    ) {
      OutlinedTextField(
          value =
              if (expanded) query else if (selected.isEmpty()) "" else "Выбрано: ${selected.size}",
          onValueChange = {
            query = it
            expanded = true
          },
          modifier =
              Modifier.fillMaxWidth()
                  .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled),
          enabled = enabled,
          singleLine = true,
          label = { Text("Поиск оборудования") },
          placeholder = { Text("Выбрать оборудование") },
          leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
          trailingIcon = {
            if (expanded && query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.Rounded.Close, contentDescription = "Очистить поиск оборудования")
              }
            } else {
              ExposedDropdownMenuDefaults.TrailingIcon(
                  expanded = expanded,
                  modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable),
              )
            }
          },
          shape = MaterialTheme.shapes.medium,
      )
      ExposedDropdownMenu(
          expanded = expanded && enabled,
          onDismissRequest = { expanded = false },
          modifier = Modifier.heightIn(max = 280.dp),
      ) {
        if (results.isEmpty()) {
          Text(
              "Оборудование не найдено",
              modifier = Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        results.forEach { equipment ->
          val checked = equipment.id in selectedIds
          DropdownMenuItem(
              text = { Text(equipment.name) },
              onClick = { toggle(equipment.id) },
              leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
              modifier =
                  Modifier.heightIn(min = 48.dp).semantics {
                    role = Role.Checkbox
                    stateDescription = if (checked) "Выбрано" else "Не выбрано"
                  },
          )
        }
      }
    }
    if (selected.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.forEach { equipment ->
          InputChip(
              selected = true,
              onClick = { toggle(equipment.id) },
              enabled = enabled,
              modifier = Modifier.heightIn(min = 48.dp),
              label = { Text(equipment.name) },
              trailingIcon = {
                Icon(Icons.Rounded.Close, contentDescription = "Убрать ${equipment.name}")
              },
          )
        }
      }
    }
  }
}
