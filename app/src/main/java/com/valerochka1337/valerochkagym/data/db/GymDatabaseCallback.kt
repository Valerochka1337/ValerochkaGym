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
 * Seeds the built-in exercises. [onCreate] fills a freshly created database; [onOpen] re-checks on
 * every open and seeds if the table is empty, which heals a process death that interrupts the
 * initial seed (otherwise the library would stay empty forever). The database is injected lazily
 * via [Provider] to avoid a dependency cycle (the callback is a dependency of the database).
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

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        scope.launch {
            val dao = database.get().exerciseDao()
            if (dao.count() == 0) {
                dao.insertAll(seedExercises)
            }
        }
    }
}
