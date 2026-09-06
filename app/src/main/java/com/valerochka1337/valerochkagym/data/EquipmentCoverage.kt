package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity

/** The only availability rule used by routine, workout and import transaction boundaries. */
internal suspend fun isEquipmentAvailable(
    exercise: ExerciseEntity,
    gyms: List<GymEntity>,
    gymDao: GymDao,
    exerciseDao: ExerciseDao,
): Boolean {
  if (gyms.isEmpty()) return true
  val canonical = CanonicalExerciseRegistry.requirementsFor(exercise)
  val requirements = canonical ?: exerciseDao.getRequirementIds(exercise.id).toSet()
  return gyms.all { gym ->
    if (!gym.inventoryConfigured) exercise.id in gymDao.getGymExerciseIds(gym.id)
    else if (
        canonical == null && exercise.equipmentRequirementState == EquipmentRequirementState.UNKNOWN
    )
        false
    else requirements.all { EquipmentCatalog.covers(gymDao.getGymEquipmentIds(gym.id).toSet(), it) }
  }
}
