package com.valerochka1337.valerochkagym.ui.library

import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict

/** Readable transaction-conflict detail shared by the library and exercise detail editors. */
fun formatExerciseSaveConflict(conflict: GymConfigurationConflict): String =
    buildList {
          if (conflict.missingEquipmentIds.isNotEmpty()) {
            add(
                "Недостающее оборудование: " +
                    conflict.missingEquipmentIds.sorted().joinToString { id ->
                      EquipmentCatalog.entries.firstOrNull { it.id == id }?.name ?: id
                    },
            )
          }
          if (conflict.exercises.isNotEmpty()) {
            add("Затронутые упражнения: ${conflict.exercises.joinToString { it.name }}")
          }
          if (conflict.routines.isNotEmpty()) {
            add(
                "Программы или активные тренировки: " + conflict.routines.joinToString { it.name },
            )
          }
        }
        .ifEmpty { listOf("Изменение несовместимо с выбранными залами.") }
        .joinToString("\n")
