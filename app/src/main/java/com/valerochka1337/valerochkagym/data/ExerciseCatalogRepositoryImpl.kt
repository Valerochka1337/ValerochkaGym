package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSnapshot
import com.valerochka1337.valerochkagym.domain.GymRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ExerciseCatalogRepositoryImpl @Inject constructor(
    private val gymRepository: GymRepository,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val workoutDao: WorkoutDao,
) : ExerciseCatalogRepository {
    override fun observeCatalog(gymIds: Set<String>): Flow<ExerciseCatalogRepositoryState> = combine(
        gymRepository.observeAvailableExercises(gymIds),
        exerciseMuscleDao.observeAll(),
        workoutDao.observeFinishedExerciseHistory(),
        gymRepository.observeGyms(),
    ) { exercises, muscles, history, gyms ->
        ExerciseCatalogRepositoryState(
            snapshot = ExerciseCatalogSnapshot(exercises, muscles, history),
            gymNames = gyms.filter { it.id in gymIds }.map { it.name },
        )
    }
}
