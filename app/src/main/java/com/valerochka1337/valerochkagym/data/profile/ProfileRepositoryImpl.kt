package com.valerochka1337.valerochkagym.data.profile

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ProfileDao
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEquipmentPreferenceEntity
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.service.WallClock
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val GUEST_SCOPE = "GUEST"

class ProfileRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val profileDao: ProfileDao,
    private val sync: BackendSync,
    private val sessions: BackendSessionStore,
    private val clock: WallClock,
) : ProfileRepository {
  private val mutationMutex = Mutex()

  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> = flow {
    val target = currentTarget()
    if (target == null) emit(null)
    else
        emitAll(
            observe(target).map { profile -> profile?.let { ProfileEditorSnapshot(target, it) } }
        )
  }

  override suspend fun openEditor(): ProfileEditorSnapshot? {
    val target = currentTarget() ?: return null
    val profile = database.withTransaction { profileFor(target.scope) }
    return if (targetStillCurrent(target)) ProfileEditorSnapshot(target, profile) else null
  }

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> =
      combine(
          profileDao.observe(target.scope),
          profileDao.observeEquipmentIds(target.scope),
          sync.transfer,
          sessions.session,
      ) { entity, equipmentIds, _, _ ->
        if (!targetStillCurrent(target)) null else entity?.toProfile(equipmentIds) ?: BasicProfile()
      }

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult {
    val normalized =
        ProfileValidator.normalize(profile, clock.nowMillis()) ?: return ProfileSaveResult.Invalid
    return mutationMutex.withLock {
      database.withTransaction {
        if (!targetStillCurrent(target)) return@withTransaction ProfileSaveResult.StaleTarget
        val existing = profileDao.get(target.scope)
        val syncId =
            target.ownerId?.let(::profileSyncId) ?: existing?.syncId ?: UUID.randomUUID().toString()
        profileDao.upsert(
            ProfileEntity(
                scope = target.scope,
                syncId = syncId,
                trainingGoal = normalized.trainingGoal?.name,
                sex = normalized.sex?.name,
                birthDate = normalized.birthDate,
                experienceLevel = normalized.experienceLevel?.name,
                plannedSessionsPerWeek = normalized.plannedSessionsPerWeek,
                preferredSessionDurationMinutes = normalized.preferredSessionDurationMinutes,
                manualConstraints = normalized.manualConstraints,
                updatedAt = clock.nowMillis().coerceAtLeast(0),
            )
        )
        profileDao.deleteEquipment(target.scope)
        profileDao.upsertEquipment(
            normalized.equipmentIds.sorted().map {
              ProfileEquipmentPreferenceEntity(target.scope, it)
            }
        )
        ProfileSaveResult.Saved
      }
    }
  }

  private suspend fun profileFor(scope: String): BasicProfile =
      profileDao.get(scope)?.toProfile(profileDao.equipmentIds(scope)) ?: BasicProfile()

  private fun currentTarget(): ProfileEditTarget? {
    val initialOwner = sync.owner()
    val session = sessions.snapshot()
    if (sync.owner() != initialOwner) return null
    return if (initialOwner == null) {
      if (session != null) null else ProfileEditTarget(GUEST_SCOPE, null, 0)
    } else if (session?.tokens?.userId == initialOwner) {
      ProfileEditTarget(initialOwner, initialOwner, session.epoch)
    } else null
  }

  private fun targetStillCurrent(target: ProfileEditTarget): Boolean = currentTarget() == target

  private fun ProfileEntity.toProfile(equipmentIds: List<String>) =
      BasicProfile(
          trainingGoal = trainingGoal?.let(TrainingGoal::valueOf),
          sex = sex?.let(ProfileSex::valueOf),
          birthDate = birthDate,
          experienceLevel = experienceLevel?.let(ExperienceLevel::valueOf),
          plannedSessionsPerWeek = plannedSessionsPerWeek,
          preferredSessionDurationMinutes = preferredSessionDurationMinutes,
          equipmentIds = equipmentIds.toSet(),
          manualConstraints = manualConstraints,
      )

  private fun profileSyncId(owner: String): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.profile.v1:$owner".toByteArray(UTF_8)).toString()
}
