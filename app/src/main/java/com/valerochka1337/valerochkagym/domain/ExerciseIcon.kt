package com.valerochka1337.valerochkagym.domain

import androidx.annotation.DrawableRes
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup

/**
 * Иконка упражнения по снаряду/движению. Ресурс выводится из названия (ключевые
 * слова), поэтому не требует поля в БД и работает и для пользовательских упражнений.
 *
 * Порядок правил важен: кардио-тренажёры и «гиря» проверяются раньше общих снарядов,
 * а «блок» — раньше «тренажёра». Для неузнанных названий — фоллбэк по группе/типу.
 */
@DrawableRes
fun iconResFor(exercise: ExerciseEntity): Int =
    iconResFor(exercise.name, exercise.type, exercise.muscleGroup)

/**
 * Вариант для экранов, где под рукой нет полной [ExerciseEntity] (история, итоги,
 * редактор программы). [type]/[group] используются только для фоллбэка неузнанных имён.
 */
@DrawableRes
fun iconResFor(
    name: String,
    type: ExerciseType? = null,
    group: MuscleGroup? = null,
): Int {
    val n = name.lowercase()

    if (type == ExerciseType.CARDIO || group == MuscleGroup.CARDIO) {
        return when {
            n.contains("лестнич") || n.contains("степпер") -> R.drawable.ic_ex_stepper
            n.contains("велотренаж") || n.contains("air bike") -> R.drawable.ic_ex_bike
            n.contains("эллипт") || n.contains("орбитрек") -> R.drawable.ic_ex_elliptical
            n.contains("греб") || n.contains("skierg") -> R.drawable.ic_ex_rower
            n.contains("скакал") || n.contains("плаван") -> R.drawable.ic_ex_bodyweight
            n.contains("дорожк") || n.contains("бег") || n.contains("ходьб") -> R.drawable.ic_ex_treadmill
            else -> R.drawable.ic_ex_treadmill
        }
    }

    // Кардио-тренажёры (до общего «тренажёр»). Осторожно: «Велосипед» — это пресс,
    // а не кардио-байк, поэтому ловим только «велотренаж…».
    when {
        n.contains("беговая") || n.contains("дорожк") -> return R.drawable.ic_ex_treadmill
        n.contains("велотренаж") -> return R.drawable.ic_ex_bike
        n.contains("эллипт") -> return R.drawable.ic_ex_elliptical
        n.contains("гребн") -> return R.drawable.ic_ex_rower
        n.contains("степпер") -> return R.drawable.ic_ex_stepper
        n.contains("тумбу") || n.contains("прыжк") -> return R.drawable.ic_ex_box
    }

    // Снаряд.
    when {
        n.contains("гир") -> return R.drawable.ic_ex_kettlebell
        n.contains("штанг") || n.contains("гриф") ||
            n.contains("становая тяга") || n.contains("румынская тяга") ||
            n.contains("трастер") -> return R.drawable.ic_ex_barbell
        n.contains("гантел") || n.contains("молотк") -> return R.drawable.ic_ex_dumbbell
        n.contains("блок") || n.contains("кроссовер") -> return R.drawable.ic_ex_cable
        n.contains("тренаж") || n.contains("смит") || n.contains("хаммер") ||
            n.contains("гакк") || n.contains("жим ногами") ||
            n.contains("разгибание ног") || n.contains("сгибание ног") ->
            return R.drawable.ic_ex_machine
    }

    // Своё тело и корпус.
    when {
        n.contains("планк") || n.contains("скручиван") ||
            n.contains("подъём ног") || n.contains("подъем ног") ||
            n.contains("велосипед") || n.contains("складка") ||
            n.contains("русские") -> return R.drawable.ic_ex_core
        n.contains("подтягиван") || n.contains("отжиман") || n.contains("брусья") ||
            n.contains("гиперэкстензия") || n.contains("бёрпи") || n.contains("берпи") ||
            n.contains("выпад") || n.contains("турецкий") -> return R.drawable.ic_ex_bodyweight
    }

    // Фоллбэк по группе/типу для неузнанных (в т.ч. пользовательских) упражнений.
    return when {
        type == ExerciseType.CARDIO || group == MuscleGroup.CARDIO -> R.drawable.ic_ex_treadmill
        group == MuscleGroup.CORE -> R.drawable.ic_ex_core
        else -> R.drawable.ic_ex_dumbbell
    }
}
