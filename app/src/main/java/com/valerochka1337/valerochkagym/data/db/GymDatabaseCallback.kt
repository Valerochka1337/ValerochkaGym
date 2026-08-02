package com.valerochka1337.valerochkagym.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valerochka1337.valerochkagym.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Seeds the built-in exercises. Seeding lives entirely in [onOpen], which fires on every open
 * including the very first: an empty table (count == 0) is filled, which both seeds a freshly
 * created database and heals a process death that interrupted a previous seed. Keeping a single
 * seeding path avoids the onCreate/onOpen race that could otherwise double-seed the catalogue. The
 * database is injected lazily via [Provider] to avoid a dependency cycle (the callback is a
 * dependency of the database).
 */
@Singleton
class GymDatabaseCallback @Inject constructor(
    private val database: Provider<GymDatabase>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : RoomDatabase.Callback() {

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        scope.launch {
            val database = database.get()
            val dao = database.exerciseDao()
            if (dao.count() == 0) {
                dao.insertAll(seedExercises)
            }
            // Карты мышц досеиваются отдельным шагом: их не хватает и после апгрейда с v2
            // (миграция создаёт таблицу пустой), и у упражнений, созданных импортом.
            seedMissingExerciseMuscles(dao, database.exerciseMuscleDao())
        }
    }
}
