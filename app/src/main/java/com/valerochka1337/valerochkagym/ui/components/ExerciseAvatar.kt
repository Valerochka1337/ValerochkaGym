package com.valerochka1337.valerochkagym.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.iconResFor

/**
 * Ведущий аватар упражнения: иконка снаряда/движения (см. [iconResFor]) на скруглённой
 * подложке [surfaceContainerHigh], по стилю совпадает с [CircleIconButton] и иконками табов.
 * Нейтральный тинт, чтобы не размывать единый зелёный акцент.
 */
@Composable
fun ExerciseAvatar(
    exercise: ExerciseEntity,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ExerciseAvatarBox(iconResFor(exercise), modifier, tint)
}

/**
 * Вариант для экранов без полной [ExerciseEntity] (история, итоги, редактор программы):
 * иконка выводится из названия, [type]/[group] нужны только для фоллбэка.
 */
@Composable
fun ExerciseAvatar(
    name: String,
    type: ExerciseType? = null,
    group: MuscleGroup? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ExerciseAvatarBox(iconResFor(name, type, group), modifier, tint)
}

@Composable
private fun ExerciseAvatarBox(
    @DrawableRes iconRes: Int,
    modifier: Modifier,
    tint: Color,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
