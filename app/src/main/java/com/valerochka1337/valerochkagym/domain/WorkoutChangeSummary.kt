package com.valerochka1337.valerochkagym.domain

/** Approval text is derived from host-resolved operations, never from a model's explanation. */
object WorkoutChangeSummary {
  data class Summary(val before: String, val after: String)

  fun describe(
      snapshot: WorkoutSnapshot,
      packet: WorkoutChangeSet.Packet,
      exerciseNames: Map<Long, String> = emptyMap(),
      equipmentNames: Map<String, String> = emptyMap(),
      undoPreview: Summary? = null,
      weightOptionalExerciseIds: Set<Long> = emptySet(),
  ): Summary {
    var state = snapshot
    require(
        packet.operations.count {
          it is WorkoutChangeSet.Operation.Rest && it.action != RestAction.FUTURE_DURATION
        } <= 1
    )
    require(
        packet.operations.none { it is WorkoutChangeSet.Operation.UndoLast } ||
            packet.operations.size == 1
    )
    fun section(id: String) =
        requireNotNull(state.exercises.singleOrNull { it.sectionId == id }) { "Unknown section" }
    fun exerciseName(id: Long) =
        exerciseNames[id]
            ?: state.exercises.firstOrNull { it.exerciseId == id }?.name
            ?: error("Unknown exercise")
    fun set(id: String): Pair<SnapshotExercise, SnapshotSet> {
      val exercise =
          requireNotNull(state.exercises.singleOrNull { e -> e.sets.any { it.syncId == id } }) {
            "Unknown set"
          }
      return exercise to exercise.sets.single { it.syncId == id }
    }
    fun row(exercise: SnapshotExercise, set: SnapshotSet) =
        "${exercise.name}, подход ${set.setIndex + 1}: ${load(set)}; ${if (set.completed) "выполнен" else "не выполнен"}"
    fun order(ids: List<String>) =
        ids.mapIndexed { index, id -> "${index + 1}. ${section(id).name}" }.joinToString("\n")
    fun equipment(ids: Set<String>) =
        ids.sorted().joinToString { equipmentNames[it] ?: it }.ifEmpty { "нет" }
    val parts =
        packet.operations.mapIndexed { step, operation ->
          val summary =
              when (operation) {
                is WorkoutChangeSet.Operation.EditSet -> {
                  val (exercise, old) = set(operation.setSyncId)
                  val updated =
                      old.copy(
                          weightKg =
                              if ("weight_kg" in operation.clearFields) null
                              else operation.weightKg ?: old.weightKg,
                          reps =
                              if ("reps" in operation.clearFields) null
                              else operation.reps ?: old.reps,
                          durationSec =
                              if ("duration_sec" in operation.clearFields) null
                              else operation.durationSec ?: old.durationSec,
                          speedKmh =
                              if ("speed_kmh" in operation.clearFields) null
                              else operation.speedKmh ?: old.speedKmh,
                          inclinePct =
                              if ("incline_pct" in operation.clearFields) null
                              else operation.inclinePct ?: old.inclinePct,
                          completed =
                              if (operation.recordResult) true
                              else operation.completed ?: old.completed,
                      )
                  Summary(row(exercise, old), row(exercise, updated))
                }
                is WorkoutChangeSet.Operation.AddSet -> {
                  val exercise = section(operation.sectionId)
                  Summary(
                      "${exercise.name}: ${exercise.sets.size} подходов",
                      "${exercise.name}: добавить один подход" +
                          (exercise.sets.lastOrNull()?.let { " (${load(it)})" }
                              ?: "; значения пока не заданы"),
                  )
                }
                is WorkoutChangeSet.Operation.DeleteSet -> {
                  val (exercise, target) = set(operation.setSyncId)
                  require(!target.completed) { "Completed result cannot be deleted" }
                  Summary(
                      row(exercise, target),
                      "${exercise.name}, подход ${target.setIndex + 1}: удалить незавершённый подход",
                  )
                }
                is WorkoutChangeSet.Operation.AddExercise ->
                    Summary(
                        "${exerciseName(operation.exerciseId)}: новой секции нет",
                        "Добавить «${exerciseName(operation.exerciseId)}» в конец тренировки с одним незавершённым подходом без заданной нагрузки",
                    )
                is WorkoutChangeSet.Operation.DeleteExercise -> {
                  val exercise = section(operation.sectionId)
                  require(exercise.sets.none { it.completed }) {
                    "Completed exercise cannot be deleted"
                  }
                  Summary(
                      "${exercise.name}: ${exercise.sets.size} подходов",
                      "Убрать секцию «${exercise.name}»",
                  )
                }
                is WorkoutChangeSet.Operation.RemoveRemaining -> {
                  val exercise = section(operation.sectionId)
                  Summary(
                      "${exercise.name}: ${exercise.sets.count { !it.completed }} незавершённых подходов",
                      "Убрать незавершённые подходы «${exercise.name}»; сохранить ${exercise.sets.count { it.completed }} выполненных",
                  )
                }
                is WorkoutChangeSet.Operation.ReplaceRemaining -> {
                  val exercise = section(operation.sourceSectionId)
                  require(
                      operation.unfinishedSetSyncIds.isNotEmpty() &&
                          operation.unfinishedSetSyncIds.distinct().size ==
                              operation.unfinishedSetSyncIds.size
                  )
                  require(
                      operation.unfinishedSetSyncIds.toSet() ==
                          exercise.sets.filterNot { it.completed }.map { it.syncId }.toSet()
                  ) {
                    "Remaining set anchors changed"
                  }
                  require(state.exercises.none { it.sectionId == operation.destinationSectionId }) {
                    "Destination section already exists"
                  }
                  val targets = exercise.sets.filterNot { it.completed }.sortedBy { it.setIndex }
                  val weight = operation.replacementWeightKg
                  require(
                      weight != null || operation.replacementExerciseId in weightOptionalExerciseIds
                  ) {
                    "Replacement weight must be resolved before approval"
                  }
                  Summary(
                      targets.joinToString("\n") { row(exercise, it) },
                      "Заменить оставшиеся ${targets.size} подходов на «${exerciseName(operation.replacementExerciseId)}»:\n" +
                          targets
                              .mapIndexed { index, target ->
                                "${index + 1}. ${load(target.copy(weightKg = weight))}"
                              }
                              .joinToString("\n") +
                          "\nСохранить ${exercise.sets.count { it.completed }} выполненных подходов «${exercise.name}».",
                  )
                }
                is WorkoutChangeSet.Operation.MoveExercise -> {
                  val before = state.exercises.sortedBy { it.position }.map { it.sectionId }
                  val after =
                      before.toMutableList().apply {
                        require(remove(operation.sectionId))
                        add(operation.targetPosition.coerceIn(0, size), operation.sectionId)
                      }
                  Summary(order(before), order(after))
                }
                is WorkoutChangeSet.Operation.ReorderExercises -> {
                  require(
                      operation.sectionIds.size == state.exercises.size &&
                          operation.sectionIds.toSet() ==
                              state.exercises.map { it.sectionId }.toSet()
                  )
                  Summary(
                      order(state.exercises.sortedBy { it.position }.map { it.sectionId }),
                      order(operation.sectionIds),
                  )
                }
                is WorkoutChangeSet.Operation.Rest -> {
                  val before =
                      if (operation.action == RestAction.FUTURE_DURATION)
                          "Следующие паузы: по текущим настройкам тренировки"
                      else
                          state.rest?.remainingSeconds?.let { "Осталось отдыха: $it с" }
                              ?: "Активный отдых не известен"
                  Summary(
                      before,
                      when (operation.action) {
                        RestAction.START ->
                            "Запустить отдых на ${requireNotNull(operation.seconds)} с"
                        RestAction.EXTEND ->
                            "Добавить ${requireNotNull(operation.seconds)} с к текущему отдыху"
                        RestAction.SKIP -> "Завершить текущий отдых"
                        RestAction.FUTURE_DURATION ->
                            "Установить следующие паузы этой тренировки: ${requireNotNull(operation.seconds)} с"
                      },
                  )
                }
                is WorkoutChangeSet.Operation.SetAvailableTime ->
                    Summary(
                        state.availableTimeMinutes?.let { "Доступное время: $it мин" }
                            ?: "Доступное время не задано",
                        operation.minutes?.let { "Оставшееся доступное время: $it мин" }
                            ?: "Убрать ограничение времени",
                    )
                is WorkoutChangeSet.Operation.SetOccupiedEquipment ->
                    Summary(
                        "Занятое оборудование: ${equipment(state.occupiedEquipment)}",
                        "Занятое оборудование: ${equipment(operation.equipmentIds)}",
                    )
                is WorkoutChangeSet.Operation.SetExcludedExercises ->
                    Summary(
                        "Исключены: ${state.excludedExerciseIds.map(::exerciseName).joinToString().ifEmpty { "нет" }}",
                        "Исключить до конца тренировки: ${operation.exerciseIds.map(::exerciseName).joinToString().ifEmpty { "нет" }}",
                    )
                is WorkoutChangeSet.Operation.ReportFeelings -> {
                  val (exercise, target) = set(operation.setSyncId)
                  Summary(
                      "${exercise.name}, подход ${target.setIndex + 1}: ${feelings(target.reportedFeelings)}",
                      "${exercise.name}, подход ${target.setIndex + 1}: ${feelings(operation.feelings)}",
                  )
                }
                WorkoutChangeSet.Operation.UndoLast ->
                    requireNotNull(undoPreview) { "Undo requires a saved inverse preview" }
                is WorkoutChangeSet.Operation.RestoreWorkout ->
                    Summary(
                        state.exercises
                            .sortedBy { it.position }
                            .joinToString("\n") { e -> e.sets.joinToString("\n") { row(e, it) } },
                        operation.sections
                            .sortedBy { it.position }
                            .joinToString("\n") { e ->
                              e.sets.joinToString("\n") { target ->
                                "${exerciseName(e.exerciseId)}, подход ${target.setIndex + 1}: " +
                                    load(
                                        SnapshotSet(
                                            syncId = target.syncId,
                                            setIndex = target.setIndex,
                                            completed = target.isCompleted,
                                            weightKg = target.weightKg,
                                            reps = target.reps,
                                            durationSec = target.durationSec,
                                            completedAt = target.completedAt,
                                            speedKmh = target.speedKmh,
                                            inclinePct = target.inclinePct,
                                        )
                                    ) +
                                    "; ${if (target.isCompleted) "выполнен" else "не выполнен"}"
                              }
                            },
                    )
              }
          state = advance(state, operation, step, ::exerciseName)
          if (packet.operations.size == 1) summary
          else Summary("Шаг ${step + 1}:\n${summary.before}", "Шаг ${step + 1}:\n${summary.after}")
        }
    return Summary(
            parts.joinToString("\n\n") { it.before },
            parts.joinToString("\n\n") { it.after },
        )
        .also {
          require(it.before.length + it.after.length <= 40000) { "Approval preview too large" }
        }
  }

