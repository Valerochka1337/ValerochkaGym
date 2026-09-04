package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/**
 * Досеивает карты вовлечения мышц: каждому упражнению без ни одной строки в `exercise_muscles`
 * добавляет карту — точную из [CanonicalExerciseRegistry] или фоллбэк по
 * [MuscleGroup.defaultMuscleLoads][com.valerochka1337.valerochkagym.data.db.defaultMuscleLoads].
 *
 * Вызывается из [GymDatabaseCallback.onOpen] при каждом открытии базы, поэтому одна и та же
 * операция закрывает три случая: чистая установка, апгрейд с v2 (миграция создаёт таблицу пустой)
 * и упражнения, приехавшие импортом из Sheets. Уже размеченные упражнения не трогаются —
 * ручные правки пользователя не перезатираются.
 */
suspend fun seedMissingExerciseMuscles(
    exerciseDao: ExerciseDao,
    exerciseMuscleDao: ExerciseMuscleDao,
) {
    val mapped = exerciseMuscleDao.getMappedExerciseIds().toSet()
    val rows = exerciseDao.getAllOnce()
        .filter { it.id !in mapped && CanonicalExerciseRegistry.isBuiltIn(it) }
        .flatMap { exercise -> CanonicalExerciseRegistry.loadsFor(exercise).orEmpty().map { load ->
            ExerciseMuscleEntity(exercise.id, load.muscle, load.contribution)
        } }
    if (rows.isNotEmpty()) exerciseMuscleDao.upsertAll(rows)
}

/**
 * Registry-only reconciliation. Existing built-ins are found through the explicit identity
 * bridge, so their local ID, historic workout links, sync ID and every gym relation survive.
 * Custom rows are intentionally invisible to this operation.
 */
suspend fun reconcileCanonicalExerciseCatalog(database: GymDatabase) = database.withTransaction {
    val exerciseDao = database.exerciseDao()
    val muscleDao = database.exerciseMuscleDao()
    val existing = exerciseDao.getAllOnce()
    val matchedKeys = mutableSetOf<String>()
    existing.forEach { exercise ->
        val entry = CanonicalExerciseRegistry.match(exercise) ?: return@forEach
        matchedKeys += entry.key
        muscleDao.replaceForExercise(
            exercise.id,
            entry.loads.map { ExerciseMuscleEntity(exercise.id, it.muscle, it.contribution) },
        )
    }
    CanonicalExerciseRegistry.entries
        .filterNot { it.key in matchedKeys }
        .forEach { entry ->
            val id = exerciseDao.insert(entry.exercise)
            muscleDao.replaceForExercise(
                id,
                entry.loads.map { ExerciseMuscleEntity(id, it.muscle, it.contribution) },
            )
        }
}

/** Строки карты для одного упражнения — точные из каталога, иначе типичные для его группы. */
fun ExerciseEntity.muscleRows(): List<ExerciseMuscleEntity> =
    muscleLoadsFor(this).map { ExerciseMuscleEntity(id, it.muscle, it.contribution) }

/**
 * Карта вовлечения упражнения: из канонического каталога по стабильной идентичности, затем по
 * совместимому имени (регистр и края игнорируются), иначе — типичная для его
 * [MuscleGroup][com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup].
 */
fun muscleLoadsFor(exercise: ExerciseEntity): List<MuscleLoad> =
    CanonicalExerciseRegistry.loadsFor(exercise)
        ?: seedExerciseMuscles[exercise.name.trim().lowercase()]
        ?: exercise.muscleGroup.defaultMuscleLoads()
