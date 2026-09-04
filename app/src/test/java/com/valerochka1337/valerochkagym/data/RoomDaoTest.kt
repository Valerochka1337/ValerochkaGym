package com.valerochka1337.valerochkagym.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base for DAO tests that run against a fresh in-memory [GymDatabase] with main-thread queries
 * allowed. Subclasses grab their DAOs off [db] and reuse [tableCount] for raw row-count assertions.
 * Seeding is intentionally absent — the database opens empty so each test controls its own data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
abstract class RoomDaoTest {

  protected lateinit var db: GymDatabase

  @Before
  fun createDatabase() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db =
        Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .allowMainThreadQueries()
            .build()
  }

  @After
  fun closeDatabase() {
    db.close()
  }

  protected fun tableCount(table: String): Int =
      db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
      }

  /** Inserts a workout named `Workout $id`; only [id] is required, timing has sane defaults. */
  protected suspend fun insertWorkout(
      id: String,
      startedAt: Long = 1_000,
      finishedAt: Long? = null,
  ): String {
    db.workoutDao()
        .insertWorkout(
            WorkoutEntity(
                id = id,
                name = "Workout $id",
                startedAt = startedAt,
                finishedAt = finishedAt,
            ),
        )
    return id
  }

  protected suspend fun insertWorkoutExercise(
      workoutId: String,
      exerciseId: Long,
      position: Int = 0,
  ): Long =
      db.workoutDao()
          .insertWorkoutExercise(
              WorkoutExerciseEntity(
                  workoutId = workoutId,
                  exerciseId = exerciseId,
                  position = position,
              ),
          )

  protected suspend fun insertSet(
      workoutExerciseId: Long,
      setIndex: Int,
      weightKg: Double? = null,
      reps: Int? = null,
      durationSec: Int? = null,
      speedKmh: Double? = null,
      inclinePct: Double? = null,
      isCompleted: Boolean = false,
  ): Long =
      db.workoutDao()
          .insertSet(
              WorkoutSetEntity(
                  workoutExerciseId = workoutExerciseId,
                  setIndex = setIndex,
                  weightKg = weightKg,
                  reps = reps,
                  durationSec = durationSec,
                  speedKmh = speedKmh,
                  inclinePct = inclinePct,
                  isCompleted = isCompleted,
              ),
          )

  protected suspend fun workoutFull(id: String): WorkoutFull = db.workoutDao().getWorkoutFull(id)!!
}
