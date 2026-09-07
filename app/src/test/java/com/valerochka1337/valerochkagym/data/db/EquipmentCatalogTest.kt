package com.valerochka1337.valerochkagym.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentCatalogTest {
  @Test
  fun `canonical requirements cover every built in exercise without generic equipment`() {
    assertEquals(
        CanonicalExerciseRegistry.entries.map { it.key }.toSet(),
        CanonicalEquipmentRequirements.byKey.keys,
    )
    assertTrue(CanonicalEquipmentRequirements.byKey.values.flatten().all(EquipmentCatalog::isKnown))
    assertFalse(CanonicalEquipmentRequirements.byKey.values.flatten().any { it == "machine" })
  }

  @Test
  fun `adjustable bench covers flat and incline but not decline`() {
    assertTrue(EquipmentCatalog.covers(setOf("adjustable_bench"), "flat_bench"))
    assertTrue(EquipmentCatalog.covers(setOf("adjustable_bench"), "incline_bench"))
    assertFalse(EquipmentCatalog.covers(setOf("adjustable_bench"), "decline_bench"))
  }

  @Test
  fun `catalog search finds agreed synonyms`() {
    assertEquals(
        setOf("elliptical"),
        EquipmentCatalog.search("орбитрек").mapTo(linkedSetOf()) { it.id },
    )
    assertTrue(EquipmentCatalog.search("кроссовер").any { it.id == "crossover" })
  }

  @Test
  fun `catalog search combines spelling folds typos synonyms and unordered words`() {
    assertTrue(EquipmentCatalog.search("КОЛЕНЕЙ ПОДЬЕМ").any { it.id == "captains_chair" })
    assertEquals(listOf("dumbbells"), EquipmentCatalog.search("ганетли").map { it.id })
    assertEquals(listOf("elliptical"), EquipmentCatalog.search("орбитерк").map { it.id })
    assertTrue(EquipmentCatalog.search("ТРЕНАЖЕР гребной").any { it.id == "rowing_machine" })
  }

  @Test
  fun `equipment search returns alphabetical names with and without a query`() {
    listOf("", "тренажер").forEach { query ->
      val names = EquipmentCatalog.search(query).map { it.name.lowercase().replace('ё', 'е') }
      assertEquals(names.sorted(), names)
    }
  }
}
