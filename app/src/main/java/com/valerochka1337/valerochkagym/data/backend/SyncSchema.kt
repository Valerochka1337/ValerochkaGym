package com.valerochka1337.valerochkagym.data.backend

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "backend_state")
data class BackendStateEntity(
    @PrimaryKey val id: Int = 1,
    val owner: String? = null,
    val generation: Long = 0,
)

@Entity(tableName = "backend_baseline")
data class BackendBaselineEntity(@PrimaryKey val key: String, val recordJson: String)

@Entity(tableName = "backend_outbox")
data class BackendOutboxEntity(
    @PrimaryKey val id: Int = 1,
    val owner: String,
    val requestJson: String,
)

object SyncSchema {
  val trackedTables =
      arrayOf(
          "exercises",
          "exercise_muscles",
          "exercise_equipment",
          "gyms",
          "gym_exercises",
          "gym_equipment",
          "routines",
          "routine_exercises",
          "routine_gyms",
          "workouts",
          "workout_exercises",
          "workout_sets",
          "workout_gyms",
          "body_measurements",
          "scheduled_workouts",
      )

  fun create(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS backend_state (id INTEGER NOT NULL,owner TEXT,generation INTEGER NOT NULL,PRIMARY KEY(id))"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS backend_baseline (`key` TEXT NOT NULL,recordJson TEXT NOT NULL,PRIMARY KEY(`key`))"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS backend_outbox (id INTEGER NOT NULL,owner TEXT NOT NULL,requestJson TEXT NOT NULL,PRIMARY KEY(id))"
    )
    install(db)
  }

  fun install(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO backend_state(id,owner,generation) VALUES (1,NULL,0)")
    trackedTables.forEach { table ->
      listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS backend_${table}_${operation.lowercase()} AFTER $operation ON $table BEGIN UPDATE backend_state SET generation=generation+1 WHERE id=1; END"
        )
      }
    }
  }
}
