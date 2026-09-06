package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.SaveExerciseConfigurationResult
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GymRepositoryImplTest : RoomDaoTest() {

  private lateinit var repository: GymRepositoryImpl

  @Before
  fun createRepository() {
    repository =
        GymRepositoryImpl(
            database = db,
            gymDao = db.gymDao(),
            exerciseDao = db.exerciseDao(),
            exerciseMuscleDao = db.exerciseMuscleDao(),
            routineDao = db.routineDao(),
            workoutDao = db.workoutDao(),
        )
  }

  @Test
  fun `creating an exercise leaves selected gym inventories unchanged`() = runTest {
    val alpha = savedGym("Альфа")
    val beta = savedGym("Бета")

    val exercise =
        repository.createExerciseAndAssign(
            NewExerciseConfiguration(
                exercise = exercise("Жим"),
                muscles = listOf(ExerciseMuscleEntity(0, Muscle.UPPER_CHEST, 100)),
            ),
            setOf(alpha, beta),
        )!!

    assertTrue(exercise.id > 0)
    assertTrue(repository.getGym(alpha)!!.exercises.isEmpty())
    assertTrue(repository.getGym(beta)!!.exercises.isEmpty())
    assertTrue(repository.observeAvailableExercises(setOf(alpha, beta)).first().isEmpty())
    assertEquals(1, db.exerciseMuscleDao().getForExercise(exercise.id).size)
  }

  @Test
  fun `creating from the active picker also adds the exercise to its workout`() = runTest {
    val gym = savedGym("Альфа")
    db.workoutDao()
        .insertWorkout(
            WorkoutEntity(id = "active", name = "Тренировка", startedAt = 1_000),
        )
    val localGymId = db.gymDao().getGymBySyncId(gym)!!.id
    db.gymDao().replaceWorkoutGyms("active", listOf(localGymId))

    val exercise =
        repository.createExerciseAssignAndAddToWorkout(
            NewExerciseConfiguration(
                exercise = exercise("Жим"),
                muscles = listOf(ExerciseMuscleEntity(0, Muscle.UPPER_CHEST, 100)),
            ),
            setOf(gym),
            "active",
        )!!

    assertEquals(
        listOf(exercise.id),
        db.workoutDao().getWorkoutExercises("active").map { it.exerciseId },
    )
    assertTrue(repository.getGym(gym)!!.exercises.isEmpty())
    assertEquals(1, tableCount("workout_sets"))
  }

  @Test
  fun `routine save and gym narrowing both reject unavailable exercises`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Присед"))
    val alpha = savedGym("Альфа", setOf(exerciseId))
    val beta = savedGym("Бета", setOf(exerciseId))
    val saved =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Ноги"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                    ),
                gymIds = setOf(alpha, beta),
            ),
        )

    assertTrue(saved is SaveRoutineConfigurationResult.Saved)
    val narrowing = repository.saveGym(beta, "Бета", emptySet())
    assertTrue(narrowing is SaveGymResult.Conflict)
    narrowing as SaveGymResult.Conflict
    assertEquals(listOf("Ноги"), narrowing.details.routines.map { it.name })
    assertEquals(listOf("Присед"), narrowing.details.exercises.map { it.name })
    assertEquals(
        setOf(exerciseId),
        repository.getGym(beta)!!.exercises.mapTo(hashSetOf()) { it.id },
    )
  }

  @Test
  fun `replaying a new routine sync id returns the committed row without rewriting it`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Жим"))
    val first =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine =
                    RoutineEntity(
                        syncId = "save-operation",
                        name = "Первое имя",
                        note = "Первый текст",
                    ),
                exercises =
                    listOf(
                        RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0)
                    ),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    val replay =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine =
                    RoutineEntity(
                        syncId = "save-operation",
                        name = "Не должно замениться",
                        note = "Другой текст",
                    ),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved
    val distinct =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(syncId = "next-operation", name = "Следующая программа"),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    assertEquals(first.routine, replay.routine)
    assertEquals(first.routineId, replay.routineId)
    assertEquals(2, tableCount("routines"))
    assertEquals(1, tableCount("routine_exercises"))
    assertEquals(
        "Первое имя",
        db.routineDao().getRoutineWithExercises(first.routineId)?.routine?.name,
    )
    assertTrue(distinct.routineId != first.routineId)
  }

  @Test
  fun `editing a nonzero routine id still replaces its fields exercises and gyms`() = runTest {
    val firstExercise = db.exerciseDao().insert(exercise("Жим"))
    val replacementExercise = db.exerciseDao().insert(exercise("Тяга"))
    val alpha = savedGym("Альфа", setOf(firstExercise))
    val beta = savedGym("Бета", setOf(replacementExercise))
    val created =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine =
                    RoutineEntity(syncId = "editor-routine", name = "Старая", note = "До правки"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(
                            routineId = 0,
                            exerciseId = firstExercise,
                            position = 0,
                        )
                    ),
                gymIds = setOf(alpha),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    val updated =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = created.routine.copy(name = "Новая", note = "После правки"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(
                            routineId = 0,
                            exerciseId = replacementExercise,
                            position = 5,
                        )
                    ),
                gymIds = setOf(beta),
            ),
        ) as SaveRoutineConfigurationResult.Saved
    val full = db.routineDao().getRoutineWithExercises(created.routineId)!!

    assertEquals(created.routineId, updated.routineId)
    assertEquals("Новая", full.routine.name)
    assertEquals("После правки", full.routine.note)
    assertEquals(listOf(replacementExercise), full.exercises.map { it.exercise.id })
    assertEquals(listOf(5), full.exercises.map { it.routineExercise.position })
    assertEquals(listOf(beta), full.gyms.map { it.syncId })
  }

  @Test
  fun `deleting a gym linked to a routine is blocked`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val gym = savedGym("Альфа", setOf(exerciseId))
    repository.saveRoutineConfiguration(
        RoutineConfigurationDraft(
            routine = RoutineEntity(name = "Спина"),
            exercises =
                listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
            gymIds = setOf(gym),
        ),
    )

    val result = repository.deleteGym(gym)

    assertTrue(result is DeleteGymResult.InUse)
    assertTrue(repository.getGym(gym) != null)
  }

  @Test
  fun `renaming a linked gym without narrowing its catalogue succeeds`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val gym = savedGym("Альфа", setOf(exerciseId))
    repository.saveRoutineConfiguration(
        RoutineConfigurationDraft(
            routine = RoutineEntity(name = "Спина"),
            exercises =
                listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
            gymIds = setOf(gym),
        ),
    )

    val result = repository.saveGym(gym, "Основной зал", setOf(exerciseId))

    assertTrue(result is SaveGymResult.Saved)
    assertEquals("Основной зал", repository.getGym(gym)?.name)
  }

  @Test
  fun `updating an exercise keeps its cloud version strictly monotonic`() = runTest {
    val futureVersion = System.currentTimeMillis() + 60_000
    val exerciseId = db.exerciseDao().insert(exercise("Тяга").copy(updatedAt = futureVersion))
    val staleDraft =
        db.exerciseDao()
            .getById(exerciseId)!!
            .copy(
                name = "Тяга блока",
                updatedAt = 1,
            )

    val saved =
        repository.updateExerciseAndAssign(
            NewExerciseConfiguration(
                exercise = staleDraft,
                muscles = listOf(ExerciseMuscleEntity(exerciseId, Muscle.LATS, 100)),
            ),
            emptySet(),
        )!!

    assertEquals(futureVersion + 1, saved.updatedAt)
    assertEquals(futureVersion + 1, db.exerciseDao().getById(exerciseId)?.updatedAt)
  }

  @Test
  fun `configured inventory exposes a builtin only when its dumbbells and bench are present`() =
      runTest {
        val builtin =
            com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry.entries
                .first { it.key == "chest-2-1" }
                .exercise
        val exerciseId = db.exerciseDao().insert(builtin.copy(id = 0))
        val saved =
            repository.saveGymInventory(
                id = null,
                name = "Основной зал",
                equipmentIds = setOf("dumbbells", "adjustable_bench"),
            ) as SaveGymResult.Saved

        assertEquals(
            listOf(exerciseId),
            repository.observeAvailableExercises(setOf(saved.gymId)).first().map { it.id },
        )
        assertTrue(repository.getGym(saved.gymId)!!.inventoryConfigured)
        assertEquals(setOf("dumbbells", "adjustable_bench"), repository.getGym(saved.gymId)!!.equipmentIds)
      }

  @Test
  fun `unknown custom exercise remains available in legacy gym and is rejected by configured or mixed gyms`() =
      runTest {
        val unknown = db.exerciseDao().insert(exercise("Старое упражнение"))
        val legacy = savedGym("Старый зал", setOf(unknown))
        val configured =
            (repository.saveGymInventory(null, "Новый зал", setOf("dumbbells")) as SaveGymResult.Saved)
                .gymId

        assertEquals(listOf(unknown), repository.observeAvailableExercises(setOf(legacy)).first().map { it.id })
        assertTrue(repository.observeAvailableExercises(setOf(configured)).first().isEmpty())
        assertTrue(repository.observeAvailableExercises(setOf(legacy, configured)).first().isEmpty())
      }

  @Test
  fun `requirement conflict rolls back linked routine active workout and inventory`() = runTest {
    val exerciseId =
        db.exerciseDao().insert(
            exercise("Жим с требованиями").copy(equipmentRequirementState = EquipmentRequirementState.KNOWN),
        )
    db.exerciseDao().replaceRequirements(exerciseId, setOf("dumbbells"))
    val gym =
        (repository.saveGymInventory(null, "Зал", setOf("dumbbells")) as SaveGymResult.Saved).gymId
    val localGym = db.gymDao().getGymBySyncId(gym)!!
    val routineId =
        db.routineDao().upsertRoutine(RoutineEntity(name = "Грудь"))
    db.routineDao().replaceRoutineExercises(
        routineId,
        listOf(RoutineExerciseEntity(routineId = routineId, exerciseId = exerciseId, position = 0)),
    )
    db.gymDao().replaceRoutineGyms(routineId, listOf(localGym.id))
    db.workoutDao().insertWorkout(WorkoutEntity(id = "active", name = "Активная", startedAt = 1))
    db.gymDao().replaceWorkoutGyms("active", listOf(localGym.id))
    val workoutExercise =
        db.workoutDao().insertWorkoutExercise(
            com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity(
                workoutId = "active",
                exerciseId = exerciseId,
                position = 0,
            ),
        )
    db.workoutDao().insertSet(
        com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity(
            workoutExerciseId = workoutExercise,
            setIndex = 0,
        ),
    )
    val existing = db.exerciseDao().getById(exerciseId)!!

    val result =
        repository.saveExerciseConfiguration(
            NewExerciseConfiguration(
                exercise = existing.copy(name = "Нельзя сохранить"),
                muscles = emptyList(),
                requirements = ExerciseEquipmentRequirements.Required(setOf("dumbbells", "flat_bench")),
            ),
            gymIds = emptySet(),
        )

    assertTrue(result is SaveExerciseConfigurationResult.Conflict)
    result as SaveExerciseConfigurationResult.Conflict
    assertEquals(setOf("flat_bench"), result.details.missingEquipmentIds)
    assertEquals(setOf("dumbbells"), db.exerciseDao().getRequirementIds(exerciseId).toSet())
    assertEquals("Жим с требованиями", db.exerciseDao().getById(exerciseId)!!.name)
    assertEquals(setOf("dumbbells"), db.gymDao().getGymEquipmentIds(localGym.id).toSet())
    assertEquals(1, db.workoutDao().getWorkoutExercises("active").size)
    assertEquals(1, tableCount("workout_sets"))
  }

  @Test
  fun `inventory conflict names only routines with equipment that becomes unavailable`() = runTest {
    val bodyweight =
        db.exerciseDao().insert(
            exercise("Планка").copy(equipmentRequirementState = EquipmentRequirementState.KNOWN),
        )
    val pullup =
        db.exerciseDao().insert(
            exercise("Подтягивание").copy(equipmentRequirementState = EquipmentRequirementState.KNOWN),
        )
    db.exerciseDao().replaceRequirements(pullup, setOf("pullup_bar"))
    val gym =
        (repository.saveGymInventory(null, "Зал с турником", setOf("pullup_bar")) as SaveGymResult.Saved)
            .gymId
    val localGym = db.gymDao().getGymBySyncId(gym)!!
    val floorRoutine = db.routineDao().upsertRoutine(RoutineEntity(name = "Пол"))
    val pullupRoutine = db.routineDao().upsertRoutine(RoutineEntity(name = "Турник"))
    db.routineDao().replaceRoutineExercises(
        floorRoutine,
        listOf(RoutineExerciseEntity(routineId = floorRoutine, exerciseId = bodyweight, position = 0)),
    )
    db.routineDao().replaceRoutineExercises(
        pullupRoutine,
        listOf(RoutineExerciseEntity(routineId = pullupRoutine, exerciseId = pullup, position = 0)),
    )
    db.gymDao().replaceRoutineGyms(floorRoutine, listOf(localGym.id))
    db.gymDao().replaceRoutineGyms(pullupRoutine, listOf(localGym.id))

    val result = repository.saveGymInventory(gym, "Зал с турником", emptySet())

    assertTrue(result is SaveGymResult.Conflict)
    result as SaveGymResult.Conflict
    assertEquals(listOf("Турник"), result.details.routines.map { it.name })
    assertEquals(listOf("Подтягивание"), result.details.exercises.map { it.name })
    assertEquals(setOf("pullup_bar"), result.details.missingEquipmentIds)
  }

  @Test
  fun `duplicating a routine copies exercises and gyms atomically`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val gym = savedGym("Альфа", setOf(exerciseId))
    val source =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Спина", note = "Тяжёлый день"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                    ),
                gymIds = setOf(gym),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    val copy = repository.duplicateRoutine(source.routineId)!!
    val full = db.routineDao().getRoutineWithExercises(copy.id)!!

    assertEquals("Спина (копия)", full.routine.name)
    assertEquals("Тяжёлый день", full.routine.note)
    assertEquals(listOf(exerciseId), full.exercises.map { it.exercise.id })
    assertEquals(listOf(gym), full.gyms.map { it.syncId })
  }

  @Test
  fun `deleting configuration keeps durable tombstones until upload succeeds`() = runTest {
    val gym = savedGym("Временный")
    val routine =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Пустая"),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    assertEquals(DeleteGymResult.Deleted, repository.deleteGym(gym))
    val routineDeletion = repository.deleteRoutine(routine.routineId)!!

    assertEquals(
        listOf(gym),
        db.configurationTombstoneDao().getByKind(ConfigurationTombstoneKind.GYM).map { it.syncId },
    )
    assertEquals(
        listOf(routineDeletion.syncId),
        db.configurationTombstoneDao().getByKind(ConfigurationTombstoneKind.ROUTINE).map {
          it.syncId
        },
    )
  }

  private suspend fun savedGym(name: String, exerciseIds: Set<Long> = emptySet()): String =
      (repository.saveGym(null, name, exerciseIds) as SaveGymResult.Saved).gymId

  private fun exercise(name: String) =
      ExerciseEntity(
          name = name,
          muscleGroup = MuscleGroup.FULL_BODY,
          type = ExerciseType.STRENGTH,
          isCustom = true,
      )
}
