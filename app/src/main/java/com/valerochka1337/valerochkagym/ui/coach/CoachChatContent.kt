package com.valerochka1337.valerochkagym.ui.coach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.LocalCoachActionColors

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
  val actionColors = LocalCoachActionColors.current
  val listState = rememberLazyListState()
  LaunchedEffect(state.messages.lastOrNull()?.id) {
    val layout = listState.layoutInfo
    if (
        layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } !=
            false && state.messages.isNotEmpty()
    )
        listState.scrollToItem(state.messages.lastIndex)
  }
  Scaffold(
      modifier = modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
            title = {
              Column {
                Text("Live Coach", modifier = Modifier.semantics { heading() })
                Text(state.workoutName, style = MaterialTheme.typography.bodyMedium)
              }
            },
            navigationIcon = {
              IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            },
        )
      },
      bottomBar = {
        if (!state.readOnly) {
          CoachComposer(
              state = state,
              onDraftChange = onDraftChange,
              onSend = onSend,
              onUndo = onUndo,
              onDisableInitiative = onDisableInitiative,
          )
        }
      },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
          state = listState,
          modifier = Modifier.widthIn(max = 960.dp).fillMaxSize().testTag("coach-conversation"),
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
          val actionResult = message.actionResult()
          if (actionResult != null) {
            AppliedActionMessage(message = message, result = actionResult)
            return@items
          }
          val isUser = message.role == "user"
          Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
          Surface(
              color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
              shape = MaterialTheme.shapes.large,
              modifier = Modifier.fillMaxWidth(0.86f).testTag("coach-message:${message.id}").semantics(mergeDescendants = true) {},
          ) {
            Column(Modifier.padding(16.dp)) {
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
          }
        }
        state.status?.let { status ->
          item(key = "status") {
            GymCard(Modifier.fillMaxWidth().testTag("coach-status").semantics { liveRegion = LiveRegionMode.Polite }) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Column { Text("Тренер отвечает", style = MaterialTheme.typography.titleSmall); Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
              }
            }
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
                    var expanded by remember(action.title, action.kind) { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(action.kind.actionTypeLabel(), style = MaterialTheme.typography.titleSmall)
                        if (expanded) {
                          Text(
                              action.title,
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                          action.details.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                        }
                      }
                      IconButton(
                          onClick = { expanded = !expanded },
                          modifier = Modifier.testTag("coach-proposal-action:$index"),
                      ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Скрыть подробности" else "Показать подробности",
                        )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Button(
                      onClick = {
                        haptics.confirm()
                        onConfirm(proposal.id)
                      },
                      enabled = !state.busy && proposal.preview?.actions?.isEmpty() != true,
                      colors = ButtonDefaults.buttonColors(
                          containerColor = actionColors.acceptedContainer,
                          contentColor = actionColors.onAcceptedContainer,
                      ),
                      modifier = Modifier.weight(1f).testTag("coach-apply"),
                  ) {
                    Text("Принять", maxLines = 1)
                  }
                  Button(
                      onClick = {
                        haptics.tap()
                        onCancel(proposal.id)
                      },
                      enabled = !state.busy,
                      colors = ButtonDefaults.buttonColors(
                          containerColor = actionColors.rejectedContainer,
                          contentColor = actionColors.onRejectedContainer,
                      ),
                      modifier = Modifier.weight(1f).testTag("coach-cancel"),
                  ) {
                    Text("Отклонить")
                  }
                }
              }
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

@Composable
private fun CoachComposer(
    state: CoachChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onUndo: () -> Unit,
    onDisableInitiative: () -> Unit,
) {
  val haptics = gymHaptics()
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  fun submit(text: String) {
    haptics.tap()
    onSend(text)
    focusManager.clearFocus()
    keyboardController?.hide()
  }
  Surface(tonalElevation = 3.dp) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.quickReplies.forEach { phrase ->
          item(key = phrase) {
            SuggestionChip(
                onClick = { submit(phrase) }, label = { Text(phrase, maxLines = 1, softWrap = false) },
                enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("coach-quick-reply:$phrase"))
          }
        }
      }
      Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = state.draft, onValueChange = { if (it.length <= 4000) onDraftChange(it) },
            label = { Text("Сообщение тренеру") }, modifier = Modifier.weight(1f).testTag("coach-input"),
            minLines = 1, maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { submit(state.draft.trim()) },
            enabled = state.draft.isNotBlank() && !state.busy,
            modifier = Modifier.size(56.dp).testTag("coach-send"),
        ) { Icon(Icons.Default.Send, contentDescription = "Отправить сообщение") }
      }
    }
  }
}

private data class CoachActionResult(val accepted: Boolean, val kind: String, val details: String)

private fun CoachChatMessage.actionResult(): CoachActionResult? =
    when {
      role != "system" -> null
      text.startsWith("APPLIED|") -> text.actionResult(true)
      text.startsWith("REJECTED|") -> text.actionResult(false)
      else -> null
    }

@Composable
private fun AppliedActionMessage(message: CoachChatMessage, result: CoachActionResult) {
  var expanded by remember(message.id) { mutableStateOf(false) }
  val actionColors = LocalCoachActionColors.current
  Surface(
      color = if (result.accepted) actionColors.acceptedContainer else actionColors.rejectedContainer,
      contentColor = if (result.accepted) actionColors.onAcceptedContainer else actionColors.onRejectedContainer,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth(0.72f).testTag("coach-action-result:${message.id}"),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(if (result.accepted) Icons.Default.CheckCircle else Icons.Default.Cancel, contentDescription = null)
      Text(
          "${if (result.accepted) "Применено" else "Отклонено"} · ${result.kind.actionTypeLabel()}",
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.weight(1f),
      )
      Icon(
          if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (expanded) "Скрыть подробности" else "Показать подробности",
      )
    }
    if (expanded) Text(result.details, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
  }
}

private fun String.actionResult(accepted: Boolean): CoachActionResult {
  val fields = split('|', limit = 3)
  return if (fields.size == 3) CoachActionResult(accepted, fields[1], fields[2])
  else CoachActionResult(accepted, "change", removePrefix(if (accepted) "APPLIED|" else "REJECTED|"))
}

private fun String.actionTypeLabel(): String =
    when (this) {
      "replace", "swap" -> "Замена"
      "move" -> "Перестановка"
      "delete" -> "Удаление"
      "add" -> "Добавление"
      "rest", "time" -> "Отдых"
      "undo" -> "Отмена"
      else -> "Изменение"
    }
