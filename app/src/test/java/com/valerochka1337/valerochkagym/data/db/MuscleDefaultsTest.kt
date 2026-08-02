package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фоллбэк-карты вовлечения по крупной группе: полнота и корректность долей. */
class MuscleDefaultsTest {

    @Test
    fun `every muscle group has a non-empty fallback map`() {
        MuscleGroup.entries.forEach { group ->
            assertTrue("пустая карта у $group", group.defaultMuscleLoads().isNotEmpty())
        }
    }

    @Test
    fun `every fallback map has a full-contribution target muscle`() {
        MuscleGroup.entries.forEach { group ->
            assertEquals(
                "у $group нет целевой мышцы на 100%",
                100,
                group.defaultMuscleLoads().maxOf { it.contribution },
            )
        }
    }

    @Test
    fun `contributions stay within the one to hundred scale`() {
        MuscleGroup.entries.forEach { group ->
            group.defaultMuscleLoads().forEach { load ->
                assertTrue(
                    "$group → ${load.muscle}: доля ${load.contribution} вне 1..100",
                    load.contribution in 1..100,
                )
            }
        }
    }

    @Test
    fun `no muscle repeats within one fallback map`() {
        MuscleGroup.entries.forEach { group ->
            val loads = group.defaultMuscleLoads()
            assertEquals(
                "дубликаты мышц у $group",
                loads.size,
                loads.map { it.muscle }.toSet().size,
            )
        }
    }
}