  /** Display-only projection; synthetic IDs never leave this formatter or authorize a command. */
  private fun advance(
      state: WorkoutSnapshot,
      op: WorkoutChangeSet.Operation,
      step: Int,
      name: (Long) -> String,
  ): WorkoutSnapshot {
    fun updateSet(id: String, transform: (SnapshotSet) -> SnapshotSet) =
        state.copy(
            exercises =
                state.exercises.map { e ->
                  e.copy(sets = e.sets.map { if (it.syncId == id) transform(it) else it })
                }
        )
    fun ordered(ids: List<String>) =
        state.copy(
            exercises =
                ids.mapIndexed { position, id ->
                  state.exercises.single { it.sectionId == id }.copy(position = position)
                }
        )
    return when (op) {
      is WorkoutChangeSet.Operation.EditSet ->
          updateSet(op.setSyncId) { old ->
            old.copy(
                weightKg = if ("weight_kg" in op.clearFields) null else op.weightKg ?: old.weightKg,
                reps = if ("reps" in op.clearFields) null else op.reps ?: old.reps,
                durationSec =
                    if ("duration_sec" in op.clearFields) null
                    else op.durationSec ?: old.durationSec,
                speedKmh = if ("speed_kmh" in op.clearFields) null else op.speedKmh ?: old.speedKmh,
                inclinePct =
                    if ("incline_pct" in op.clearFields) null else op.inclinePct ?: old.inclinePct,
                completed = if (op.recordResult) true else op.completed ?: old.completed,
            )
          }
      is WorkoutChangeSet.Operation.AddSet ->
          state.copy(
              exercises =
                  state.exercises.map { e ->
                    if (e.sectionId != op.sectionId) e
                    else {
                      val last = e.sets.lastOrNull()
                      val added =
                          last?.copy(
                              syncId = "preview-set-$step",
                              setIndex = (e.sets.maxOfOrNull { it.setIndex } ?: -1) + 1,
                              completed = false,
                              completedAt = null,
                              reportedFeelings = emptySet(),
                          ) ?: SnapshotSet("preview-set-$step", 0, false, null, null, null)
                      e.copy(sets = e.sets + added)
                    }
                  }
          )
      is WorkoutChangeSet.Operation.DeleteSet ->
          state.copy(
              exercises =
                  state.exercises.map {
                    it.copy(sets = it.sets.filterNot { set -> set.syncId == op.setSyncId })
                  }
          )
      is WorkoutChangeSet.Operation.MoveExercise ->
          ordered(
              state.exercises
                  .sortedBy { it.position }
                  .map { it.sectionId }
                  .toMutableList()
                  .apply {
                    remove(op.sectionId)
                    add(op.targetPosition.coerceIn(0, size), op.sectionId)
                  }
          )
      is WorkoutChangeSet.Operation.ReorderExercises -> ordered(op.sectionIds)
      is WorkoutChangeSet.Operation.AddExercise ->
          state.copy(
              exercises =
                  state.exercises +
                      SnapshotExercise(
                          "preview-section-$step",
                          op.exerciseId,
                          name = name(op.exerciseId),
                          position = (state.exercises.maxOfOrNull { it.position } ?: -1) + 1,
                          sets =
                              listOf(SnapshotSet("preview-set-$step", 0, false, null, null, null)),
                      )
          )
      is WorkoutChangeSet.Operation.DeleteExercise ->
          state.copy(
              exercises =
                  state.exercises
                      .filterNot { it.sectionId == op.sectionId }
                      .sortedBy { it.position }
                      .mapIndexed { index, e -> e.copy(position = index) }
          )
      is WorkoutChangeSet.Operation.RemoveRemaining ->
          state.copy(
              exercises =
                  state.exercises.map {
                    if (it.sectionId == op.sectionId)
                        it.copy(sets = it.sets.filter { set -> set.completed })
                    else it
                  }
          )
      is WorkoutChangeSet.Operation.ReplaceRemaining -> {
        val source = state.exercises.single { it.sectionId == op.sourceSectionId }
        val destination =
            SnapshotExercise(
                op.destinationSectionId,
                op.replacementExerciseId,
                name = name(op.replacementExerciseId),
                position = source.position + 1,
                sets =
                    source.sets
                        .filterNot { it.completed }
                        .sortedBy { it.setIndex }
                        .mapIndexed { index, set ->
                          set.copy(setIndex = index, weightKg = op.replacementWeightKg)
                        },
            )
        state.copy(
            exercises =
                state.exercises.map {
                  when {
                    it.sectionId == source.sectionId ->
                        it.copy(sets = it.sets.filter { set -> set.completed })
                    it.position > source.position -> it.copy(position = it.position + 1)
                    else -> it
                  }
                } + destination
        )
      }
      is WorkoutChangeSet.Operation.SetAvailableTime ->
          state.copy(availableTimeMinutes = op.minutes)
      is WorkoutChangeSet.Operation.SetOccupiedEquipment ->
          state.copy(occupiedEquipment = op.equipmentIds)
      is WorkoutChangeSet.Operation.SetExcludedExercises ->
          state.copy(excludedExerciseIds = op.exerciseIds)
      is WorkoutChangeSet.Operation.ReportFeelings ->
          updateSet(op.setSyncId) { it.copy(reportedFeelings = op.feelings) }
      is WorkoutChangeSet.Operation.Rest,
      WorkoutChangeSet.Operation.UndoLast,
      is WorkoutChangeSet.Operation.RestoreWorkout -> state
    }
  }

  private fun number(value: Double) =
      if (value == value.toLong().toDouble()) value.toLong().toString()
      else value.toString().replace('.', ',')

  private fun load(set: SnapshotSet) =
      listOfNotNull(
              set.weightKg?.let { "${number(it)} кг" },
              set.reps?.let { "$it повт." },
              set.durationSec?.let { "$it с" },
              set.speedKmh?.let { "${number(it)} км/ч" },
              set.inclinePct?.let { "наклон ${number(it)}%" },
          )
          .joinToString(" · ")
          .ifEmpty { "значения не заданы" }

  private fun feelings(values: Set<String>) =
      values
          .sorted()
          .joinToString {
            when (it) {
              "PAIN" -> "боль"
              "FATIGUE" -> "усталость"
              "TECHNIQUE_BREAKDOWN" -> "нарушение техники"
              "INTERRUPTED" -> "подход прерван"
              else -> error("Unknown feeling")
            }
          }
          .ifEmpty { "ощущения не указаны" }
}
