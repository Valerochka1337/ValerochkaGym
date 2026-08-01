package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.R

/**
 * Общая шапка вкладки: крупный заголовок слева, опциональные действия и круглая кнопка
 * «Настройки» справа. Заголовок и паддинги повторяют прежние инлайновые заголовки экранов.
 */
@Composable
fun GymTopBar(
    title: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        actions()
        CircleIconButton(
            icon = ImageVector.vectorResource(R.drawable.ic_tab_settings),
            contentDescription = "Настройки",
            onClick = onOpenSettings,
        )
    }
}
