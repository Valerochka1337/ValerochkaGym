package com.valerochka1337.valerochkagym.ui.coach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

data class CoachChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val status: String? = null,
    val quickReplies: List<String>? = null,
)

data class CoachChatProposal(
    val id: String,
    val before: String,
    val after: String,
    val preview: WorkoutApprovalPreview? = null,
)

data class CoachChatUiState(
    val workoutName: String = "Тренировка",
    val messages: List<CoachChatMessage> = emptyList(),
    val proposal: CoachChatProposal? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val readOnly: Boolean = false,
    val initiativeEnabled: Boolean = true,
    val canUndo: Boolean = false,
) {
  val quickReplies: List<String>
    get() =
        when {
          readOnly || proposal != null -> emptyList()
          messages.lastOrNull()?.role == "assistant" && messages.last().quickReplies != null ->
              messages.last().quickReplies.orEmpty().take(4)
          messages.none { it.role == "user" || it.quickReplies != null } ->
              listOf("Тренажёр занят", "Слишком тяжело", "Добавь подход")
          else -> emptyList()
        }
}

/** Display-only contract: the service owns requests and the coordinator owns every mutation. */
@Composable
fun CoachChatContent(
    state: CoachChatUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onCancel: (String) -> Unit,
    onUndo: () -> Unit,
    onDisableInitiative: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val haptics = gymHaptics()
  val listState = rememberLazyListState()
  LaunchedEffect(state.messages.lastOrNull()?.id) {
    val layout = listState.layoutInfo
    if (
        layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } !=
            false && state.messages.isNotEmpty()
    )
        listState.scrollToItem(state.messages.lastIndex)
  }
  Box(
      modifier.fillMaxSize().safeDrawingPadding().imePadding(),
      contentAlignment = Alignment.TopCenter,
  ) {
    Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
      Row(
          Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
          Text(
              "Live Coach",
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.semantics { heading() },
          )
          Text(state.workoutName, style = MaterialTheme.typography.bodyMedium)
        }
      }
      LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().testTag("coach-conversation"),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (state.messages.isEmpty())
            item(key = "empty") {
              Text(
                  if (state.readOnly) "В этой тренировке нет сообщений тренера."
                  else "Опишите, что нужно изменить, или выберите быструю фразу."
              )
            }
        items(state.messages, key = { "message:${it.id}" }) { message ->
          GymCard(
              Modifier.fillMaxWidth().testTag("coach-message:${message.id}").semantics(
                  mergeDescendants = true
              ) {}
          ) {
            Text(
                when (message.role) {
                  "user" -> "Вы"
                  "assistant" -> "Тренер"
                  else -> "Действие"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            message.status
                ?.takeIf { it.isNotBlank() }
                ?.let {
                  Spacer(Modifier.height(8.dp))
                  Text(
                      it,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
          }
        }
        state.status?.let { status ->
          item(key = "status") {
            Text(
                status,
                Modifier.testTag("coach-status").semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        state.error?.let { error ->
          item(key = "error") {
            Text(
                error,
                Modifier.testTag("coach-error").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
        if (!state.readOnly) {
          state.proposal?.let { proposal ->
            item(key = "proposal:${proposal.id}") {
              GymCard(Modifier.fillMaxWidth().testTag("coach-proposal")) {
                Text(
                    "Предложение тренера",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                if (proposal.preview != null) {
                  proposal.preview.actions.forEachIndexed { index, action ->
                    if (index > 0) {
                      Spacer(Modifier.height(12.dp))
                      HorizontalDivider()
                      Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                      Icon(
                          when (action.kind) {
                            "replace",
                            "swap",
                            "move" -> Icons.Default.SwapVert
                            "delete" -> Icons.Default.RemoveCircleOutline
                            "add" -> Icons.Default.AddCircleOutline
                            "rest",
                            "time" -> Icons.Default.Timer
                            "undo" -> Icons.Default.History
                            else -> Icons.Default.Edit
                          },
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                      Column(
                          Modifier.weight(1f),
                          verticalArrangement = Arrangement.spacedBy(4.dp),
                      ) {
                        Text(action.title, style = MaterialTheme.typography.titleSmall)
                        action.details.forEach {
                          Text(
                              it,
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      }
                    }
                  }
                } else {
                  Text("Было", style = MaterialTheme.typography.labelLarge)
                  Text(proposal.before)
                  Spacer(Modifier.height(12.dp))
                  Text("Станет", style = MaterialTheme.typography.labelLarge)
                  Text(proposal.after)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Button(
                      onClick = {
                        haptics.confirm()
                        onConfirm(proposal.id)
                      },
                      enabled = !state.busy && proposal.preview?.actions?.isEmpty() != true,
                      modifier = Modifier.testTag("coach-apply"),
                  ) {
                    Text("Применить изменения")
                  }
                  OutlinedButton(
                      onClick = {
                        haptics.tap()
                        onCancel(proposal.id)
                      },
                      enabled = !state.busy,
                      modifier = Modifier.testTag("coach-cancel"),
                  ) {
                    Text("Отклонить")
                  }
                }
              }
            }
          }
          item(key = "input") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                state.quickReplies.forEach { phrase ->
                  SuggestionChip(
                      onClick = {
                        haptics.tap()
                        onSend(phrase)
                      },
                      label = { Text(phrase) },
                      enabled = !state.busy,
                      modifier =
                          Modifier.heightIn(min = 48.dp).testTag("coach-quick-reply:$phrase"),
                  )
                }
              }
              OutlinedTextField(
                  value = state.draft,
                  onValueChange = { if (it.length <= 4000) onDraftChange(it) },
                  label = { Text("Сообщение тренеру") },
                  modifier = Modifier.fillMaxWidth().testTag("coach-input"),
                  minLines = 2,
                  maxLines = 6,
                  supportingText = {
                    Text(
                        "Точные команды выполняются сразу. Подобранные изменения — после подтверждения."
                    )
                  },
              )
              Button(
                  onClick = {
                    haptics.tap()
                    onSend(state.draft.trim())
                  },
                  enabled = state.draft.isNotBlank() && !state.busy,
                  modifier = Modifier.testTag("coach-send"),
              ) {
                Text("Отправить")
              }
              if (state.canUndo)
                  OutlinedButton(
                      onClick = onUndo,
                      enabled = !state.busy,
                      modifier = Modifier.testTag("coach-undo"),
                  ) {
                    Text("Отменить последнее изменение")
                  }
              if (state.initiativeEnabled)
                  TextButton(
                      onClick = onDisableInitiative,
                      modifier = Modifier.testTag("coach-mute"),
                  ) {
                    Text("Не подсказывать до конца тренировки")
                  }
              else
                  Text(
                      "Подсказки отключены до конца тренировки",
                      style = MaterialTheme.typography.bodySmall,
                  )
            }
          }
        } else
            item(key = "read-only") {
              Text(
                  "Тренировка завершена. Диалог доступен для чтения.",
                  Modifier.testTag("coach-read-only"),
                  style = MaterialTheme.typography.bodyMedium,
              )
            }
      }
    }
  }
}
