package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSnapshot
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.domain.GymRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ExerciseCatalogRepositoryImpl
@Inject
constructor(
    private val gymRepository: GymRepository,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val workoutDao: WorkoutDao,
) : ExerciseCatalogRepository {
  override fun observeCatalog(gymIds: Set<String>): Flow<ExerciseCatalogRepositoryState> =
      combine(
          gymRepository.observeAvailableExercises(gymIds),
          exerciseMuscleDao.observeAll(),
          workoutDao.observeFinishedExerciseHistory(),
          gymRepository.observeGyms(),
          exerciseDao.observeAllRequirements(),
      ) { exercises, muscles, history, gyms, requirementRows ->
        val rowsByExercise = requirementRows.groupBy { it.exerciseId }
        val requirements =
            exercises.associate { exercise ->
              val value =
                  CanonicalExerciseRegistry.requirementsFor(exercise)?.toRequirements()
                      ?: if (
                          exercise.equipmentRequirementState == EquipmentRequirementState.UNKNOWN
                      ) {
                        ExerciseEquipmentRequirements.UnknownLegacy
                      } else {
                        rowsByExercise[exercise.id]
                            .orEmpty()
                            .mapTo(linkedSetOf()) { it.equipmentId }
                            .toRequirements()
                      }
              exercise.id to value
            }
        ExerciseCatalogRepositoryState(
            snapshot = ExerciseCatalogSnapshot(exercises, muscles, history, requirements),
            gymNames = gyms.filter { it.id in gymIds }.map { it.name },
        )
      }

  private fun Set<String>.toRequirements(): ExerciseEquipmentRequirements =
      if (isEmpty()) ExerciseEquipmentRequirements.ExplicitNone
      else ExerciseEquipmentRequirements.Required(this)
}
