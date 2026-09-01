package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
@OptIn(ExperimentalMaterial3Api::class)
fun GymTopBar(
    title: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
            )
        },
        modifier = modifier,
        actions = {
            actions()
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = ImageVector.vectorResource(R.drawable.ic_tab_settings),
                contentDescription = "Настройки",
                onClick = onOpenSettings,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}
