package com.valerochka1337.valerochkagym.ui.analysis.body

import androidx.compose.ui.geometry.Size
import com.valerochka1337.valerochkagym.data.db.entity.Muscle

/** Какую сторону тела показываем. */
enum class BodyView {
  FRONT,
  BACK,
}

/** One SVG sector and all persisted muscles it represents. */
data class MuscleSector(
    val slug: String,
    val members: List<Muscle>,
    val defaultMuscle: Muscle = members.first(),
)

/** Every logical muscle is reachable through an existing front or back sector. */
val offFigureMuscles: List<Muscle> = emptyList()

private val FRONT_SECTORS =
    listOf(
        MuscleSector("chest", listOf(Muscle.UPPER_CHEST, Muscle.LOWER_CHEST)),
        MuscleSector("deltoids", listOf(Muscle.FRONT_DELTS, Muscle.SIDE_DELTS)),
        MuscleSector("trapezius", listOf(Muscle.TRAPS)),
        MuscleSector("biceps", listOf(Muscle.BICEPS)),
        MuscleSector("triceps", listOf(Muscle.TRICEPS)),
        MuscleSector("forearm", listOf(Muscle.FOREARMS)),
        MuscleSector("abs", listOf(Muscle.ABS)),
        MuscleSector("obliques", listOf(Muscle.OBLIQUES, Muscle.SERRATUS_ANTERIOR)),
        MuscleSector("quadriceps", listOf(Muscle.QUADS, Muscle.HIP_FLEXORS)),
        MuscleSector("adductors", listOf(Muscle.ADDUCTORS)),
        MuscleSector("calves", listOf(Muscle.CALVES)),
        MuscleSector("neck", listOf(Muscle.NECK)),
        MuscleSector("tibialis", listOf(Muscle.TIBIALIS_ANTERIOR)),
    )

private val BACK_SECTORS =
    listOf(
        MuscleSector("neck", listOf(Muscle.NECK)),
        MuscleSector("trapezius", listOf(Muscle.TRAPS)),
        MuscleSector("deltoids", listOf(Muscle.REAR_DELTS, Muscle.ROTATOR_CUFF, Muscle.SIDE_DELTS)),
        MuscleSector("upper-back", listOf(Muscle.LATS, Muscle.UPPER_BACK)),
        MuscleSector("lower-back", listOf(Muscle.LOWER_BACK)),
        MuscleSector("triceps", listOf(Muscle.TRICEPS)),
        MuscleSector("forearm", listOf(Muscle.FOREARMS)),
        MuscleSector("gluteal", listOf(Muscle.GLUTES, Muscle.HIP_ABDUCTORS)),
        MuscleSector("hamstring", listOf(Muscle.HAMSTRINGS)),
        MuscleSector("adductors", listOf(Muscle.ADDUCTORS)),
        MuscleSector("calves", listOf(Muscle.CALVES)),
    )

internal fun muscleSectors(view: BodyView): List<MuscleSector> =
    if (view == BodyView.FRONT) FRONT_SECTORS else BACK_SECTORS

internal fun sectorPaths(view: BodyView, sector: MuscleSector): List<String> =
    (if (view == BodyView.FRONT) BodyFrontPaths.muscles else BodyBackPaths.muscles)[sector.slug]
        .orEmpty()

/** Explicit deterministic preferred side for selector/editor restoration. */
fun preferredBodyView(muscle: Muscle): BodyView =
    when (muscle) {
      Muscle.REAR_DELTS,
      Muscle.ROTATOR_CUFF,
      Muscle.HAMSTRINGS,
      Muscle.GLUTES,
      Muscle.HIP_ABDUCTORS,
      Muscle.LOWER_BACK,
      Muscle.LATS,
      Muscle.UPPER_BACK -> BodyView.BACK
      else -> BodyView.FRONT
    }

/** Selected logical member wins a shared-sector tap; otherwise use the frozen default. */
internal fun MuscleSector.memberForTap(selected: Muscle?): Muscle =
    selected?.takeIf { it in members } ?: defaultMuscle

/** Heatmap sectors use the largest member value, never a sum or average. */
internal fun <T> MuscleSector.maxMember(values: Map<Muscle, T>, value: (T) -> Double): T? =
    members.mapNotNull { values[it] }.maxByOrNull(value)

/** Detail/editor sectors use the strongest role, with frozen member order resolving ties. */
internal fun <T> MuscleSector.strongestMember(values: Map<Muscle, T>, strength: (T) -> Int): T? =
    members.mapNotNull { values[it] }.maxByOrNull(strength)

private val SILHOUETTE_EXTRA_SLUGS = listOf("head", "hair")

fun silhouettePaths(view: BodyView): List<String> {
  val (silhouette, source) =
      if (view == BodyView.FRONT) {
        BodyFrontPaths.silhouette to BodyFrontPaths.muscles
      } else {
        BodyBackPaths.silhouette to BodyBackPaths.muscles
      }
  return listOf(silhouette) + SILHOUETTE_EXTRA_SLUGS.flatMap { source[it].orEmpty() }
}

fun viewportSize(view: BodyView): Size =
    if (view == BodyView.FRONT) {
      Size(BodyFrontPaths.VIEWPORT_W, BodyFrontPaths.VIEWPORT_H)
    } else {
      Size(BodyBackPaths.VIEWPORT_W, BodyBackPaths.VIEWPORT_H)
    }
