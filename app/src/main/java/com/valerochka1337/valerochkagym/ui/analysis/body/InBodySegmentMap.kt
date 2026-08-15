package com.valerochka1337.valerochkagym.ui.analysis.body

import android.graphics.RectF
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.ui.analysis.LegendSwatch
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import kotlin.math.min

private const val SEGMENT_BODY_ASPECT = 0.5f
private const val SEGMENT_OUTLINE_STROKE = 3f
private const val SEGMENT_SEPARATION_STROKE = 4.5f

/** Which of the two factual segmental analyses is drawn on the shared body geometry. */
enum class InBodySegmentMapMode {
    LEAN,
    FAT,
}

/** Semantic colour step for a percentage printed in an InBody segment table. */
internal enum class InBodyHeatZone {
    NO_DATA,
    RED,
    AMBER,
    GREEN,
}

internal fun InBodySegmentValues?.heatZoneFor(mode: InBodySegmentMapMode): InBodyHeatZone {
    val percentage = when (mode) {
        InBodySegmentMapMode.LEAN -> this?.leanPercentage
        InBodySegmentMapMode.FAT -> this?.fatPercentage
    }?.takeIf { it.isFinite() && it >= 0.0 } ?: return InBodyHeatZone.NO_DATA

    return when (mode) {
        InBodySegmentMapMode.LEAN -> when {
            percentage < 90.0 -> InBodyHeatZone.RED
            percentage < 100.0 -> InBodyHeatZone.AMBER
            else -> InBodyHeatZone.GREEN
        }

        InBodySegmentMapMode.FAT -> when {
            percentage <= 100.0 -> InBodyHeatZone.GREEN
            percentage <= 160.0 -> InBodyHeatZone.AMBER
            else -> InBodyHeatZone.RED
        }
    }
}

/**
 * Segmental InBody visualisation on top of the same front/back SVG body model used by analyses.
 * A flip changes visual left/right to the matching anatomical side. Colour reflects the percentage
 * printed by InBody against fixed reference steps, never a medical diagnosis.
 */
