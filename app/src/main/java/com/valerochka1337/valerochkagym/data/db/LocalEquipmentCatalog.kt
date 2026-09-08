package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.domain.TextSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable in-memory projection of catalog_equipment. Writes always originate in Room. */
object LocalEquipmentCatalog {
  data class Entry(val equipment: EquipmentCatalog.Equipment, val archived: Boolean)

  private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())
  val state = mutableEntries.asStateFlow()

  fun publish(entries: List<Entry>) {
    mutableEntries.value = entries
  }

  val entries
    get() = state.value.filter { !it.archived }.map { it.equipment }

  val alphabeticalEntries
    get() =
        entries.sortedWith(
            compareBy<EquipmentCatalog.Equipment> { TextSearch.normalize(it.name) }.thenBy { it.id }
        )

  fun isKnown(id: String) = state.value.any { it.equipment.id == id }

  fun require(id: String) =
      requireNotNull(state.value.firstOrNull { it.equipment.id == id }?.equipment) {
        "Unknown equipment id: $id"
      }

  fun search(query: String): List<EquipmentCatalog.Equipment> {
    val search = TextSearch(query)
    return alphabeticalEntries.filter { search.matches(listOf(it.name, it.group) + it.synonyms) }
  }

  fun covers(selected: Set<String>, required: String) =
      state.value.any { it.equipment.id in selected && required in it.equipment.provides }
}
