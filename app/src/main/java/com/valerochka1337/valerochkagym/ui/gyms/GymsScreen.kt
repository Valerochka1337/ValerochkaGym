package com.valerochka1337.valerochkagym.ui.gyms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

/** Pushed-раздел настроек со списком конфигураций тренажёрных залов. */
@Composable
fun GymsScreen(
    onBack: () -> Unit,
    onCreateGym: () -> Unit,
    onEditGym: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GymsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            GymsHeader(
                onBack = onBack,
                onAdd = {
                    haptics.tap()
                    onCreateGym()
                },
            )

            val gyms = state.gyms
            when {
                gyms == null -> Unit
                state.loadError -> FadeInContent(modifier = Modifier.weight(1f)) {
                    GymsMessage(
                        title = "Не удалось загрузить залы",
                        description = "Вернитесь назад и попробуйте открыть раздел снова.",
                        action = "Вернуться",
                        onAction = onBack,
                    )
                }
                gyms.isEmpty() -> FadeInContent(modifier = Modifier.weight(1f)) {
                    GymsMessage(
                        title = "Добавьте первый зал",
                        description = "Отметьте упражнения, которые доступны в этом зале. Потом зал можно будет выбрать для программы.",
                        action = "Создать зал",
                        onAction = {
                            haptics.tap()
                            onCreateGym()
                        },
                    )
                }
                else -> FadeInContent(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 24.dp,
                            end = 24.dp,
                            top = 4.dp,
                            bottom = 32.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(gyms, key = { it.id }) { gym ->
                            GymConfigurationCard(
                                gym = gym,
                                onClick = {
                                    haptics.tap()
                                    onEditGym(gym.id)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GymsHeader(
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Залы",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        CircleIconButton(
            icon = Icons.Rounded.Add,
            contentDescription = "Создать зал",
            onClick = onAdd,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun GymConfigurationCard(
    gym: GymConfiguration,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GymCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.FitnessCenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gym.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${gym.exercises.size} ${exerciseCountWord(gym.exercises.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GymsMessage(
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.FitnessCenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            PillButton(text = action, onClick = onAction)
        }
    }
}

private fun exerciseCountWord(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        lastTwo in 11..14 -> "упражнений"
        last == 1 -> "упражнение"
        last in 2..4 -> "упражнения"
        else -> "упражнений"
    }
}
