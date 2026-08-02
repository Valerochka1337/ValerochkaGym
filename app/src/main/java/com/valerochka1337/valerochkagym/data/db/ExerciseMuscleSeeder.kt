package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/**
 * Досеивает карты вовлечения мышц: каждому упражнению без ни одной строки в `exercise_muscles`
 * добавляет карту — точную из каталога [seedExerciseMuscles] или фоллбэк по
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
        .filter { it.id !in mapped }
        .flatMap { exercise -> exercise.muscleRows() }
    if (rows.isNotEmpty()) exerciseMuscleDao.upsertAll(rows)
}

/** Строки карты для одного упражнения — точные из каталога, иначе типичные для его группы. */
fun ExerciseEntity.muscleRows(): List<ExerciseMuscleEntity> =
    muscleLoadsFor(this).map { ExerciseMuscleEntity(id, it.muscle, it.contribution) }

/**
 * Карта вовлечения упражнения: из каталога по имени (регистр и края игнорируются), иначе —
 * типичная для его [MuscleGroup][com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup].
 */
fun muscleLoadsFor(exercise: ExerciseEntity): List<MuscleLoad> =
    seedExerciseMuscles[exercise.name.trim().lowercase()]
        ?: exercise.muscleGroup.defaultMuscleLoads()
