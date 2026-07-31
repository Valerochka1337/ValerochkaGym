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
 * Seeds the built-in exercises the first time the database is created. The database is injected
 * lazily via [Provider] to avoid a dependency cycle (the callback is a dependency of the database).
 */
@Singleton
class GymDatabaseCallback @Inject constructor(
    private val database: Provider<GymDatabase>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        scope.launch {
            database.get().exerciseDao().insertAll(seedExercises)
        }
    }
}
