package com.valerochka1337.valerochkagym.ui.coachrelation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.coachrelation.*
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.trainingproposal.ProposalDraftForm
import java.time.Instant
import java.time.ZoneId

@Composable
fun CoachRelationsScreen(
    onBack: () -> Unit,
    onOpen: (String, Boolean) -> Unit,
    viewModel: CoachRelationsViewModel = hiltViewModel(),
) {
  val s by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { viewModel.refresh() }
  var token by remember { mutableStateOf("") }
  var calendar by remember { mutableStateOf(false) }
  var completed by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  // Secrets and grants intentionally are not rememberSaveable; account changes clear input too.
  LaunchedEffect(s.error) {
    if (s.error?.startsWith("Аккаунт изменился") == true) {
      token = ""
      calendar = false
      completed = false
    }
  }
  GlowBackground {
    RelationLayout {
      Text("Связи с тренером", style = MaterialTheme.typography.headlineSmall)
      RelationAction("Назад", onBack)
      RelationAction("Мои подопечные", { viewModel.refresh(true) }, !s.busy)
      RelationAction("Мои тренеры", { viewModel.refresh(false) }, !s.busy)
      Status(s)
      RelationAction("Обновить", { viewModel.refresh() }, !s.busy)
      if (s.relations.isEmpty() && !s.busy) Text("Связей пока нет")
      s.relations.forEach { r ->
        RelationAction(
            "Участник ${r.counterpartyId} · ${if(r.state=="ACTIVE")"Активна"else"Отозвана"}",
            { onOpen(r.relationId, s.clients) },
            !s.busy,
        )
      }
      Text("Пригласить подопечного", style = MaterialTheme.typography.titleMedium)
      RelationAction("Создать приглашение", { viewModel.createInvite() }, !s.busy)
      s.invite?.let { invite ->
        Text("Одноразовый код: ${invite.token}")
        Text(
            "Действует до ${Instant.ofEpochMilli(invite.expiresAtMillis).atZone(ZoneId.systemDefault())}"
        )
        RelationAction("Скопировать код", { clipboard.setText(AnnotatedString(invite.token)) })
        RelationAction("Скрыть код", { viewModel.clearInvite() })
      }
      Text("Принять приглашение тренера", style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(
          token,
          { token = it },
          label = { Text("Код приглашения") },
          modifier = Modifier.fillMaxWidth(),
          enabled = !s.busy,
      )
      RelationGrant("Разрешить просмотр календаря", calendar, { calendar = it }, !s.busy)
      RelationGrant(
          "Разрешить просмотр завершённых тренировок",
          completed,
          { completed = it },
          !s.busy,
      )
      Text("Каждое разрешение независимо. Профиль, заметки и здоровье не передаются.")
      RelationAction(
          "Принять с выбранными разрешениями",
          {
            val value = token
            token = ""
            viewModel.accept(value, calendar, completed)
          },
          !s.busy && Regex("[A-Za-z0-9_-]{43}").matches(token),
      )
      Pending(s, viewModel::retry)
    }
  }
}

@Composable
fun CoachRelationDetailScreen(
    id: String,
    clients: Boolean,
    onBack: () -> Unit,
    viewModel: CoachRelationsViewModel = hiltViewModel(),
) {
  val s by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(id, clients) { viewModel.refresh(clients, id) }
  GlowBackground {
    RelationLayout {
      Text("Связь с участником", style = MaterialTheme.typography.headlineSmall)
      RelationAction("Назад", onBack)
      Status(s)
      RelationAction("Обновить доступ", { viewModel.refresh(clients, id) }, !s.busy)
      val r = s.relation
      if (r == null) {
        if (!s.busy) Text("Связь недоступна")
        return@RelationLayout
      }
      Text("Участник ${r.counterpartyId}")
      Text(if (r.state == "ACTIVE") "Активная связь" else "Связь отозвана")
      Text("Календарь: ${if(r.calendar)"разрешён"else"закрыт"}")
      Text("Завершённые тренировки: ${if(r.completedWorkouts)"разрешены"else"закрыты"}")
      if (r.state == "ACTIVE") {
        RelationAction("Отозвать связь", viewModel::revoke, !s.busy)
        if (clients) {
          if (r.calendar) {
            RelationAction("Показать календарь", { viewModel.projection(true) }, !s.busy)
            s.calendar.forEach { item ->
              Text(item.title, style = MaterialTheme.typography.titleMedium)
              Text(
                  Instant.ofEpochMilli(item.startsAtMillis)
                      .atZone(ZoneId.of(item.timeZoneId))
                      .toString()
              )
              item.exercises.forEach { Text("${it.name}: ${it.plannedSetCount} подходов") }
            }
            if (s.calendarCursor != null)
                RelationAction("Ещё планы", { viewModel.projection(true, true) }, !s.busy)
          }
          if (r.completedWorkouts) {
            RelationAction(
                "Показать завершённые тренировки",
                { viewModel.projection(false) },
                !s.busy,
            )
            s.completed.forEach { item ->
              Text(
                  Instant.ofEpochMilli(item.finishedAtMillis)
                      .atZone(ZoneId.systemDefault())
                      .toString(),
                  style = MaterialTheme.typography.titleMedium,
              )
              item.exercises.forEach { e ->
                Text(e.name)
                e.sets.forEachIndexed { index, set ->
                  Text("${index+1}. ${projectionSetText(set)}")
                }
              }
            }
            if (s.completedCursor != null)
                RelationAction("Ещё тренировки", { viewModel.projection(false, true) }, !s.busy)
          }
          if (s.recipientRevision != null && s.calendar.isEmpty() && s.completed.isEmpty())
              Text("В выбранном разделе пока нет тренировок")
          RelationAction(
              "Подготовить предложение",
              viewModel::newDraft,
              !s.busy && (r.calendar || r.completedWorkouts),
          )
          if (!r.calendar && !r.completedWorkouts)
              Text("Для подготовки предложения участник должен открыть календарь или историю.")
          s.proposals.forEach { p ->
            Text("${p.snapshot.draft.name} · версия ${p.currentVersion} · ${p.status}")
            if (p.status == ProposalStatus.PENDING) {
              RelationAction("Изменить предложение", { viewModel.edit(p) }, !s.busy)
              RelationAction("Отозвать предложение", { viewModel.revokeProposal(p) }, !s.busy)
            }
          }
          s.draft?.let { draft ->
            var valid by remember(s.editing?.proposalId) { mutableStateOf(false) }
            key(s.editing?.proposalId) {
              ProposalDraftForm(
                  draft,
                  s.exercises,
                  emptyList(),
                  viewModel::updateDraft,
                  { valid = it },
              )
            }
            RelationAction(
                if (s.editing == null) "Отправить предложение" else "Сохранить новую версию",
                viewModel::submit,
                !s.busy && valid,
            )
            RelationAction("Закрыть черновик", viewModel::closeDraft, !s.busy)
          }
        }
      }
      Pending(s, viewModel::retry)
    }
  }
}

internal fun projectionSetText(s: ProjectionSet): String =
    listOfNotNull(
            s.weightKg?.let { "$it кг" },
            s.reps?.let { "$it повторений" },
            s.durationSec?.let { "$it с" },
            s.speedKmh?.let { "$it км/ч" },
            s.inclinePct?.let { "наклон $it %" },
        )
        .joinToString(" · ")
        .ifEmpty { "Значения не указаны" }

@Composable
private fun Pending(s: CoachRelationsState, retry: (String) -> Unit) {
  s.operations
      .filter { it.state == "PENDING" && it.firstSendBytes != null }
      .forEach { op ->
        Text("Операция ожидает подтверждения")
        RelationAction(
            "Повторить: ${when(op.action) { "REVOKE_RELATION" -> "отзыв связи"
 "CREATE_COACH_PROPOSAL" -> "предложение"
 "REVISE_COACH_PROPOSAL" -> "изменение предложения"
 else -> "отзыв предложения" }}",
            { retry(op.operationId) },
            !s.busy,
        )
      }
}

@Composable
private fun Status(s: CoachRelationsState) {
  if (s.busy) Text("Загружаем…")
  s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun RelationLayout(content: @Composable ColumnScope.() -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 840.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(if (maxWidth < 600.dp) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
  }
}

@Composable
internal fun RelationAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
  val h = gymHaptics()
  OutlinedButton(
      onClick = {
        h.tap()
        onClick()
      },
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
  ) {
    Text(text)
  }
}

@Composable
internal fun RelationGrant(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
  val h = gymHaptics()
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 48.dp)
          .toggleable(
              value = checked,
              enabled = enabled,
              role = Role.Checkbox,
              onValueChange = {
                h.tap()
                onChange(it)
              },
          ),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
        checked,
        null,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    )
    Text(text, Modifier.weight(1f))
  }
}
