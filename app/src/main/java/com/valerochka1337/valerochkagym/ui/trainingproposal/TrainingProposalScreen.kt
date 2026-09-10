package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.trainingproposal.ApprovalDraft
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalAuthor
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedExercise
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedSet
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalSource
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalStatus
import com.valerochka1337.valerochkagym.data.trainingproposal.TrainingProposal
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val localDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

@Composable
fun TrainingProposalInboxContent(
    items: List<TrainingProposal>,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
  val haptics = gymHaptics()
  ProposalContentLayout {
    Text("Предложения тренировок", style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(
          onClick = {
            haptics.tap()
            onBack()
          },
          modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Назад")
      }
      TextButton(
          onClick = {
            haptics.tap()
            onRefresh()
          },
          modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Обновить")
      }
    }

    if (loading) {
      Text(
          "Загружаем предложения…",
          modifier = Modifier.semantics { contentDescription = "Загружаем предложения" },
      )
    }
    if (error != null) {
      Text(error, color = MaterialTheme.colorScheme.error)
      PillButton(
          "Повторить",
          {
            haptics.tap()
            onRefresh()
          },
          modifier = Modifier.fillMaxWidth(),
      )
    }
    if (!loading && error == null && items.isEmpty()) {
      Text("Пока нет предложений.")
    }

    items.forEach { proposal ->
      GymCard(
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            haptics.tap()
            onOpen(proposal.proposalId)
          },
      ) {
        Text(proposal.snapshot.draft.name, style = MaterialTheme.typography.titleMedium)
        Text(inboxDescription(proposal))
        if (isExpired(proposal)) {
          Text("Срок действия истёк", color = MaterialTheme.colorScheme.error)
        }
      }
    }
    if (hasMore) {
      PillButton(
          "Загрузить ещё",
          {
            haptics.tap()
            onMore()
          },
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
fun TrainingProposalDetailContent(
    proposal: TrainingProposal?,
    draft: ApprovalDraft?,
    saving: Boolean,
    applied: Boolean,
    error: String?,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
    onDraftChange: (ApprovalDraft) -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
  val haptics = gymHaptics()
  val invalidInputs =
      remember(proposal?.proposalId, proposal?.currentVersion) {
        mutableStateMapOf<String, Boolean>()
      }
  var validationEpoch by
      remember(proposal?.proposalId, proposal?.currentVersion) { mutableStateOf(0) }
  val transientInputInvalid = invalidInputs.values.any { it }
  ProposalContentLayout {
    Text("Предложение", style = MaterialTheme.typography.headlineSmall)
    TextButton(
        onClick = {
          haptics.tap()
          onBack()
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
      Text("Назад")
    }

    if (error != null) {
      Text(error, color = MaterialTheme.colorScheme.error)
      PillButton(
          "Повторить",
          {
            haptics.tap()
            onRetry()
          },
          modifier = Modifier.fillMaxWidth(),
      )
    }
    if (proposal == null || draft == null) {
      Text(
          if (error == null) "Загружаем предложение…" else "Предложение пока недоступно.",
          modifier = Modifier.semantics { contentDescription = "Загружаем предложение" },
      )
      return@ProposalContentLayout
    }

    Text(detailDescription(proposal))
    if (isExpired(proposal)) {
      Text("Срок действия истёк", color = MaterialTheme.colorScheme.error)
    }
    if (applied) {
      Text("Применено")
    }

    GymCard(modifier = Modifier.fillMaxWidth()) {
      Text("Оригинал", style = MaterialTheme.typography.titleMedium)
      ProposalDraftSummary(proposal.snapshot.draft, exerciseChoices, gymChoices)
    }

    val canEdit = proposal.status == ProposalStatus.PENDING && !applied && !isExpired(proposal)
    if (canEdit) {
      GymCard(modifier = Modifier.fillMaxWidth()) {
        Text("Изменённый план", style = MaterialTheme.typography.titleMedium)
        if (draft != proposal.snapshot.draft) {
          Text("Есть изменения относительно оригинала")
          DraftDifference(original = proposal.snapshot.draft, edited = draft)
        }
        key(proposal.proposalId, proposal.currentVersion) {
          DraftEditor(
              draft = draft,
              exerciseChoices = exerciseChoices,
              gymChoices = gymChoices,
              onDraftChange = onDraftChange,
              invalidInputs = invalidInputs,
              validationEpoch = validationEpoch,
              onExerciseInputsChanged = { validationEpoch++ },
          )
        }
      }
    } else {
      Text("Этот вариант больше нельзя редактировать.")
    }

    when {
      proposal.status == ProposalStatus.APPROVED && !applied -> {
        PillButton(
            "Загрузить результат",
            {
              haptics.confirm()
              onApply()
            },
            enabled = !saving,
            modifier =
                Modifier.fillMaxWidth().semantics { contentDescription = "Загрузить результат" },
        )
      }
      canEdit -> {
        PillButton(
            "Применить",
            {
              haptics.confirm()
              onApply()
            },
            enabled = !saving && !transientInputInvalid && isDraftValid(draft),
            modifier =
                Modifier.fillMaxWidth().semantics { contentDescription = "Применить предложение" },
        )
        OutlinedButton(
            onClick = {
              haptics.reject()
              onReject()
            },
            enabled = !saving,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                  contentDescription = "Отклонить предложение"
                },
        ) {
          Text("Отклонить")
        }
      }
    }
  }
}

@Composable
private fun ProposalContentLayout(content: @Composable () -> Unit) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val horizontalPadding = if (maxWidth >= 840.dp) 24.dp else 16.dp
    Column(
        modifier =
            Modifier.align(Alignment.TopCenter)
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = { content() },
    )
  }
}

@Composable
private fun ProposalDraftSummary(
    draft: ApprovalDraft,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
) {
  Text("Название: ${draft.name.ifBlank { "Не указано" }}")
  Text("Начало: ${formatStart(draft.startsAtMillis, draft.timeZoneId)}")
  val gymNames = draft.gymIds.mapNotNull { id -> gymChoices.nameFor(id) }
  Text("Залы: ${gymNames.ifEmpty { listOf("Не выбраны") }.joinToString()}")
  draft.exercises.forEachIndexed { index, exercise ->
    val exerciseName = exerciseChoices.nameFor(exercise.exerciseId) ?: "Упражнение недоступно"
    Text("${index + 1}. $exerciseName · ${exercise.plannedSets.joinToString { setSummary(it) }}")
  }
}

@Composable
private fun DraftDifference(original: ApprovalDraft, edited: ApprovalDraft) {
  if (original.name != edited.name) Text("Изменено название")
  if (original.gymIds != edited.gymIds) Text("Изменён выбор залов")
  if (
      original.startsAtMillis != edited.startsAtMillis || original.timeZoneId != edited.timeZoneId
  ) {
    Text("Изменено время начала")
  }
  if (original.exercises != edited.exercises) {
    Text("Изменены упражнения или подходы")
  }
}

@Composable
private fun DraftEditor(
    draft: ApprovalDraft,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
    onDraftChange: (ApprovalDraft) -> Unit,
    invalidInputs: MutableMap<String, Boolean>,
    validationEpoch: Int,
    onExerciseInputsChanged: () -> Unit,
) {
  fun setInvalid(key: String, invalid: Boolean) {
    invalidInputs[key] = invalid
  }

  OutlinedTextField(
      value = draft.name,
      onValueChange = { onDraftChange(draft.copy(name = it)) },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Название") },
      isError = draft.name.isBlank(),
      supportingText =
          if (draft.name.isBlank()) {
            { Text("Укажите название") }
          } else null,
  )

  Text("Залы")
  gymChoices.forEach { (id, name) ->
    FilterChip(
        selected = id in draft.gymIds,
        onClick = {
          val gyms = if (id in draft.gymIds) draft.gymIds - id else (draft.gymIds + id).distinct()
          onDraftChange(draft.copy(gymIds = gyms.sorted()))
        },
        label = { Text(name) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
  }
  if (gymChoices.isEmpty()) Text("Доступных залов нет.")

  DateTimeEditor(
      draft = draft,
      onDraftChange = onDraftChange,
      onInvalid = { setInvalid("starts", it) },
  )

  fun clearExerciseInputErrors() {
    invalidInputs.keys.filter { it != "starts" }.forEach(invalidInputs::remove)
    onExerciseInputsChanged()
  }

  draft.exercises.forEachIndexed { exerciseIndex, exercise ->
    ExerciseEditor(
        exerciseIndex = exerciseIndex,
        exercise = exercise,
        allExercises = draft.exercises,
        exerciseChoices = exerciseChoices,
        onChange = { updated -> onDraftChange(draft.copy(exercises = updated)) },
        onInvalid = { key, invalid -> setInvalid(key, invalid) },
        validationEpoch = validationEpoch,
        onSetRemoved = ::clearExerciseInputErrors,
        onExerciseRemoved = ::clearExerciseInputErrors,
    )
  }

  val nextExercise =
      exerciseChoices.firstOrNull { (id, _) -> draft.exercises.none { it.exerciseId == id } }
  if (nextExercise != null) {
    TextButton(
        onClick = {
          onDraftChange(
              draft.copy(
                  exercises =
                      draft.exercises +
                          ProposalPlannedExercise(
                              exerciseId = nextExercise.first,
                              restSeconds = null,
                              plannedSets = listOf(emptyPlannedSet()),
                          ),
              )
          )
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
      Text("Добавить упражнение")
    }
  } else if (exerciseChoices.isNotEmpty()) {
    Text("Все доступные упражнения уже выбраны.")
  }
}

@Composable
private fun DateTimeEditor(
    draft: ApprovalDraft,
    onDraftChange: (ApprovalDraft) -> Unit,
    onInvalid: (Boolean) -> Unit,
) {
  var startText by
      rememberSaveable(draft.startsAtMillis, draft.timeZoneId) {
        mutableStateOf(formatEditableStart(draft.startsAtMillis, draft.timeZoneId))
      }
  var zoneText by rememberSaveable(draft.timeZoneId) { mutableStateOf(draft.timeZoneId) }
  val parsedStart = parseLocalDateTime(startText)
  val zoneInvalid = runCatching { ZoneId.of(zoneText.trim()) }.isFailure
  val parsedZone = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()
  val startInvalid =
      parsedStart == null ||
          (parsedZone != null && parsedZone.rules.getValidOffsets(parsedStart).isEmpty())

  LaunchedEffect(startText, zoneText) { onInvalid(startInvalid || zoneInvalid) }

  fun saveIfValid() {
    val zone = parsedZone
    val dateTime = parsedStart
    if (zone != null && dateTime != null && zone.rules.getValidOffsets(dateTime).isNotEmpty()) {
      onDraftChange(
          draft.copy(
              startsAtMillis = dateTime.atZone(zone).toInstant().toEpochMilli(),
              timeZoneId = zone.id,
          )
      )
    }
  }

  OutlinedTextField(
      value = startText,
      onValueChange = {
        startText = it
        saveIfValid()
      },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Дата и время начала") },
      placeholder = { Text("2026-09-10 18:30") },
      isError = startInvalid,
      supportingText =
          if (startInvalid) {
            { Text("Формат: ГГГГ-ММ-ДД ЧЧ:ММ") }
          } else null,
  )
  OutlinedTextField(
      value = zoneText,
      onValueChange = {
        zoneText = it
        saveIfValid()
      },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Часовой пояс") },
      placeholder = { Text("Europe/Moscow") },
      isError = zoneInvalid,
      supportingText =
          if (zoneInvalid) {
            { Text("Укажите известный часовой пояс") }
          } else {
            null
          },
  )
}

@Composable
private fun ExerciseEditor(
    exerciseIndex: Int,
    exercise: ProposalPlannedExercise,
    allExercises: List<ProposalPlannedExercise>,
    exerciseChoices: List<Pair<String, String>>,
    onChange: (List<ProposalPlannedExercise>) -> Unit,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onSetRemoved: () -> Unit,
    onExerciseRemoved: () -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text("Упражнение ${exerciseIndex + 1}", style = MaterialTheme.typography.titleSmall)
    exerciseChoices.forEach { (id, name) ->
      FilterChip(
          selected = exercise.exerciseId == id,
          onClick = {
            onChange(allExercises.replaceAt(exerciseIndex, exercise.copy(exerciseId = id)))
          },
          label = { Text(name) },
          modifier = Modifier.heightIn(min = 48.dp),
      )
    }
    if (exerciseChoices.isEmpty()) Text("Доступных упражнений нет.")

    IntField(
        label = "Отдых, сек",
        value = exercise.restSeconds,
        key = "rest-$exerciseIndex",
        onInvalid = onInvalid,
        validationEpoch = validationEpoch,
        onChange = { value ->
          onChange(allExercises.replaceAt(exerciseIndex, exercise.copy(restSeconds = value)))
        },
    )
    exercise.plannedSets.forEachIndexed { setIndex, plannedSet ->
      SetEditor(
          exerciseIndex = exerciseIndex,
          setIndex = setIndex,
          plannedSet = plannedSet,
          onInvalid = onInvalid,
          validationEpoch = validationEpoch,
          onChange = { changedSet ->
            val sets = exercise.plannedSets.replaceAt(setIndex, changedSet)
            onChange(allExercises.replaceAt(exerciseIndex, exercise.copy(plannedSets = sets)))
          },
          onRemove = {
            onSetRemoved()
            onChange(
                allExercises.replaceAt(
                    exerciseIndex,
                    exercise.copy(
                        plannedSets =
                            exercise.plannedSets.toMutableList().also { it.removeAt(setIndex) }
                    ),
                )
            )
          },
      )
    }
    TextButton(
        onClick = {
          onChange(
              allExercises.replaceAt(
                  exerciseIndex,
                  exercise.copy(plannedSets = exercise.plannedSets + emptyPlannedSet()),
              )
          )
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
      Text("Добавить подход")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(
          onClick = { onChange(allExercises.move(exerciseIndex, exerciseIndex - 1)) },
          enabled = exerciseIndex > 0,
          modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Выше")
      }
      TextButton(
          onClick = { onChange(allExercises.move(exerciseIndex, exerciseIndex + 1)) },
          enabled = exerciseIndex < allExercises.lastIndex,
          modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Ниже")
      }
      TextButton(
          onClick = {
            onExerciseRemoved()
            onChange(allExercises.toMutableList().also { it.removeAt(exerciseIndex) })
          },
          modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Удалить упражнение")
      }
    }
  }
}

@Composable
private fun SetEditor(
    exerciseIndex: Int,
    setIndex: Int,
    plannedSet: ProposalPlannedSet,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (ProposalPlannedSet) -> Unit,
    onRemove: () -> Unit,
) {
  Text("Подход ${setIndex + 1}", style = MaterialTheme.typography.titleSmall)
  DecimalField(
      label = "Вес подхода ${setIndex + 1}, кг",
      value = plannedSet.weightKg,
      key = "weight-$exerciseIndex-$setIndex",
      onInvalid = onInvalid,
      validationEpoch = validationEpoch,
      onChange = { onChange(plannedSet.copy(weightKg = it)) },
  )
  IntField(
      label = "Повторы подхода ${setIndex + 1}",
      value = plannedSet.reps,
      key = "reps-$exerciseIndex-$setIndex",
      onInvalid = onInvalid,
      validationEpoch = validationEpoch,
      onChange = { onChange(plannedSet.copy(reps = it)) },
  )
  IntField(
      label = "Длительность подхода ${setIndex + 1}, сек",
      value = plannedSet.durationSec,
      key = "duration-$exerciseIndex-$setIndex",
      onInvalid = onInvalid,
      validationEpoch = validationEpoch,
      onChange = { onChange(plannedSet.copy(durationSec = it)) },
  )
  DecimalField(
      label = "Скорость подхода ${setIndex + 1}, км/ч",
      value = plannedSet.speedKmh,
      key = "speed-$exerciseIndex-$setIndex",
      onInvalid = onInvalid,
      validationEpoch = validationEpoch,
      onChange = { onChange(plannedSet.copy(speedKmh = it)) },
  )
  DecimalField(
      label = "Наклон подхода ${setIndex + 1}, %",
      value = plannedSet.inclinePct,
      key = "incline-$exerciseIndex-$setIndex",
      onInvalid = onInvalid,
      validationEpoch = validationEpoch,
      onChange = { onChange(plannedSet.copy(inclinePct = it)) },
  )
  TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
    Text("Удалить подход")
  }
}

@Composable
private fun IntField(
    label: String,
    value: Int?,
    key: String,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (Int?) -> Unit,
) {
  var text by rememberSaveable(key, value) { mutableStateOf(value?.toString().orEmpty()) }
  val invalid = text.isNotBlank() && text.toIntOrNull() == null
  LaunchedEffect(key, text, validationEpoch) { onInvalid(key, invalid) }
  OutlinedTextField(
      value = text,
      onValueChange = {
        text = it
        val parsed = it.toIntOrNull()
        if (it.isBlank() || parsed != null) onChange(parsed)
      },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(label) },
      isError = invalid,
      supportingText =
          if (invalid) {
            { Text("Введите целое число") }
          } else null,
  )
}

@Composable
private fun DecimalField(
    label: String,
    value: Double?,
    key: String,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (Double?) -> Unit,
) {
  var text by rememberSaveable(key, value) { mutableStateOf(value?.toString().orEmpty()) }
  val invalid = text.isNotBlank() && text.replace(',', '.').toDoubleOrNull() == null
  LaunchedEffect(key, text, validationEpoch) { onInvalid(key, invalid) }
  OutlinedTextField(
      value = text,
      onValueChange = {
        text = it
        val parsed = it.replace(',', '.').toDoubleOrNull()
        if (it.isBlank() || parsed != null) onChange(parsed)
      },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(label) },
      isError = invalid,
      supportingText =
          if (invalid) {
            { Text("Введите число") }
          } else null,
  )
}

private fun emptyPlannedSet() = ProposalPlannedSet(null, null, null, null, null)

private fun isDraftValid(draft: ApprovalDraft): Boolean =
    draft.name == draft.name.trim() &&
        draft.name.isNotEmpty() &&
        draft.name.length <= 200 &&
        draft.gymIds.size <= 1_000 &&
        draft.gymIds == draft.gymIds.distinct().sorted() &&
        draft.exercises.size in 1..30 &&
        draft.exercises.map { it.exerciseId }.distinct().size == draft.exercises.size &&
        draft.startsAtMillis > System.currentTimeMillis() &&
        runCatching { ZoneId.of(draft.timeZoneId).id == draft.timeZoneId }.getOrDefault(false) &&
        draft.exercises.all { exercise ->
          exercise.exerciseId.isNotBlank() &&
              (exercise.restSeconds == null || exercise.restSeconds in 0..86_400) &&
              exercise.plannedSets.size in 1..20 &&
              exercise.plannedSets.all(::isPlannedSetValid)
        }

private fun isPlannedSetValid(plannedSet: ProposalPlannedSet): Boolean {
  fun Double?.within(minimum: Double = 0.0): Boolean =
      this == null || (isFinite() && this in minimum..1_000_000.0)

  val validShape =
      if (plannedSet.reps != null) {
        plannedSet.durationSec == null &&
            plannedSet.speedKmh == null &&
            plannedSet.inclinePct == null
      } else {
        plannedSet.durationSec != null && plannedSet.weightKg == null
      }
  return validShape &&
      plannedSet.weightKg.within() &&
      plannedSet.speedKmh.within() &&
      plannedSet.inclinePct.within(-100.0) &&
      (plannedSet.reps == null || plannedSet.reps in 1..1_000_000) &&
      (plannedSet.durationSec == null || plannedSet.durationSec in 1..1_000_000)
}

private fun inboxDescription(proposal: TrainingProposal): String =
    "Источник: ${sourceLabel(proposal.source)} · Автор: ${authorLabel(proposal.author)} · " +
        "версия ${proposal.currentVersion} · ${statusLabel(proposal.status)}"

private fun detailDescription(proposal: TrainingProposal): String =
    "Источник: ${sourceLabel(proposal.source)} · Автор: ${authorLabel(proposal.author)} · " +
        "версия ${proposal.currentVersion} · ${statusLabel(proposal.status)}"

private fun sourceLabel(source: ProposalSource): String =
    if (source == ProposalSource.AI) "AI" else "Тренер"

private fun authorLabel(author: ProposalAuthor): String =
    if (author.kind == ProposalSource.AI) "AI" else "Тренер"

private fun statusLabel(status: ProposalStatus): String =
    when (status) {
      ProposalStatus.PENDING -> "Ожидает решения"
      ProposalStatus.APPROVED -> "Принято"
      ProposalStatus.REJECTED -> "Отклонено"
      ProposalStatus.REVOKED -> "Отозвано"
      ProposalStatus.STALE -> "Устарело"
    }

private fun isExpired(proposal: TrainingProposal): Boolean =
    proposal.expiresAt <= System.currentTimeMillis()

private fun formatStart(startsAtMillis: Long, timeZoneId: String): String {
  val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.of("UTC") }
  return "${Instant.ofEpochMilli(startsAtMillis).atZone(zone).format(localDateTimeFormatter)} ($timeZoneId)"
}

