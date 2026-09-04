package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Одна строка карты вовлечения мышц упражнения: «жим лёжа → трицепс 65%».
 *
 * Составной ключ `(exerciseId, muscle)` не даёт продублировать мышцу у одного упражнения,
 * каскад по `exerciseId` убирает карту вместе с упражнением. Хранится отдельной таблицей,
 * а не JSON-колонкой: строки читаются целиком одним запросом (`ExerciseMuscleDao.observeAll`),
 * а агрегирует по мышцам уже аналитика в памяти.
 */
@Entity(
    tableName = "exercise_muscles",
    primaryKeys = ["exerciseId", "muscle"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("exerciseId")],
)
data class ExerciseMuscleEntity(
    val exerciseId: Long,
    val muscle: Muscle,
    /** Canonical role encoding: 100 primary, 50 secondary, 0 explicit stabilizer. */
    val contribution: Int,
)