@Composable
fun InBodySegmentMapFlip(
    values: Map<InBodySegment, InBodySegmentValues>,
    mode: InBodySegmentMapMode,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf(BodyView.FRONT) }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth(0.56f)) {
            Crossfade(
                targetState = view,
                animationSpec = GymMotion.effectsDefault(),
                label = "inbody-segment-view",
            ) { currentView ->
                InBodySegmentMap(
                    values = values,
                    mode = mode,
                    view = currentView,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(
                onClick = { view = if (view == BodyView.FRONT) BodyView.BACK else BodyView.FRONT },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cached,
                    contentDescription = "Повернуть фигуру",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (view == BodyView.FRONT) "Спереди" else "Сзади",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        InBodyHeatLegend(mode)
    }
}

@Composable
private fun InBodyHeatLegend(mode: InBodySegmentMapMode) {
    val entries = when (mode) {
        InBodySegmentMapMode.LEAN -> listOf(
            InBodyHeatZone.NO_DATA to "нет %",
            InBodyHeatZone.RED to "<90%",
            InBodyHeatZone.AMBER to "90–99%",
            InBodyHeatZone.GREEN to "≥100%",
        )

        InBodySegmentMapMode.FAT -> listOf(
            InBodyHeatZone.NO_DATA to "нет %",
            InBodyHeatZone.GREEN to "≤100%",
            InBodyHeatZone.AMBER to "101–160%",
            InBodyHeatZone.RED to ">160%",
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { (zone, label) ->
            LegendSwatch(color = zone.color, label = label)
        }
    }
}

@Composable
private fun InBodySegmentMap(
    values: Map<InBodySegment, InBodySegmentValues>,
    mode: InBodySegmentMapMode,
    view: BodyView,
    modifier: Modifier,
) {
    val parsed = remember(view) { SegmentalParsedBody.of(view) }
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = modifier
            .aspectRatio(SEGMENT_BODY_ASPECT)
            .semantics {
                contentDescription = "Сегментная карта тела, ${if (view == BodyView.FRONT) "спереди" else "сзади"}, ${mode.displayName.lowercase()}"
            },
    ) {
        val scale = min(size.width / parsed.viewportW, size.height / parsed.viewportH)
        val dx = (size.width - parsed.viewportW * scale) / 2f
        val dy = (size.height - parsed.viewportH * scale) / 2f
        translate(dx, dy) {
            scale(scale, scale, pivot = Offset.Zero) {
                drawPath(parsed.silhouette, color = body)
                drawPath(parsed.silhouette, color = outline.copy(alpha = 0.45f), style = Stroke(SEGMENT_OUTLINE_STROKE))
                parsed.paths.forEach { shape ->
                    val zone = values[shape.segment].heatZoneFor(mode)
                    drawPath(shape.path, color = zone.color)
                    drawPath(
                        shape.path,
                        color = body,
                        style = Stroke(SEGMENT_SEPARATION_STROKE, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

private val InBodyHeatZone.color
    get() = when (this) {
        InBodyHeatZone.NO_DATA -> ChartPalette.Empty
        InBodyHeatZone.RED -> ChartPalette.HeatRed
        InBodyHeatZone.AMBER -> ChartPalette.HeatAmber
        InBodyHeatZone.GREEN -> ChartPalette.HeatGreen
    }

private data class SegmentPath(val segment: InBodySegment, val path: Path)

private class SegmentalParsedBody(
    val silhouette: Path,
    val paths: List<SegmentPath>,
    val viewportW: Float,
    val viewportH: Float,
) {
    companion object {
        private val cache = mutableMapOf<BodyView, SegmentalParsedBody>()

        fun of(view: BodyView): SegmentalParsedBody = cache.getOrPut(view) {
            val body = ParsedBody.of(view)
            SegmentalParsedBody(
                silhouette = body.silhouette,
                paths = body.muscles.flatMap { muscleShape ->
                    muscleShape.paths.mapNotNull { path ->
                        segmentFor(muscleShape.muscle, path, view, body.viewportW)?.let { segment ->
                            SegmentPath(segment = segment, path = path)
                        }
                    }
                },
                viewportW = body.viewportW,
                viewportH = body.viewportH,
            )
        }

        private fun segmentFor(
            muscle: Muscle,
            path: Path,
            view: BodyView,
            viewportW: Float,
        ): InBodySegment? = when (muscle) {
            Muscle.FRONT_DELTS,
            Muscle.SIDE_DELTS,
            Muscle.REAR_DELTS,
            Muscle.BICEPS,
            Muscle.TRICEPS,
            Muscle.FOREARMS,
            -> armFor(path, view, viewportW)

            Muscle.GLUTES,
            Muscle.QUADS,
            Muscle.HAMSTRINGS,
            Muscle.ADDUCTORS,
            Muscle.CALVES,
            -> legFor(path, view, viewportW)

            else -> InBodySegment.TRUNK
        }

        /** Visual left is anatomical right from the front and anatomical left from the back. */
        private fun armFor(path: Path, view: BodyView, viewportW: Float): InBodySegment =
            if (isAnatomicalLeft(path, view, viewportW)) InBodySegment.LEFT_ARM else InBodySegment.RIGHT_ARM

        private fun legFor(path: Path, view: BodyView, viewportW: Float): InBodySegment =
            if (isAnatomicalLeft(path, view, viewportW)) InBodySegment.LEFT_LEG else InBodySegment.RIGHT_LEG

        private fun isAnatomicalLeft(path: Path, view: BodyView, viewportW: Float): Boolean {
            val bounds = RectF()
            path.asAndroidPath().computeBounds(bounds, true)
            val visualLeft = bounds.centerX() < viewportW / 2f
            return if (view == BodyView.FRONT) !visualLeft else visualLeft
        }
    }
}

private val InBodySegmentMapMode.displayName: String
    get() = if (this == InBodySegmentMapMode.LEAN) "Мышцы" else "Жир"
