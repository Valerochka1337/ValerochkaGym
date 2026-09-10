package com.valerochka1337.valerochkagym.data.profile

import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.service.WallClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The only supported entry kinds. They contain no request payload or URI. */
enum class AiProfilePromptKind {
  EXERCISE,
  IN_BODY,
  CALENDAR,
}

sealed interface AiProfilePromptDecision {
  data object Proceed : AiProfilePromptDecision

  /** Another prompt has a live reservation; never bypass it with a second AI request. */
  data object Busy : AiProfilePromptDecision

  /** The captured owner/session changed while a suspend boundary was crossed. */
  data object Stale : AiProfilePromptDecision

  data class Show(val token: String, val kind: AiProfilePromptKind) : AiProfilePromptDecision
}

/**
 * Owner/session-safe, at-most-once boundary before an AI action. The action itself is intentionally
 * never accepted here: UI owns its in-memory continuation and may run it only after [consume].
 */
@Singleton
class AiProfilePromptGate
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val clock: WallClock,
    private val activeWorkoutRepository: ActiveWorkoutRepository? = null,
) {
  private val processMarker = UUID.randomUUID().toString()
  private val liveTargets = mutableMapOf<String, ProfileEditTarget>()

  suspend fun request(kind: AiProfilePromptKind): AiProfilePromptDecision {
    if (activeWorkoutRepository?.observeActive()?.first() != null)
        return AiProfilePromptDecision.Proceed
    val target = profileRepository.openEditor() ?: return AiProfilePromptDecision.Proceed
    liveTargets
        .filterValues { it.scope == target.target.scope && it != target.target }
        .keys
        .toList()
        .forEach { staleToken -> cancel(staleToken) }
    settingsRepository.clearAiProfilePromptOrphan(target.target.scope, processMarker)
    if (!needsProfile(target)) return AiProfilePromptDecision.Proceed

    val token = UUID.randomUUID().toString()
    val reserved =
        try {
          settingsRepository.reserveAiProfilePrompt(
              scope = target.target.scope,
              token = token,
              kind = kind.name,
              config = "$CONFIG_VERSION:reserved",
              processMarker = processMarker,
              nowMillis = clock.nowMillis(),
              cooldownMillis = COOLDOWN_MILLIS,
          )
        } catch (error: CancellationException) {
          withContext(NonCancellable) {
            settingsRepository.cancelAiProfilePrompt(target.target.scope, token, processMarker)
          }
          throw error
        }
    try {
      if (!reserved) {
        return if (
            settingsRepository.aiProfilePromptState(target.target.scope).reservationToken != null
        ) {
          AiProfilePromptDecision.Busy
        } else {
          AiProfilePromptDecision.Proceed
        }
      }

      // DataStore edit can suspend: a scope switch must never expose a decision for the old owner.
      if (!isCurrent(target.target)) {
        settingsRepository.cancelAiProfilePrompt(target.target.scope, token, processMarker)
        return AiProfilePromptDecision.Stale
      }
      // An active workout may have started while profile/DataStore work was suspended. The
      // reservation is local bookkeeping only; it must not result in an in-workout prompt.
      if (activeWorkoutRepository?.observeActive()?.first() != null) {
        settingsRepository.cancelAiProfilePrompt(target.target.scope, token, processMarker)
        return if (isCurrent(target.target)) AiProfilePromptDecision.Proceed
        else AiProfilePromptDecision.Stale
      }
      liveTargets[token] = target.target
      return AiProfilePromptDecision.Show(token, kind)
    } catch (error: CancellationException) {
      withContext(NonCancellable) {
        settingsRepository.cancelAiProfilePrompt(target.target.scope, token, processMarker)
      }
      throw error
    }
  }

  suspend fun acknowledgeVisible(token: String): Boolean {
    val target = liveTargets[token] ?: return false
    if (!isCurrent(target)) return invalidate(token, target)
    return settingsRepository.acknowledgeAiProfilePrompt(
        scope = target.scope,
        token = token,
        processMarker = processMarker,
        shownAtMillis = clock.nowMillis(),
    )
  }

  /** Returns true exactly once for the current live reservation. */
  suspend fun consume(token: String, disableFuturePrompts: Boolean): Boolean {
    val target = liveTargets.remove(token) ?: return false
    if (!isCurrent(target)) {
      settingsRepository.cancelAiProfilePrompt(target.scope, token, processMarker)
      return false
    }
    val consumed =
        settingsRepository.consumeAiProfilePrompt(
            scope = target.scope,
            token = token,
            processMarker = processMarker,
            disable = disableFuturePrompts,
        )
    return consumed && isCurrent(target)
  }

  suspend fun cancel(token: String) {
    val target = liveTargets.remove(token) ?: return
    settingsRepository.cancelAiProfilePrompt(target.scope, token, processMarker)
  }

  suspend fun setDisabledForCurrentScope(disabled: Boolean) {
    val target = profileRepository.openEditor() ?: return
    settingsRepository.setAiProfilePromptDisabled(target.target.scope, disabled)
  }

  suspend fun isDisabledForCurrentScope(): Boolean {
    val target = profileRepository.openEditor() ?: return false
    return settingsRepository.aiProfilePromptState(target.target.scope).disabled
  }

  private suspend fun isCurrent(target: ProfileEditTarget): Boolean =
      profileRepository.openEditor()?.target == target

  private suspend fun needsProfile(
      snapshot: com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
  ): Boolean = snapshot.profile.trainingGoal == null || snapshot.profile.experienceLevel == null

  private suspend fun invalidate(token: String, target: ProfileEditTarget): Boolean {
    liveTargets.remove(token)
    settingsRepository.cancelAiProfilePrompt(target.scope, token, processMarker)
    return false
  }

  private companion object {
    const val CONFIG_VERSION = "1"
    const val COOLDOWN_MILLIS = 72L * 60L * 60L * 1000L
  }
}
