package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Закрыть подход и сразу уйти на отдых: длительность резолвится по программе
 * ([com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity.restSeconds]), иначе по
 * настройкам.
 *
 * Общий для экрана тренировки и кнопки «Готово» в уведомлении — чтобы галочка в шторке вела себя
 * ровно как галочка на карточке подхода. Снимок тренировки читается здесь же, а не приходит
 * параметром: у сервиса своего свежего снимка может не оказаться.
 */
@Singleton
class CompleteSetUseCase @Inject constructor(
    private val repository: ActiveWorkoutRepository,
    private val restDurationResolver: RestDurationResolver,
    private val restTimerEngine: RestTimerEngine,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Отдых не стартует, если подход не принадлежит активной тренировке — но отметка ставится.
     * При выключенном [GymSettings.restAutostart] отметка тоже ставится, а таймер не запускается.
     */
    suspend operator fun invoke(setId: Long) {
        val workout = repository.observeActive().first()
        repository.toggleSetCompleted(setId, true)
        val exerciseId = workout?.exercises
            ?.firstOrNull { exercise -> exercise.sets.any { it.id == setId } }
            ?.exercise?.id ?: return
        val settings = settingsRepository.settings.first()
        if (!settings.restAutostart) return
        if (settings.heartRateRestEnabled) {
            restTimerEngine.startUntilHeartRateAtMost(
                thresholdBpm = settings.heartRateRestThresholdBpm,
                holdSeconds = settings.heartRateRestHoldSeconds,
            )
        } else {
            restTimerEngine.start(restDurationResolver(workout, exerciseId))
        }
    }
}
