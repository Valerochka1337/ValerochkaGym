package com.valerochka1337.valerochkagym.ui.analysis.body

import androidx.compose.ui.geometry.Size
import com.valerochka1337.valerochkagym.data.db.entity.Muscle

/** Какую сторону тела показываем. */
enum class BodyView { FRONT, BACK }

/**
 * Связка между 18-мышечной моделью приложения ([Muscle]) и фигурой из
 * react-native-body-highlighter ([BodyPaths]).
 *
 * Рисунок огрубляет анатомию сильнее, чем модель нагрузки: плечо — одна фигура «дельта» на вид,
 * а спина — верх и низ без отдельной широчайшей. Поэтому каждая фигура библиотеки соответствует
 * одной характерной мышце, а те, которым фигуры не хватило ([offFigureMuscles]), остаются
 * доступными в списках-графиках вкладки «Анализы» и в отдельном выпадающем списке редактора
 * упражнения — на карте их просто не подсветить.
 *
 * Спереди виден бицепс, сзади — трицепс; общие для видов мышцы (трапеция, предплечье,
 * приводящие, икры) заданы в обоих отображениях.
 */

/** Мышцы без своей фигуры на карте — их выбирают из списка, а не тапом по телу. */
val offFigureMuscles: List<Muscle> = listOf(
    Muscle.LOWER_CHEST, Muscle.SIDE_DELTS, Muscle.ROTATOR_CUFF, Muscle.SERRATUS_ANTERIOR,
    Muscle.HIP_FLEXORS, Muscle.TIBIALIS_ANTERIOR, Muscle.HIP_ABDUCTORS, Muscle.UPPER_BACK, Muscle.NECK,
)

/** Слаги фигуры → мышца для вида спереди. */
private val FRONT_SLUGS: Map<String, Muscle> = mapOf(
    "chest" to Muscle.UPPER_CHEST,
    "deltoids" to Muscle.FRONT_DELTS,
    "trapezius" to Muscle.TRAPS,
    "biceps" to Muscle.BICEPS,
    "triceps" to Muscle.TRICEPS,
    "forearm" to Muscle.FOREARMS,
    "abs" to Muscle.ABS,
    "obliques" to Muscle.OBLIQUES,
    "quadriceps" to Muscle.QUADS,
    "adductors" to Muscle.ADDUCTORS,
    "calves" to Muscle.CALVES,
)

/** Слаги фигуры → мышца для вида сзади. `upper-back` отдаём широчайшей — она заметнее и её чаще качают. */
private val BACK_SLUGS: Map<String, Muscle> = mapOf(
    "trapezius" to Muscle.TRAPS,
    "deltoids" to Muscle.REAR_DELTS,
    "upper-back" to Muscle.LATS,
    "lower-back" to Muscle.LOWER_BACK,
    "triceps" to Muscle.TRICEPS,
    "forearm" to Muscle.FOREARMS,
    "gluteal" to Muscle.GLUTES,
    "hamstring" to Muscle.HAMSTRINGS,
    "adductors" to Muscle.ADDUCTORS,
    "calves" to Muscle.CALVES,
)

/** Мышцы вида и их SVG-контуры (`d`-строки) — источник заливки и попаданий по карте. */
fun musclePaths(view: BodyView): Map<Muscle, List<String>> {
    val (slugs, source) = when (view) {
        BodyView.FRONT -> FRONT_SLUGS to BodyFrontPaths.muscles
        BodyView.BACK -> BACK_SLUGS to BodyBackPaths.muscles
    }
    return slugs.mapNotNull { (slug, muscle) ->
        source[slug]?.takeIf { it.isNotEmpty() }?.let { muscle to it }
    }.toMap()
}

/** Null means no dedicated geometry, so the current side must not jump. */
fun preferredBodyView(muscle: Muscle): BodyView? = when {
    muscle in FRONT_SLUGS.values -> BodyView.FRONT
    muscle in BACK_SLUGS.values -> BodyView.BACK
    else -> null
}

/**
 * Части рисунка без мышц, достраивающие силуэт. Контур из библиотеки обрывается по линии волос
 * (спереди) и по шее (сзади): череп там — отдельные фигуры `head` и `hair`. Без них голова
 * выглядит срезанной, поэтому они идут в силуэт, а не в мышцы — тапом их не выбрать.
 */
private val SILHOUETTE_EXTRA_SLUGS = listOf("head", "hair")

/** Контуры силуэта вида: сам контур плюс [SILHOUETTE_EXTRA_SLUGS]. Рисуются как одна фигура. */
fun silhouettePaths(view: BodyView): List<String> {
    val (silhouette, source) = when (view) {
        BodyView.FRONT -> BodyFrontPaths.silhouette to BodyFrontPaths.muscles
        BodyView.BACK -> BodyBackPaths.silhouette to BodyBackPaths.muscles
    }
    return listOf(silhouette) + SILHOUETTE_EXTRA_SLUGS.flatMap { source[it].orEmpty() }
}

/** Размер вьюпорта фигуры в координатах SVG. */
fun viewportSize(view: BodyView): Size = when (view) {
    BodyView.FRONT -> Size(BodyFrontPaths.VIEWPORT_W, BodyFrontPaths.VIEWPORT_H)
    BodyView.BACK -> Size(BodyBackPaths.VIEWPORT_W, BodyBackPaths.VIEWPORT_H)
}
