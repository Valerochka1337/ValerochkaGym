package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/**
 * Canonical role maps for every built-in exercise. Keys are normalized display names for
 * compatibility with imported legacy rows; values use only `100` primary, `50` secondary,
 * and `0` stabilizer roles.
 *
 * This compatibility view intentionally derives from [CanonicalExerciseRegistry], which is the
 * sole reviewed catalog authority. Custom exercises still fall back by group when they do not
 * match a built-in name.
 */
val seedExerciseMuscles: Map<String, List<MuscleLoad>> =
    CanonicalExerciseRegistry.entries.associate { entry ->
        entry.exercise.name.trim().lowercase() to entry.loads
    }
