package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.builtInExerciseSyncId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalExerciseRegistryTest {
  @Test
  fun `catalogue has unique explicit rows and complete legacy bridges`() {
    val entries = CanonicalExerciseRegistry.entries
    fun normalized(name: String) = name.lowercase().replace('ё', 'е').filter(Char::isLetterOrDigit)
    assertTrue(entries.size in 250..350)
    assertEquals(entries.size, entries.map { it.key }.toSet().size)
    assertEquals(entries.size, entries.map { it.exercise.syncId }.toSet().size)
    assertEquals(entries.size, entries.map { normalized(it.exercise.name) }.toSet().size)
    assertTrue(entries.none { " — " in it.exercise.name })
    assertEquals(legacySeedExercises.size, entries.count { it.legacyNames.isNotEmpty() })
    legacySeedExercises.forEach { legacy ->
      val entry = requireNotNull(CanonicalExerciseRegistry.match(legacy))
      assertTrue(legacy.name in entry.legacyNames)
      assertTrue(builtInExerciseSyncId(legacy.name) in entry.legacySyncIds)
      assertEquals(builtInExerciseSyncId(legacy.name), entry.exercise.syncId)
    }
  }

  @Test
  fun `explicit movement patterns have canonical role maps`() {
    val entries = CanonicalExerciseRegistry.entries
    assertTrue(
        entries.all { it.loads == CanonicalExerciseRegistry.canonicalLoads(it.movementPattern) }
    )
    fun named(name: String) = entries.first { it.exercise.name == name }
    fun primary(entry: CanonicalExerciseRegistry.Entry, muscle: Muscle) =
        entry.loads.any { it.muscle == muscle && it.contribution == 100 }
    val pecDeck = named("Пек-дек")
    assertEquals(CanonicalExerciseRegistry.MovementPattern.CHEST_FLY, pecDeck.movementPattern)
    assertTrue(primary(pecDeck, Muscle.UPPER_CHEST) && primary(pecDeck, Muscle.LOWER_CHEST))
    assertTrue(primary(named("Подъём гантелей в стороны сидя"), Muscle.SIDE_DELTS))
    assertEquals(
        CanonicalExerciseRegistry.MovementPattern.LATERAL_RAISE,
        named("Подъём руки в сторону в кроссовере").movementPattern,
    )
    assertTrue(primary(named("Подъём руки в сторону в кроссовере"), Muscle.SIDE_DELTS))
    assertEquals(
        CanonicalExerciseRegistry.MovementPattern.REAR_DELT_FLY,
        named("Обратный пек-дек").movementPattern,
    )
    assertTrue(primary(named("Обратный пек-дек"), Muscle.REAR_DELTS))
    listOf("Наружная ротация блока", "Внутренняя ротация блока").forEach {
      assertTrue(primary(named(it), Muscle.ROTATOR_CUFF))
    }
    assertTrue(primary(named("Шраги с гантелями"), Muscle.TRAPS))
    assertTrue(primary(named("Сгибание EZ-грифа"), Muscle.BICEPS))
    assertTrue(primary(named("Разгибание канатом"), Muscle.TRICEPS))
    val facePull = named("Face pull")
    assertTrue(primary(facePull, Muscle.REAR_DELTS) && primary(facePull, Muscle.UPPER_BACK))
    assertEquals(
        CanonicalExerciseRegistry.MovementPattern.HORIZONTAL_PULL,
        named("Тяга одной рукой в кроссовере").movementPattern,
    )
    assertTrue(primary(named("Тяга одной рукой в кроссовере"), Muscle.LATS))
    assertEquals(
        CanonicalExerciseRegistry.MovementPattern.HIP_ABDUCTION,
        named("Отведение бедра в кроссовере").movementPattern,
    )
    assertTrue(primary(named("Отведение бедра в кроссовере"), Muscle.HIP_ABDUCTORS))
    assertEquals(
        CanonicalExerciseRegistry.MovementPattern.HIP_ADDUCTION,
        named("Приведение бедра в кроссовере").movementPattern,
    )
    assertTrue(primary(named("Приведение бедра в кроссовере"), Muscle.ADDUCTORS))
    assertEquals(
        CanonicalExerciseRegistry.MovementPattern.TIBIALIS_RAISE,
        named("Подъём носков").movementPattern,
    )
    assertTrue(primary(named("Подъём носков"), Muscle.TIBIALIS_ANTERIOR))
    listOf(
            "Подъём руки в сторону в кроссовере",
            "Обратный пек-дек",
            "Тяга одной рукой в кроссовере",
            "Отведение бедра в кроссовере",
            "Приведение бедра в кроссовере",
        )
        .forEach { name ->
          assertTrue(
              named(name).loads.none {
                it.muscle in setOf(Muscle.UPPER_CHEST, Muscle.LOWER_CHEST) && it.contribution == 100
              }
          )
        }
    assertTrue(named("Беговая дорожка").loads.all { it.contribution == 50 })
  }

  @Test
  fun `catalogue covers approved families and excludes impossible pairs`() {
    val coverage = CanonicalExerciseRegistry.entries.flatMap { it.coverage }.toSet()
    assertTrue(
        setOf(
                "chest",
                "shoulders",
                "back",
                "arms",
                "legs",
                "core",
                "powerlifting",
                "calisthenics",
                "cardio",
            )
            .all(coverage::contains)
    )
    assertTrue(
        setOf(
                "barbell",
                "dumbbell",
                "machine",
                "cable",
                "bodyweight",
                "rings",
                "kettlebell",
                "cardio_machine",
            )
            .all(coverage::contains)
    )
    val names = CanonicalExerciseRegistry.entries.map { it.exercise.name.lowercase() }
    assertTrue(names.none { "кольц" in it && "тренажёр" in it })
    assertTrue(names.none { "дорож" in it && "свободный вес" in it })
  }

  @Test
  fun `every static row parses and legacy core rows keep conservative core loads`() {
    val entries = CanonicalExerciseRegistry.entries
    assertTrue(entries.isNotEmpty())
    val plank = entries.first { it.exercise.name == "Планка" }
    assertEquals(CanonicalExerciseRegistry.MovementPattern.CORE, plank.movementPattern)
    assertTrue(plank.loads.any { it.muscle == Muscle.ABS && it.contribution == 100 })
    assertTrue(plank.loads.any { it.muscle == Muscle.OBLIQUES && it.contribution == 50 })
  }
}