private fun formatEditableStart(startsAtMillis: Long, timeZoneId: String): String {
  val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.of("UTC") }
  return Instant.ofEpochMilli(startsAtMillis).atZone(zone).format(localDateTimeFormatter)
}

private fun parseLocalDateTime(value: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(value.trim(), localDateTimeFormatter) }.getOrNull()

private fun setSummary(plannedSet: ProposalPlannedSet): String =
    buildList {
          plannedSet.weightKg?.let { add("$it кг") }
          plannedSet.reps?.let { add("$it повторов") }
          plannedSet.durationSec?.let { add("$it сек") }
          plannedSet.speedKmh?.let { add("$it км/ч") }
          plannedSet.inclinePct?.let { add("наклон $it%") }
        }
        .ifEmpty { listOf("значения не указаны") }
        .joinToString()

private fun List<Pair<String, String>>.nameFor(id: String): String? =
    firstOrNull { it.first == id }?.second

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun <T> List<T>.move(from: Int, to: Int): List<T> =
    toMutableList().also { values ->
      if (to in values.indices) values.add(to, values.removeAt(from))
    }

/** Shared typed editor for a coach-authored proposal; no recipient apply side effects. */
@Composable
fun ProposalDraftForm(
    draft: ApprovalDraft,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
    onDraftChange: (ApprovalDraft) -> Unit,
    onValidity: (Boolean) -> Unit,
) {
  val invalid = remember { mutableStateMapOf<String, Boolean>() }
  var epoch by remember { mutableStateOf(0) }
  val valid = invalid.values.none { it } && isDraftValid(draft)
  LaunchedEffect(valid) { onValidity(valid) }
  DraftEditor(draft, exerciseChoices, gymChoices, onDraftChange, invalid, epoch, { epoch++ })
}
