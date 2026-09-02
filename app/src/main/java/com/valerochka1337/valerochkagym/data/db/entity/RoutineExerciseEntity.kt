package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.valerochka1337.valerochkagym.data.db.PlannedSet

@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
        ),
        ForeignKey(
            entity = ExerciseVariantEntity::class,
            parentColumns = ["exerciseId", "syncId"],
            childColumns = ["exerciseId", "variantSyncId"],
        ),
    ],
    indices = [Index("routineId"), Index("exerciseId"), Index(value = ["exerciseId", "variantSyncId"])],
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val exerciseId: Long,
    /** Null is the durable and comparable "Без варианта" group. */
    val variantSyncId: String? = null,
    val position: Int,
    val restSeconds: Int? = null,
    @ColumnInfo(name = "plannedSetsJson") val plannedSets: List<PlannedSet> = emptyList(),
)
